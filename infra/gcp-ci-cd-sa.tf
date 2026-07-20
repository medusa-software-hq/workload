# Read shared Terraform State

data "terraform_remote_state" "shared" {
  backend = "gcs"

  config = {
    bucket = module.common.gcp_terraform_state_bucket_name
    prefix = "shared/foundation"
  }
}

locals {
  gcp_cicd_wi_pool_name = data.terraform_remote_state.shared.outputs.gcp_cicd_wi_pool_name
}

# CI/CD service account

resource "google_service_account" "cicd_sa" {
  project      = local.gcp_project_id
  account_id   = "github-actions"
  display_name = "CI/CD Service Account"

  depends_on = [google_project_service.apis["iam.googleapis.com"]]
}

# Allow GitHub Actions to impersonate the CI/CD SA via WIF
resource "google_service_account_iam_member" "cicd_sa_wi_user" {
  service_account_id = google_service_account.cicd_sa.name
  role               = "roles/iam.workloadIdentityUser"
  member             = "principalSet://iam.googleapis.com/${local.gcp_cicd_wi_pool_name}/attribute.repository/${module.common.gh_organization_name}/${module.common.gh_repo_name}"
}

# Grant CI/CD SA read access to all Terraform state
resource "google_storage_bucket_iam_member" "cicd_sa_object_viewer" {
  bucket = module.common.gcp_terraform_state_bucket_name
  role   = "roles/storage.objectViewer"
  member = "serviceAccount:${google_service_account.cicd_sa.email}"
}

# Grant CI/CD SA write access to this project's Terraform state prefix
resource "google_storage_bucket_iam_member" "cicd_sa_object_admin" {
  bucket = module.common.gcp_terraform_state_bucket_name
  role   = "roles/storage.objectAdmin"
  member = "serviceAccount:${google_service_account.cicd_sa.email}"

  condition {
    title       = "project_prefix_only"
    description = "Allow read/write access to objects in the project prefix"
    expression  = "resource.name.startsWith('projects/_/buckets/${module.common.gcp_terraform_state_bucket_name}/objects/projects/${module.common.project_base_name}/${module.common.project_variant}/')"
  }
}

# Grant CI/CD SA storage admin on the GCP project (create buckets, upload files)
resource "google_project_iam_member" "cicd_sa_storage_admin" {
  project = local.gcp_project_id
  role    = "roles/storage.admin"
  member  = "serviceAccount:${google_service_account.cicd_sa.email}"
}

# Grant CI/CD SA Cloud Run admin (deploy Cloud Run services)
resource "google_project_iam_member" "cicd_sa_run_admin" {
  project = local.gcp_project_id
  role    = "roles/run.admin"
  member  = "serviceAccount:${google_service_account.cicd_sa.email}"
}

# Grant CI/CD SA Cloud Scheduler admin (manage the front-door refresher's schedule)
resource "google_project_iam_member" "cicd_sa_scheduler_admin" {
  project = local.gcp_project_id
  role    = "roles/cloudscheduler.admin"
  member  = "serviceAccount:${google_service_account.cicd_sa.email}"
}

# Grant CI/CD SA IAP admin (manage IAP access policies)
resource "google_project_iam_member" "cicd_sa_iap_admin" {
  project = local.gcp_project_id
  role    = "roles/iap.admin"
  member  = "serviceAccount:${google_service_account.cicd_sa.email}"
}

# Grant CI/CD SA service account admin (create/manage Cloud Run SAs)
resource "google_project_iam_member" "cicd_sa_sa_admin" {
  project = local.gcp_project_id
  role    = "roles/iam.serviceAccountAdmin"
  member  = "serviceAccount:${google_service_account.cicd_sa.email}"
}

# Grant CI/CD SA service account user (act as service accounts when deploying Cloud Run)
resource "google_project_iam_member" "cicd_sa_sa_user" {
  project = local.gcp_project_id
  role    = "roles/iam.serviceAccountUser"
  member  = "serviceAccount:${google_service_account.cicd_sa.email}"
}

# Grant CI/CD SA Secret Manager admin (manage the DB connection-string secret)
resource "google_project_iam_member" "cicd_sa_secretmanager_admin" {
  project = local.gcp_project_id
  role    = "roles/secretmanager.admin"
  member  = "serviceAccount:${google_service_account.cicd_sa.email}"
}

# Grant CI/CD SA project IAM admin (manage project-level IAM bindings)
resource "google_project_iam_member" "cicd_sa_project_iam_admin" {
  project = local.gcp_project_id
  role    = "roles/resourcemanager.projectIamAdmin"
  member  = "serviceAccount:${google_service_account.cicd_sa.email}"
}

# Grant CI/CD SA Monitoring editor (manage the front-door refresher's alert policies + notification
# channel — backend/infra/gcp-monitoring.tf). NB: the `infra` root has no apply workflow (it
# bootstraps this very SA); it's applied by hand, so this binding was also granted by hand once to
# unblock the backend apply — `gcloud projects add-iam-policy-binding <project> --member=... --role=
# roles/monitoring.editor`. Kept here so the next hand-apply reconciles it.
resource "google_project_iam_member" "cicd_sa_monitoring_editor" {
  project = local.gcp_project_id
  role    = "roles/monitoring.editor"
  member  = "serviceAccount:${google_service_account.cicd_sa.email}"
}

# Allow the CI/CD SA to push images to Artifact Registry.
resource "google_artifact_registry_repository_iam_member" "registry_ci_writer" {
  project    = google_project.gcp_project.project_id
  location   = module.common.gcp_primary_location
  repository = google_artifact_registry_repository.primary.repository_id
  role       = "roles/artifactregistry.writer"
  member     = "serviceAccount:${google_service_account.cicd_sa.email}"
}

# Outputs

output "gcp_cicd_sa_email" {
  description = "GCP CI/CD service account e-mail."
  value       = google_service_account.cicd_sa.email
}
