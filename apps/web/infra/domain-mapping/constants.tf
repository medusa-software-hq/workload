locals {
  # Per-environment web host (M5-02/04). Use infra/common's env-derived values rather than
  # recomputing from project_base_name-project_variant, which is a *shared* constant and so resolved
  # to the prod host ("workload-baseline.…") in every workspace — a staging apply then tried to map
  # the prod domain and collided. subdomain_label is "workload-baseline" on prod (no-op) and
  # "workload-baseline-staging" on staging.
  web_subdomain_name = module.common.subdomain_label
  web_host_name      = module.common.web_host_name
}
