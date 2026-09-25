variable "repository" {
  description = "Repository name (owner is the provider's owner)"
  type        = string
}

variable "environment" {
  description = "GitHub Environment name. The AWS OIDC role restricts who can assume it by this name (infra/terraform/shared/ci.tf)"
  type        = string
}

variable "branch_patterns" {
  description = "Branches that can deploy to this Environment. If both branch_patterns and tag_patterns are empty, there is no restriction (for plan)"
  type        = list(string)
  default     = []
}

variable "tag_patterns" {
  description = "Tags that can deploy to this Environment"
  type        = list(string)
  default     = []
}

variable "reviewer_user_ids" {
  description = "People whose approval is required before deploying (GitHub user IDs). Empty means no approval"
  type        = list(number)
  default     = []
}

variable "variables" {
  description = "Environment variables (values that may appear in logs)"
  type        = map(string)
  default     = {}
}

variable "secrets" {
  description = "Environment secrets (masked in logs)"
  type        = map(string)
  default     = {}
  sensitive   = true
}
