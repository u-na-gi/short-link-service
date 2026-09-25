import { describeUrlProblem } from "./url";

export type ShortLink = {
  code: string;
  shortUrl: string;
  originalUrl: string;
};

/** Shorten and resolve both return a link of the same shape, so they share the result type. */
export type LinkResult = { ok: true; link: ShortLink } | { ok: false; message: string };

/** The server returns only error codes (never input values or internal details). User-facing text lives here. */
type ErrorBody = { error?: string; reason?: string };

const retryLater = "Please try again later.";

/** Text used when not distinguishing by error code. Shared by shorten and resolve. */
function describeCommon(status: number): string {
  if (status === 429)
    return "Too many requests in a short time. Wait about a minute and try again.";
  return status >= 500
    ? `A server error occurred. ${retryLater}`
    : `Something went wrong (${status}). ${retryLater}`;
}

/** Turns a server error code into text that tells the user what to do next. */
function describeShorten(status: number, body: ErrorBody | null): string {
  switch (body?.error) {
    case "invalid_url":
      // The same check runs before sending, but if the browser and server disagree, guide the user by the server's reason.
      return describeInvalidUrl(body.reason);
    case "self_reference":
      return "This URL is already shortened. To resolve it, paste it in the field below.";
    case "code_generation_failed":
      return `Could not create a short URL. ${retryLater}`;
    case "storage_full":
      return "The limit on the number of short URLs has been reached, so no new short URLs can be created.";
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
      return "Paste the URL to shorten.";
    default:
      return describeUrlProblem("unsupported_scheme");
  }
}

function describeResolve(status: number, body: ErrorBody | null): string {
  switch (body?.error) {
    case "not_short_url":
      return "Paste a short URL created by this service.";
    case "not_found":
      return "This short URL was not found. Check that the URL is correct.";
    default:
      return describeCommon(status);
  }
}

/** Can happen for both shorten and resolve. Only in environments with Turnstile (staging / prod). */
const turnstileFailedMessage = "Could not verify that you are human. Please try again.";

async function requestLink(
  input: RequestInfo,
  init: RequestInit | undefined,
  describe: (status: number, body: ErrorBody | null) => string,
): Promise<LinkResult> {
  let res: Response;
  try {
    res = await fetch(input, init);
  } catch {
    return { ok: false, message: `Could not connect to the server. ${retryLater}` };
  }

  // Treat non-JSON as null so HTML from a proxy (such as a 502) does not break this.
  const body: unknown = await res.json().catch(() => null);
  if (res.ok) return { ok: true, link: body as ShortLink };
  if ((body as ErrorBody | null)?.error === "turnstile_failed") {
    return { ok: false, message: turnstileFailedMessage };
  }
  return { ok: false, message: describe(res.status, body as ErrorBody | null) };
}

/**
 * Turnstile token. null means an environment without Turnstile; "failed" means the widget could not get one.
 * When it could not be obtained, show a message without sending (the Worker would reject it anyway).
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

/** Passes the whole short URL. The server decides whether it belongs to this service and which part is the code. */
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
