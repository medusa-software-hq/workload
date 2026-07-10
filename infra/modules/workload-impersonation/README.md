# workload-impersonation

Opts a service account in to being impersonated by the Workload broker: a
single `roles/iam.serviceAccountTokenCreator` binding on that SA, granted to
the broker's runtime service account. Ownership of the grant stays with
whoever owns this Terraform — delete the module block to revoke access.

See [`plan/m1/design/04-impersonation-opt-in.md`](../../../plan/m1/design/04-impersonation-opt-in.md)
for the full design.

## Usage

```hcl
module "workload_impersonation" {
  source = "git::ssh://git@github.com/medusa-software-hq/workload.git//infra/modules/workload-impersonation?ref=v1"

  service_account_id    = google_service_account.xyz_1.name
  service_account_email = google_service_account.xyz_1.email
}

output "workload_target_service_account_email" {
  description = "Paste this into the Workload console when creating a profile."
  value       = module.workload_impersonation.service_account_email
}
```

Hand the output email to a Workload admin (Slack is fine); they create or
update a profile with it in the console, which verifies the binding is live
before the profile is claimable.
