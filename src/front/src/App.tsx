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
  // 貼り付けを続けたとき、遅れて返った古い応答で新しい結果を上書きしないための連番。
  const latest = useRef(0);
  const lastSubmitted = useRef("");
  const errorId = useId();
  const turnstile = useTurnstile("shorten");

  async function submit(raw: string) {
    const url = raw.trim();
    const problem = findUrlProblem(url);
    if (problem !== null) {
      const message =
        problem === "empty" ? "短くしたい URL を貼り付けてください。" : describeUrlProblem(problem);
      setState({ kind: "error", message });
      return;
    }
    // 同じ URL を貼り直しただけなら、表示中の結果がそのまま答えなので送らない。
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
    // 送る前から問題が分かるよう、入力のたびに確かめる。空欄は打ち始める前と同じ扱いでエラーにしない。
    const problem = findUrlProblem(next);
    if (problem !== null && problem !== "empty") {
      // 送信中なら、遅れて返る応答で今のエラーを上書きしないよう捨てる。
      latest.current++;
      setState({ kind: "error", message: describeUrlProblem(problem) });
    } else if (state.kind === "error") {
      setState({ kind: "idle" });
    }
  }

  function onPaste(_: ClipboardEvent<HTMLInputElement>) {
    // 貼り付けが入力欄に反映された後の値で送る。途中に貼った場合も、見えている URL をそのまま短縮する。
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

  // 「コピーしました」は確認できれば十分なので、少ししたら元のラベルに戻す。
  useEffect(() => {
    if (copy !== "copied") return;
    const timer = setTimeout(() => setCopy("idle"), 2000);
    return () => clearTimeout(timer);
  }, [copy]);

  const loading = state.kind === "loading";
  const hasError = state.kind === "error";

  return (
    <main className="page">
      <form className="search" onSubmit={onSubmit} noValidate aria-busy={loading}>
        <label className="visually-hidden" htmlFor="url">
          短くしたい URL
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
          placeholder="短くしたい URL を貼り付け"
          value={value}
          onChange={(e) => onChange(e.target.value)}
          onPaste={onPaste}
          aria-invalid={hasError}
          aria-describedby={hasError ? errorId : undefined}
        />
        <button className="search-submit" type="submit" disabled={loading}>
          {loading ? "短縮中…" : "短縮する"}
        </button>
      </form>
      {/* Turnstile が必要と判断したときだけ、ここにチェックが出る */}
      <div ref={turnstile.containerRef} className="turnstile" />

      <div className="outcome" aria-live="polite">
        {state.kind === "error" && (
          <p id={errorId} className="error" role="alert">
            {state.message}
          </p>
        )}
        {state.kind === "done" && (
          <section className="result" key={state.link.code} aria-label="短縮した URL">
            <div className="result-row">
              <a className="result-url" href={state.link.shortUrl} target="_blank" rel="noreferrer">
                {state.link.shortUrl.replace(/^https?:\/\//, "")}
              </a>
              <button
                className="copy"
                type="button"
                onClick={() => void onCopy(state.link.shortUrl)}
              >
                {copy === "copied" ? "コピーしました" : "コピー"}
              </button>
            </div>
            <p className="result-original" title={state.link.originalUrl}>
              {state.link.originalUrl}
            </p>
            {copy === "failed" && (
              <p className="error">コピーできませんでした。URL を選択してコピーしてください。</p>
            )}
          </section>
        )}
      </div>

      <ResolveForm />
    </main>
  );
}
