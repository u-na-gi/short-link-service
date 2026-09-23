このディレクトリは E2E 用。シナリオは runn (YAML) で書く。

- シナリオは `scenarios/*.yml` に置く。向き先は `${E2E_BASE_URL:-http://localhost:9000}`
- 実行はリポジトリルートの `make e2e`。E2E 専用の compose (`compose.e2e.yaml`) で server / front を立ち上げ、front 経由で流して片付ける
- 構文だけ見るなら `runn list -l "tests/scenarios/*.yml" </dev/null` (stdin を閉じないと待ち続ける)
- ドメインのルールや複雑なアサーションは `src/server/test/` の Scala 側に書く
