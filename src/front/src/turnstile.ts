import { useCallback, useEffect, useRef } from "react";

// Turnstile widget (staging / prod). Gets a token on every shorten and resolve submit and sends it as X-Turnstile-Token.
// The Worker (worker/turnstile.ts) verifies it with siteverify. The site key is VITE_TURNSTILE_SITE_KEY at build time;
// environments without it (develop / local) do nothing.

const siteKey: string | undefined = import.meta.env.VITE_TURNSTILE_SITE_KEY || undefined;

export const turnstileEnabled = siteKey !== undefined;

// Keep in sync with the Worker's TurnstileAction
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

// Load the Cloudflare script only in environments that use Turnstile, once, the first time it is needed
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
 * One widget per form. `getToken` gets a new token on every submit (a token can be used only once).
 * Normally invisible; shows a check at containerRef only when Cloudflare decides it is needed.
 * Always returns null in environments without Turnstile. Returns "failed" when a token could not be obtained.
 */
export function useTurnstile(action: TurnstileAction) {
  const containerRef = useRef<HTMLDivElement>(null);
  const widget = useRef<{ api: TurnstileApi; id: string } | null>(null);
  // Pending token wait for a submit. A new submit discards the old wait (callers also discard stale responses)
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
    // If submitted before the script finishes loading, wait for it
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
