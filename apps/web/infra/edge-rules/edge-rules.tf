variable "enforced_environments" {
  description = <<-EOT
    Which environments' front-door hosts the edge rules are enforced for. Rolled out staging-first:
    this starts as ["staging"] so the rules are proven against staging before a follow-up expands it
    to ["staging", "production"]. (A single root owns both — see main.tf for why it isn't workspaced.)
  EOT
  type        = list(string)
  default     = ["staging", "production"]

  validation {
    condition     = length(var.enforced_environments) > 0
    error_message = "enforced_environments must list at least one environment (an empty set would produce an invalid empty host expression)."
  }
}

locals {
  # The front-door API hosts per environment, in the shared medusa.software zone. These mirror
  # infra/common's api_host_name for each env (api.<subdomain_label>.medusa.software) and the hosts
  # hardcoded in apps/front-door/wrangler.toml; kept here as a local because this cross-env root
  # can't derive both from the workspace-scoped `common` module.
  api_hosts = {
    staging    = "api.workload-baseline-staging.medusa.software"
    production = "api.workload-baseline.medusa.software"
  }

  enforced_hosts = [for env, host in local.api_hosts : host if contains(var.enforced_environments, env)]

  # Countries whose traffic may reach the WORKER plane (/worker/v2/* — token brokering for registered
  # worker nodes, which are team machines). Everything else is blocked at the edge. ISO 3166-1 alpha-2.
  allowed_countries = ["PL"]

  # Only the worker plane is country-restricted. /health and the admin FleetService plane are
  # deliberately left open (rate-limited only): they're hit by CI from cloud regions (the promotion
  # smoke and s2s admin), so a country block there would lock out the deploy gate.
  worker_plane_path_prefix = "/worker/"

  hosts_expr     = join(" ", [for h in local.enforced_hosts : "\"${h}\""])
  countries_expr = join(" ", [for c in local.allowed_countries : "\"${c}\""])

  country_block_expression = format(
    "(http.host in {%s} and starts_with(http.request.uri.path, \"%s\") and not ip.geoip.country in {%s})",
    local.hosts_expr,
    local.worker_plane_path_prefix,
    local.countries_expr,
  )

  rate_limit_scope_expression = "(http.host in {${local.hosts_expr}})"
}

# Country allowlist on the worker plane. Blocks (not challenges) so an automated worker node fails
# fast and loud rather than hanging on an interactive challenge it can't solve.
resource "cloudflare_ruleset" "worker_plane_country_allowlist" {
  zone_id     = var.cloudflare_zone_id
  name        = "workload worker-plane country allowlist"
  description = "M5-07: restrict the /worker/ token-broker plane to team countries."
  kind        = "zone"
  phase       = "http_request_firewall_custom"

  rules = [
    {
      ref         = "worker_plane_country_block"
      description = "Worker plane: block requests from non-allowlisted countries"
      action      = "block"
      enabled     = true
      expression  = local.country_block_expression
    }
  ]
}

# One per-IP rate-limit rule across the whole API front door (both planes). Generous enough that
# legitimate CLI/worker/registration traffic and the handful of smoke requests never trip it, but it
# caps a single source's burst. Counted per (client IP, edge colo) — Cloudflare's default, available
# on every plan tier.
#
# NOTE ON THE 10s WINDOW: the zone is on a plan that only entitles a rate-limit `period`/
# `mitigation_timeout` of 10s (the API rejects 60 with "can only use a period among [10]"). So the
# limit is expressed as 50 requests / 10s — the same sustained 300 req/min the design calls for — and
# the block lifts after 10s. A paid plan would allow a longer window + mitigation; revisit then.
resource "cloudflare_ruleset" "api_rate_limit" {
  zone_id     = var.cloudflare_zone_id
  name        = "workload api per-IP rate limit"
  description = "M5-07: per-IP request rate limit on the API front door."
  kind        = "zone"
  phase       = "http_ratelimit"

  rules = [
    {
      ref         = "api_per_ip_rate_limit"
      description = "API front door: per-IP request rate limit (50/10s = 300/min)"
      action      = "block"
      enabled     = true
      expression  = local.rate_limit_scope_expression
      ratelimit = {
        characteristics     = ["ip.src", "cf.colo.id"]
        period              = 10
        requests_per_period = 50
        mitigation_timeout  = 10
      }
    }
  ]
}
