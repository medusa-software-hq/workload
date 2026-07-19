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
