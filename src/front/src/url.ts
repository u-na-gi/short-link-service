/** URL problems rejected before sending. Mirrors the server's `Url.Error`. */
export type UrlProblem = "empty" | "too_long" | "malformed" | "unsupported_scheme" | "credentials";

/** Same limit as the server (`Url.MaxLength`). */
const MaxLength = 2048;

const AllowedSchemes = new Set(["http", "https"]);

/** Like the server, extract the scheme first to tell errors apart. */
const Scheme = /^([A-Za-z][A-Za-z0-9+.-]*):/;

/**
 * Finds URLs that clearly would not pass, before sending. Returns null if there is no problem.
 *
 * The server makes the final decision. Rejecting a URL here that the server accepts leaves the user no way to send it,
 * so this is never stricter than the server. Things where the browser URL parser and the server (okhttp) may disagree,
 * such as length after normalization or fine details of IDN handling, are left to the server.
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
  // user:pass@host is a common phishing trick to disguise the destination, so the server rejects it too.
  if (url.username !== "" || url.password !== "") return "credentials";
  return null;
}

/** The text for an empty value differs per form, so callers own it. */
export function describeUrlProblem(problem: Exclude<UrlProblem, "empty">): string {
  switch (problem) {
    case "too_long":
      return `The URL is too long. Paste a URL of up to ${MaxLength} characters.`;
    case "malformed":
      return "Could not read this as a URL. Paste a URL that starts with http:// or https://.";
    case "unsupported_scheme":
      return "Paste a URL that starts with http:// or https://.";
    case "credentials":
      return "URLs that contain a user name or password are not allowed.";
  }
}
