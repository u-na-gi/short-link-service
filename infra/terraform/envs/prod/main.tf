# prod 環境 (タグを切ったらリリース)。中身は modules/aws と modules/cloudflare に置き、ここは backend / provider / 環境ごとの値と
# モジュール間のつなぎ (Tunnel と E2E 用サービストークンを Cloudflare から AWS の SSM へ) だけ持つ。

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
    key          = "envs/prod/terraform.tfstate"
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
      Env       = "prod"
      ManagedBy = "terraform"
    }
  }
}

# API トークンは CLOUDFLARE_API_TOKEN (infra/.envrc.local) から読む
provider "cloudflare" {}

variable "cloudflare_account_id" {
  description = "TF_VAR_cloudflare_account_id (infra/.envrc.local) で渡す"
  type        = string
  sensitive   = true
}


locals {
  # Worker を公開するホスト名 (src/front/wrangler.jsonc の env.prod の routes と揃える)
  hostname = "s.u-na-gi.com"

  # 利用者に見せる公開 URL。Worker の routes と make e2e-remote の向き先で、短縮 URL のベースも兼ねる
  public_base_url = "https://${local.hostname}"
}

module "aws" {
  source = "../../modules/aws"

  env      = "prod"
  vpc_cidr = "10.30.0.0/16"
  azs      = ["ap-northeast-1a", "ap-northeast-1c"]

  tunnel_token      = module.cloudflare.tunnel_token
  e2e_access_client = module.cloudflare.e2e_access_client
}

module "cloudflare" {
  source = "../../modules/cloudflare"

  env        = "prod"
  account_id = var.cloudflare_account_id
  hostname   = local.hostname

  turnstile_enabled = true

  # 公開サービスなので Cloudflare Access はかけない (E2E 用のサービストークンも作らない)
  access_allowed_email = null
}
