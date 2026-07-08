# Worker MVP Terraform configuration

Provisions a **temporary, throwaway** GCP project for the MVP brokered access token
flow: a target service account (`worker-mvp@<test-project-id>.iam.gserviceaccount.com`),
a sample GCS bucket + object the worker CLI reads to prove the flow works, and the
cross-project IAM binding that lets the existing backend's Cloud Run service account
impersonate the target SA.

This is deliberately a separate Terraform root, with its own remote state, from
`backend/infra/` — so it can be torn down later (delete the project, delete this
directory) without touching the real backend infrastructure.

## One-time manual bootstrap

Because this project doesn't exist until it's applied, and `backend/infra` needs to
know the target service account's email (which includes a random suffix), there's a
one-time manual step to connect the two:

1. `terraform init && terraform apply` in this directory (needs `gcp_project_id` set to
   the MAIN workload project's ID, e.g. via `-var` or `TF_VAR_gcp_project_id`).
2. Copy this root's `target_service_account_email` output.
3. Set it as the `TARGET_SERVICE_ACCOUNT_EMAIL` GitHub Actions variable (Terraform
   already seeds a placeholder for it in `infra/github-variables.tf`, ignoring future
   changes — update it manually via `gh variable set` or the GitHub UI).
4. Apply `backend/infra` so it picks up the real value.

## Bootstrap token

The bootstrap token itself is **not** managed here. `backend/infra` creates the Secret
Manager secret *container* (`worker-mvp-bootstrap-token`); create the real secret
version manually, e.g.:

```sh
openssl rand -base64 32 | gcloud secrets versions add worker-mvp-bootstrap-token --data-file=-
```

Then set the same value as the `WORKER_MVP_BOOTSTRAP_TOKEN` GitHub secret (Terraform
seeds a placeholder for that one too, in `.github/config/github-repo.tf`).

Once both the secret version and `TARGET_SERVICE_ACCOUNT_EMAIL` are in place, flip
`WORKER_TOKEN_BROKER_ENABLED=true` on the Cloud Run service (new revision) to turn the
endpoint on.

## Tearing down

This project is meant to be temporary. To remove it: `terraform destroy` here, then
delete this directory and its Terraform state prefix
(`projects/workload/baseline/worker-mvp`) once it's no longer needed.
