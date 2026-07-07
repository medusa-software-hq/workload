locals {
  # Domain
  counter_web_subdomain_name = "${module.common.project_base_name}-${module.common.project_variant}"
  counter_web_host_name      = "${local.counter_web_subdomain_name}.${module.common.organization_domain}"
}
