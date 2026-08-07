output "profile_id" {
  description = "Pass-through of var.profile_id, for convenience when this module is instantiated with for_each/count."
  value       = var.profile_id
}

output "exists" {
  description = "Whether the profile exists in FleetService as of the last apply."
  value       = data.external.current.result.exists == "true"
}

output "latest_revision" {
  description = "The latest revision number FleetService reports for this profile, or null if the profile doesn't exist."
  value       = data.external.current.result.exists == "true" ? tonumber(data.external.current.result.latestRevision) : null
}

output "docker_image_digest" {
  description = "The resolved sha256:... digest of the latest revision's docker_image, or null if the profile doesn't exist or has no image."
  value       = data.external.current.result.exists == "true" ? data.external.current.result.dockerImageDigest : null
}

output "image_status" {
  description = "The latest revision's image resolution verdict (e.g. IMAGE_STATUS_RESOLVED), or null if the profile doesn't exist."
  value       = data.external.current.result.exists == "true" ? data.external.current.result.imageStatus : null
}

output "verification_status" {
  description = "The latest revision's verification status (e.g. whether the target service account's impersonation binding is live), or null if the profile doesn't exist."
  value       = data.external.current.result.exists == "true" ? data.external.current.result.verificationStatus : null
}

output "fallback_eligible" {
  description = "The profile's current fallback_eligible flag, or null if the profile doesn't exist."
  value       = data.external.current.result.exists == "true" ? data.external.current.result.fallbackEligible == "true" : null
}
