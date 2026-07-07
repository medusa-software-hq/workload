# 🎨 TEMPLATE POST-EJECT: IAP requires an OAuth consent screen (brand) configured
# for the project. Set it up once in the console
# (https://console.cloud.google.com/auth/branding) before the first apply.

# Ensure the IAP managed service agent exists for this project.
resource "google_project_service_identity" "iap_sa" {
  provider = google-beta
  project  = var.gcp_project_id
  service  = "iap.googleapis.com"
}

# Allow the IAP managed service agent to invoke the Cloud Run frontend service.
resource "google_cloud_run_v2_service_iam_member" "run_invoker_iap" {
  project  = var.gcp_project_id
  location = google_cloud_run_v2_service.primary.location
  name     = google_cloud_run_v2_service.primary.name
  role     = "roles/run.invoker"
  member   = google_project_service_identity.iap_sa.member
}

# Restrict access through IAP to the organization domain.
resource "google_iap_web_cloud_run_service_iam_member" "domain_access" {
  project                = var.gcp_project_id
  location               = google_cloud_run_v2_service.primary.location
  cloud_run_service_name = google_cloud_run_v2_service.primary.name
  role                   = "roles/iap.httpsResourceAccessor"
  member                 = "domain:${module.common.organization_domain}"
}
