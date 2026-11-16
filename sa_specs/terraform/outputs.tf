output "deployment" {
  description = "供 deploy.mjs 使用的已建立專案資訊，不含憑證。"
  value = {
    account_id        = cloudflare_pages_project.site.account_id
    project_name      = cloudflare_pages_project.site.name
    production_branch = cloudflare_pages_project.site.production_branch
  }
}

output "pages_url" {
  description = "首次上傳網站後可使用的 Cloudflare Pages 網址。"
  value       = "https://${cloudflare_pages_project.site.subdomain}"
}

output "custom_domain_url" {
  description = "選用網域；須等待 DNS 與憑證驗證完成才能使用。"
  value       = var.custom_domain == null ? null : "https://${var.custom_domain}"
}

output "manual_dns_record" {
  description = "自行管理 DNS 時需要建立的 CNAME；使用外部 DNS 僅支援子網域。"
  value = var.custom_domain != null && var.dns_zone_id == null ? {
    type   = "CNAME"
    name   = var.custom_domain
    target = cloudflare_pages_project.site.subdomain
  } : null
}
