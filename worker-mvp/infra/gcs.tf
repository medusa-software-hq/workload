locals {
  # Also hardcoded as the CLI's default --object value in
  # cli/src/main/kotlin/software/medusa/workload/cli/ReadObjectCommand.kt — keep these two in sync.
  sample_object_name = "hello.txt"
}

# Sample bucket the worker CLI reads from (using the impersonated target SA) to prove
# the brokered-token flow actually works end to end.
resource "google_storage_bucket" "worker_mvp_sample" {
  project                     = local.test_project_id
  name                        = "${local.test_project_id}-sample"
  location                    = module.common.gcp_primary_location
  uniform_bucket_level_access = true
  force_destroy               = true # Temporary/throwaway project.

  depends_on = [google_project_service.apis]
}

resource "google_storage_bucket_object" "sample" {
  bucket  = google_storage_bucket.worker_mvp_sample.name
  name    = local.sample_object_name
  content = "Hello from worker-mvp! If you can read this, the brokered token flow works.\n"
}

# Grant the target SA read-only access, scoped to just this bucket.
resource "google_storage_bucket_iam_member" "target_sa_object_viewer" {
  bucket = google_storage_bucket.worker_mvp_sample.name
  role   = "roles/storage.objectViewer"
  member = "serviceAccount:${google_service_account.worker_mvp_target.email}"
}
