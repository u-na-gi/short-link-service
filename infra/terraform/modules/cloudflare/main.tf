# Cloudflare side: the Tunnel, and the VPC Service (Workers VPC) that lets the Worker reach Play behind the Tunnel.
#
# cloudflared is a sidecar in the ECS task and shares the network namespace (awsvpc) with Play.
# So the VPC Service target is 127.0.0.1:9000. The token is returned as an output and modules/aws stores it in SSM.
# The Worker itself is deployed with wrangler (src/front/wrangler.jsonc).

resource "cloudflare_zero_trust_tunnel_cloudflared" "server" {
  account_id = var.account_id
  name       = local.name
  config_src = "cloudflare"
}

data "cloudflare_zero_trust_tunnel_cloudflared_token" "server" {
  account_id = var.account_id
  tunnel_id  = cloudflare_zero_trust_tunnel_cloudflared.server.id
}

resource "cloudflare_connectivity_directory_service" "server" {
  account_id = var.account_id
  name       = local.name
  type       = "http"
  http_port  = 9000

  host = {
    ipv4 = "127.0.0.1"
    network = {
      tunnel_id = cloudflare_zero_trust_tunnel_cloudflared.server.id
    }
  }
}
