# Mocked plans only: no Cloudflare credentials or remote resources are used.
mock_provider "cloudflare" {
  mock_resource "cloudflare_pages_project" {
    defaults = {
      subdomain = "archone-sa-test.pages.dev"
    }
  }
}

variables {
  account_id   = "0123456789abcdef0123456789abcdef"
  project_name = "archone-sa-test"
}

run "pages_only" {
  command = plan

  assert {
    condition     = length(cloudflare_pages_domain.site) == 0 && length(cloudflare_dns_record.site) == 0
    error_message = "預設只建立 Pages，不應建立網域或 DNS。"
  }
  assert {
    condition     = cloudflare_pages_project.site.source == null && output.deployment.production_branch == "main"
    error_message = "應使用 Direct Upload，並明確選定正式部署分支。"
  }
}

run "cloudflare_domain" {
  command = plan
  variables {
    custom_domain = "sa.example.com"
    dns_zone_id   = "abcdef0123456789abcdef0123456789"
  }
  assert {
    condition     = cloudflare_pages_domain.site[0].name == "sa.example.com" && cloudflare_dns_record.site[0].type == "CNAME" && cloudflare_dns_record.site[0].proxied
    error_message = "自訂網域必須同時註冊 Pages domain 與 CNAME。"
  }
  assert {
    condition     = output.manual_dns_record == null
    error_message = "Terraform 管理 DNS 時不應要求手動建立記錄。"
  }
}

run "external_dns" {
  command = plan
  variables {
    custom_domain = "sa.example.com"
  }
  assert {
    condition     = length(cloudflare_pages_domain.site) == 1 && length(cloudflare_dns_record.site) == 0
    error_message = "外部 DNS 只建立 Pages domain。"
  }
}

run "reject_zone_without_domain" {
  command = plan
  variables {
    dns_zone_id = "abcdef0123456789abcdef0123456789"
  }
  expect_failures = [var.dns_zone_id]
}

run "reject_url_as_domain" {
  command = plan
  variables {
    custom_domain = "https://sa.example.com/path"
  }
  expect_failures = [var.custom_domain]
}
