# Artifact Registry

resource "google_artifact_registry_repository" "primary" {
  project       = google_project.gcp_project.project_id
  location      = module.common.gcp_primary_location
  repository_id = module.common.project_base_name
  format        = "DOCKER"
  description   = "Primary artifact repository."
}

locals {
  # `registry_uri` means "repository endpoint", for example: us-docker.pkg.dev/my-proj/my-repo (not a URI at all)
  gcp_ar_repo_endpoint = google_artifact_registry_repository.primary.registry_uri
}

# Outputs

output "gcp_ar_repo_endpoint" {
  description = "Artifact Registry repository endpoint"
  value       = local.gcp_ar_repo_endpoint
}
