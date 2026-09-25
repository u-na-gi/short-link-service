# staging environment (main branch). The contents live in modules/aws and modules/cloudflare; this only holds backend / provider / per-environment values and
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
    key          = "envs/staging/terraform.tfstate"
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
      Env       = "staging"
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
  # Hostname that serves the Worker (keep in sync with routes of env.staging in src/front/wrangler.jsonc)
  hostname = "s-stg.u-na-gi.com"

  # Public URL shown to users. Worker routes and the target of make e2e-remote; also the short URL base
  public_base_url = "https://${local.hostname}"
}

module "aws" {
  source = "../../modules/aws"

  env      = "staging"
  vpc_cidr = "10.20.0.0/16"
  azs      = ["ap-northeast-1a", "ap-northeast-1c"]

  tunnel_token      = module.cloudflare.tunnel_token
  e2e_access_client = module.cloudflare.e2e_access_client
}

module "cloudflare" {
  source = "../../modules/cloudflare"

  env        = "staging"
  account_id = var.cloudflare_account_id
  hostname   = local.hostname

  turnstile_enabled = true

  access_allowed_email = var.access_allowed_email
}
