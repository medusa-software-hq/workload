# The target service account that worker CLIs impersonate via the broker endpoint.
resource "google_service_account" "worker_mvp_target" {
  project      = local.test_project_id
  account_id   = "worker-mvp"
  display_name = "Worker MVP target service account"

  depends_on = [google_project_service.apis]
}

locals {
  # The EXISTING Cloud Run service account in the main project (backend/infra's
  # primary_service_sa), computed deterministically rather than via cross-root state.
  broker_service_account_email = "${module.common.gcp_api_run_service_name}-sa@${var.gcp_project_id}.iam.gserviceaccount.com"
}

# Grant the broker (existing Cloud Run) service account permission to impersonate the
# target SA — scoped to this one SA resource, not project-wide.
resource "google_service_account_iam_member" "broker_can_impersonate_target" {
  service_account_id = google_service_account.worker_mvp_target.name
  role               = "roles/iam.serviceAccountTokenCreator"
  member             = "serviceAccount:${local.broker_service_account_email}"
}
