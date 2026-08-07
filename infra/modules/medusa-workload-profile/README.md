# medusa-workload-profile

Manages a Workload worker profile declaratively — `name`, `docker_image`,
`env`, `secret_env`, `drain_deadline`, `target_service_account`, and
`fallback_eligible` — as HCL, instead of imperative
`ms-workload admin profiles update -f json`. MVP scope (workload#161): a thin
module wrapping FleetService's own unframed Connect/JSON endpoint (the same
one the CLI and console call), not a real
`terraform-provider-medusa-workload` — see "How it works" and "Follow-up"
below for what that would need beyond this.

## Usage

```hcl
module "flow_worker_profile" {
  source = "git::ssh://git@github.com/medusa-software-hq/workload.git//infra/modules/medusa-workload-profile?ref=v1"

  api_base_url            = module.common.api_url
  profile_id               = "flow-worker"
  display_name             = "Flow worker"
  target_service_account   = module.workload_impersonation.service_account_email
  docker_image              = "us-docker.pkg.dev/my-project/repo/flow-worker:latest"

  env_vars = {
    LOG_LEVEL = "info"
  }
  secret_env_vars = {
    API_KEY = google_secret_manager_secret.api_key.id
  }

  drain_deadline    = "6h"
  fallback_eligible = true
}

output "flow_worker_digest" {
  value = module.flow_worker_profile.docker_image_digest
}
```

Pair it with [`workload-impersonation`](../workload-impersonation) for the
`target_service_account`: that module grants the Workload broker the right to
impersonate the SA (plus, optionally, read access to the secrets and
Artifact Registry repository this profile references); this module then
creates or updates the profile that names it.

## Requirements

`terraform apply` (and `plan`, once a profile already exists) shells out to
[`scripts/fleet-client.sh`](scripts/fleet-client.sh), which needs `bash`,
`curl`, `jq`, and (unless `FLEET_ID_TOKEN` is set) `gcloud` on the machine
running Terraform. Auth is ambient Google credentials — Application Default
Credentials on a dev box, or a Workload Identity Federation credential in CI
(exactly what `google-github-actions/auth` sets up, the same mechanism this
repo's own `apply-*-infra.yml` workflows already use) — minted into a Google
ID token whose audience is `api_base_url` itself. This is the same shape the
`ms-workload` CLI's `--auth sa` path and the server's
`GooglePrincipalVerifier` expect for a service principal; the default
browser-based `gsi` sign-in the CLI otherwise uses is not usable here.

## How it works

FleetService has no `GetProfile`/patch RPC and no in-place revision mutation:
`UpdateProfile` always **appends** a new `ProfileRevision` (see
`fleet_service.proto`). This module models desired state as "the latest
revision should look like this HCL" rather than trying to represent
individual revisions as resources:

- A `terraform_data.profile` resource's `triggers_replace` is a SHA-256 hash
  of the entire desired spec (every variable below, including
  `tunnel_egress`). Terraform's own diff of that hash is what makes re-apply
  idempotent — unless something in the HCL actually changed, this resource
  shows no diff and its `local-exec` provisioner never runs, so a repeat
  `terraform apply` performs **zero** FleetService calls.
- When the hash *does* change, the provisioner runs
  `scripts/fleet-client.sh apply`, which calls `CreateProfile` if the profile
  doesn't exist yet, or `UpdateProfile` (append a revision) if it does — no
  further remote diffing, since the trigger firing already answered "did
  anything change?".
- A `data "external" "current"` block (`depends_on` the resource above, so it
  reads *after* apply) reports the profile's actual state back as this
  module's outputs (`latest_revision`, `docker_image_digest`,
  `image_status`, `verification_status`, `fallback_eligible`).

### Digest CAS (`expected_docker_image_digest`)

