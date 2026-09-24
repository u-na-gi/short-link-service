import { describeUrlProblem } from "./url";

export type ShortLink = {
  code: string;
  shortUrl: string;
  originalUrl: string;
};

/** 短縮と復元はどちらも同じ形のリンクを返すので、結果の型も共通にする。 */
export type LinkResult = { ok: true; link: ShortLink } | { ok: false; message: string };

/** サーバはエラーコードだけを返す (入力値や内部の事情は返さない)。利用者向けの文言はここで持つ。 */
type ErrorBody = { error?: string; reason?: string };

const retryLater = "時間をおいて、もう一度お試しください。";

/** エラーコードで出し分けないときの文言。短縮と復元で共通。 */
function describeCommon(status: number): string {
  if (status === 429)
    return "短い時間に何度も送信されました。1 分ほど待ってから、もう一度お試しください。";
  return status >= 500
    ? `サーバーでエラーが起きました。${retryLater}`
    : `うまくいきませんでした (${status})。${retryLater}`;
}

/** サーバのエラーコードを、利用者が次に何をすればいいか分かる文に直す。 */
function describeShorten(status: number, body: ErrorBody | null): string {
  switch (body?.error) {
    case "invalid_url":
      // 送信前にも同じ検証をしているが、ブラウザとサーバで判定がずれたときはサーバの理由で案内する。
      return describeInvalidUrl(body.reason);
    case "self_reference":
      return "すでに短縮された URL です。元に戻すなら下の欄に貼り付けてください。";
    case "code_generation_failed":
      return `短縮 URL を発行できませんでした。${retryLater}`;
    case "storage_full":
      return "発行できる件数の上限に達したため、新しい短縮 URL を発行できません。";
    default:
      return describeCommon(status);
  }
}

function describeInvalidUrl(reason: string | undefined): string {
  switch (reason) {
    case "too_long":
    case "malformed":
    case "unsupported_scheme":
    case "credentials":
      return describeUrlProblem(reason);
    case "empty":
      return "短縮する URL を貼り付けてください。";
    default:
      return describeUrlProblem("unsupported_scheme");
  }
}

function describeResolve(status: number, body: ErrorBody | null): string {
  switch (body?.error) {
    case "not_short_url":
      return "このサービスで発行した短縮 URL を貼り付けてください。";
    case "not_found":
      return "この短縮 URL は見つかりませんでした。URL が正しいか確かめてください。";
    default:
      return describeCommon(status);
  }
}

/** 短縮と復元のどちらでも起きる。Turnstile をかける環境 (staging / prod) だけ。 */
const turnstileFailedMessage = "人による操作か確認できませんでした。もう一度お試しください。";

async function requestLink(
  input: RequestInfo,
  init: RequestInit | undefined,
  describe: (status: number, body: ErrorBody | null) => string,
): Promise<LinkResult> {
  let res: Response;
  try {
    res = await fetch(input, init);
  } catch {
    return { ok: false, message: `サーバーに接続できませんでした。${retryLater}` };
  }

  // 502 などプロキシが返す HTML でも落ちないよう、JSON でなければ null として扱う。
  const body: unknown = await res.json().catch(() => null);
  if (res.ok) return { ok: true, link: body as ShortLink };
  if ((body as ErrorBody | null)?.error === "turnstile_failed") {
    return { ok: false, message: turnstileFailedMessage };
  }
  return { ok: false, message: describe(res.status, body as ErrorBody | null) };
}

/**
 * Turnstile のトークン。null は Turnstile を使わない環境、"failed" はウィジェットで取れなかったとき。
 * 取れなかったときは送らずに案内する (送っても Worker に断られる)。
 */
export type TurnstileToken = string | null | "failed";

function withTurnstile(token: string | null): Record<string, string> {
  return token === null ? {} : { "X-Turnstile-Token": token };
}

export function shortenUrl(url: string, token: TurnstileToken = null): Promise<LinkResult> {
  if (token === "failed") return Promise.resolve({ ok: false, message: turnstileFailedMessage });
  return requestLink(
    "/api/v1/links",
    {
      method: "POST",
      headers: { "Content-Type": "application/json", ...withTurnstile(token) },
      body: JSON.stringify({ url }),
    },
    describeShorten,
  );
}

/** 短縮 URL を丸ごと渡す。自サービスの URL か、どこがコードかの判定はサーバが行う。 */
export function resolveShortUrl(
  shortUrl: string,
  token: TurnstileToken = null,
): Promise<LinkResult> {
  if (token === "failed") return Promise.resolve({ ok: false, message: turnstileFailedMessage });
  const query = new URLSearchParams({ shortUrl });
  return requestLink(
    `/api/v1/links/resolve?${query}`,
    { headers: withTurnstile(token) },
    describeResolve,
  );
}
