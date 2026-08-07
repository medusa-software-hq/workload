variable "api_base_url" {
  description = "FleetService's Connect/JSON base URL for the target environment, e.g. module.common.api_url. The module POSTs to \"<api_base_url>/medusa.workload.v1.FleetService/<Method>\", the same unframed endpoint the ms-workload CLI and console use."
  type        = string
}

variable "profile_id" {
  description = "Stable profile identifier (FleetService's Profile.profile_id, the CLI's <profile-id>). Immutable for the lifetime of this resource block — changing it targets a different (or new) profile rather than renaming the existing one, since FleetService has no rename RPC."
  type        = string
}

variable "display_name" {
  description = "Human-readable label shown in the console. Only takes effect when the profile doesn't exist yet (CreateProfile has no rename equivalent for an existing profile, so changing this later has no effect). Empty defaults to profile_id."
  type        = string
  default     = ""
}

variable "target_service_account" {
  description = "The service account this profile's runs are minted a token for (ProfileRevision.target_service_account) — typically the service_account_email output of a workload-impersonation module instance, since that's what grants the broker permission to impersonate it."
  type        = string
}

variable "docker_image" {
  description = "Container image tag ref, e.g. LOCATION-docker.pkg.dev/PROJECT/REPO/IMAGE:TAG. Empty for a pure exec/env profile. Re-resolved to a digest and pinned via the CAS-checked ResolveImage flow (see the module README) on every apply that this module actually runs."
  type        = string
  default     = ""
}

variable "env_vars" {
  description = "Plain, non-secret environment variables (name -> value)."
  type        = map(string)
  default     = {}
}

variable "secret_env_vars" {
  description = "Secret-backed environment variables (name -> Secret Manager resource name, e.g. projects/P/secrets/S/versions/latest). Never a raw secret value — the target service account must separately be granted roles/secretmanager.secretAccessor on each one, e.g. via infra/modules/workload-impersonation's secret_ids."
  type        = map(string)
  default     = {}
}

variable "drain_deadline" {
  description = "Stop-timeout the supervisor gives an in-flight claim of this revision before SIGKILL, e.g. \"6h\" (see ProfileRevision.drain_deadline). Empty means the supervisor's own default applies."
  type        = string
  default     = ""
}

variable "note" {
  description = "Free-text note attached to the created/updated revision, shown in the console/CLI."
  type        = string
  default     = "managed by Terraform (medusa_workload_profile)"
}

variable "fallback_eligible" {
  description = "Whether this profile participates in fallback auto-placement onto the shared fallback node when no dedicated assignment exists — see FleetService's SetProfileFallbackEligible doc comment."
  type        = bool
  default     = false
}

variable "tunnel_egress" {
  description = "Tunnel-egress configuration for this profile (workload#159). FleetService does not accept this field yet — see the module README's \"tunnel_egress\" section. Setting it is accepted so callers can wire up their HCL ahead of time, but it has no server-side effect until #159 lands and this module is updated to forward it."
  type = object({
    enabled     = optional(bool, false)
    target_host = optional(string, "")
    target_port = optional(number, 0)
  })
  default = null
}

variable "auth_id_token_audience" {
  description = "Audience for the ambient-credential Google ID token minted to call FleetService. Defaults to api_base_url, matching the CLI's --auth sa behavior and the server's GooglePrincipalVerifier expectation; override only if the API sits behind a front door whose audience differs from its own URL."
  type        = string
  default     = ""
}
