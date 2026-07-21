# Promotion & deployment model (M5-04)

How code and infrastructure reach staging and prod, and how to roll back.

Every deployable promotes **staging → smoke → prod in a single run**. Prod never
deploys unless the *same run's* staging deploy + smoke are green — the gate is a
job dependency (`deploy-prod` `needs: [deploy-staging, smoke]`), because required
reviewers on a `production` GitHub Environment are Enterprise-only for private
repos on this plan.

## The pipelines

| Deployable | Workflow | Trigger | Model |
|---|---|---|---|
| API broker | `promote-api.yml` | push `backend/api/impl/**` | **build-once, promote-by-digest** |
| Web SPA | `promote-web-spa.yml` | push `apps/web/spa/**`, `proto/**` | **build-per-env** (bakes `VITE_*`) |
| Backend infra | `apply-backend-infra.yml` | push `backend/infra/**` | `[staging, production]` matrix |
| Web infra | `apply-web-infra.yml` | push `apps/web/infra/foundation/**` | `[staging, production]` matrix |
| Web domain mapping | `apply-web-domain-mapping.yml` | push `apps/web/infra/domain-mapping/**` | `[staging, production]` matrix |
| Front door (Worker) | `deploy-front-door.yml` | `workflow_dispatch` (env input) | per-env (M5-03) |

### API — promote-by-digest

The image is built in **each environment's own Artifact Registry** (independent
per project, like the Neon DBs). jib builds are reproducible, so the prod-side
rebuild is bit-identical to staging's; `deploy-prod`'s **Assert digest** step
compares the prod digest against `deploy-staging`'s output and **fails closed** if
they differ. That gives "prod runs exactly what staging validated" with zero
cross-environment IAM — no image copy, no cross-project registry grant.

### SPA — build-per-env

The bundle bakes environment config at build (`VITE_API_URL`,
`VITE_GOOGLE_CLIENT_ID`, `VITE_GOOGLE_HD`), so staging and prod images
legitimately differ — there is no shared digest to assert. The promotion property
is **ordering + the smoke gate**, not artifact identity.

### Infra applies — matrix, staging first

`[staging, production]`, `max-parallel: 1`, `fail-fast: true`, **staging first**.
A failed staging apply cancels the still-queued production job, so broken infra
never reaches prod. `environment:` resolves `vars.*` per env; the Terraform
**workspace** (`staging` / `default`) keeps their states apart. Production is the
`default` workspace (its state predates the split).

## Smoke gate (`check-staging-smoke.yml` → `smoke/smoke.sh`)

Runs against the deployed **staging** environment as the CI principal; reused by
both promotion chains, also `workflow_dispatch`-runnable. v1 (deliberately small —
it also closes the M4 "front-door integration test" debt):

1. **Front door reaches the app** — `GET /health` through the Cloudflare hostname
   returns a real app body (proves client → Worker → GFE → app).
2. **IAM lock holds** — a direct hit on the `run.app` origin → `403` at Google's
   edge, no instance start.
3. **gRPC-web is proxied** — lenient transport check (the app answers a gRPC-web
   frame); stricter authenticated streaming is future work.
4. **Admin plane via s2s** — **stub**, marked non-fatal until the s2s principal
   lands (M5-05); the CI runner has no admin credential to call with until then.

## Rollback

Re-run an earlier green run's **`deploy-prod`** job. For the API that redeploys
the exact digest that run validated; for the SPA it rebuilds that commit's
env-baked bundle. (Cloud Run also keeps prior revisions — `gcloud run services
update-traffic <svc> --to-revisions=<rev>=100` is the fastest manual revert.)

## First-time staging bring-up

Staging only *serves* once its infra + services exist. Order (one-time, via
`workflow_dispatch` against trunk so the chains don't gate prod on an empty
staging): apply `infra` staging → `apply-backend-infra` (staging) → `apply-web-infra`
+ `apply-web-domain-mapping` (staging) → `promote-api` / `promote-web-spa` →
`deploy-front-door` (environment: staging). Then the push triggers are steady-state.
