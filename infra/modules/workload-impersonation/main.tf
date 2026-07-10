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

variable "secret_ids" {
  description = "Optional Secret Manager secret IDs (e.g. google_secret_manager_secret.xyz.id) this service account may read via a profile's secret_env_vars. Each gets a roles/secretmanager.secretAccessor binding for the service account — grant only the secrets this profile actually references."
  type        = list(string)
  default     = []
}

# Grants the Workload broker permission to impersonate this one service account — scoped to the
# SA resource itself, never to the project. Ownership of the grant sits with whoever owns this
# Terraform: deleting this resource unilaterally revokes Workload's access.
resource "google_service_account_iam_member" "workload_broker_can_impersonate" {
  service_account_id = var.service_account_id
  role               = "roles/iam.serviceAccountTokenCreator"
  member             = "serviceAccount:${local.workload_broker_sa_email}"
}

# Grants the service account (once impersonated) permission to read each listed secret — scoped
# to that one secret, never to the project. The CLI resolves these worker-side, using the
# impersonated token, so the read is attributable to this service account in Cloud Audit Logs.
#
# Keyed by index, not by value: a secret_ids entry is commonly a same-plan resource's .id (e.g.
# google_secret_manager_secret.foo.id), which is unknown until apply. for_each requires its keys
# to be known at plan time, so toset(var.secret_ids) fails with "Invalid for_each argument" the
# first time a secret and its grant are created together; the list's length (and so the set of
# indices) is always known statically, even when its elements aren't.
resource "google_secret_manager_secret_iam_member" "service_account_can_access_secret" {
  for_each  = { for index, secret_id in var.secret_ids : index => secret_id }
  secret_id = each.value
  role      = "roles/secretmanager.secretAccessor"
  member    = "serviceAccount:${var.service_account_email}"
}

output "service_account_email" {
  description = "Paste this into the Workload console when creating or updating a profile."
  value       = var.service_account_email
}
