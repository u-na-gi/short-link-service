# Cloudflare Access (develop / staging). Requires login in front of the Worker hostname and lets only allowed email addresses through.
# Login uses a one-time code sent by email (the Zero Trust default login method).
# prod is a public service, so access_allowed_email = null and Access is not applied.
#
# Machines such as E2E (runn) pass with a service token, sending CF-Access-Client-Id / CF-Access-Client-Secret headers.
# The token is returned as an output and modules/aws stores it in SSM.

locals {
  # The email address itself is sensitive, so extract only whether it is null (used for outputs and count)
  access_enabled = nonsensitive(var.access_allowed_email != null)
}

resource "cloudflare_zero_trust_access_policy" "owner" {
  count = local.access_enabled ? 1 : 0

  account_id = var.account_id
  name       = "${local.name}-owner"
  decision   = "allow"

  include = [{
    email = { email = var.access_allowed_email }
  }]
}

resource "cloudflare_zero_trust_access_service_token" "e2e" {
  count = local.access_enabled ? 1 : 0

  account_id = var.account_id
  name       = "${local.name}-e2e"
  # Default of 1 year. When it expires, bump client_secret_version to recreate it
  duration = "8760h"
}

# A service token is not a person, so use non_identity (passes with headers only, no login screen)
resource "cloudflare_zero_trust_access_policy" "e2e" {
  count = local.access_enabled ? 1 : 0

  account_id = var.account_id
  name       = "${local.name}-e2e"
  decision   = "non_identity"

  include = [{
    service_token = { token_id = cloudflare_zero_trust_access_service_token.e2e[0].id }
  }]
}

resource "cloudflare_zero_trust_access_application" "site" {
  count = local.access_enabled ? 1 : 0

  account_id       = var.account_id
  name             = local.name
  type             = "self_hosted"
  domain           = var.hostname
  session_duration = "24h"

  policies = [
    {
      id         = cloudflare_zero_trust_access_policy.owner[0].id
      precedence = 1
    },
    {
      id         = cloudflare_zero_trust_access_policy.e2e[0].id
      precedence = 2
    },
  ]
}
