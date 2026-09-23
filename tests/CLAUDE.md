このディレクトリは E2E 用。シナリオは runn (YAML)、その周りの段取りだけ TypeScript (bun) で書く。

- シナリオは `scenarios/*.yml` に置く。向き先は `${E2E_BASE_URL:-http://localhost:9000}`
- サーバの起動〜停止は `e2e.ts`。Node.js ではなく bun で動かす (`bun run e2e.ts`)
- 実行は `bun run e2e` (tests/ で)。構文だけ見るなら `runn list -l "tests/scenarios/*.yml"`
- ドメインのルールや複雑なアサーションは `src/server/test/` の Scala 側に書く
