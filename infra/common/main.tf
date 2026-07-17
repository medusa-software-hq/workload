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

  # Google OAuth 2.0 client ID — the console SPA's Web client.
  # https://console.cloud.google.com/auth/clients/852264381191-gi3hrcfbkn6mm43qh26b6hl1hmjvlo7a.apps.googleusercontent.com?project=ms-auth-284371d2
  google_client_id = "852264381191-gi3hrcfbkn6mm43qh26b6hl1hmjvlo7a.apps.googleusercontent.com"

  # Google OAuth 2.0 client ID — the `workload admin` CLI's Desktop client. Human sign-ins through
  # the CLI mint ID tokens with this as their audience; the API accepts it alongside the SPA client.
  # https://console.cloud.google.com/auth/clients/852264381191-8blq9kgof5o0j65cjjb00peqb144hpen.apps.googleusercontent.com?project=ms-auth-284371d2
  cli_client_id = "852264381191-8blq9kgof5o0j65cjjb00peqb144hpen.apps.googleusercontent.com"
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

output "cli_client_id" {
  value = local.cli_client_id
}
