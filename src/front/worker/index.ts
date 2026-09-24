// Cloudflare Worker のエントリ。静的アセット (vite build の dist) と Play への振り分けを持つ。
// Play へは Workers VPC の VPC Service (Cloudflare Tunnel) で届く。設定は ../wrangler.jsonc。

import {
  clientKey,
  limitTargetOf,
  rateLimited,
  serverUnavailable,
  toServerRequest,
} from "./route.ts";
import {
  TURNSTILE_HEADER,
  turnstileActionOf,
  turnstileFailed,
  verifyTurnstile,
} from "./turnstile.ts";

interface Env {
  ASSETS: Fetcher;
  SERVER: Fetcher;
  API_LIMITER: RateLimit;
  REDIRECT_LIMITER: RateLimit;
  // "on" の環境 (staging / prod) だけ短縮と復元に Turnstile をかける。wrangler.jsonc の vars
  TURNSTILE?: string;
  // wrangler secret put TURNSTILE_SECRET_KEY (make deploy-front が Terraform の output から入れる)
  TURNSTILE_SECRET_KEY?: string;
}

export default {
  async fetch(request, env): Promise<Response> {
    const url = new URL(request.url);
    const target = limitTargetOf(url.pathname);
    if (target !== null) {
      const limiter = target === "api" ? env.API_LIMITER : env.REDIRECT_LIMITER;
      const { success } = await limiter.limit({ key: clientKey(request) });
      if (!success) return rateLimited();

      const action = turnstileActionOf(request.method, url.pathname);
      if (env.TURNSTILE === "on" && action !== null) {
        // シークレットの入れ忘れで黙って Turnstile が外れないよう、無ければ断る
        if (!env.TURNSTILE_SECRET_KEY) {
          console.error("TURNSTILE is on but TURNSTILE_SECRET_KEY is not set");
          return serverUnavailable();
        }
        const token = request.headers.get(TURNSTILE_HEADER);
        const verified =
          token !== null &&
          (await verifyTurnstile({
            secret: env.TURNSTILE_SECRET_KEY,
            token,
            remoteIp: request.headers.get("cf-connecting-ip"),
            action,
            hostname: url.hostname,
          }));
        if (!verified) return turnstileFailed();
      }

      try {
        return await env.SERVER.fetch(toServerRequest(request));
      } catch (e) {
        console.error("failed to reach the server through the tunnel", e);
        return serverUnavailable();
      }
    }
    return env.ASSETS.fetch(request);
  },
} satisfies ExportedHandler<Env>;
