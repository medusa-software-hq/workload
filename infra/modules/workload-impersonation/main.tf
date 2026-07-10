terraform {
  required_version = ">= 1.14"

  required_providers {
    google = {
      source  = "hashicorp/google"
      version = ">= 5, < 8"
    }
  }
}

locals {
  # The Workload broker's Cloud Run runtime service account — stable per deployment, only
  # changes if we recreate that service account (an event rare enough that consumers should see
  # it as a diff when they bump their pinned module ref, not something this module hides behind
  # a lookup). See plan/m1/design/04-impersonation-opt-in.md.
  workload_broker_sa_email = "api-sa@ms-workload-d91b0eaf.iam.gserviceaccount.com"
}

variable "service_account_id" {
  description = "The fully-qualified resource ID of the service account this project is exposing to Workload (e.g. google_service_account.xyz.name)."
  type        = string
}

variable "service_account_email" {
  description = "The email of that same service account (e.g. google_service_account.xyz.email) — passed through as this module's output, so callers don't need to re-derive it."
  type        = string
}

# Grants the Workload broker permission to impersonate this one service account — scoped to the
# SA resource itself, never to the project. Ownership of the grant sits with whoever owns this
# Terraform: deleting this resource unilaterally revokes Workload's access.
resource "google_service_account_iam_member" "workload_broker_can_impersonate" {
  service_account_id = var.service_account_id
  role               = "roles/iam.serviceAccountTokenCreator"
  member             = "serviceAccount:${local.workload_broker_sa_email}"
}

output "service_account_email" {
  description = "Paste this into the Workload console when creating or updating a profile."
  value       = var.service_account_email
}
