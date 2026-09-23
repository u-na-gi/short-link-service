# front

URL を貼り付けると短縮 URL を返し、短縮 URL を貼り付けると元の URL を返す画面。ログインなし。Vite + React + TypeScript (bun)。

```sh
bun install
bun run dev        # http://localhost:5173
bun run build      # 型チェック + dist/ に出力
bun run test       # bun test (url.test.ts など)
bun run format
```

普段はリポジトリルートの `make up` で、server と一緒にコンテナで起動する (`src/front/Dockerfile`)。
ソースはマウントするので、編集は HMR で反映される。node_modules はコンテナ側のものを使う。

## ローカルの繋ぎ方

コンテナを使わず直接動かすときは、`bun run dev` の前に `src/server` で `sbt run` (9000) を立ち上げておく。

Vite の proxy で `/api/*` と `/{英数 8 文字}` を Play に流し、ブラウザからは `localhost:5173` の 1 オリジンに見せている。
サーバの `shortener.base-url` の既定値が `http://localhost:5173` なので、返ってくる短縮 URL をそのまま踏める。
Play の向き先を変えるときは `API_ORIGIN=http://host:port bun run dev`。compose では `http://server:9000` を渡している。

compose (`make up`) では root の `.env` の `SHORTENER_BASE_URL` (`https://example.com/`) が使われるので、返ってくる短縮 URL はローカルでは開けない。
E2E では runn が `Host: front` で来るので、`vite.config.ts` の `allowedHosts` に `front` を入れている。

## 画面

- 上のフォーム: URL を短縮する (`POST /api/v1/links`)。
- 下のフォーム (`ResolveForm.tsx`): 短縮 URL を丸ごと貼り付けると元の URL を表示する (`GET /api/v1/links/resolve?shortUrl=`)。自サービスの URL か、どこがコードかの判定はサーバが行う。
- どちらも貼り付けた時点で送信する。送る前に `url.ts` で、明らかに通らない URL (空・スキーム違いなど) だけを弾く。

本番では CloudFront が同じ振り分けをする (default → S3、`/api/*` と `/????????` → EC2)。
