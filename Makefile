.PHONY: up down logs logs-server e2e e2e-remote deploy-front

# Dev compose (compose.yaml). Create .env with cp .env.example .env

# --renew-anon-volumes: do not keep the previous node_modules after package.json changes
up:
	docker compose up -d --build --renew-anon-volumes

down:
	docker compose down

logs:
	docker compose logs -f

# Server logs are one JSON object per line, so format them with jq. Non-JSON lines (e.g. sbt's own output) pass through as-is
logs-server:
	docker compose logs -f --no-log-prefix server | jq -R 'fromjson? // .'

# E2E-only compose (a separate project that layers compose.e2e.yaml). Does not interfere with a running dev stack.
# Starts server / front, runs the scenarios, and tears down afterwards regardless of the result.
# Tearing down also removes the logs, so print the server / front logs first, only on failure.
# Pass extra runn arguments via E2E_ARGS (e.g. make e2e E2E_ARGS=--debug)
E2E_COMPOSE := docker compose -p short-link-e2e -f compose.yaml -f compose.e2e.yaml

e2e:
	$(E2E_COMPOSE) build
	$(E2E_COMPOSE) run --rm runn $(E2E_ARGS); \
	code=$$?; \
	if [ $$code -ne 0 ]; then $(E2E_COMPOSE) logs --no-color server front; fi; \
	$(E2E_COMPOSE) rm -fsv; \
	$(E2E_COMPOSE) down; \
	exit $$code

# Run the same scenarios against a deployed environment. e.g. make e2e-remote ENV=develop
# The target is the Terraform output (public_base_url). Self-referencing / unissued short URLs are built from the short URL base (shortener_base_url)
# (develop uses example.com per the requirements, which differs from the site host).
# - Environments behind Cloudflare Access (develop / staging): read the service token from SSM and send it as headers.
#   The token is not put on the command line; it is passed to runn via environment variables.
# - Environments behind Turnstile (staging / prod): with E2E_TURNSTILE=on, skip the shorten/resolve scenarios and
#   check that requests without a token are rejected with 403 (turnstile.yml).
# The Worker has a rate limit (API: 20 requests per minute per IP), so wait a minute between consecutive runs.
# First hit the health check once and print the status and Cloudflare's verdict (302 to the Access login, cf-mitigated, etc.).
# When every scenario fails, this tells whether the problem is the app or the entry point (Access / bot protection).
# --debug / --debug-on-failure print request headers (the token secret) as-is, so they are not added here.
# Add them with E2E_ARGS=--debug only when investigating locally, and do not leave the output in CI logs etc.
ENV ?= develop

# Mask secret values read during the run in the public GitHub Actions log (does nothing locally)
MASK = mask() { if [ -n "$${GITHUB_ACTIONS:-}" ] && [ -n "$$1" ]; then echo "::add-mask::$$1"; fi; }

RUNN_IMAGE := ghcr.io/k1low/runn:v1.11.0

# Locally, use the SSO profile. CI (GitHub Actions sets CI=true) has OIDC credentials in environment variables
ifndef CI
export AWS_PROFILE ?= short-link-develop
endif

e2e-remote:
	@$(MASK); outputs=$$(terraform -chdir=infra/terraform/envs/$(ENV) output -json) || exit 1; \
	out() { printf '%s' "$$outputs" | jq -r --arg k "$$1" '.[$$k].value // empty'; }; \
	base=$$(out public_base_url); \
	short=$$(out shortener_base_url); \
	E2E_TURNSTILE=$$(if [ -n "$$(out turnstile_site_key)" ]; then echo on; fi); \
	CF_ACCESS_CLIENT_ID=; CF_ACCESS_CLIENT_SECRET=; \
	if [ "$$(out access_enabled)" = true ]; then \
		param() { aws ssm get-parameter --with-decryption --name "/short-link-$(ENV)/e2e/$$1" --query Parameter.Value --output text; }; \
		CF_ACCESS_CLIENT_ID=$$(param access-client-id) || exit 1; \
		CF_ACCESS_CLIENT_SECRET=$$(param access-client-secret) || exit 1; \
		mask "$$CF_ACCESS_CLIENT_ID"; mask "$$CF_ACCESS_CLIENT_SECRET"; \
	fi; \
	export CF_ACCESS_CLIENT_ID CF_ACCESS_CLIENT_SECRET; \
	echo "E2E -> $$base (turnstile: $${E2E_TURNSTILE:-off})"; \
	echo "preflight: $$(curl -sS -o /dev/null -D - -H "CF-Access-Client-Id: $$CF_ACCESS_CLIENT_ID" -H "CF-Access-Client-Secret: $$CF_ACCESS_CLIENT_SECRET" "$$base/api/v1/health" \
		| grep -i -E '^(HTTP/|location:|cf-mitigated:|server:|content-type:)' | tr -d '\r' | tr '\n' ' ')"; \
	docker run --rm \
		-e E2E_BASE_URL=$$base \
		-e E2E_SELF_URL=$$short/abcd1234 \
		-e E2E_UNKNOWN_SHORT_URL=$$short/zzzzzzzz \
		-e E2E_TURNSTILE=$$E2E_TURNSTILE \
		-e CF_ACCESS_CLIENT_ID -e CF_ACCESS_CLIENT_SECRET \
		-v $${LOCAL_WORKSPACE_FOLDER:-$$PWD}/tests/scenarios:/scenarios:ro \
		$(RUNN_IMAGE) run "/scenarios/*.yml" --verbose $(E2E_ARGS)

# Deploy front (Cloudflare Worker) to ENV. e.g. make deploy-front ENV=staging
# In environments that use Turnstile (staging / prod), pass the site key from the Terraform output to the build and the secret to
# the Worker secret. The secret is written to a temp file (600) and uploaded with --secrets-file in the same version,
# so there is never a moment where "Turnstile is on but the secret is missing". It is not put on the command line.
# Cloudflare credentials are read from infra/.envrc.local (environment variables in CI).
# Outputs whose value is null (develop, which does not use Turnstile) are not stored in state, so treat missing ones as empty.
# Before deploying, check that the VPC Service ID, hostname, and Turnstile on/off in wrangler.jsonc match the outputs.
deploy-front:
	@$(MASK); set -a; [ -f infra/.envrc.local ] && . ./infra/.envrc.local; set +a; \
	outputs=$$(terraform -chdir=infra/terraform/envs/$(ENV) output -json) || exit 1; \
	out() { printf '%s' "$$outputs" | jq -r --arg k "$$1" '.[$$k].value // empty'; }; \
	site=$$(out turnstile_site_key); \
	secret=$$(out turnstile_secret_key); mask "$$secret"; \
	secrets=$$(mktemp) || exit 1; trap 'rm -f "$$secrets"' EXIT; chmod 600 "$$secrets"; \
	if [ -n "$$secret" ]; then printf 'TURNSTILE_SECRET_KEY=%s\n' "$$secret" > "$$secrets"; fi; \
	printf '%s' "$$outputs" | (cd src/front && bun run scripts/check-wrangler-config.ts $(ENV)) || exit 1; \
	cd src/front && VITE_TURNSTILE_SITE_KEY=$$site bun run build && \
	bunx wrangler deploy --env $(ENV) $$(if [ -s "$$secrets" ]; then echo --secrets-file "$$secrets"; fi)