`docker_image` is a tag ref (e.g. `...:latest`), and FleetService resolves it
to an immutable digest server-side. Every time this module actually runs
(i.e. the trigger hash changed), the script previews that resolution with a
read-only `ResolveImage` call, then passes the digest straight back as
`expected_docker_image_digest` on the `Create`/`UpdateProfile` call — the
same preview-then-pin flow the console's UI uses, which the CLI's
`admin profiles update` never exercises (it always sends an empty CAS token,
"resolve fresh, no check"). The backend re-resolves the tag at mutation time
and rejects the write (`FAILED_PRECONDITION`) if a concurrent change moved it
between the preview and the pin — so this module never silently pins a
different digest than the one the apply run actually observed and logged.

If `ResolveImage` reports `IMAGE_STATUS_UNRESOLVABLE` (missing
`artifactregistry.reader` grant, a typo, a missing tag/repo) or
`IMAGE_STATUS_UNDETERMINED` (transient), `terraform apply` fails outright
with the server's diagnostic detail, rather than creating a revision the
console would separately flag as broken — check that
`target_service_account` has `roles/artifactregistry.reader` on the image's
repository (see `workload-impersonation`'s `artifact_repository_id`).

### What this module does *not* do

A moved tag alone (the upstream `:latest` now points at a new digest) is
**not** auto-detected by a plain `terraform plan`/`apply` with unchanged HCL
— that would need continuous drift detection, which is out of MVP scope (see
"Follow-up"). This mirrors the existing manual/semi-automated path
(`ops/roll-worker`, `admin profiles update`): an explicit trigger — even a
no-op re-set of the same `docker_image` string, which still changes nothing
in the hash today, so in practice re-running `roll-worker`/CI is still the
way to force a fresh pin — is what causes a resolve-and-repin pass.

## Inputs

| Name | Description | Default |
| --- | --- | --- |
| `api_base_url` | FleetService's Connect/JSON base URL, e.g. `module.common.api_url`. | (required) |
| `profile_id` | Stable profile identifier. Immutable — FleetService has no rename RPC. | (required) |
| `display_name` | Console label. Only takes effect on first create. | `profile_id` |
| `target_service_account` | The SA runs are minted a token for. | (required) |
| `docker_image` | Container image tag ref; empty for a pure exec/env profile. | `""` |
| `env_vars` | Plain env vars (`name -> value`). | `{}` |
| `secret_env_vars` | Secret-backed env vars (`name -> Secret Manager resource name`). Never a raw value. | `{}` |
| `drain_deadline` | Supervisor stop-timeout, e.g. `"6h"`. | `""` |
| `note` | Free-text note on the created/updated revision. | `"managed by Terraform (medusa_workload_profile)"` |
| `fallback_eligible` | Opt in to fallback auto-placement. | `false` |
| `tunnel_egress` | See below — accepted but not yet sent to FleetService. | `null` |
| `auth_id_token_audience` | Override the ID token audience (default: `api_base_url`). | `""` |

## Outputs

`profile_id`, `exists`, `latest_revision`, `docker_image_digest`,
`image_status`, `verification_status`, `fallback_eligible`.

## `tunnel_egress`

FleetService's `Profile`/`ProfileRevision` messages don't have a
`tunnel_egress` field yet — that's workload#159, this module's dependency.
The variable exists here already (`enabled`, `target_host`, `target_port`) so
callers can start wiring up their HCL, and it's folded into the
`triggers_replace` hash — but `scripts/fleet-client.sh` does not forward it
anywhere, because there's nowhere on the wire to put it. Once #159 lands
(proto + backend + the CLI's `-f json` spec), this module needs a follow-up
change to add it to the `CreateProfile`/`UpdateProfile` request bodies in
`scripts/fleet-client.sh`; setting a non-null `tunnel_egress` today changes
the trigger hash (so it'll force a resolve-and-repin pass the moment that
follow-up ships) but otherwise has no effect on the created/updated
revision.

## Follow-up (not this module)

This is an MVP wrapper, not a real Terraform provider: no drift detection
(a moved tag or an out-of-band console edit isn't reconciled until the HCL
itself changes), no partial-attribute updates (every apply that runs
resends the whole spec), and state lives in `terraform_data`'s opaque
trigger hash rather than a typed resource FleetService itself is the source
of truth for. Graduate to `terraform-provider-medusa-workload` (Go plugin
SDK, real state/drift) once this shape is proven — see workload#161's
"Follow-up" section.
