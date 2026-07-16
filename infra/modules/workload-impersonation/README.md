# workload-impersonation

Opts a service account in to being impersonated by the Workload broker: a
single `roles/iam.serviceAccountTokenCreator` binding on that SA, granted to
the broker's runtime service account. Ownership of the grant stays with
whoever owns this Terraform — delete the module block to revoke access.

Optionally also opts the same service account in to reading specific Secret
Manager secrets (`secret_ids`), for profiles that reference them via
`secret_env_vars` — see [M2 story A3](../../../plan/m2/stories/path-a/a3-secret-resolution.md).
The worker CLI resolves these itself, using the impersonated token, so the
read is attributable to this service account in Cloud Audit Logs; the broker
never sees the value.

For an **image profile**, optionally grant the same service account
`roles/artifactregistry.reader` on the one repository holding its container
image (`artifact_repository_id`) — see
[M3 story 04](../../../plan/m3/stories/04-image-fields-in-revisions.md). The
backend resolves the image tag to a digest at revision-creation time using
this SA's identity, and the worker pulls the image with the same impersonated
token, so both reads are attributable to this service account. Without this
grant, the console flags the revision `image_unresolvable` and it isn't
claimable.

See [`plan/m1/design/04-impersonation-opt-in.md`](../../../plan/m1/design/04-impersonation-opt-in.md)
for the full design.

## Usage

```hcl
module "workload_impersonation" {
  source = "git::ssh://git@github.com/medusa-software-hq/workload.git//infra/modules/workload-impersonation?ref=v1"

  service_account_id    = google_service_account.xyz_1.name
  service_account_email = google_service_account.xyz_1.email

  # Optional: grant secretAccessor on exactly the secrets this profile references.
  secret_ids = [google_secret_manager_secret.api_key.id]

  # Optional: grant artifactregistry.reader on the repository backing an image profile.
  artifact_repository_id = google_artifact_registry_repository.app.id
}

output "workload_target_service_account_email" {
  description = "Paste this into the Workload console when creating a profile."
  value       = module.workload_impersonation.service_account_email
}
```

Hand the output email to a Workload admin (Slack is fine); they create or
update a profile with it in the console, which verifies the binding is live
before the profile is claimable. If the profile references secrets, the
console verification also dry-run-checks that those secrets are readable.
