# Things that exist only once across environments (develop / staging / prod).
#
# - ECR: images are built once and reused in every environment (prod re-tags the image built on main
#   and uses it), so the repository is not split per environment.

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

  # Tags come from git commits; do not allow overwriting a tag with a different image
  image_tag_mutability = "IMMUTABLE"

  image_scanning_configuration {
    scan_on_push = true
  }
}

resource "aws_ecr_lifecycle_policy" "server" {
  repository = aws_ecr_repository.server.name

  policy = jsonencode({
    rules = [
      # Images used in prod (deploy.yml adds release-<sha>) are kept without counting toward the "newest 30" below.
      # Rules are applied in priority order, and images matched by one rule are not counted by later rules
      {
        rulePriority = 1
        description  = "Keep up to the newest 50 images used in prod"
        selection = {
          tagStatus     = "tagged"
          tagPrefixList = ["release-"]
          countType     = "imageCountMoreThan"
          countNumber   = 50
        }
        action = { type = "expire" }
      },
      {
        rulePriority = 2
        description  = "Delete untagged images after 1 day"
        selection = {
          tagStatus   = "untagged"
          countType   = "sinceImagePushed"
          countUnit   = "days"
          countNumber = 1
        }
        action = { type = "expire" }
      },
      {
        rulePriority = 3
        description  = "Keep only the newest 30 tagged images"
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
  description = "Used for image in the ecspresso task def"
  value       = aws_ecr_repository.server.repository_url
}
