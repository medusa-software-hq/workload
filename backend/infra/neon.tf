# Neon (serverless Postgres) project backing the counter store.
resource "neon_project" "main" {
  name      = "${module.common.project_base_name}-${module.common.project_variant}"
  region_id = "aws-eu-central-1"

  # The provider defaults this to 86400s (24h), which exceeds the Free plan's
  # maximum of 21600s (6h). Point-in-time restore isn't needed for a counter, so
  # pin it to the plan maximum to keep the project provisionable on the Free tier.
  history_retention_seconds = 21600
}

# JDBC connection string for the default branch/database/role. We use the direct
# (non-pooled) endpoint (`database_host`, not `database_host_pooler`) so Flyway's
# session-level advisory lock works reliably at startup; PgBouncer's transaction
# pooling would make that lock unreliable. The per-instance Hikari pool is small and
# Cloud Run runs few instances, so direct connections are well within Neon's limits.
#
# We build a native pgjdbc URL from Neon's structured attributes rather than reusing
# its `connection_uri`. pgjdbc does NOT accept libpq-style `user:password@host`
# userinfo (it rejects the URL in acceptsURL); credentials must be host/database in
# the URL with user/password as query parameters. `sslmode=require` is mandatory for
# Neon, and `urlencode` guards against special characters in the generated password.
locals {
  database_jdbc_url = join("", [
    "jdbc:postgresql://",
    neon_project.main.database_host,
    "/",
    neon_project.main.database_name,
    "?sslmode=require",
    "&user=", urlencode(neon_project.main.database_user),
    "&password=", urlencode(neon_project.main.database_password),
  ])
}
