import { useId, useRef, useState, type ClipboardEvent, type SubmitEvent } from "react";
import { resolveShortUrl, type ShortLink } from "./api.ts";
import { useTurnstile } from "./turnstile.ts";
import { describeUrlProblem, findUrlProblem } from "./url.ts";

type State =
  | { kind: "idle" }
  | { kind: "loading" }
  | { kind: "done"; link: ShortLink }
  | { kind: "error"; message: string };

/** 短縮 URL を貼り付けると、元の URL を表示する。 */
export function ResolveForm() {
  const [value, setValue] = useState("");
  const [state, setState] = useState<State>({ kind: "idle" });
  const inputRef = useRef<HTMLInputElement>(null);
  // 貼り付けを続けたとき、遅れて返った古い応答で新しい結果を上書きしないための連番。
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
          ? "元に戻したい短縮 URL を貼り付けてください。"
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
    // 短縮フォームと同じく、入力のたびに確かめる。空欄はエラーにしない。
    const problem = findUrlProblem(next);
    if (problem !== null && problem !== "empty") {
      latest.current++;
      setState({ kind: "error", message: describeUrlProblem(problem) });
    } else if (state.kind === "error") {
      setState({ kind: "idle" });
    }
  }

  function onPaste(_: ClipboardEvent<HTMLInputElement>) {
    // 短縮フォームと同じく、貼り付けが入力欄に反映された後の値で送る。
    setTimeout(() => {
      if (inputRef.current) void submit(inputRef.current.value);
    }, 0);
  }

  const loading = state.kind === "loading";
  const hasError = state.kind === "error";

  return (
    <section className="resolve" aria-label="短縮 URL を元に戻す">
      <form className="search" onSubmit={onSubmit} noValidate aria-busy={loading}>
        <label className="visually-hidden" htmlFor={inputId}>
          元に戻したい短縮 URL
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
          placeholder="元に戻したい短縮 URL を貼り付け"
          value={value}
          onChange={(e) => onChange(e.target.value)}
          onPaste={onPaste}
          aria-invalid={hasError}
          aria-describedby={hasError ? errorId : undefined}
        />
        <button className="search-submit" type="submit" disabled={loading}>
          {loading ? "確認中…" : "元に戻す"}
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
          <section className="result" key={state.link.code} aria-label="元の URL">
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
