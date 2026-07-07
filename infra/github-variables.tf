# Actions variables consumed by CI/CD jobs

resource "github_actions_variable" "gcp_project_id" {
  repository    = github_repository.this.name
  variable_name = "GCP_PROJECT_ID"
  value         = google_project.gcp_project.project_id
}

resource "github_actions_variable" "gcp_primary_location" {
  repository    = github_repository.this.name
  variable_name = "GCP_PRIMARY_LOCATION"
  value         = module.common.gcp_primary_location
}

resource "github_actions_variable" "gcp_api_run_service_name" {
  repository    = github_repository.this.name
  variable_name = "GCP_API_RUN_SERVICE_NAME"
  value         = module.common.gcp_api_run_service_name
}

resource "github_actions_variable" "gcp_web_run_service_name" {
  repository    = github_repository.this.name
  variable_name = "GCP_WEB_RUN_SERVICE_NAME"
  value         = module.common.gcp_web_run_service_name
}

resource "github_actions_variable" "gcp_cicd_sa_email" {
  repository    = github_repository.this.name
  variable_name = "GCP_CICD_SA_EMAIL"
  value         = google_service_account.cicd_sa.email
}

resource "github_actions_variable" "gcp_ar_repo_hostname" {
  repository    = github_repository.this.name
  variable_name = "GCP_AR_REPO_HOSTNAME"
  value         = split("/", google_artifact_registry_repository.primary.registry_uri)[0]
}

resource "github_actions_variable" "gcp_ar_repo_endpoint" {
  repository    = github_repository.this.name
  variable_name = "GCP_AR_REPO_ENDPOINT"
  value         = local.gcp_ar_repo_endpoint
}

resource "github_actions_variable" "gcp_api_url" {
  repository    = github_repository.this.name
  variable_name = module.common.gh_api_url_var_name

  # Managed manually (for now):
  # https://github.com/medusa-software-hq/counter/settings/variables/actions/API_URL
  value = "https://example.com/placeholder"

  lifecycle {
    ignore_changes = [value]
  }
}

# Consumed by the web frontend build (baked into the JS bundle).
resource "github_actions_variable" "google_client_id" {
  repository    = github_repository.this.name
  variable_name = "GOOGLE_CLIENT_ID"
  value         = module.common.google_client_id
}

resource "github_actions_variable" "google_allowed_domain" {
  repository    = github_repository.this.name
  variable_name = "GOOGLE_ALLOWED_DOMAIN"
  value         = module.common.organization_domain
}
