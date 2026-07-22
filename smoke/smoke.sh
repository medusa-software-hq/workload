#!/usr/bin/env bash
#
# Staging smoke suite v1 (M5-04). Runs against the *deployed* staging environment as the CI
# principal; wired as the gate between staging and prod deploys by the promotion chains
# (.github/workflows/check-staging-smoke.yml → promote-api / promote-web-spa).
#
# Deliberately small (design 00 § 3). It doubles as the M4 "front-door integration test" debt: it
# proves the whole client → Cloudflare Worker → Google front end → app path end-to-end, and that the
# IAM lock holds (direct run.app is refused at Google's edge for free).
#
# Inputs (from the workflow):
#   API_URL      the front-door hostname, e.g. https://api.workload-baseline-staging.medusa.software
#   RUN_APP_URL  the broker's generated Cloud Run origin (https://api-...run.app)
#
# Exit non-zero on the first hard failure. Check 3 (gRPC-web) is the one remaining v1 stub, marked so
# and non-fatal; a stricter authenticated streaming assertion is future work.

set -euo pipefail

: "${API_URL:?API_URL not set}"
: "${RUN_APP_URL:?RUN_APP_URL not set}"
: "${ADMIN_ID_TOKEN:?ADMIN_ID_TOKEN not set}"

fail() {
  echo "::error::smoke: $*" >&2
  exit 1
}

echo "== staging smoke =="
echo "front door: $API_URL"
echo "origin:     $RUN_APP_URL"
echo

# ---------------------------------------------------------------------------
# 1. Front-door chain: client → Cloudflare Worker → GFE → app. Assert an app-level response, not just
#    a 200 from the edge — /health is served by the broker itself, so a real body proves the Worker's
#    X-Serverless-Authorization token got the request past the IAM-locked front end to the app.
# ---------------------------------------------------------------------------
echo "[1/4] front door reaches the app (GET /health)"
code=$(curl -sS -o /tmp/health.body -w '%{http_code}' --max-time 30 "$API_URL/health" || true)
body=$(cat /tmp/health.body 2>/dev/null || true)
echo "      HTTP $code; body: ${body:0:200}"
[ "$code" = "200" ] || fail "front door /health returned HTTP $code (expected 200) — Worker/IAM path is broken"
[ -n "$body" ] || fail "front door /health returned an empty body — reached the edge but not the app"
echo "      ok"
echo

# ---------------------------------------------------------------------------
# 4. Negative: the IAM lock holds. A direct hit on the run.app origin (bypassing the Worker, so no
#    X-Serverless-Authorization) must be refused by Google's front end with 403 — no instance start,
#    no billable work. This is the whole point of --no-allow-unauthenticated.
# ---------------------------------------------------------------------------
echo "[2/4] direct run.app origin is refused (GET /health → 403)"
code=$(curl -sS -o /dev/null -w '%{http_code}' --max-time 30 "$RUN_APP_URL/health" || true)
echo "      HTTP $code"
[ "$code" = "403" ] || fail "direct run.app /health returned HTTP $code (expected 403) — the IAM lock is NOT holding"
echo "      ok"
echo

# ---------------------------------------------------------------------------
# 3. gRPC-web through the Worker (the SPA plane). v1 lenient transport check: the admin gRPC-web
#    endpoint requires auth, so an unauthenticated call is expected to be *rejected by the app*
#    (a gRPC-web response), NOT by the edge. Proves the Worker forwards gRPC-web content correctly.
#    A stricter authenticated streaming assertion is future work.
# ---------------------------------------------------------------------------
echo "[3/4] gRPC-web is proxied to the app (transport check)"
ct=$(curl -sS -o /dev/null -w '%{content_type}' --max-time 30 \
  -X POST -H 'Content-Type: application/grpc-web+proto' \
  --data-binary '' "$API_URL/" || true)
echo "      response content-type: ${ct:-<none>}"
case "$ct" in
  application/grpc-web*) echo "      ok (app answered with a gRPC-web frame)" ;;
  *) echo "      ::warning::gRPC-web transport check inconclusive (content-type: ${ct:-none}) — v1 non-fatal" ;;
esac
echo

# ---------------------------------------------------------------------------
# 2. Admin plane via s2s (read-only RPC as the CI principal). A genuine end-to-end check of the M5-05
#    service principal: ADMIN_ID_TOKEN is a WIF-minted workload-ci-admin ID token (audience = this
#    API URL, email included), so the server's GooglePrincipalVerifier must classify it as an
#    allowlisted service principal and let the read-only ListProfiles through the front door. A 200
#    proves the whole path — Cloudflare Worker → GFE → app → s2s auth. (The negative cases — wrong
#    audience, non-allowlisted SA, staging-token-against-prod — are covered by the verifier unit
#    tests and the registry-auth integration workflow, not re-run here.)
# ---------------------------------------------------------------------------
echo "[4/4] admin plane via s2s (read-only ListProfiles as workload-ci-admin)"
code=$(curl -sS -o /tmp/admin.body -w '%{http_code}' --max-time 30 \
  -X POST \
  -H "Authorization: Bearer $ADMIN_ID_TOKEN" \
  -H 'Content-Type: application/json' \
  --data '{}' \
  "$API_URL/medusa.workload.v1.FleetService/ListProfiles" || true)
body=$(cat /tmp/admin.body 2>/dev/null || true)
echo "      HTTP $code; body: ${body:0:200}"
[ "$code" = "200" ] || fail "admin ListProfiles as workload-ci-admin returned HTTP $code (expected 200) — the s2s service principal is not being accepted"
echo "      ok"
echo

echo "== smoke passed (checks 1, 2 & 4 enforced; 3 is a marked v1 stub) =="
