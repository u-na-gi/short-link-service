// Cloudflare Worker のエントリ。静的アセット (vite build の dist) と Play への振り分けを持つ。
// Play へは Workers VPC の VPC Service (Cloudflare Tunnel) で届く。設定は ../wrangler.jsonc。

import {
  clientKey,
  limitTargetOf,
  rateLimited,
  serverUnavailable,
  toServerRequest,
} from "./route.ts";

interface Env {
  ASSETS: Fetcher;
  SERVER: Fetcher;
  API_LIMITER: RateLimit;
  REDIRECT_LIMITER: RateLimit;
}

export default {
  async fetch(request, env): Promise<Response> {
    const target = limitTargetOf(new URL(request.url).pathname);
    if (target !== null) {
      const limiter = target === "api" ? env.API_LIMITER : env.REDIRECT_LIMITER;
      const { success } = await limiter.limit({ key: clientKey(request) });
      if (!success) return rateLimited();

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
