# The target service account that worker CLIs impersonate, via a profile pointing at it in the
# Workload console.
resource "google_service_account" "worker_mvp_target" {
  project      = local.test_project_id
  account_id   = "worker-mvp"
  display_name = "Worker MVP target service account"

  depends_on = [google_project_service.apis]
}

# Opts this SA in to being impersonated by the Workload broker — the first real consumer of the
# shared module, in place of the old hand-rolled cross-project binding. Also opts it in to
# reading the sample secret below, the first real consumer of the module's secret_ids input.
module "workload_impersonation" {
  source = "../../infra/modules/workload-impersonation"

  service_account_id    = google_service_account.worker_mvp_target.name
  service_account_email = google_service_account.worker_mvp_target.email
  secret_ids            = [google_secret_manager_secret.worker_mvp_sample.id]
}
