# The M4-A6 front-door token refresher (see backend/front-door-refresher + apps/front-door). A Cloud
# Run job, triggered by Cloud Scheduler, that keeps the Worker's INVOKER_ID_TOKEN secret fresh
# keylessly: running as front-door-invoker it self-mints that SA's ID token and pushes it to
# Cloudflare. Moved off GitHub Actions' best-effort cron onto Cloud Scheduler (reliable, GCP-native).

locals {
  cloudflare_account_id = "b703ded0019355a0913800063af2a5f5"

  # Per-environment Cloudflare Worker name — must match the name the Deploy front door workflow
  # deploys (apps/front-door/wrangler.toml: top-level for prod, [env.staging] for staging). prod
  # keeps the bare name (top-level wrangler env); non-prod envs get an "-<env>" suffix.
  front_door_worker_name = "workload-front-door${module.common.environment == "prod" ? "" : "-${module.common.environment}"}"
}

# The scoped Cloudflare token (Workers Scripts: Edit only — not the broad deploy token) the job uses
# to update the Worker secret. Set in CI via TF_VAR_cloudflare_refresh_token / the
# CLOUDFLARE_REFRESH_TOKEN secret; stored in Secret Manager and read by the job.
variable "cloudflare_refresh_token" {
  description = "Scoped Cloudflare API token (Workers Scripts: Edit) used only to update the front-door Worker's secret."
  type        = string
  sensitive   = true
}

resource "google_secret_manager_secret" "cloudflare_refresh_token" {
  project   = var.gcp_project_id
  secret_id = "cloudflare-front-door-refresh-token"

  replication {
    auto {}
  }
}

resource "google_secret_manager_secret_version" "cloudflare_refresh_token" {
  secret      = google_secret_manager_secret.cloudflare_refresh_token.id
  secret_data = var.cloudflare_refresh_token
}

resource "google_secret_manager_secret_iam_member" "front_door_invoker_reads_cf_token" {
  project   = var.gcp_project_id
  secret_id = google_secret_manager_secret.cloudflare_refresh_token.secret_id
  role      = "roles/secretmanager.secretAccessor"
  member    = "serviceAccount:${google_service_account.front_door_invoker.email}"
}

# The refresher job runs *as* front-door-invoker, so it self-mints that SA's ID token from the
# metadata server (audience = the broker's run.app URL) — no impersonation, no key.
resource "google_cloud_run_v2_job" "front_door_refresher" {
  project             = var.gcp_project_id
  name                = "front-door-refresher"
  location            = module.common.gcp_primary_location
  deletion_protection = false

  template {
    template {
      service_account = google_service_account.front_door_invoker.email
      max_retries     = 2
      timeout         = "120s"

      containers {
        # Placeholder; the Deploy front-door refresher workflow pushes the real jib image.
        image = "us-docker.pkg.dev/cloudrun/container/hello"

        env {
          name  = "TARGET_AUDIENCE"
          value = google_cloud_run_v2_service.primary.uri
        }
        env {
          name  = "CF_ACCOUNT_ID"
          value = local.cloudflare_account_id
        }
        env {
          name  = "CF_WORKER_NAME"
          value = local.front_door_worker_name
        }
        env {
          name = "CF_API_TOKEN"
          value_source {
            secret_key_ref {
              secret  = google_secret_manager_secret.cloudflare_refresh_token.secret_id
              version = "latest"
            }
          }
        }
      }
    }
  }

  # The image is managed by CI/CD after initial creation; env/SA stay Terraform-managed.
  lifecycle {
    # noinspection HILUnresolvedReference
    ignore_changes = [
      template[0].template[0].containers[0].image,
      client,
      client_version,
    ]
  }

  depends_on = [
    google_secret_manager_secret_version.cloudflare_refresh_token,
    google_secret_manager_secret_iam_member.front_door_invoker_reads_cf_token,
  ]
}

# Dedicated identity for the scheduler → job trigger; its only power is running this one job.
resource "google_service_account" "front_door_scheduler" {
  project      = var.gcp_project_id
  account_id   = "front-door-scheduler"
  display_name = "Cloud Scheduler → front-door refresher"
}

resource "google_cloud_run_v2_job_iam_member" "scheduler_runs_refresher" {
  project  = var.gcp_project_id
  location = module.common.gcp_primary_location
  name     = google_cloud_run_v2_job.front_door_refresher.name
  role     = "roles/run.invoker"
  member   = "serviceAccount:${google_service_account.front_door_scheduler.email}"
}

resource "google_cloud_scheduler_job" "front_door_refresher" {
  project = var.gcp_project_id
  region  = module.common.gcp_primary_location
  name    = "front-door-refresher"
  # Every 15 min; the ID token is valid ~60 min, so ~4x overlap absorbs a delayed/missed run.
  schedule  = "*/15 * * * *"
  time_zone = "Etc/UTC"

  http_target {
    http_method = "POST"
    uri         = "https://${module.common.gcp_primary_location}-run.googleapis.com/apis/run.googleapis.com/v1/namespaces/${var.gcp_project_id}/jobs/${google_cloud_run_v2_job.front_door_refresher.name}:run"

    oauth_token {
      service_account_email = google_service_account.front_door_scheduler.email
    }
  }
}
