# Backend Terraform configuration

Provisions the resources the backend needs. Currently that's just the API, but this
is meant to stay a single Terraform config as other backend services (e.g. a worker)
are added:

- the Cloud Run service and its service account (`gcp-service.tf`)
- a **Neon serverless Postgres** project backing the workload database (`neon.tf`)
- a **Secret Manager** secret holding the Neon JDBC connection string, injected
  into Cloud Run as `DATABASE_URL` (`gcp-secret-manager.tf`)

## Spend guardrails

The backend's origin (`run.app`) is **IAM-locked** (M4-A6): only the Cloudflare
front-door Worker, carrying an invoker ID token, gets past Google's front end —
every other caller is rejected there for free, before any instance starts. The
public surface is the Cloudflare front door, which forwards legitimate traffic on
to the origin. Abuse can still drive billable Cloud Run work *through* the front
door, so the failure mode under load remains a runaway Cloud Run bill rather than
an outage. Two layers bound that:

- **`max_instance_count` cap** on both the API and web services
  (`gcp-service.tf`). This is a deliberate **bills-over-availability** trade: the
  worst an abusive spike can achieve is running the cap hot and degrading
  service — never an open-ended bill. For an in-house tool, protecting the bill
  matters more than protecting availability. The cap is generous enough for the
  real fleet plus the admin console; bump it **consciously, not reflexively**,
  and only after confirming the load is legitimate.
- **Budget alert** on the billing account (manual — see below), so a slow leak
  that stays under the instance cap still surfaces.

### Budget alert (manual step)

A GCP budget lives at the **billing-account** level, not the project level, so
it isn't managed by this project's Terraform: the billing account ID is not a
repo value and creating a budget needs `billing.budgets.*` on the billing
account, which the CI identity doesn't hold. Set it up once by hand (Console →
**Billing → Budgets & alerts → Create budget**, or `gcloud billing budgets
create`):

- Scope the budget to this project's billing account (optionally filter to the
  `workload` project).
- Set a small monthly amount appropriate for an experimental in-house service.
- Alert thresholds at **50 % / 90 % / 100 %** of actual spend, delivered to the
  team's email.

Verify by lowering the amount so a threshold trips on current spend (or
hand-check the configuration if test-firing isn't practical), then restore it.

## Front-door refresher monitoring

The M4-A6 front door depends on one Cloud Run job — `front-door-refresher` — to
keep the Cloudflare Worker's invoker ID token fresh (pushed every 15 min, valid
~60 min). If that loop silently stops, the **entire API goes dark ~45 min later
with no other signal**. `gcp-monitoring.tf` guards it with two Cloud Monitoring
alert policies, both notifying the `alerts@medusa.software` group (an `email`
notification channel; the group is hand-created in the Workspace admin console —
Terraform/CI lacks group-admin rights):

- **Execution failed** — a threshold on `run.googleapis.com/job/completed_execution_count`
  with `result="failed"`: an execution ran but returned non-zero.
- **No successful run in 20m** — an MQL `absent_for` (missing-data) condition on
  the `result="succeeded"` series. Unlike a threshold, this also fires when the
  job stops emitting entirely — a paused/deleted Cloud Scheduler trigger, a
  deleted job — which is the failure mode most likely to go unnoticed. The 20-min
  window trips after a single fully-missed 15-min run, leaving ~40 min of runway
  before the current token actually expires.

The notification channel targets a **Google Group**, not an individual, so the
alert survives people coming and going.

### Verifying the alerts (hand-test)

Neither policy can be exercised in CI — both need real job telemetry over real
wall-clock time:

- **Staleness:** pause the Cloud Scheduler trigger
  (`gcloud scheduler jobs pause front-door-refresher --location=<region>`), wait
  ~25 min, confirm the "no successful run in 20m" alert fires and an email
  reaches the group; then resume
  (`gcloud scheduler jobs resume front-door-refresher …`) and confirm it clears.
- **Failure:** trigger one execution with an argument override that makes the job
  exit non-zero (e.g. a bogus `CF_ACCOUNT_ID`), confirm the "execution failed"
  alert fires, then let a normal run auto-close it.

## Neon provisioning

The Neon project is created via the `kislerdm/neon` provider, which requires an API
key passed as the `neon_api_key` variable (set in CI via
`TF_VAR_neon_api_key` / the `NEON_API_KEY` secret).

The connection string uses Neon's **direct (non-pooled)** endpoint so that Flyway's
session-level advisory lock works reliably at startup; PgBouncer's transaction
pooling would make that lock unreliable. The per-instance Hikari pool is small and
Cloud Run runs few instances, so direct connections stay well within Neon's limits.

## Note: database credentials in Terraform state

The Neon connection string (including the password) is stored in Terraform state.
This is **unavoidable once Terraform provisions the Neon project**: the `neon`
provider exposes `connection_uri` / `database_password` as sensitive attributes that
land in state regardless of whether we also copy the URL into Secret Manager. State
lives in the access-controlled, encrypted GCS backend bucket and the values are
marked `sensitive`. For this experimental template that is an acceptable trade-off.
Removing secrets from state entirely would require provisioning Neon out-of-band and
injecting the URL as a variable/data source, rather than managing it here.
