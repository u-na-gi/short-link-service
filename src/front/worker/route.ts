// Worker が受けたリクエストを、Play (Tunnel の先) と静的アセットのどちらに渡すか決める。
// 振り分けは開発用の vite.config.ts の proxy と同じ: /api/* と短縮 URL (/{英数 8 文字}) が Play。

const SHORT_CODE_PATH = /^\/[A-Za-z0-9]{8}$/;

// Play に渡すパスと、その回数制限の区分。null は静的アセット。
// 公開の書き込み (短縮・復元) は厳しく、短縮 URL のリダイレクトは緩くする。
// 回数は wrangler.jsonc の ratelimits (API_LIMITER / REDIRECT_LIMITER)
export type LimitTarget = "api" | "redirect";

export function limitTargetOf(pathname: string): LimitTarget | null {
  if (pathname.startsWith("/api/")) return "api";
  if (SHORT_CODE_PATH.test(pathname)) return "redirect";
  return null;
}

// 回数制限のキー。Cloudflare が付ける接続元 IP を使う (利用者が送る X-Forwarded-For は信用しない)
export function clientKey(request: Request): string {
  return request.headers.get("cf-connecting-ip") ?? "unknown";
}

// Tunnel 越しの Play の宛先。cloudflared はタスクのサイドカーで、Play とネットワーク名前空間を共有する。
// Host は localhost になり、Play の既定の Host 許可リストに入っている
export const SERVER_ORIGIN = "http://localhost:9000";

// 利用者から来ても Play に渡さないヘッダ。
// - cookie / cf-access-jwt-assertion: Play はセッションを持たず、Cloudflare Access の認証情報を渡す理由がない
// - x-forwarded-for: Play は 127.0.0.1 (cloudflared) をプロキシとして信頼するので、利用者が送った値を
//   そのまま渡すと remoteAddress を偽装できてしまう
const DROPPED_HEADERS = ["cookie", "cf-access-jwt-assertion", "x-forwarded-for", "host"];

// Play に渡すリクエストを作る。
// - 元のホストとスキームは X-Forwarded-* で渡す (アクセスログの host は X-Forwarded-Host を優先する)
// - リダイレクトは追わない。短縮 URL の 302 をそのまま利用者に返すため
export function toServerRequest(request: Request): Request {
  const url = new URL(request.url);
  const headers = new Headers(request.headers);
  for (const name of DROPPED_HEADERS) headers.delete(name);
  headers.set("x-forwarded-host", url.host);
  headers.set("x-forwarded-proto", url.protocol.replace(":", ""));

  return new Request(SERVER_ORIGIN + url.pathname + url.search, {
    method: request.method,
    headers,
    body: request.body,
    redirect: "manual",
  });
}

// Tunnel の先に繋がらないとき (タスクの再起動中など) に返す。API のエラーと同じ {"error"} の形にして、
// フロントが「サーバーでエラー」と案内できるようにする。Cloudflare の既定のエラーページ (1101) は返さない
export function serverUnavailable(): Response {
  return Response.json({ error: "server_unavailable" }, { status: 502 });
}

// 回数制限を超えたときに返す。API のエラーと同じ {"error"} の形にする
export function rateLimited(): Response {
  return Response.json(
    { error: "rate_limited" },
    { status: 429, headers: { "retry-after": "60" } },
  );
}
