# Map the custom subdomain directly to the Cloud Run service (no load balancer).
# Google provisions and manages the TLS certificate for the mapped domain, and
# the IAP policy on the service (in the foundation root) gates access to it.
#
# This root is applied by the shared org-level domain-mapper service account,
# which is a verified owner of the domain (a one-time org bootstrap performed in
# the `meta` repo) — so no per-project domain-ownership step is needed.
resource "google_cloud_run_domain_mapping" "web" {
  name     = local.counter_web_host_name
  location = module.common.gcp_primary_location
  project  = var.gcp_project_id

  metadata {
    namespace = var.gcp_project_id
  }

  spec {
    # The Cloud Run service itself is managed by the foundation root.
    route_name = module.common.gcp_web_run_service_name
  }
}

output "web_url" {
  description = "Public URL of the web app."
  value       = "https://${local.counter_web_host_name}"
}
