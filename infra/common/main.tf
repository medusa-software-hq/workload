terraform {
  required_version = ">= 1.14"
}

locals {
  organization_domain = "medusa.software"

  gcp_organization_prefix         = "ms"
  gcp_primary_location            = "europe-west1"
  gcp_meta_project_id             = "ms-meta-9aaf29f0"
  gcp_terraform_state_bucket_name = "ms-tfstate-c1984596bdabf023"

  gcp_api_run_service_name = "api"
  gcp_web_run_service_name = "web"

  gh_organization_name = "medusa-software-hq"
  gh_repo_name         = "workload"
  gh_api_url_var_name  = "API_URL"

  project_base_name = "workload"
  project_variant   = "baseline"

  # Google OAuth 2.0 client ID
  # https://console.cloud.google.com/auth/clients/852264381191-crah9udgr1d44tposdv348k091t2upb6.apps.googleusercontent.com?project=ms-auth-284371d2
  # 🎨 TEMPLATE POST-EJECT: Create a new OAuth Client ID manually (🔗 https://console.cloud.google.com/auth/clients/create?project=ms-auth-284371d2) and change it here 👆
  google_client_id = "852264381191-crah9udgr1d44tposdv348k091t2upb6.apps.googleusercontent.com"
}

output "organization_domain" {
  value = local.organization_domain
}

output "gcp_organization_prefix" {
  value = local.gcp_organization_prefix
}

output "gcp_primary_location" {
  value = local.gcp_primary_location
}

output "gcp_meta_project_id" {
  value = local.gcp_meta_project_id
}

output "gcp_terraform_state_bucket_name" {
  value = local.gcp_terraform_state_bucket_name
}

output "gcp_api_run_service_name" {
  value = local.gcp_api_run_service_name
}

output "gcp_web_run_service_name" {
  value = local.gcp_web_run_service_name
}

output "gh_organization_name" {
  value = local.gh_organization_name
}

output "gh_repo_name" {
  value = local.gh_repo_name
}

output "gh_api_url_var_name" {
  value = local.gh_api_url_var_name
}

output "project_base_name" {
  value = local.project_base_name
}

output "project_variant" {
  value = local.project_variant
}

output "google_client_id" {
  value = local.google_client_id
}
