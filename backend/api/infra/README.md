# Backend API Terraform configuration

Provisions the resources the API needs:

- the Cloud Run service and its service account (`gcp-service.tf`)
- a **Neon serverless Postgres** project backing the counter store (`neon.tf`)
- a **Secret Manager** secret holding the Neon JDBC connection string, injected
  into Cloud Run as `DATABASE_URL` (`gcp-secret-manager.tf`)

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
