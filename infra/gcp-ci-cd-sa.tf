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

# s2s admin principal (M5-05): a dedicated CI identity that calls the *admin plane* as a service
# principal — the runner impersonates it via WIF, then mints an ID token for the API's own audience
# (the API allow-lists this SA's email in ADMIN_SERVICE_ACCOUNTS). Deliberately separate from the
# deploy SA (github-actions) above: deploying and administering are different powers, and the
# allowlist is the admin boundary. Per env (its own project via the workspace), so a staging token
# is never accepted by prod (different SA email *and* different API audience).
resource "google_service_account" "ci_admin" {
  project      = local.gcp_project_id
  account_id   = "workload-ci-admin"
  display_name = "CI admin (s2s admin-plane principal)"

  depends_on = [google_project_service.apis["iam.googleapis.com"]]
}

# Allow GitHub Actions from this repo to impersonate the CI admin SA via WIF (repo-pinned, same
# pattern as the deploy SA).
resource "google_service_account_iam_member" "ci_admin_wi_user" {
  service_account_id = google_service_account.ci_admin.name
  role               = "roles/iam.workloadIdentityUser"
  member             = "principalSet://iam.googleapis.com/${local.gcp_cicd_wi_pool_name}/attribute.repository/${module.common.gh_organization_name}/${module.common.gh_repo_name}"
}

output "gcp_ci_admin_sa_email" {
  description = "GCP CI admin service account e-mail (the s2s admin-plane principal)."
  value       = google_service_account.ci_admin.email
}

# Flow worker digest-push principal (Phase 2 of the automated-rollout epic, workload#126): a
# separate, narrower s2s principal Flow's own CI impersonates via WIF (from *its* repo, not this
# one — the WI binding below is intentionally left for that repo's own Terraform to grant) to push
# a freshly-published worker image's digest into the flow-worker profile. Allow-listed on
# ADMIN_SERVICE_ACCOUNTS like ci_admin, but given a non-empty profile scope
# (ADMIN_SERVICE_ACCOUNT_PROFILE_SCOPES, see backend/infra/gcp-service.tf) so it can only ever
# create/update the flow-worker profile — never approve workers, grant profiles, or touch any
# other profile, unlike the unrestricted ci_admin.
resource "google_service_account" "flow_worker_ci" {
  project      = local.gcp_project_id
  account_id   = "flow-worker-ci"
  display_name = "Flow worker CI (scoped digest-push principal)"

  depends_on = [google_project_service.apis["iam.googleapis.com"]]
}

output "gcp_flow_worker_ci_sa_email" {
  description = "GCP scoped CI principal e-mail Flow's release automation impersonates to push the flow-worker profile's digest."
  value       = google_service_account.flow_worker_ci.email
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
