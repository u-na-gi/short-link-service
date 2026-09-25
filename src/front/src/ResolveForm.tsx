import { useId, useRef, useState, type ClipboardEvent, type SubmitEvent } from "react";
import { resolveShortUrl, type ShortLink } from "./api.ts";
import { useTurnstile } from "./turnstile.ts";
import { describeUrlProblem, findUrlProblem } from "./url.ts";

type State =
  | { kind: "idle" }
  | { kind: "loading" }
  | { kind: "done"; link: ShortLink }
  | { kind: "error"; message: string };

/** Paste a short URL to show the original URL. */
export function ResolveForm() {
  const [value, setValue] = useState("");
  const [state, setState] = useState<State>({ kind: "idle" });
  const inputRef = useRef<HTMLInputElement>(null);
  // Sequence number so a stale response arriving late does not overwrite a newer result when pasting repeatedly.
  const latest = useRef(0);
  const inputId = useId();
  const errorId = useId();
  const turnstile = useTurnstile("resolve");

  async function submit(raw: string) {
    const shortUrl = raw.trim();
    const problem = findUrlProblem(shortUrl);
    if (problem !== null) {
      const message =
        problem === "empty"
          ? "Paste the short URL you want to resolve."
          : describeUrlProblem(problem);
      setState({ kind: "error", message });
      return;
    }

    const id = ++latest.current;
    setState({ kind: "loading" });

    const result = await resolveShortUrl(shortUrl, await turnstile.getToken());
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
    // Like the shorten form, check on every input. An empty field is not an error.
    const problem = findUrlProblem(next);
    if (problem !== null && problem !== "empty") {
      latest.current++;
      setState({ kind: "error", message: describeUrlProblem(problem) });
    } else if (state.kind === "error") {
      setState({ kind: "idle" });
    }
  }

  function onPaste(_: ClipboardEvent<HTMLInputElement>) {
    // Like the shorten form, send the value after the paste is applied to the input.
    setTimeout(() => {
      if (inputRef.current) void submit(inputRef.current.value);
    }, 0);
  }

  const loading = state.kind === "loading";
  const hasError = state.kind === "error";

  return (
    <section className="resolve" aria-label="Resolve a short URL">
      <form className="search" onSubmit={onSubmit} noValidate aria-busy={loading}>
        <label className="visually-hidden" htmlFor={inputId}>
          Short URL to resolve
        </label>
        <input
          ref={inputRef}
          id={inputId}
          className="search-input"
          type="url"
          inputMode="url"
          autoComplete="off"
          autoCapitalize="off"
          spellCheck={false}
          placeholder="Paste a short URL to resolve"
          value={value}
          onChange={(e) => onChange(e.target.value)}
          onPaste={onPaste}
          aria-invalid={hasError}
          aria-describedby={hasError ? errorId : undefined}
        />
        <button className="search-submit" type="submit" disabled={loading}>
          {loading ? "Checking…" : "Resolve"}
        </button>
      </form>
      <div ref={turnstile.containerRef} className="turnstile" />

      <div className="outcome" aria-live="polite">
        {state.kind === "error" && (
          <p id={errorId} className="error" role="alert">
            {state.message}
          </p>
        )}
        {state.kind === "done" && (
          <section className="result" key={state.link.code} aria-label="Original URL">
            <a
              className="resolved-url"
              href={state.link.originalUrl}
              target="_blank"
              rel="noreferrer"
            >
              {state.link.originalUrl}
            </a>
            <p className="result-original" title={state.link.shortUrl}>
              {state.link.shortUrl}
            </p>
          </section>
        )}
      </div>
    </section>
  );
}
