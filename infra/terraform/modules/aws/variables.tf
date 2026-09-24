variable "env" {
  description = "環境名 (develop / staging / prod)。リソース名の接頭辞に使う"
  type        = string
}

variable "vpc_cidr" {
  description = "VPC の CIDR。/16 を想定し、先頭から /24 を切り出して subnet にする"
  type        = string
}

variable "azs" {
  description = "public subnet を置く AZ。Fargate がタスクをどれかに置く"
  type        = list(string)
}

variable "tunnel_token" {
  description = "cloudflared に渡す Cloudflare Tunnel のトークン (modules/cloudflare の output)。SSM に入れる"
  type        = string
  sensitive   = true
}

variable "e2e_access_client" {
  description = "E2E が Cloudflare Access を通るためのサービストークン (modules/cloudflare の output)。null なら SSM に入れない (prod)"
  type = object({
    client_id     = string
    client_secret = string
  })
  default   = null
  sensitive = true
}
