# アカウントに 1 つだけ作る土台。環境 (develop / staging / prod) の tfstate を置く S3 バケット。
#
# このスタック自身の state も、このスタックが作るバケットに置く。
# 最初の 1 回だけは backend "s3" を外してローカルの state で apply し、backend を戻して
# terraform init -migrate-state でバケットへ移した。作り直すときも同じ手順を踏む。

terraform {
  required_version = ">= 1.16"

  backend "s3" {
    bucket       = "short-link-service-tfstate-0b102b6e"
    key          = "bootstrap/terraform.tfstate"
    region       = "ap-northeast-1"
    encrypt      = true
    use_lockfile = true
  }

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.66"
    }
    random = {
      source  = "hashicorp/random"
      version = "~> 3.9"
    }
  }
}

provider "aws" {
  region = "ap-northeast-1"

  default_tags {
    tags = {
      Project   = "short-link-service"
      Stack     = "bootstrap"
      ManagedBy = "terraform"
    }
  }
}

# バケット名は全世界で一意でないといけない。アカウント ID を名前に入れると
# 各環境の backend 設定 (リポジトリにコミットする) にアカウント ID が載るので、乱数で一意にする
resource "random_id" "tfstate_suffix" {
  byte_length = 4
}

resource "aws_s3_bucket" "tfstate" {
  bucket = "short-link-service-tfstate-${random_id.tfstate_suffix.hex}"

  lifecycle {
    prevent_destroy = true
  }
}

# state を壊したり消したりしたときに戻せるよう、版を残す
resource "aws_s3_bucket_versioning" "tfstate" {
  bucket = aws_s3_bucket.tfstate.id

  versioning_configuration {
    status = "Enabled"
  }
}

# 古い版は 90 日で消す。残し続けると state を書くたびに溜まる
resource "aws_s3_bucket_lifecycle_configuration" "tfstate" {
  bucket = aws_s3_bucket.tfstate.id

  rule {
    id     = "expire-noncurrent-versions"
    status = "Enabled"

    filter {}

    noncurrent_version_expiration {
      noncurrent_days = 90
    }
  }

  depends_on = [aws_s3_bucket_versioning.tfstate]
}

# state には random_password で作るシークレットも載るので、暗号化と公開の遮断を明示する
resource "aws_s3_bucket_server_side_encryption_configuration" "tfstate" {
  bucket = aws_s3_bucket.tfstate.id

  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
  }
}

resource "aws_s3_bucket_public_access_block" "tfstate" {
  bucket = aws_s3_bucket.tfstate.id

  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

# TLS を使わないアクセスを拒否する
data "aws_iam_policy_document" "tfstate" {
  statement {
    sid     = "DenyInsecureTransport"
    effect  = "Deny"
    actions = ["s3:*"]
    resources = [
      aws_s3_bucket.tfstate.arn,
      "${aws_s3_bucket.tfstate.arn}/*",
    ]

    principals {
      type        = "*"
      identifiers = ["*"]
    }

    condition {
      test     = "Bool"
      variable = "aws:SecureTransport"
      values   = ["false"]
    }
  }
}

resource "aws_s3_bucket_policy" "tfstate" {
  bucket = aws_s3_bucket.tfstate.id
  policy = data.aws_iam_policy_document.tfstate.json

  depends_on = [aws_s3_bucket_public_access_block.tfstate]
}

output "tfstate_bucket" {
  description = "各環境の backend \"s3\" の bucket に書く名前"
  value       = aws_s3_bucket.tfstate.bucket
}
