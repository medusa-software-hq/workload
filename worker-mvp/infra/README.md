# Worker MVP Terraform configuration

Provisions a **temporary, throwaway** GCP project that proves the Workload
brokered-token flow end to end: a target service account
(`worker-mvp@<test-project-id>.iam.gserviceaccount.com`), a sample GCS bucket +
object the worker CLI reads, a sample secret the worker CLI resolves
worker-side, and the `workload-impersonation` module bindings that opt that SA
in to being impersonated by the Workload broker and to reading the sample
secret.

This is deliberately a separate Terraform root, with its own remote state, from
`backend/infra/` — so it can be torn down later (delete the project, delete this
directory) without touching the real backend infrastructure.

## Proving the flow end to end

1. `terraform init && terraform apply` in this directory. This creates the test
   project, the target service account, the sample bucket/object and secret,
   and grants the Workload broker permission to impersonate the target SA and
   read the sample secret (via the `infra/modules/workload-impersonation`
   module).
2. Copy this root's `target_service_account_email` output.
3. As a Workload admin, create a profile in the console pointing at that email
   (or update an existing one — see the root README for the register/approve/
   grant/claim flow). Optionally add a `secret_env_vars` entry using this
   root's `sample_secret_resource_name` output, to prove secret resolution
   (M2 story A3) too. The console verifies both the impersonation binding and
   secret access are live before the profile is claimable.
4. On a worker machine: `ms-workload register`, get approved in the console,
   then `ms-workload read-object --profile <profile-id> --bucket
   <sample_bucket_name output> --object hello.txt` to read the sample object
   through the brokered token, or `ms-workload exec --profile <profile-id> --
   env` to see the resolved secret injected as an env var.

## Tearing down

This project is meant to be temporary. To remove it: `terraform destroy` here, then
delete this directory and its Terraform state prefix
(`projects/workload/baseline/worker-mvp`) once it's no longer needed.
