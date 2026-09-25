variable "env" {
  description = "Environment name (develop / staging / prod). Used as the resource name prefix"
  type        = string
}

variable "vpc_cidr" {
  description = "VPC CIDR. Assumes a /16; /24 blocks are carved from the start for subnets"
  type        = string
}

variable "azs" {
  description = "AZs for the public subnets. Fargate places tasks in one of them"
  type        = list(string)
}

variable "tunnel_token" {
  description = "Cloudflare Tunnel token passed to cloudflared (output of modules/cloudflare). Stored in SSM"
  type        = string
  sensitive   = true
}

variable "e2e_access_client" {
  description = "Service token for E2E to pass Cloudflare Access (output of modules/cloudflare). If null, nothing is stored in SSM (prod)"
  type = object({
    client_id     = string
    client_secret = string
  })
  default   = null
  sensitive = true
}
