export type ShortLink = {
  code: string;
  shortUrl: string;
  originalUrl: string;
};

export type ShortenResult = { ok: true; link: ShortLink } | { ok: false; message: string };

type ErrorBody = { error?: string; message?: string };

const retryLater = "時間をおいて、もう一度短縮してください。";

/** サーバのエラーコードを、利用者が次に何をすればいいか分かる文に直す。 */
function describe(status: number, body: ErrorBody | null): string {
  switch (body?.error) {
    case "invalid_url":
      // 空・スキーム違い・長すぎるなど原因が複数あるので、サーバの説明をそのまま見せる。
      return body.message ?? "http:// か https:// で始まる URL を貼り付けてください。";
    case "self_reference":
      return "すでに短縮された URL です。元の URL を貼り付けてください。";
    case "code_generation_failed":
      return `短縮 URL を発行できませんでした。${retryLater}`;
    default:
      return status >= 500
        ? `サーバーでエラーが起きました。${retryLater}`
        : `短縮できませんでした (${status})。${retryLater}`;
  }
}

export async function shortenUrl(url: string): Promise<ShortenResult> {
  let res: Response;
  try {
    res = await fetch("/api/v1/links", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ url }),
    });
  } catch {
    return { ok: false, message: `サーバーに接続できませんでした。${retryLater}` };
  }

  // 502 などプロキシが返す HTML でも落ちないよう、JSON でなければ null として扱う。
  const body: unknown = await res.json().catch(() => null);
  if (res.ok) return { ok: true, link: body as ShortLink };
  return { ok: false, message: describe(res.status, body as ErrorBody | null) };
}
