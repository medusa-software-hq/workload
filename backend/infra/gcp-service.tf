# Dedicated service account for the Cloud Run service.
resource "google_service_account" "primary_service_sa" {
  project      = var.gcp_project_id
  account_id   = "${module.common.gcp_api_run_service_name}-sa"
  display_name = "Cloud Run Service Account"
}

# The primary Cloud Run service for the app
resource "google_cloud_run_v2_service" "primary" {
  project             = var.gcp_project_id
  name                = module.common.gcp_api_run_service_name
  location            = module.common.gcp_primary_location
  deletion_protection = false # This project is experimental
  ingress             = "INGRESS_TRAFFIC_ALL"

  template {
    service_account = google_service_account.primary_service_sa.email

    containers {
      # Initial placeholder; CI/CD will deploy the real image from Artifact Registry.
      image = "us-docker.pkg.dev/cloudrun/container/hello"

      ports {
        container_port = 8080
      }

      env {
        name  = "GOOGLE_CLIENT_ID"
        value = module.common.google_client_id
      }

      env {
        name  = "GOOGLE_ALLOWED_DOMAIN"
        value = module.common.organization_domain
      }

      env {
        name  = "CORS_ALLOWED_ORIGIN_REGEX"
        value = "https://[a-z0-9-]+\\.medusa\\.software"
      }

      env {
        name = "DATABASE_URL"
        value_source {
          secret_key_ref {
            secret  = google_secret_manager_secret.database_url.secret_id
            version = "latest"
          }
        }
      }

      env {
        name  = "WORKER_TOKEN_BROKER_ENABLED"
        value = "true"
      }

      env {
        name  = "TARGET_SERVICE_ACCOUNT_EMAIL"
        value = var.target_service_account_email
      }

      env {
        name  = "BOOTSTRAP_TOKEN_SECRET_NAME"
        value = "projects/${var.gcp_project_id}/secrets/${google_secret_manager_secret.worker_mvp_bootstrap_token.secret_id}/versions/latest"
      }

      env {
        name  = "TOKEN_LIFETIME_SECONDS"
        value = "900"
      }
    }
  }

  depends_on = [google_secret_manager_secret_version.database_url]

  # The image is managed by CI/CD after initial creation.
  # Env vars are managed by Terraform and must not be overwritten by deploys.
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

# Allow unauthenticated (public) access — no auth for now.
resource "google_cloud_run_v2_service_iam_member" "public_invoker" {
  project  = google_cloud_run_v2_service.primary.project
  location = google_cloud_run_v2_service.primary.location
  name     = google_cloud_run_v2_service.primary.name
  role     = "roles/run.invoker"
  member   = "allUsers"
}

output "cloud_run_primary_service_url" {
  value = google_cloud_run_v2_service.primary.uri
}
