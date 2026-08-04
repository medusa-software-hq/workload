# Phase 0 of the automated-rollout epic (workload#126): a scheduled worker version drift/staleness
# check, per environment. See backend/version-drift-checker and gcp-monitoring.tf (the alert
# policies that key off the custom metrics this job writes).
#
# Runs *as* the broker's own runtime SA (primary_service_sa, gcp-service.tf) rather than a dedicated
# identity: resolving a profile's "latest-published" digest requires the same
# roles/iam.serviceAccountTokenCreator impersonation rights the API already holds on every opted-in
# target SA (infra/modules/workload-impersonation), and re-granting that per project for a second SA
# would touch every downstream project's own Terraform — exactly the blast radius Phase 0 is meant to
# avoid. It also already has read access to the fleet database secret below.

resource "google_cloud_run_v2_job" "version_drift_checker" {
  project             = var.gcp_project_id
  name                = "version-drift-checker"
  location            = module.common.gcp_primary_location
  deletion_protection = false

  template {
    template {
      service_account = google_service_account.primary_service_sa.email
      max_retries     = 1
      timeout         = "120s"

      containers {
        # Placeholder; the Deploy version-drift-checker workflow pushes the real jib image.
        image = "us-docker.pkg.dev/cloudrun/container/hello"

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
          name  = "GCP_PROJECT_ID"
          value = var.gcp_project_id
        }
      }
    }
  }

  # The image is managed by CI/CD after initial creation.
  lifecycle {
    # noinspection HILUnresolvedReference
    ignore_changes = [
      template[0].template[0].containers[0].image,
      client,
      client_version,
    ]
  }

  depends_on = [
    google_secret_manager_secret_version.database_url,
    google_secret_manager_secret_iam_member.primary_service_sa_database_url_accessor,
  ]
}

resource "google_project_iam_member" "version_drift_checker_writes_metrics" {
  project = var.gcp_project_id
  role    = "roles/monitoring.metricWriter"
  member  = "serviceAccount:${google_service_account.primary_service_sa.email}"
}

# Dedicated identity for the scheduler → job trigger; its only power is running this one job.
resource "google_service_account" "version_drift_checker_scheduler" {
  project      = var.gcp_project_id
  account_id   = "version-drift-scheduler"
  display_name = "Cloud Scheduler → version-drift-checker"
}

resource "google_cloud_run_v2_job_iam_member" "scheduler_runs_version_drift_checker" {
  project  = var.gcp_project_id
  location = module.common.gcp_primary_location
  name     = google_cloud_run_v2_job.version_drift_checker.name
  role     = "roles/run.invoker"
  member   = "serviceAccount:${google_service_account.version_drift_checker_scheduler.email}"
}

resource "google_cloud_scheduler_job" "version_drift_checker" {
  project = var.gcp_project_id
  region  = module.common.gcp_primary_location
  name    = "version-drift-checker"
  # Every 15 min — tight enough that a published-but-unrolled image or a stuck worker is caught well
  # inside the alert policies' windows (2h / 8h), loose enough to leave headroom against the registry
  # manifest lookups this does per image-based profile on every run.
  schedule  = "*/15 * * * *"
  time_zone = "Etc/UTC"

  http_target {
    http_method = "POST"
    uri         = "https://${module.common.gcp_primary_location}-run.googleapis.com/apis/run.googleapis.com/v1/namespaces/${var.gcp_project_id}/jobs/${google_cloud_run_v2_job.version_drift_checker.name}:run"

    oauth_token {
      service_account_email = google_service_account.version_drift_checker_scheduler.email
    }
  }
}
