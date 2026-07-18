# Dedicated service account for the Cloud Run frontend service.
resource "google_service_account" "primary_service_sa" {
  project      = var.gcp_project_id
  account_id   = "${module.common.gcp_web_run_service_name}-sa"
  display_name = "Cloud Run Service Account"
}

# The primary Cloud Run service for the web app
resource "google_cloud_run_v2_service" "primary" {
  project             = var.gcp_project_id
  name                = module.common.gcp_web_run_service_name
  location            = module.common.gcp_primary_location
  deletion_protection = false # This project is experimental
  ingress             = "INGRESS_TRAFFIC_ALL"

  # Identity-Aware Proxy enabled directly on the service — no load balancer
  # required. IAP secures every ingress path, including the default run.app URL
  # and the mapped custom domain.
  iap_enabled = true

  template {
    service_account = google_service_account.primary_service_sa.email

    # Hard ceiling on concurrently-billable instances — the same
    # bills-over-availability trade as the API service. This service is already
    # behind IAP, so exposure is lower, but the cap is cheap insurance against a
    # runaway scale-out. Bump it consciously, not reflexively.
    scaling {
      max_instance_count = 3
    }

    containers {
      # Initial placeholder; CI/CD will deploy the real image from Artifact Registry.
      image = "us-docker.pkg.dev/cloudrun/container/hello"

      ports {
        container_port = 8080
      }
    }
  }

  # The image is managed by CI/CD after initial creation.
  lifecycle {
    # noinspection HILUnresolvedReference
    ignore_changes = [
      template[0].containers[0].image,
      client,
      client_version,
    ]
  }

  traffic {
    type    = "TRAFFIC_TARGET_ALLOCATION_TYPE_LATEST"
    percent = 100
  }
}

output "cloud_run_primary_service_url" {
  value = google_cloud_run_v2_service.primary.uri
}
