locals {
  # Domain
  web_subdomain_name = "${module.common.project_base_name}-${module.common.project_variant}"
  web_host_name      = "${local.web_subdomain_name}.${module.common.organization_domain}"
}
