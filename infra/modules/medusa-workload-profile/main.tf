terraform {
  required_version = ">= 1.14"

  required_providers {
    external = {
      source  = "hashicorp/external"
      version = ">= 2.3, < 3"
    }
  }
}

# Declarative front end for FleetService's Profile/ProfileRevision surface (workload#161) — lets a
# consuming app declare a worker profile in HCL instead of shelling out to `ms-workload admin
# profiles update -f json`. MVP scope: a thin wrapper (a local-exec'd helper script talking
# FleetService's own unframed Connect/JSON endpoint, see scripts/fleet-client.sh) rather than a real
# `terraform-provider-medusa-workload` — see the module README for the two frictions this papers
# over (the profile/revision split, and the digest CAS/ResolveImage dance) and what "real state"
# would need beyond this.
#
# Desired state = the latest ProfileRevision. FleetService's UpdateProfile always appends a new
# revision rather than mutating one in place (see fleet_service.proto), so "update in place" here
# means "Terraform decided the desired spec changed, so append a revision that matches it" — the
# `triggers_replace` hash below *is* that decision; the script itself does not re-derive it from
# remote state, so a plain re-apply with unchanged HCL never calls FleetService at all.
locals {
  auth_audience = var.auth_id_token_audience != "" ? var.auth_id_token_audience : var.api_base_url
  display_name  = var.display_name != "" ? var.display_name : var.profile_id

  # camelCase to match FleetService's proto3 JSON wire format (and the ms-workload CLI's `-f json`
  # spec file, see cli/.../AdminFormat.kt's ProfileRevisionSpec) directly — scripts/fleet-client.sh
  # forwards these keys into the request body almost verbatim, only adding the resolved digest CAS
  # token it previews itself.
  #
  # tunnel_egress travels through here (and so participates in the trigger hash below) even though
  # FleetService doesn't accept it yet (workload#159 — see the README's "tunnel_egress" section) and
  # the script does not send it anywhere. That means flipping it *does* force a resolve-and-repin
  # pass the moment #159 lands and the script is updated to forward it, without callers needing to
  # touch any other field to pick the change up.
  desired_spec = {
    profileId            = var.profile_id
    displayName          = local.display_name
    targetServiceAccount = var.target_service_account
    note                 = var.note
    envVars              = var.env_vars
    secretEnvVars        = var.secret_env_vars
    dockerImage          = var.docker_image
    drainDeadline        = var.drain_deadline
    fallbackEligible     = var.fallback_eligible
    tunnelEgress         = var.tunnel_egress
  }
}

# The reconcile step. Terraform's own diff of triggers_replace (a hash of the full desired spec) is
# what makes re-apply idempotent: unless something in the HCL actually changed, this resource shows
# no diff and the provisioner below never runs, so a repeat `terraform apply` performs zero
# FleetService calls. A profile whose upstream docker_image tag moved without any HCL change is
# *not* auto-detected by this MVP module (that would need a real provider with drift detection —
# see the README's "Follow-up" section); an explicit change (even a no-op re-set of the same tag
# string) is what forces a fresh resolve-and-pin.
resource "terraform_data" "profile" {
  triggers_replace = {
    spec_sha256 = sha256(jsonencode(local.desired_spec))
  }

  provisioner "local-exec" {
    command = "bash \"${path.module}/scripts/fleet-client.sh\" apply"
    environment = {
      FLEET_API_BASE_URL      = var.api_base_url
      FLEET_AUTH_AUDIENCE     = local.auth_audience
      FLEET_DESIRED_SPEC_JSON = jsonencode(local.desired_spec)
    }
  }
}

# Read-only, post-apply lookup of the profile's actual current state — used only to populate this
# module's outputs. depends_on defers evaluation until after terraform_data.profile has run, so a
# fresh create/update is reflected instead of the pre-apply state; on a no-op apply (trigger
# unchanged) it still re-reads FleetService, so outputs stay accurate even when nothing else ran.
data "external" "current" {
  program = ["bash", "${path.module}/scripts/fleet-client.sh", "get"]

  query = {
    profile_id    = var.profile_id
    api_base_url  = var.api_base_url
    auth_audience = local.auth_audience
  }

  depends_on = [terraform_data.profile]
}
