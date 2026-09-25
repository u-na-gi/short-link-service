variable "env" {
  description = "Environment name (develop / staging / prod). Used as the resource name prefix"
  type        = string
}

variable "account_id" {
  description = "Cloudflare account ID. Passed via TF_VAR_cloudflare_account_id to keep it out of the public repository"
  type        = string
  sensitive   = true
}

variable "hostname" {
  description = "Hostname that serves the Worker (a subdomain of u-na-gi.com). The target of Cloudflare Access"
  type        = string
}

variable "access_allowed_email" {
  description = "Email address allowed to log in through Cloudflare Access. If null, Access is not applied (prod). Passed via TF_VAR to keep it out of the public repository"
  type        = string
  default     = null
  sensitive   = true
}

variable "turnstile_enabled" {
  description = "Whether to apply Turnstile to shorten and resolve (true for staging / prod, false for develop)"
  type        = bool
  default     = false
}
