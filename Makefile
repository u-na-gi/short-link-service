.PHONY: up down logs logs-server e2e

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
