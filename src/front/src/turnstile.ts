import { useCallback, useEffect, useRef } from "react";

// Turnstile (staging / prod) のウィジェット。短縮と復元の送信のたびにトークンを取り、X-Turnstile-Token で送る。
// Worker (worker/turnstile.ts) が siteverify で検証する。サイトキーはビルド時の VITE_TURNSTILE_SITE_KEY で、
// 無い環境 (develop / ローカル) では何もしない。

const siteKey: string | undefined = import.meta.env.VITE_TURNSTILE_SITE_KEY || undefined;

export const turnstileEnabled = siteKey !== undefined;

// Worker の TurnstileAction と揃える
export type TurnstileAction = "shorten" | "resolve";

type RenderOptions = {
  sitekey: string;
  action: string;
  execution: "render" | "execute";
  appearance: "always" | "execute" | "interaction-only";
  callback: (token: string) => void;
  "error-callback": () => void;
  "expired-callback": () => void;
};

type TurnstileApi = {
  render(container: HTMLElement, options: RenderOptions): string;
  execute(widgetId: string): void;
  reset(widgetId: string): void;
  remove(widgetId: string): void;
};

declare global {
  interface Window {
    turnstile?: TurnstileApi;
  }
}

let scriptLoading: Promise<TurnstileApi> | null = null;

// Cloudflare のスクリプトは Turnstile を使う環境でだけ、最初に要ったときに 1 回だけ読む
function loadTurnstile(): Promise<TurnstileApi> {
  scriptLoading ??= new Promise((resolve, reject) => {
    const script = document.createElement("script");
    script.src = "https://challenges.cloudflare.com/turnstile/v0/api.js?render=explicit";
    script.async = true;
    script.onload = () =>
      window.turnstile ? resolve(window.turnstile) : reject(new Error("turnstile not loaded"));
    script.onerror = () => {
      scriptLoading = null;
      reject(new Error("failed to load turnstile"));
    };
    document.head.append(script);
  });
  return scriptLoading;
}

/**
 * フォームごとに 1 つウィジェットを置く。`getToken` は送信のたびに新しいトークンを取る (トークンは 1 回しか使えない)。
 * 普段は見えず、Cloudflare が必要と判断したときだけ containerRef の場所にチェックを出す。
 * Turnstile を使わない環境では常に null を返す。取れなかったときは "failed"。
 */
export function useTurnstile(action: TurnstileAction) {
  const containerRef = useRef<HTMLDivElement>(null);
  const widget = useRef<{ api: TurnstileApi; id: string } | null>(null);
  // 送信中のトークン待ち。次の送信が来たら古い待ちは捨てる (古い応答は呼び出し側でも捨てている)
  const pending = useRef<((token: string | "failed") => void) | null>(null);

  useEffect(() => {
    if (!siteKey || !containerRef.current) return;
    const container = containerRef.current;
    let removed = false;

    loadTurnstile()
      .then((api) => {
        if (removed) return;
        const settle = (result: string | "failed") => {
          pending.current?.(result);
          pending.current = null;
        };
        const id = api.render(container, {
          sitekey: siteKey,
          action,
          execution: "execute",
          appearance: "interaction-only",
          callback: (token) => settle(token),
          "error-callback": () => settle("failed"),
          "expired-callback": () => settle("failed"),
        });
        widget.current = { api, id };
      })
      .catch(() => {
        pending.current?.("failed");
        pending.current = null;
      });

    return () => {
      removed = true;
      if (widget.current) widget.current.api.remove(widget.current.id);
      widget.current = null;
    };
  }, [action]);

  const getToken = useCallback(async (): Promise<string | null | "failed"> => {
    if (!siteKey) return null;
    // スクリプトの読み込みが終わる前に送られたら、読み込みを待つ
    await loadTurnstile().catch(() => undefined);
    const current = widget.current;
    if (!current) return "failed";

    pending.current?.("failed");
    return new Promise((resolve) => {
      pending.current = resolve;
      current.api.reset(current.id);
      current.api.execute(current.id);
    });
  }, []);

  return { containerRef, getToken };
}
