# 環境 (develop / staging / prod) をまたいで 1 つだけ持つもの。
#
# - ECR: イメージは一度だけビルドして全環境で使い回す (prod は main で作ったイメージを
#   付け直して使う) ので、リポジトリは環境ごとに分けない。

terraform {
  required_version = ">= 1.16"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.66"
    }
  }

  backend "s3" {
    bucket       = "short-link-service-tfstate-0b102b6e"
    key          = "shared/terraform.tfstate"
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
      Stack     = "shared"
      ManagedBy = "terraform"
    }
  }
}

resource "aws_ecr_repository" "server" {
  name = "short-link-service/server"

  # タグは git のコミットで付け、同じタグで別のイメージを上書きさせない
  image_tag_mutability = "IMMUTABLE"

  image_scanning_configuration {
    scan_on_push = true
  }
}

resource "aws_ecr_lifecycle_policy" "server" {
  repository = aws_ecr_repository.server.name

  policy = jsonencode({
    rules = [
      {
        rulePriority = 1
        description  = "タグの無いイメージは 1 日で消す"
        selection = {
          tagStatus   = "untagged"
          countType   = "sinceImagePushed"
          countUnit   = "days"
          countNumber = 1
        }
        action = { type = "expire" }
      },
      {
        rulePriority = 2
        description  = "タグ付きは新しい 30 個だけ残す"
        selection = {
          tagStatus   = "any"
          countType   = "imageCountMoreThan"
          countNumber = 30
        }
        action = { type = "expire" }
      },
    ]
  })
}

output "server_repository_url" {
  description = "ecspresso の task def の image に使う"
  value       = aws_ecr_repository.server.repository_url
}
