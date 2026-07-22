# Cloudflare edge rules for the front door (M5-07).
#
# WHY THIS ROOT IS NOT PER-ENV WORKSPACED (unlike every other TF root here).
# The other roots are per-GCP-project, so a `staging` workspace and a `default` (prod) workspace own
# disjoint resources. Cloudflare is different: prod and staging share ONE zone (medusa.software), and
# a phase's *entrypoint* ruleset (e.g. http_request_firewall_custom) is a single object per zone —
# two workspaces would fight over it, each overwriting the other's ordered rule list. So a single,
# un-workspaced root owns both environments' host-scoped rules, and the staging-first rollout is done
# with `var.enforced_environments` (see edge-rules.tf) rather than by applying two workspaces.

terraform {
  required_version = ">= 1.14"

  backend "gcs" {
    bucket = "ms-tfstate-c1984596bdabf023"
    prefix = "projects/workload/baseline/apps/web/edge-rules"
  }

  required_providers {
    cloudflare = {
      source  = "cloudflare/cloudflare"
      version = "~> 5.18"
    }
  }
}

variable "cloudflare_zone_id" {
  description = "Cloudflare zone ID for the medusa.software organization domain (shared by both envs)."
  type        = string
}

variable "cloudflare_api_token" {
  description = "Cloudflare API token scoped to Zone WAF/Rulesets:Edit on the medusa.software zone. A deliberately narrow sibling of the Worker-deploy token (M5-07 token hygiene)."
  type        = string
  sensitive   = true
}

provider "cloudflare" {
  api_token = var.cloudflare_api_token
}
