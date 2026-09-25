# Foundation created only once per account: the S3 bucket that holds the tfstate of each environment (develop / staging / prod).
#
# This stack's own state is also stored in the bucket it creates.
# The very first time, remove backend "s3", apply with local state, restore the backend, and
# move the state into the bucket with terraform init -migrate-state. Follow the same steps when recreating it.

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

# Bucket names must be globally unique. Putting the account ID in the name would expose it in
# each environment's backend config (committed to the repository), so use a random suffix for uniqueness
resource "random_id" "tfstate_suffix" {
  byte_length = 4
}

resource "aws_s3_bucket" "tfstate" {
  bucket = "short-link-service-tfstate-${random_id.tfstate_suffix.hex}"

  lifecycle {
    prevent_destroy = true
  }
}

# Keep versions so state can be restored if it is corrupted or deleted
resource "aws_s3_bucket_versioning" "tfstate" {
  bucket = aws_s3_bucket.tfstate.id

  versioning_configuration {
    status = "Enabled"
  }
}

# Delete old versions after 90 days. Otherwise they pile up on every state write
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

# State also contains secrets created with random_password, so make encryption and public access blocking explicit
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

# Deny access without TLS
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
  description = "Name to put in bucket of each environment's backend \"s3\""
  value       = aws_s3_bucket.tfstate.bucket
}
