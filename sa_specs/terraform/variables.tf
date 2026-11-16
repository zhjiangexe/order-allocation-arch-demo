variable "account_id" {
  description = "Cloudflare Account ID（不是 Zone ID）。"
  type        = string
  nullable    = false

  validation {
    condition     = can(regex("^[0-9a-f]{32}$", var.account_id))
    error_message = "account_id 必須是 32 位小寫十六進位 Cloudflare Account ID。"
  }
}

variable "project_name" {
  description = "Cloudflare Pages 專案名稱；預設網址以 Cloudflare 回傳的 subdomain 為準。"
  type        = string
  default     = "archone-sa"
  nullable    = false

  validation {
    condition     = length(var.project_name) <= 58 && can(regex("^[a-z0-9]([a-z0-9-]*[a-z0-9])?$", var.project_name))
    error_message = "project_name 必須為 1～58 個小寫英數或連字號，且以英數開頭及結尾。"
  }
}

variable "production_branch" {
  description = "正式部署的分支標籤；部署指令明確指定此值，不依賴目前 Git 分支。"
  type        = string
  default     = "main"
  nullable    = false

  validation {
    condition     = can(regex("^[A-Za-z0-9][A-Za-z0-9._/-]*$", var.production_branch))
    error_message = "production_branch 必須為非空白的分支名稱。"
  }
}

variable "custom_domain" {
  description = "選用的完整網域，例如 sa.example.com；null 表示只使用 pages.dev。"
  type        = string
  default     = null

  validation {
    condition     = var.custom_domain == null ? true : length(var.custom_domain) <= 253 && can(regex("^([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z]([a-z0-9-]{0,61}[a-z0-9])?$", var.custom_domain))
    error_message = "custom_domain 必須為小寫完整網域，不含 https://、路徑、萬用字元或結尾的點。"
  }
}

variable "dns_zone_id" {
  description = "選用的 Cloudflare Zone ID；設定後由 Terraform 建立 CNAME，null 表示自行管理 DNS。"
  type        = string
  default     = null

  validation {
    condition     = var.dns_zone_id == null ? true : can(regex("^[0-9a-f]{32}$", var.dns_zone_id)) && var.custom_domain != null
    error_message = "dns_zone_id 必須為 32 位小寫十六進位 ID，且必須同時設定 custom_domain。"
  }
}
