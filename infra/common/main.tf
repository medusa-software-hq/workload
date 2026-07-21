terraform {
  required_version = ">= 1.14"
}

locals {
  # Deployment environment, derived from the Terraform workspace. The `default` workspace is
  # production — its state predates the prod/staging split, so it stays in place (no state
  # migration); every other workspace is a named non-prod environment. This is the single dimension
  # that distinguishes prod from staging across every root that imports this module.
  environment = terraform.workspace == "default" ? "prod" : terraform.workspace

  # Per-environment values. Everything *outside* this map is shared across environments (same GCP
  # meta project, state bucket, region, org domain, …); only what genuinely differs per environment
  # lives here. `default`/prod resolves to exactly the values used before this split, so introducing
  # the workspace dimension is a no-op on the prod state.
  environment_config = {
    prod = {
      # Suffix appended to the GCP project's display name. Empty for prod: its project predates the
      # split and must not be renamed.
      gcp_project_name_suffix = ""

      # Public hostname label under organization_domain: the web app lives at <label>.<domain> and
      # the API front door at api.<label>.<domain>. Prod keeps its pre-split value.
      subdomain_label = "workload-baseline"

      # The GitHub deployment Environment holding this environment's Actions variables. Note prod's
      # is "production", not "prod".
      gh_environment_name = "production"

      # Google OAuth 2.0 client ID — the console SPA's Web client; the audience of the *user* tokens
      # this environment's API accepts and the client its SPA signs in with.
      #
      # NOTE: this is still the legacy client in the shared ms-auth project (852264381191 =
      # ms-auth-284371d2), which is being SUNSET. A project-specific replacement already exists in
      # the prod project (136908422577-d9nesf47igb7es3ok4lergqaf2g18aci, origin
      # workload-baseline.medusa.software, Internal) but is not yet wired in — cutting prod over to it
      # is a pending, deliberate step (change this value + redeploy). Until then prod runs the ms-auth
      # client, so ms-auth must not be deleted before the cutover. See M5-02.
      # https://console.cloud.google.com/auth/clients/852264381191-gi3hrcfbkn6mm43qh26b6hl1hmjvlo7a.apps.googleusercontent.com?project=ms-auth-284371d2
      google_client_id = "852264381191-gi3hrcfbkn6mm43qh26b6hl1hmjvlo7a.apps.googleusercontent.com"

      # Google OAuth 2.0 client ID — the `workload admin` CLI's Desktop client. Human CLI sign-ins
      # mint ID tokens with this as their audience; the API accepts it alongside the SPA client.
      # Same sunset story as google_client_id above: legacy ms-auth client; project-specific
      # replacement 136908422577-jllms7h9l7gopqujps2d8e7jvtrc2qgb exists in the prod project, cutover
      # pending.
      # https://console.cloud.google.com/auth/clients/852264381191-8blq9kgof5o0j65cjjb00peqb144hpen.apps.googleusercontent.com?project=ms-auth-284371d2
      cli_client_id = "852264381191-8blq9kgof5o0j65cjjb00peqb144hpen.apps.googleusercontent.com"
    }
    staging = {
      gcp_project_name_suffix = " - staging"

      subdomain_label = "workload-baseline-staging"

      gh_environment_name = "staging"

      # A *separate* OAuth client, authorized for staging's own domain — not a second origin bolted
      # onto prod's client. The API authenticates a user by checking `aud` against this ID
      # (GoogleIdTokenAuthDecorator), so a shared client would make a token minted through staging's
      # SPA indistinguishable from a production one and accepted by the production API. Staging runs
      # not-yet-promoted code; it must not hold a credential production honours. The credential
      # boundary is the environment boundary. These are project-specific clients in the staging
      # project (983402080078 = ms-workload-868c1b71), created for M5-02 — Web client's authorized JS
      # origin is https://workload-baseline-staging.medusa.software; Desktop client is the CLI.
      # https://console.cloud.google.com/auth/clients?project=ms-workload-868c1b71
      google_client_id = "983402080078-p2haqgrghma6pkgqkbov2p38lcbh1041.apps.googleusercontent.com"
      cli_client_id    = "983402080078-rsidgj3id5cv5nqmt6jkgq81v4kpg1tm.apps.googleusercontent.com"
    }
  }
  selected_environment = local.environment_config[local.environment]

  organization_domain = "medusa.software"

  gcp_organization_prefix         = "ms"
  gcp_primary_location            = "europe-west1"
  gcp_meta_project_id             = "ms-meta-9aaf29f0"
  gcp_terraform_state_bucket_name = "ms-tfstate-c1984596bdabf023"

  gcp_api_run_service_name = "api"
  gcp_web_run_service_name = "web"

  # The GitHub org + repo that holds the code and runs CI/CD. This is the SAME for every environment
  # — one repo, one Actions pipeline — so it is a shared constant, NOT part of environment_config.
  # Used for WIF principalSets, the `github` provider owner, and Terraform state prefixes.
  gh_organization_name   = "medusa-software-hq"
  gh_repo_name           = "workload"
  gh_api_url_var_name    = "API_URL"
  gh_default_branch_name = "trunk/baseline"

  project_base_name = "workload"
  project_variant   = "baseline"

  # Where operational alerts (Cloud Monitoring, the hand-made budget alert) are delivered. A Google
  # Group, not an individual — durable across people, no single-person dependency for a load-bearing
  # alert. Hand-created in the Workspace admin console (Terraform/CI lacks group-admin rights). A
  # single shared address across envs — not a per-env value; staging failures reach the same humans.
  alert_notification_email = "alerts@medusa.software"

  # region Per-environment derived values

  gcp_project_name_suffix = local.selected_environment.gcp_project_name_suffix
  subdomain_label         = local.selected_environment.subdomain_label
  gh_environment_name     = local.selected_environment.gh_environment_name
  google_client_id        = local.selected_environment.google_client_id
  cli_client_id           = local.selected_environment.cli_client_id

  # The stable public hostnames, defined once per environment. The web app lives at
  # <subdomain_label>.<domain>; the API front door (M4-A6) is api.<web host>. Both are deterministic
  # — unlike Cloud Run's generated run.app URL — so API_URL can be computed instead of set by hand.
  web_host_name = "${local.subdomain_label}.${local.organization_domain}"
  api_host_name = "api.${local.web_host_name}"
  api_url       = "https://${local.api_host_name}"

  # endregion
}

output "environment" {
  value = local.environment
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

output "gh_default_branch_name" {
  value = local.gh_default_branch_name
}

output "gh_environment_name" {
  value = local.gh_environment_name
}

output "project_base_name" {
  value = local.project_base_name
}

output "project_variant" {
  value = local.project_variant
}

output "gcp_project_name_suffix" {
  value = local.gcp_project_name_suffix
}

output "subdomain_label" {
  value = local.subdomain_label
}

output "web_host_name" {
  value = local.web_host_name
}

output "api_host_name" {
  value = local.api_host_name
}

output "api_url" {
  value = local.api_url
}

output "google_client_id" {
  value = local.google_client_id
}

output "cli_client_id" {
  value = local.cli_client_id
}

output "alert_notification_email" {
  value = local.alert_notification_email
}
