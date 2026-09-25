# Service token for E2E (runn) to pass Cloudflare Access. Local and CI E2E runs read it from here.
# Not created in environments without Access (prod).

locals {
  # The variable itself is sensitive, so pass only whether it is null to count
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
