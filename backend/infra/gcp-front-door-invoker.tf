# The Cloudflare front door's invoker identity (M4-A6). A dedicated service account whose *sole*
# power is `roles/run.invoker` on this one broker service — i.e. the ability to send it requests,
# which the design already assumes the whole Internet can do. The front-door Worker holds a key for
# this SA (in a Cloudflare secret), mints a short-lived ID token, and attaches it as
# `X-Serverless-Authorization` so Google's front end admits the request once the broker is deployed
# `--no-allow-unauthenticated` (the A6 cutover). Its near-worthlessness in isolation is what makes
# that key a safe "sacrificial anode" — see plan/m4/design/00-enrollment-tokens.md § Edge & cost.
#
# This grant is additive and harmless on its own: the broker still allows unauthenticated access
# until the cutover, so nothing changes until then.
resource "google_service_account" "front_door_invoker" {
  project      = var.gcp_project_id
  account_id   = "front-door-invoker"
  display_name = "Cloudflare front-door invoker"
  description  = "Mints the X-Serverless-Authorization ID token the front-door Worker attaches to broker requests. Sole permission: run.invoker on the broker."
}

resource "google_cloud_run_v2_service_iam_member" "front_door_invoker" {
  project  = google_cloud_run_v2_service.primary.project
  location = google_cloud_run_v2_service.primary.location
  name     = google_cloud_run_v2_service.primary.name
  role     = "roles/run.invoker"
  member   = "serviceAccount:${google_service_account.front_door_invoker.email}"
}

output "front_door_invoker_sa_email" {
  description = "Email of the SA the front-door Worker impersonates to mint its X-Serverless-Authorization token."
  value       = google_service_account.front_door_invoker.email
}

# Let the CI/CD identity deploy the refresher Cloud Run job *as* this SA (actAs). The job runs as
# front-door-invoker and self-mints that SA's ID token from the metadata server — keyless, no
# impersonation grant needed (the org disables SA keys anyway; the front door only ever holds a
# short-lived ID token). CI can already deploy the backend, so granting it actAs on this one SA is
# strictly weaker. See design 00 § anode rotation and backend/front-door-refresher.
variable "ci_service_account_email" {
  description = "Email of the CI/CD service account (vars.GCP_CICD_SA_EMAIL) that deploys the front-door refresher job as this SA."
  type        = string
}

resource "google_service_account_iam_member" "ci_deploys_front_door_job" {
  service_account_id = google_service_account.front_door_invoker.name
  role               = "roles/iam.serviceAccountUser"
  member             = "serviceAccount:${var.ci_service_account_email}"
}
