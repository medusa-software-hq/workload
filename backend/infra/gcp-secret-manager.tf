# Secret holding the Neon JDBC connection string, injected into Cloud Run.
resource "google_secret_manager_secret" "database_url" {
  project   = var.gcp_project_id
  secret_id = "${module.common.gcp_api_run_service_name}-database-url"

  replication {
    auto {}
  }
}

resource "google_secret_manager_secret_version" "database_url" {
  secret      = google_secret_manager_secret.database_url.id
  secret_data = local.database_jdbc_url
}

# Allow the Cloud Run service account to read the connection-string secret.
resource "google_secret_manager_secret_iam_member" "primary_service_sa_database_url_accessor" {
  project   = var.gcp_project_id
  secret_id = google_secret_manager_secret.database_url.secret_id
  role      = "roles/secretmanager.secretAccessor"
  member    = "serviceAccount:${google_service_account.primary_service_sa.email}"
}

# Secret container for the worker MVP bootstrap token. The actual secret value is created
# out-of-band (e.g. `gcloud secrets versions add`) — Terraform only manages the container.
resource "google_secret_manager_secret" "worker_mvp_bootstrap_token" {
  project   = var.gcp_project_id
  secret_id = "worker-mvp-bootstrap-token"

  replication {
    auto {}
  }
}

# Allow the Cloud Run service account to read the bootstrap token.
resource "google_secret_manager_secret_iam_member" "primary_service_sa_worker_mvp_bootstrap_token_accessor" {
  project   = var.gcp_project_id
  secret_id = google_secret_manager_secret.worker_mvp_bootstrap_token.secret_id
  role      = "roles/secretmanager.secretAccessor"
  member    = "serviceAccount:${google_service_account.primary_service_sa.email}"
}
