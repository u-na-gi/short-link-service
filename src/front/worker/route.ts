// Decides whether a request the Worker receives goes to Play (behind the Tunnel) or to static assets.
// Routing matches the proxy in the dev vite.config.ts: /api/* and short URLs (/{8 alphanumeric chars}) go to Play.

const SHORT_CODE_PATH = /^\/[A-Za-z0-9]{8}$/;

// Paths passed to Play and their rate limit class. null means static assets.
// Public writes (shorten, resolve) are limited strictly; short URL redirects more loosely.
// The limits are the ratelimits in wrangler.jsonc (API_LIMITER / REDIRECT_LIMITER)
export type LimitTarget = "api" | "redirect";

export function limitTargetOf(pathname: string): LimitTarget | null {
  if (pathname.startsWith("/api/")) return "api";
  if (SHORT_CODE_PATH.test(pathname)) return "redirect";
  return null;
}

// Rate limit key. Uses the client IP added by Cloudflare (X-Forwarded-For sent by users is not trusted)
export function clientKey(request: Request): string {
  return request.headers.get("cf-connecting-ip") ?? "unknown";
}

// Play's address through the Tunnel. cloudflared is a sidecar in the task and shares the network namespace with Play.
// The Host becomes localhost, which is in Play's default allowed hosts list
export const SERVER_ORIGIN = "http://localhost:9000";

// Headers from users that are not passed to Play.
// - cookie / cf-access-jwt-assertion: Play has no sessions, and there is no reason to pass Cloudflare Access credentials
// - x-forwarded-for: Play trusts 127.0.0.1 (cloudflared) as a proxy, so passing the user's value
//   as is would let them spoof remoteAddress
// - x-turnstile-token: already verified by the Worker. Not relevant to Play
const DROPPED_HEADERS = [
  "cookie",
  "cf-access-jwt-assertion",
  "x-forwarded-for",
  "x-turnstile-token",
  "host",
];

// Builds the request passed to Play.
// - The original host and scheme are passed in X-Forwarded-* (the access log host prefers X-Forwarded-Host)
// - Redirects are not followed, so the short URL's 302 goes back to the user as is
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

// Returned when the Tunnel target is unreachable (e.g. while the task restarts). Uses the same {"error"} shape as API errors
// so the front end can show "a server error occurred". Cloudflare's default error page (1101) is not returned
export function serverUnavailable(): Response {
  return Response.json({ error: "server_unavailable" }, { status: 502 });
}

// Returned when the rate limit is exceeded. Uses the same {"error"} shape as API errors
export function rateLimited(): Response {
  return Response.json(
    { error: "rate_limited" },
    { status: 429, headers: { "retry-after": "60" } },
  );
}
