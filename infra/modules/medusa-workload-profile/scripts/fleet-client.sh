#!/usr/bin/env bash
#
# fleet-client.sh — the reconcile helper behind the medusa_workload_profile Terraform module. Talks
# to FleetService's unframed Connect/JSON endpoint exactly like the ms-workload CLI's admin plane
# does (see cli/src/main/kotlin/software/medusa/workload/cli/AdminApiClient.kt): a plain HTTPS POST
# of the request as proto3 JSON (camelCase), bearer-authenticated with a Google ID token. It also
# runs the digest-CAS preview flow (ResolveImage -> expected_docker_image_digest) that the console
# uses but the CLI itself never exercises — see CreateProfileRequest.expected_docker_image_digest's
# doc comment in fleet_service.proto.
#
# Usage:
#   fleet-client.sh get
#     Read-only. Reads a query object {profile_id, api_base_url, auth_audience} as JSON on stdin —
#     the shape Terraform's `external` data source provides — and prints the profile's current
#     state as a flat string map on stdout. Never mutates anything.
#
#   fleet-client.sh apply
#     Reconciles the profile to the desired spec. Reads FLEET_API_BASE_URL / FLEET_AUTH_AUDIENCE /
#     FLEET_DESIRED_SPEC_JSON from the environment (set by the module's local-exec provisioner).
#     Creates the profile if it doesn't exist yet, otherwise appends an updated revision —
#     FleetService has no in-place revision mutation (see UpdateProfile's doc comment). Always
#     mutates when invoked: the module only invokes this when Terraform's own trigger-hash diff
#     says the desired spec changed, so there's no remote-state diff to re-derive here.
#
# Auth: mints a Google ID token from ambient credentials via gcloud (ADC on a dev box, or a
# Workload Identity Federation credential in CI, exactly what google-github-actions/auth sets up)
# with audience = the API's own base URL — the same shape the CLI's --auth sa path and the server's
# GooglePrincipalVerifier expect for a service principal. Set FLEET_ID_TOKEN to supply a token
# directly and skip gcloud entirely (e.g. tests, or a runner that mints tokens some other way).
#
# Requires: bash, curl, jq, and (unless FLEET_ID_TOKEN is set) gcloud.

set -euo pipefail

script_name="$(basename "${BASH_SOURCE[0]}")"

fail() {
  echo "$script_name: $*" >&2
  exit 1
}

command -v curl >/dev/null 2>&1 || fail "curl is required."
command -v jq >/dev/null 2>&1 || fail "jq is required."

# ---------------------------------------------------------------------------
# Auth + transport
# ---------------------------------------------------------------------------

# id_token <audience> — prints a Google ID token for the given audience.
id_token() {
  if [ -n "${FLEET_ID_TOKEN:-}" ]; then
    printf '%s' "$FLEET_ID_TOKEN"
    return
  fi
  command -v gcloud >/dev/null 2>&1 ||
    fail "gcloud is required to mint an ID token (or set FLEET_ID_TOKEN)."
  gcloud auth print-identity-token --audiences="$1" 2>/dev/null ||
    fail "failed to mint a Google ID token for audience '$1' -- is an ambient credential (ADC / WIF) available?"
}

# post_fleet <base_url> <token> <method> <json_body> — POSTs to
# "<base_url>/medusa.workload.v1.FleetService/<method>" and prints the JSON response body, or fails
# with the HTTP status and a truncated body on a non-2xx response.
post_fleet() {
  local base_url="$1" token="$2" method="$3" body="$4"
  local url="${base_url%/}/medusa.workload.v1.FleetService/$method"
  local raw status resp_body
  raw="$(curl -sS -w '\n%{http_code}' -X POST "$url" \
    -H "Authorization: Bearer $token" \
    -H "Content-Type: application/json" \
    -H "Accept: application/json" \
    -d "$body")"
  status="${raw##*$'\n'}"
  resp_body="${raw%$'\n'*}"
  if [ "$status" -lt 200 ] || [ "$status" -ge 300 ]; then
    fail "FleetService $method failed (HTTP $status): $(printf '%s' "$resp_body" | head -c 500)"
  fi
  printf '%s' "$resp_body"
}

