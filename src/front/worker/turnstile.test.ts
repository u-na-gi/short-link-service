import assert from "node:assert/strict";
import { describe, test } from "node:test";
import { turnstileActionOf, turnstileFailed, verifyTurnstile } from "./turnstile.ts";

describe("turnstileActionOf", () => {
  test("短縮と復元だけに Turnstile をかける", () => {
    assert.equal(turnstileActionOf("POST", "/api/v1/links"), "shorten");
    assert.equal(turnstileActionOf("GET", "/api/v1/links/resolve"), "resolve");
  });

  test("ヘルスチェックや短縮 URL のリダイレクトにはかけない", () => {
    assert.equal(turnstileActionOf("GET", "/api/v1/health"), null);
    assert.equal(turnstileActionOf("GET", "/abcd1234"), null);
    assert.equal(turnstileActionOf("GET", "/api/v1/links"), null);
  });
});

describe("verifyTurnstile", () => {
  const base = {
    secret: "secret",
    token: "token",
    remoteIp: "198.51.100.7",
    action: "shorten" as const,
    hostname: "s.u-na-gi.com",
  };

  // siteverify の応答を差し替え、送った内容を記録する
  function stub(body: unknown, status = 200) {
    const sent: FormData[] = [];
    const fetchFn = (async (_url: unknown, init?: RequestInit) => {
      sent.push(init?.body as FormData);
      return new Response(JSON.stringify(body), { status });
    }) as typeof fetch;
    return { fetchFn, sent };
  }

  test("本物で、action とホストが合っていれば通す", async () => {
    const { fetchFn, sent } = stub({ success: true, action: "shorten", hostname: "s.u-na-gi.com" });
    assert.equal(await verifyTurnstile({ ...base, fetchFn }), true);
    assert.equal(sent[0]?.get("secret"), "secret");
    assert.equal(sent[0]?.get("response"), "token");
    assert.equal(sent[0]?.get("remoteip"), "198.51.100.7");
  });

  test("siteverify が success: false なら通さない", async () => {
    const { fetchFn } = stub({ success: false });
    assert.equal(await verifyTurnstile({ ...base, fetchFn }), false);
  });

  test("別の action (復元のフォーム) で取ったトークンは通さない", async () => {
    const { fetchFn } = stub({ success: true, action: "resolve", hostname: "s.u-na-gi.com" });
    assert.equal(await verifyTurnstile({ ...base, fetchFn }), false);
  });

  test("別のホストで取ったトークンは通さない", async () => {
    const { fetchFn } = stub({ success: true, action: "shorten", hostname: "s-stg.u-na-gi.com" });
    assert.equal(await verifyTurnstile({ ...base, fetchFn }), false);
  });

  test("siteverify に繋がらない・エラーを返すときは通さない", async () => {
    const failing = (async () => {
      throw new Error("network");
    }) as unknown as typeof fetch;
    assert.equal(await verifyTurnstile({ ...base, fetchFn: failing }), false);
    assert.equal(await verifyTurnstile({ ...base, fetchFn: stub({}, 500).fetchFn }), false);
  });
});

describe("turnstileFailed", () => {
  test("API のエラーと同じ形の 403 を返す", async () => {
    const res = turnstileFailed();
    assert.equal(res.status, 403);
    assert.deepEqual(await res.json(), { error: "turnstile_failed" });
  });
});
