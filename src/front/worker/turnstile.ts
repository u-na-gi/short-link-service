// Turnstile verification (staging / prod). Applied only to shorten and resolve: the token the front end sends in the
// X-Turnstile-Token header is checked with siteverify before passing to Play. Not applied to short URL redirects or health checks.

export const TURNSTILE_HEADER = "x-turnstile-token";

const SITEVERIFY_URL = "https://challenges.cloudflare.com/turnstile/v0/siteverify";

// Keep in sync with the front end widget's action (src/front/src/turnstile.ts).
// The action is checked so a token from the shorten form cannot be reused for resolve
export type TurnstileAction = "shorten" | "resolve";

export function turnstileActionOf(method: string, pathname: string): TurnstileAction | null {
  if (method === "POST" && pathname === "/api/v1/links") return "shorten";
  if (method === "GET" && pathname === "/api/v1/links/resolve") return "resolve";
  return null;
}

type SiteverifyResponse = {
  success: boolean;
  action?: string;
  hostname?: string;
};

// Checks that the token is genuine and came from a widget with the same action on the same host.
// Also rejects when siteverify is unreachable (so Turnstile cannot be bypassed)
export async function verifyTurnstile(params: {
  secret: string;
  token: string;
  remoteIp: string | null;
  action: TurnstileAction;
  hostname: string;
  fetchFn?: typeof fetch;
}): Promise<boolean> {
  const form = new FormData();
  form.append("secret", params.secret);
  form.append("response", params.token);
  if (params.remoteIp !== null) form.append("remoteip", params.remoteIp);

  try {
    const res = await (params.fetchFn ?? fetch)(SITEVERIFY_URL, { method: "POST", body: form });
    if (!res.ok) return false;
    const body = (await res.json()) as SiteverifyResponse;
    return body.success && body.action === params.action && body.hostname === params.hostname;
  } catch {
    return false;
  }
}

// Returned when the token is missing or fails verification. Uses the same {"error"} shape as API errors
export function turnstileFailed(): Response {
  return Response.json({ error: "turnstile_failed" }, { status: 403 });
}
