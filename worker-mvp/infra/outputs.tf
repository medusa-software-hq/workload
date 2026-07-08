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
