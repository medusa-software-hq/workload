output "test_project_id" {
  value = local.test_project_id
}

output "target_service_account_email" {
  value = google_service_account.worker_mvp_target.email
}

output "sample_bucket_name" {
  value = google_storage_bucket.worker_mvp_sample.name
}

output "sample_object_name" {
  value = local.sample_object_name
}

# Paste this into a profile's secret_env_vars in the console to prove worker-side secret
# resolution (M2 story A3) end to end.
output "sample_secret_resource_name" {
  value = "projects/${local.test_project_id}/secrets/${google_secret_manager_secret.worker_mvp_sample.secret_id}/versions/latest"
}
