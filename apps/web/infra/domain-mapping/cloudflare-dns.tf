locals {
  ttl_auto = 1 # means "automatic" in Cloudflare
}

variable "cloudflare_zone_id" {
  description = "Cloudflare zone ID for the organization domain."
  type        = string
}

resource "cloudflare_dns_record" "app_dns" {
  zone_id = var.cloudflare_zone_id
  type    = "CNAME"
  name    = local.counter_web_subdomain_name
  # Cloud Run domain mappings for a subdomain resolve to Google's hosted target.
  content = "ghs.googlehosted.com"
  ttl     = local.ttl_auto

  # DNS-only: Google manages TLS for the mapped domain and IAP gates access, so
  # Cloudflare proxying must stay off.
  proxied = false

  depends_on = [google_cloud_run_domain_mapping.web]
}
