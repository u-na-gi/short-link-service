# E2E (runn) が Cloudflare Access を通るためのサービストークン。手元や CI の E2E はここから読む。
# Access をかけない環境 (prod) では作らない。

locals {
  # 変数そのものは sensitive なので、count には null かどうかだけを渡す
  e2e_access_enabled = nonsensitive(var.e2e_access_client != null)
}

resource "aws_ssm_parameter" "e2e_access_client_id" {
  count = local.e2e_access_enabled ? 1 : 0

  name  = "/${local.name}/e2e/access-client-id"
  type  = "SecureString"
  value = var.e2e_access_client.client_id
}

resource "aws_ssm_parameter" "e2e_access_client_secret" {
  count = local.e2e_access_enabled ? 1 : 0

  name  = "/${local.name}/e2e/access-client-secret"
  type  = "SecureString"
  value = var.e2e_access_client.client_secret
}
