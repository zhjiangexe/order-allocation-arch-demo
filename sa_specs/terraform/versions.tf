terraform {
  required_version = ">= 1.9.0, < 2.0.0"

  required_providers {
    cloudflare = {
      source  = "cloudflare/cloudflare"
      version = "~> 5.0"
    }
  }
}

# Credentials come from CLOUDFLARE_API_TOKEN, never from a committed file.
provider "cloudflare" {}
