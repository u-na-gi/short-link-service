# develop environment. The contents live in modules/aws and modules/cloudflare; this only holds backend / provider / per-environment values and
# glue between modules (Tunnel and E2E service token from Cloudflare into AWS SSM).

terraform {
  required_version = ">= 1.16"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.66"
    }
    cloudflare = {
      source  = "cloudflare/cloudflare"
      version = "~> 5.25"
    }
  }

  backend "s3" {
    bucket       = "short-link-service-tfstate-0b102b6e"
    key          = "envs/develop/terraform.tfstate"
    region       = "ap-northeast-1"
    encrypt      = true
    use_lockfile = true
  }
}

provider "aws" {
  region = "ap-northeast-1"

  default_tags {
    tags = {
      Project   = "short-link-service"
      Env       = "develop"
      ManagedBy = "terraform"
    }
  }
}

# The API token is read from CLOUDFLARE_API_TOKEN (infra/.envrc.local)
provider "cloudflare" {}

variable "cloudflare_account_id" {
  description = "Passed via TF_VAR_cloudflare_account_id (infra/.envrc.local)"
  type        = string
  sensitive   = true
}

variable "access_allowed_email" {
  description = "Email address allowed to log in through Cloudflare Access. Passed via TF_VAR_access_allowed_email (infra/.envrc.local)"
  type        = string
  sensitive   = true
}

locals {
  # Hostname that serves the Worker (keep in sync with routes of env.develop in src/front/wrangler.jsonc)
  hostname = "s-dev.u-na-gi.com"

  # Public URL of the site. Worker routes and the target of make e2e-remote
  public_base_url = "https://${local.hostname}"

  # Short URL base (Play's SHORTENER_BASE_URL). Matches the requirement "the short URL domain is https://example.com/".
  # Issued short URLs cannot be opened directly; resolve them with the site's resolve form. Self-reference is also checked against example.com,
  # so in develop URLs pointing to s-dev.u-na-gi.com can be shortened (the redirect loop only affects develop, so this is accepted)
  shortener_base_url = "https://example.com"
}

module "aws" {
  source = "../../modules/aws"

  env      = "develop"
  vpc_cidr = "10.10.0.0/16"
  azs      = ["ap-northeast-1a", "ap-northeast-1c"]

  tunnel_token      = module.cloudflare.tunnel_token
  e2e_access_client = module.cloudflare.e2e_access_client
}

module "cloudflare" {
  source = "../../modules/cloudflare"

  env        = "develop"
  account_id = var.cloudflare_account_id
  hostname   = local.hostname

  # Turnstile only in staging / prod
  turnstile_enabled = false

  access_allowed_email = var.access_allowed_email
}

# Moves from when modules/app was split into modules/aws and modules/cloudflare
moved {
  from = module.app
  to   = module.aws
}
