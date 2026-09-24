// Turnstile (staging / prod) の検証。短縮と復元だけにかけ、front が X-Turnstile-Token ヘッダで送るトークンを
// siteverify で確かめてから Play に渡す。短縮 URL のリダイレクトやヘルスチェックにはかけない。

export const TURNSTILE_HEADER = "x-turnstile-token";

const SITEVERIFY_URL = "https://challenges.cloudflare.com/turnstile/v0/siteverify";

// front のウィジェットの action と揃える (src/front/src/turnstile.ts)。
// action まで確かめるのは、短縮のフォームで取ったトークンを復元に使い回させないため
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

// トークンが本物で、同じホストの同じ action のウィジェットで取ったものかを確かめる。
// siteverify に繋がらないときも通さない (Turnstile を迂回させない)
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

// トークンが無い・検証に通らないときに返す。API のエラーと同じ {"error"} の形にする
export function turnstileFailed(): Response {
  return Response.json({ error: "turnstile_failed" }, { status: 403 });
}
