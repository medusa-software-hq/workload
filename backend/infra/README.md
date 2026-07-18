# Backend Terraform configuration

Provisions the resources the backend needs. Currently that's just the API, but this
is meant to stay a single Terraform config as other backend services (e.g. a worker)
are added:

- the Cloud Run service and its service account (`gcp-service.tf`)
- a **Neon serverless Postgres** project backing the workload database (`neon.tf`)
- a **Secret Manager** secret holding the Neon JDBC connection string, injected
  into Cloud Run as `DATABASE_URL` (`gcp-secret-manager.tf`)
- a **Secret Manager** secret holding the deployment-wide worker API path prefix
  (`gcp-secret-manager.tf`) — see below

## Worker API path prefix

All worker-facing HTTP endpoints (registration, self-status, the token broker) are
mounted under `/<uuid>/worker/v1/...` purely to shed bot/scanner noise before it
reaches billable logic — it is **not** a security boundary. It is generated and
fully managed by Terraform (`random_uuid.worker_api_path_prefix` in
`gcp-secret-manager.tf`), since there's no human-approval step and rotating it is
just a re-apply (which strands any CLI configs pointing at the old value — an accepted,
crude kill switch).

Retrieve the current value to hand to a new CLI user with:

```sh
terraform output -raw worker_api_path_prefix
```

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
