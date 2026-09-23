# front

URL を貼り付けると短縮 URL を返す画面。ログインなし。Vite + React + TypeScript (bun)。

```sh
bun install
bun run dev        # http://localhost:5173
bun run build      # 型チェック + dist/ に出力
bun run format
```

## ローカルの繋ぎ方

`bun run dev` の前に `src/server` で `sbt run` (9000) を立ち上げておく。

Vite の proxy で `/api/*` と `/{英数 8 文字}` を Play に流し、ブラウザからは `localhost:5173` の 1 オリジンに見せている。
サーバの `shortener.base-url` の既定値が `http://localhost:5173` なので、返ってくる短縮 URL をそのまま踏める。
Play の向き先を変えるときは `API_ORIGIN=http://host:port bun run dev`。

本番では CloudFront が同じ振り分けをする (default → S3、`/api/*` と `/????????` → EC2)。
