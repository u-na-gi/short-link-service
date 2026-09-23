# リポジトリ全体のタスク。Scala 側のタスクは url-shortener-server/Makefile にある。
.PHONY: help e2e e2e-scenarios

# runn に渡す追加オプション (例: make e2e E2E_ARGS=--debug)
E2E_ARGS ?=

help:
	@echo "make e2e            # サーバの起動から停止まで面倒を見て E2E を流す"
	@echo "make e2e-scenarios  # 起動済みのサーバに runn のシナリオを流すだけ"
	@echo "make e2e E2E_ARGS=--debug   # 成功したステップも HTTP のやり取りを出す"

e2e:
	@bun run tests/e2e.ts $(E2E_ARGS)

e2e-scenarios:
	@runn run "tests/scenarios/*.yml" --verbose --debug-on-failure $(E2E_ARGS)
