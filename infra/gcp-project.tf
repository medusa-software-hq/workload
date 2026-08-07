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

  # Per environment: "workload - baseline" on prod (suffix empty — the existing project must not be
  # renamed), "workload - baseline - staging" on the staging workspace. The project_id keeps its
  # random suffix; each workspace has its own state, hence its own random_id, hence a distinct project.
  name       = "${module.common.project_base_name} - ${module.common.project_variant}${module.common.gcp_project_name_suffix}"
  project_id = "${module.common.gcp_organization_prefix}-${module.common.project_base_name}-${random_id.gcp_project_random_id.hex}"

  auto_create_network = false
}

locals {
  gcp_project_id = google_project.gcp_project.project_id
}

# Enabled GCP APIs
resource "google_project_service" "apis" {
  for_each = toset([
    "artifactregistry.googleapis.com",
    "cloudresourcemanager.googleapis.com",
    "cloudscheduler.googleapis.com",
    # The fallback node's one GCE VM (gcp-fallback-node.tf) — nothing needed this before, since
    # everything else in this project is serverless (Cloud Run).
    "compute.googleapis.com",
    "iam.googleapis.com",
    "iamcredentials.googleapis.com",
    "iap.googleapis.com",
    "monitoring.googleapis.com",
    "orgpolicy.googleapis.com",
    "run.googleapis.com",
    "secretmanager.googleapis.com",
    "serviceusage.googleapis.com",
    "storage.googleapis.com",
  ])

  project            = google_project.gcp_project.id
  service            = each.key
  disable_on_destroy = false
}

# Override the org-level iam.allowedPolicyMemberDomains constraint at the project level to allow allUsers on the public
# assets bucket.
resource "google_org_policy_policy" "allow_all_iam_members" {
  provider = google.quota_override

  name   = "projects/${local.gcp_project_id}/policies/iam.allowedPolicyMemberDomains"
  parent = "projects/${local.gcp_project_id}"

  spec {
    rules {
      allow_all = "TRUE"
    }
  }

  depends_on = [google_project_service.apis["orgpolicy.googleapis.com"]]
}

# Outputs

output "gcp_project_id" {
  description = "GCP project ID."
  value       = local.gcp_project_id
}
