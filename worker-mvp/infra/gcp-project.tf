# GCP organization data

data "google_organization" "gcp_organization" {
  domain = module.common.organization_domain
}

data "google_billing_account" "gcp_billing_account" {
  display_name = "My Billing Account"
  open         = true
}

# GCP project

resource "random_id" "gcp_project_random_id" {
  byte_length = 4
}

resource "google_project" "gcp_project" {
  org_id          = data.google_organization.gcp_organization.org_id
  billing_account = data.google_billing_account.gcp_billing_account.id

  name       = "${module.common.project_base_name} - test"
  project_id = "${module.common.gcp_organization_prefix}-${module.common.project_base_name}-test-${random_id.gcp_project_random_id.hex}"

  auto_create_network = false
}

locals {
  test_project_id = google_project.gcp_project.project_id
}

# Enabled GCP APIs
resource "google_project_service" "apis" {
  for_each = toset([
    "iam.googleapis.com",
    "iamcredentials.googleapis.com",
    "storage.googleapis.com",
    "secretmanager.googleapis.com",
  ])

  project            = google_project.gcp_project.id
  service            = each.key
  disable_on_destroy = false
}
