import { useEffect, useId, useRef, useState, type ClipboardEvent, type SubmitEvent } from "react";
import { shortenUrl, type ShortLink } from "./api.ts";
import { ResolveForm } from "./ResolveForm.tsx";
import { useTurnstile } from "./turnstile.ts";
import { describeUrlProblem, findUrlProblem } from "./url.ts";

type State =
  | { kind: "idle" }
  | { kind: "loading" }
  | { kind: "done"; link: ShortLink }
  | { kind: "error"; message: string };

type CopyState = "idle" | "copied" | "failed";

export function App() {
  const [value, setValue] = useState("");
  const [state, setState] = useState<State>({ kind: "idle" });
  const [copy, setCopy] = useState<CopyState>("idle");
  const inputRef = useRef<HTMLInputElement>(null);
  // Sequence number so a stale response arriving late does not overwrite a newer result when pasting repeatedly.
  const latest = useRef(0);
  const lastSubmitted = useRef("");
  const errorId = useId();
  const turnstile = useTurnstile("shorten");

  async function submit(raw: string) {
    const url = raw.trim();
    const problem = findUrlProblem(url);
    if (problem !== null) {
      const message =
        problem === "empty" ? "Paste the URL you want to shorten." : describeUrlProblem(problem);
      setState({ kind: "error", message });
      return;
    }
    // If the same URL was just pasted again, the result on screen is already the answer, so do not send.
    if (url === lastSubmitted.current && state.kind === "done") return;

    const id = ++latest.current;
    lastSubmitted.current = url;
    setState({ kind: "loading" });
    setCopy("idle");

    const result = await shortenUrl(url, await turnstile.getToken());
    if (id !== latest.current) return;
    setState(
      result.ok ? { kind: "done", link: result.link } : { kind: "error", message: result.message },
    );
  }

  function onSubmit(e: SubmitEvent<HTMLFormElement>) {
    e.preventDefault();
    void submit(value);
  }

  function onChange(next: string) {
    setValue(next);
    // Check on every input so problems show before sending. An empty field is treated like before typing and is not an error.
    const problem = findUrlProblem(next);
    if (problem !== null && problem !== "empty") {
      // If a request is in flight, discard it so its late response does not overwrite the current error.
      latest.current++;
      setState({ kind: "error", message: describeUrlProblem(problem) });
    } else if (state.kind === "error") {
      setState({ kind: "idle" });
    }
  }

  function onPaste(_: ClipboardEvent<HTMLInputElement>) {
    // Send the value after the paste is applied to the input. Even when pasted mid-text, shorten the URL as shown.
    setTimeout(() => {
      if (inputRef.current) void submit(inputRef.current.value);
    }, 0);
  }

  async function onCopy(shortUrl: string) {
    try {
      await navigator.clipboard.writeText(shortUrl);
      setCopy("copied");
    } catch {
      setCopy("failed");
    }
  }

  // "Copied" only needs to be seen briefly, so switch back to the original label after a moment.
  useEffect(() => {
    if (copy !== "copied") return;
    const timer = setTimeout(() => setCopy("idle"), 2000);
    return () => clearTimeout(timer);
  }, [copy]);

  const loading = state.kind === "loading";
  const hasError = state.kind === "error";

  return (
    <main className="page">
      {/* The service is public but meant to be removed later, so show this first so people know before using it */}
      <p className="notice" role="note">
        This service is an experiment and may end without notice. Short URLs you create may also
        disappear at any time. Do not use it for important links.
      </p>
      <form className="search" onSubmit={onSubmit} noValidate aria-busy={loading}>
        <label className="visually-hidden" htmlFor="url">
          URL to shorten
        </label>
        <input
          ref={inputRef}
          id="url"
          className="search-input"
          type="url"
          inputMode="url"
          autoComplete="off"
          autoCapitalize="off"
          spellCheck={false}
          autoFocus
          placeholder="Paste a URL to shorten"
          value={value}
          onChange={(e) => onChange(e.target.value)}
          onPaste={onPaste}
          aria-invalid={hasError}
          aria-describedby={hasError ? errorId : undefined}
        />
        <button className="search-submit" type="submit" disabled={loading}>
          {loading ? "Shortening…" : "Shorten"}
        </button>
      </form>
      {/* A check appears here only when Turnstile decides it is needed */}
      <div ref={turnstile.containerRef} className="turnstile" />

      <div className="outcome" aria-live="polite">
        {state.kind === "error" && (
          <p id={errorId} className="error" role="alert">
            {state.message}
          </p>
        )}
        {state.kind === "done" && (
          <section className="result" key={state.link.code} aria-label="Short URL">
            <div className="result-row">
              <a className="result-url" href={state.link.shortUrl} target="_blank" rel="noreferrer">
                {state.link.shortUrl.replace(/^https?:\/\//, "")}
              </a>
              <button
                className="copy"
                type="button"
                onClick={() => void onCopy(state.link.shortUrl)}
              >
                {copy === "copied" ? "Copied" : "Copy"}
              </button>
            </div>
            <p className="result-original" title={state.link.originalUrl}>
              {state.link.originalUrl}
            </p>
            {copy === "failed" && (
              <p className="error">Could not copy. Select the URL and copy it.</p>
            )}
          </section>
        )}
      </div>

      <ResolveForm />
    </main>
  );
}
