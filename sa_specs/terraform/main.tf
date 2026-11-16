# No source block: assets are built locally and uploaded with Wrangler.
resource "cloudflare_pages_project" "site" {
  account_id        = var.account_id
  name              = var.project_name
  production_branch = var.production_branch

  lifecycle {
    prevent_destroy = true
  }
}

resource "cloudflare_pages_domain" "site" {
  count = var.custom_domain == null ? 0 : 1

  account_id   = var.account_id
  project_name = cloudflare_pages_project.site.name
  name         = var.custom_domain
}

resource "cloudflare_dns_record" "site" {
  count = var.dns_zone_id == null ? 0 : 1

  zone_id = var.dns_zone_id
  name    = var.custom_domain
  type    = "CNAME"
  content = cloudflare_pages_project.site.subdomain
  proxied = true
  ttl     = 1
  comment = "Archone SA documentation — managed by Terraform"

  depends_on = [cloudflare_pages_domain.site]
}
