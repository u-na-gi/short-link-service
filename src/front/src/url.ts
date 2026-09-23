/** 送信前に弾く URL の問題。サーバの `Url.Error` と対応させている。 */
export type UrlProblem = "empty" | "too_long" | "malformed" | "unsupported_scheme" | "credentials";

/** サーバ (`Url.MaxLength`) と同じ上限。 */
const MaxLength = 2048;

const AllowedSchemes = new Set(["http", "https"]);

/** サーバと同じく、エラーを出し分けるためにスキームだけ先に取り出す。 */
const Scheme = /^([A-Za-z][A-Za-z0-9+.-]*):/;

/**
 * 明らかに送っても通らない URL を送信前に見つける。問題がなければ null。
 *
 * 最終的な判定はサーバが行う。サーバが受け付ける URL をここで弾くと利用者が送る手段を失うので、
 * サーバより厳しくはしない。正規化後の長さや IDN の細かい扱いのように、
 * ブラウザの URL パーサとサーバ (okhttp) で結果がずれうるものはサーバに任せる。
 */
export function findUrlProblem(raw: string): UrlProblem | null {
  const trimmed = raw.trim();
  if (trimmed === "") return "empty";
  if (trimmed.length > MaxLength) return "too_long";

  const scheme = Scheme.exec(trimmed)?.[1];
  if (scheme === undefined) return "malformed";
  if (!AllowedSchemes.has(scheme.toLowerCase())) return "unsupported_scheme";

  let url: URL;
  try {
    url = new URL(trimmed);
  } catch {
    return "malformed";
  }
  // user:pass@host は行き先を誤認させるフィッシングの常套手段なので、サーバでも拒否している。
  if (url.username !== "" || url.password !== "") return "credentials";
  return null;
}

/** 空のときの文言はフォームごとに違うので、呼び出し側で持つ。 */
export function describeUrlProblem(problem: Exclude<UrlProblem, "empty">): string {
  switch (problem) {
    case "too_long":
      return `URL が長すぎます。${MaxLength} 文字以内の URL を貼り付けてください。`;
    case "malformed":
      return "URL として読み取れませんでした。http:// か https:// で始まる URL を貼り付けてください。";
    case "unsupported_scheme":
      return "http:// か https:// で始まる URL を貼り付けてください。";
    case "credentials":
      return "ユーザー名やパスワードを含む URL は使えません。";
  }
}
