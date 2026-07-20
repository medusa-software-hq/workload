locals {
  # Referenced by ID (not a file), unlike gcs.tf's sample object — keep in sync with README.md
  # if renamed.
  sample_secret_id = "worker-mvp-sample-secret"
}

# Sample secret the worker CLI resolves (using the impersonated target SA, worker-side, via the
# M2 A3 secret-resolution flow) to prove that half of the brokered-token story works too.
resource "google_secret_manager_secret" "worker_mvp_sample" {
  project   = local.test_project_id
  secret_id = local.sample_secret_id

  replication {
    auto {}
  }

  depends_on = [google_project_service.apis]
}

resource "google_secret_manager_secret_version" "worker_mvp_sample" {
  secret      = google_secret_manager_secret.worker_mvp_sample.id
  secret_data = "Hello from a worker-mvp secret! If you can read this, secret resolution works.\n"
}
