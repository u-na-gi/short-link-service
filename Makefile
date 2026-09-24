.PHONY: up down logs logs-server e2e e2e-remote deploy-front

# 開発用の compose (compose.yaml)。.env は cp .env.example .env で用意する

# --renew-anon-volumes: package.json を変えたときに、前回の node_modules が残らないようにする
up:
	docker compose up -d --build --renew-anon-volumes

down:
	docker compose down

logs:
	docker compose logs -f

# server のログは JSON で 1 行ずつ出るので jq で整形する。sbt 自身の出力など JSON でない行はそのまま出す
logs-server:
	docker compose logs -f --no-log-prefix server | jq -R 'fromjson? // .'

# E2E 専用の compose (compose.e2e.yaml を重ねた別プロジェクト)。開発用が起動していても干渉しない。
# server / front を立ち上げてシナリオを流し、終わったら結果に関わらず片付ける。
# 片付けるとログも消えるので、失敗したときだけ先に server / front のログを出す。
# runn に渡す引数は E2E_ARGS で足す (例: make e2e E2E_ARGS=--debug)
E2E_COMPOSE := docker compose -p short-link-e2e -f compose.yaml -f compose.e2e.yaml

e2e:
	$(E2E_COMPOSE) build
	$(E2E_COMPOSE) run --rm runn $(E2E_ARGS); \
	code=$$?; \
	if [ $$code -ne 0 ]; then $(E2E_COMPOSE) logs --no-color server front; fi; \
	$(E2E_COMPOSE) rm -fsv; \
	$(E2E_COMPOSE) down; \
	exit $$code

# デプロイ済みの環境に同じシナリオを流す。例: make e2e-remote ENV=develop
# 向き先は Terraform の output (public_base_url)。
# - Cloudflare Access をかけた環境 (develop / staging) は、サービストークンを SSM から読んでヘッダで付ける。
#   トークンはコマンドラインに載せず、環境変数で runn に渡す。
# - Turnstile をかけた環境 (staging / prod) は E2E_TURNSTILE=on で、短縮・復元のシナリオを飛ばし、
#   トークンの無いリクエストが 403 で断られることを確かめる (turnstile.yml)。
# Worker の回数制限 (API は IP ごとに 1 分 20 回) があるので、続けて流すときは 1 分空ける。
# --debug / --debug-on-failure はリクエストヘッダ (トークンのシークレット) をそのまま出すので、ここでは付けない。
# 手元で調べるときだけ E2E_ARGS=--debug で足し、出力を CI のログなどに残さない。
ENV ?= develop

# 実行中に読んだ秘密の値を、GitHub Actions の公開ログで伏せ字にする (手元では何もしない)
MASK = mask() { if [ -n "$${GITHUB_ACTIONS:-}" ] && [ -n "$$1" ]; then echo "::add-mask::$$1"; fi; }

RUNN_IMAGE := ghcr.io/k1low/runn:v1.11.0

# 手元では SSO のプロファイルを使う。CI (GitHub Actions は CI=true) は OIDC の認証情報を環境変数で持つ
ifndef CI
export AWS_PROFILE ?= short-link-develop
endif

e2e-remote:
	@$(MASK); outputs=$$(terraform -chdir=infra/terraform/envs/$(ENV) output -json) || exit 1; \
	out() { printf '%s' "$$outputs" | jq -r --arg k "$$1" '.[$$k].value // empty'; }; \
	base=$$(out public_base_url); \
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
	docker run --rm \
		-e E2E_BASE_URL=$$base \
		-e E2E_SELF_URL=$$base/abcd1234 \
		-e E2E_UNKNOWN_SHORT_URL=$$base/zzzzzzzz \
		-e E2E_TURNSTILE=$$E2E_TURNSTILE \
		-e CF_ACCESS_CLIENT_ID -e CF_ACCESS_CLIENT_SECRET \
		-v $${LOCAL_WORKSPACE_FOLDER:-$$PWD}/tests/scenarios:/scenarios:ro \
		$(RUNN_IMAGE) run "/scenarios/*.yml" --verbose $(E2E_ARGS)

# front (Cloudflare Worker) を ENV に deploy する。例: make deploy-front ENV=staging
# Turnstile を使う環境 (staging / prod) では、Terraform の output からサイトキーをビルドに、シークレットを
# Worker の secret に渡す。シークレットは一時ファイル (600) に書いて --secrets-file で同じバージョンに載せ、
# 「Turnstile は on なのにシークレットが無い」瞬間を作らない。コマンドラインには載せない。
# Cloudflare の認証情報は infra/.envrc.local (CI では環境変数) から読む。
# 値が null の output (Turnstile を使わない develop) は state に載らないので、無ければ空として扱う。
# deploy の前に、wrangler.jsonc の VPC Service の ID・ホスト名・Turnstile の有無が output と合っているか確かめる。
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
