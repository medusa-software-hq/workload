# GCE worker-plane node identities (M7): VMs allow-listed to authenticate to the broker with their
# own service-account ID token (fetched from the metadata server) instead of a worker secret. Empty
# by default — no node is accepted until an environment populates this with real node SA emails.
# Separate from ADMIN_SERVICE_ACCOUNTS below: allow-listing a node here grants it no admin power.
variable "gce_node_service_accounts" {
  description = "GCE VM service-account emails allow-listed as worker-plane node identities."
  type        = list(string)
  default     = []
}

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

    # Hard ceiling on concurrently-billable instances. This is a deliberate
    # bills-over-availability trade (see backend/infra/README.md): the worst an
    # abusive spike can do is run the cap hot and degrade service, never
    # generate an open-ended bill. With the default per-instance concurrency
    # this still serves hundreds of simultaneous requests — ample for an
    # in-house fleet plus the admin console. Bump it consciously, not reflexively.
    scaling {
      max_instance_count = 3
    }

    containers {
      # Initial placeholder; CI/CD will deploy the real image from Artifact Registry.
      image = "us-docker.pkg.dev/cloudrun/container/hello"

      ports {
        container_port = 8080
      }

      resources {
        # Extra CPU during container startup only — no idle cost. Cuts the JVM cold-start
        # time that was tripping worker/console request timeouts while the service scales
        # to zero. See backend/infra/README.md's bills-over-availability stance: this keeps
        # min instances at 0 (no always-warm billing), it just makes the unavoidable cold
        # start fast.
        startup_cpu_boost = true
      }

      env {
        name  = "GOOGLE_CLIENT_ID"
        value = module.common.google_client_id
      }

      env {
        name  = "GOOGLE_CLI_CLIENT_ID"
        value = module.common.cli_client_id
      }

      env {
        name  = "GOOGLE_ALLOWED_DOMAIN"
        value = module.common.organization_domain
      }

      # s2s admin principal (M5-05). API_URL is the audience an allow-listed service account's ID
      # token must name (this API's own front-door URL, per env). ADMIN_SERVICE_ACCOUNTS is the
      # comma-separated allowlist — currently just the per-env workload-ci-admin SA (created in the
      # infra root; its email is deterministic from the project id).
      env {
        name  = "API_URL"
        value = module.common.api_url
      }

      env {
        name = "ADMIN_SERVICE_ACCOUNTS"
        value = join(",", [
          "workload-ci-admin@${var.gcp_project_id}.iam.gserviceaccount.com",
          "flow-worker-ci@${var.gcp_project_id}.iam.gserviceaccount.com",
        ])
      }

      # Phase 2 of the automated-rollout epic (workload#126): confines flow-worker-ci to exactly
      # the flow-worker profile (create/update/read only — see requireProfileScope/
      # requireUnscopedPrincipal in FleetServiceImpl). workload-ci-admin is absent from this map, so
      # it stays the pre-existing unrestricted s2s admin.
      env {
        name  = "ADMIN_SERVICE_ACCOUNT_PROFILE_SCOPES"
        value = "flow-worker-ci@${var.gcp_project_id}.iam.gserviceaccount.com=flow-worker"
      }

      # GCE node principal (M7): the worker plane's counterpart to ADMIN_SERVICE_ACCOUNTS above,
      # verified with the same API_URL audience but a separate allowlist (see
      # var.gce_node_service_accounts and WorkerNodeIdentityService).
      env {
        name  = "GCE_NODE_SERVICE_ACCOUNTS"
        value = join(",", var.gce_node_service_accounts)
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
        name  = "TOKEN_LIFETIME_SECONDS"
        value = "900"
      }
    }
  }

  depends_on = [
    google_secret_manager_secret_version.database_url,
  ]

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

# IAM-locked origin (M4-A6 cutover): the broker no longer allows unauthenticated access. Only
# identities holding roles/run.invoker on this service may reach it past Google's front end — i.e.
# the Cloudflare front-door Worker, which attaches an X-Serverless-Authorization ID token for
# front-door-invoker (see gcp-front-door-invoker.tf). Every other caller — a direct run.app hit, a
# worker still pointed at the old origin — is rejected at Google's edge for free (no instance start).
# Rollback is re-adding an allUsers roles/run.invoker binding here and re-applying.

output "cloud_run_primary_service_url" {
  value = google_cloud_run_v2_service.primary.uri
}
