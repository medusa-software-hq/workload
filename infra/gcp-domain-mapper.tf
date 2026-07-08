# Wiring for the shared org-level domain-mapping service account (defined in the
# `meta` repo). This project lets that SA create Cloud Run domain mappings here,
# so the local CI/CD SA never needs to be a domain owner.

locals {
  domain_mapper_sa_email  = data.terraform_remote_state.shared.outputs.domain_mapper_sa_email
  domain_mapper_sa_member = "serviceAccount:${local.domain_mapper_sa_email}"
}

# Narrow project-level role: manage Cloud Run domain mappings only —
# deliberately not roles/run.admin, so the shared SA can never deploy or reach
# data, only create/redirect/delete domain mappings.
resource "google_project_iam_custom_role" "domain_mapper" {
  project     = local.gcp_project_id
  role_id     = "domainMapper"
  title       = "Cloud Run Domain Mapper"
  description = "Create and manage Cloud Run domain mappings."

  permissions = [
    "run.domainmappings.create",
    "run.domainmappings.delete",
    "run.domainmappings.get",
    "run.domainmappings.list",
    "run.domainmappings.update",
    "run.operations.get",
    "run.services.get",
  ]
}

resource "google_project_iam_member" "domain_mapper_role" {
  project = local.gcp_project_id
  role    = google_project_iam_custom_role.domain_mapper.id
  member  = local.domain_mapper_sa_member
}

# Let this repo's GitHub Actions impersonate the shared domain-mapper SA via WIF
# (per-repo enrollment — additive, so each project enrolls only itself).
resource "google_service_account_iam_member" "domain_mapper_wi_user" {
  service_account_id = "projects/${module.common.gcp_meta_project_id}/serviceAccounts/${local.domain_mapper_sa_email}"
  role               = "roles/iam.workloadIdentityUser"
  member             = "principalSet://iam.googleapis.com/${local.gcp_cicd_wi_pool_name}/attribute.repository/${module.common.gh_organization_name}/${module.common.gh_repo_name}"
}

# Let the shared domain-mapper SA read/write only this project's domain-mapping
# Terraform state prefix.
resource "google_storage_bucket_iam_member" "domain_mapper_state" {
  bucket = module.common.gcp_terraform_state_bucket_name
  role   = "roles/storage.objectAdmin"
  member = local.domain_mapper_sa_member

  condition {
    title       = "web_domain_mapping_prefix_only"
    description = "Read/write only the web domain-mapping Terraform state prefix"
    expression  = "resource.name.startsWith('projects/_/buckets/${module.common.gcp_terraform_state_bucket_name}/objects/projects/${module.common.project_base_name}/${module.common.project_variant}/apps/web/domain-mapping/')"
  }
}
