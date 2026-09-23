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
  return { ok: false, message: describe(res.status, body as ErrorBody | null) };
}

export function shortenUrl(url: string): Promise<LinkResult> {
  return requestLink(
    "/api/v1/links",
    {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ url }),
    },
    describeShorten,
  );
}

/** 短縮 URL を丸ごと渡す。自サービスの URL か、どこがコードかの判定はサーバが行う。 */
export function resolveShortUrl(shortUrl: string): Promise<LinkResult> {
  const query = new URLSearchParams({ shortUrl });
  return requestLink(`/api/v1/links/resolve?${query}`, undefined, describeResolve);
}