# ---------------------------------------------------------------------------
# get — read-only. Used by the module's `data "external" "current"`.
# ---------------------------------------------------------------------------

cmd_get() {
  local query profile_id base_url audience token
  query="$(cat)"
  profile_id="$(printf '%s' "$query" | jq -r '.profile_id')"
  base_url="$(printf '%s' "$query" | jq -r '.api_base_url')"
  audience="$(printf '%s' "$query" | jq -r '.auth_audience')"
  token="$(id_token "$audience")"

  local profiles profile
  profiles="$(post_fleet "$base_url" "$token" ListProfiles '{}')"
  profile="$(printf '%s' "$profiles" | jq -c --arg id "$profile_id" '[.profiles[]? | select(.profileId == $id)] | first')"

  if [ "$profile" = "null" ] || [ -z "$profile" ]; then
    jq -n '{exists: "false"}'
    return
  fi

  local revisions latest
  revisions="$(post_fleet "$base_url" "$token" ListProfileRevisions "$(jq -n --arg id "$profile_id" '{profileId: $id}')")"
  latest="$(printf '%s' "$revisions" | jq -c '[.revisions[]?] | (max_by(.revision) // {})')"

  jq -n \
    --argjson profile "$profile" \
    --argjson revision "$latest" \
    '{
      exists: "true",
      archived: ($profile.archived // false | tostring),
      fallbackEligible: ($profile.fallbackEligible // false | tostring),
      latestRevision: ($revision.revision // 0 | tostring),
      targetServiceAccount: ($revision.targetServiceAccount // ""),
      dockerImage: ($revision.dockerImage // ""),
      dockerImageDigest: ($revision.dockerImageDigest // ""),
      imageStatus: ($revision.imageStatus // ""),
      verificationStatus: ($revision.verificationStatus // ""),
      drainDeadline: ($revision.drainDeadline // ""),
      envVarsJson: ($revision.envVars // {} | tojson),
      secretEnvVarsJson: ($revision.secretEnvVars // {} | tojson)
    }'
}

# ---------------------------------------------------------------------------
# apply — reconciles the profile to FLEET_DESIRED_SPEC_JSON.
# ---------------------------------------------------------------------------

cmd_apply() {
  local base_url="${FLEET_API_BASE_URL:?FLEET_API_BASE_URL must be set}"
  local audience="${FLEET_AUTH_AUDIENCE:?FLEET_AUTH_AUDIENCE must be set}"
  local spec="${FLEET_DESIRED_SPEC_JSON:?FLEET_DESIRED_SPEC_JSON must be set}"
  local token
  token="$(id_token "$audience")"

  local profile_id display_name target_sa note docker_image drain_deadline fallback_eligible
  local env_vars secret_env_vars
  profile_id="$(printf '%s' "$spec" | jq -r '.profileId')"
  display_name="$(printf '%s' "$spec" | jq -r '.displayName')"
  target_sa="$(printf '%s' "$spec" | jq -r '.targetServiceAccount')"
  note="$(printf '%s' "$spec" | jq -r '.note')"
  docker_image="$(printf '%s' "$spec" | jq -r '.dockerImage')"
  drain_deadline="$(printf '%s' "$spec" | jq -r '.drainDeadline')"
  fallback_eligible="$(printf '%s' "$spec" | jq -r '.fallbackEligible')"
  env_vars="$(printf '%s' "$spec" | jq -c '.envVars // {}')"
  secret_env_vars="$(printf '%s' "$spec" | jq -c '.secretEnvVars // {}')"

  [ -n "$profile_id" ] && [ "$profile_id" != "null" ] || fail "desired spec is missing profileId"

  # Digest CAS: preview the tag's current digest with a read-only ResolveImage call, then pass it
  # straight back as expected_docker_image_digest on the Create/UpdateProfile call below, exactly
  # as the console does. The backend re-resolves docker_image server-side and rejects
  # (FAILED_PRECONDITION) if a concurrent write moved the tag between the preview and the pin,
  # instead of silently pinning whatever it happens to resolve to at mutation time.
  local expected_digest=""
  if [ -n "$docker_image" ] && [ "$docker_image" != "null" ]; then
    local resolve_resp image_status detail
    resolve_resp="$(post_fleet "$base_url" "$token" ResolveImage \
      "$(jq -n --arg sa "$target_sa" --arg img "$docker_image" '{targetServiceAccount: $sa, dockerImage: $img}')")"
    image_status="$(printf '%s' "$resolve_resp" | jq -r '.imageStatus // ""')"
    expected_digest="$(printf '%s' "$resolve_resp" | jq -r '.dockerImageDigest // ""')"
    detail="$(printf '%s' "$resolve_resp" | jq -r '.detail // ""')"
    case "$image_status" in
      IMAGE_STATUS_RESOLVED) ;;
      IMAGE_STATUS_UNRESOLVABLE | IMAGE_STATUS_UNDETERMINED)
        fail "docker_image '$docker_image' did not resolve ($image_status): ${detail:-no detail returned}"
        ;;
      *) fail "unexpected ResolveImage status '$image_status' for '$docker_image'" ;;
    esac
  fi

  local profiles profile method request_body response revision_out
  profiles="$(post_fleet "$base_url" "$token" ListProfiles '{}')"
  profile="$(printf '%s' "$profiles" | jq -c --arg id "$profile_id" '[.profiles[]? | select(.profileId == $id)] | first')"

  if [ "$profile" = "null" ] || [ -z "$profile" ]; then
    method="CreateProfile"
    request_body="$(jq -n \
      --arg profileId "$profile_id" \
      --arg displayName "$display_name" \
      --arg targetServiceAccount "$target_sa" \
      --arg note "$note" \
      --argjson envVars "$env_vars" \
      --argjson secretEnvVars "$secret_env_vars" \
      --arg dockerImage "$docker_image" \
      --arg expectedDockerImageDigest "$expected_digest" \
      --arg drainDeadline "$drain_deadline" \
      '{profileId: $profileId, displayName: $displayName, targetServiceAccount: $targetServiceAccount, note: $note, envVars: $envVars, secretEnvVars: $secretEnvVars, dockerImage: $dockerImage, expectedDockerImageDigest: $expectedDockerImageDigest, drainDeadline: $drainDeadline}')"
  else
    method="UpdateProfile"
    request_body="$(jq -n \
      --arg profileId "$profile_id" \
      --arg targetServiceAccount "$target_sa" \
      --arg note "$note" \
      --argjson envVars "$env_vars" \
      --argjson secretEnvVars "$secret_env_vars" \
      --arg dockerImage "$docker_image" \
      --arg expectedDockerImageDigest "$expected_digest" \
      --arg drainDeadline "$drain_deadline" \
      '{profileId: $profileId, targetServiceAccount: $targetServiceAccount, note: $note, envVars: $envVars, secretEnvVars: $secretEnvVars, dockerImage: $dockerImage, expectedDockerImageDigest: $expectedDockerImageDigest, drainDeadline: $drainDeadline}')"
  fi

  response="$(post_fleet "$base_url" "$token" "$method" "$request_body")"
  revision_out="$(printf '%s' "$response" | jq -c '.revision // {}')"

  # SetProfileFallbackEligible is a separate RPC from Create/UpdateProfile (fallback_eligible lives
  # on Profile, not ProfileRevision) but idempotent either direction, so it's safe to always call
  # once we already know the trigger fired.
  post_fleet "$base_url" "$token" SetProfileFallbackEligible \
    "$(jq -n --arg id "$profile_id" --argjson eligible "$fallback_eligible" '{profileId: $id, fallbackEligible: $eligible}')" >/dev/null

  echo "$script_name: $method ok for '$profile_id' -- $(printf '%s' "$revision_out" | jq -c '{revision, dockerImageDigest, imageStatus, verificationStatus}')"
}

case "${1:-}" in
  get) cmd_get ;;
  apply) cmd_apply ;;
  *) fail "usage: $script_name {get|apply} (see header comment)" ;;
esac
