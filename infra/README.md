# Project-level Terraform configuration

The lowest-level configuration: the GCP project, its enabled APIs, the Artifact
Registry, the CI/CD service account + Workload Identity trust, and the
GitHub Actions variables. This root has **no apply workflow** — it bootstraps
the very SA that CI runs as, so it is applied **by hand** (it also needs the
org `gh_token` variable for the `github` provider).

## Environments (prod / staging) — Terraform workspaces (M5-02)

Environment separation follows flow's pattern: one config, switched by
**Terraform workspace**, with the per-environment differences isolated in
`infra/common`'s `environment_config` map (project name suffix, subdomain label,
GitHub Environment name, OAuth client IDs). Everything else is shared.

- `default` workspace = **prod**. Its state predates the split, so it stays in
  place — no migration. `terraform.workspace == "default"` resolves every
  per-env value to exactly the pre-split prod value, so introducing the
  dimension is a **no-op on prod**.
- `staging` workspace = **staging**. A separate GCP project (its own random id,
  hence its own project), its own hostnames (`workload-baseline-staging.…`), its
  own OAuth clients, and its own `staging` GitHub Environment variables.

The GCS backend already stored the current state under the implicit `default`
workspace, so a `staging` workspace is purely additive (`<prefix>/staging.tfstate`
alongside `<prefix>/default.tfstate`).

### GitHub Actions variables: staged migration

`github-variables.tf` is mid-migration to Environment-scoped variables:

1. **(current)** Both environments get `github_actions_environment_variable`s
   (each workspace writes only its own `gh_environment_name`), **and** the
   legacy repo-level `github_actions_variable`s stay — gated `count = is_prod`
   so a staging apply never clobbers the repo-wide namespace. Workflows still
   declare no `environment:`, so they read the repo-level values and prod is
   untouched.
2. **(M5-04)** Workflows flip to a `[production, staging]` matrix with
   `environment:`, reading the Environment-scoped copies.
3. Drop the repo-level block once nothing reads it.

## Bringing up staging (operator, one-time)

Staging does **not** converge until these hand steps are done (CI/Terraform
lacks the perms; same out-of-band pattern as the APIs and SA roles):

1. **Create the staging OAuth clients** (Web + Desktop) in the Google console
   and paste their IDs into `infra/common`'s `environment_config.staging`
   (`google_client_id`, `cli_client_id`) — a *separate* client is the point (the
   env boundary is the credential boundary: a staging-minted token must fail
   prod's `aud` check).
2. **Apply `.github/config`** first (creates the `production` + `staging`
   GitHub Environments that `github-variables.tf` writes into).
3. **Apply this root in the staging workspace:**
   `terraform workspace new staging` (once) then
   `terraform workspace select staging && terraform apply`. This creates the
   staging GCP project, registry, CI/CD SA + WIF trust, and `staging`
   Environment variables.
4. Per the out-of-band convention, enable `monitoring.googleapis.com` and grant
   the new staging `github-actions@…` SA `roles/monitoring.editor` (see
   `backend/infra/README.md`), then apply the remaining roots
   (`backend/infra`, `apps/web/infra/*`) in the `staging` workspace.
5. The staging **front door** (Cloudflare Worker route, invoker SA, refresher)
   is **M5-03** — staging's API is not reachable through a front door until then.

Prod applies stay no-op throughout (they run in the `default` workspace).
