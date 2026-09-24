import assert from "node:assert/strict";
import { describe, test } from "node:test";
import {
  SERVER_ORIGIN,
  clientKey,
  limitTargetOf,
  rateLimited,
  serverUnavailable,
  toServerRequest,
} from "./route.ts";

describe("limitTargetOf", () => {
  test("API は api として Play に渡す", () => {
    assert.equal(limitTargetOf("/api/v1/links"), "api");
    assert.equal(limitTargetOf("/api/v1/links/resolve"), "api");
  });

  test("英数 8 文字の 1 階層は短縮 URL (redirect) として Play に渡す", () => {
    assert.equal(limitTargetOf("/Xk3pR8vN"), "redirect");
    assert.equal(limitTargetOf("/abcd1234"), "redirect");
  });

  test("それ以外は静的アセット", () => {
    assert.equal(limitTargetOf("/"), null);
    assert.equal(limitTargetOf("/index.html"), null); // 記号を含む
    assert.equal(limitTargetOf("/assets/index-abc.js"), null);
    assert.equal(limitTargetOf("/abcd123"), null); // 7 文字
    assert.equal(limitTargetOf("/abcd12345"), null); // 9 文字
    assert.equal(limitTargetOf("/abcd1234/x"), null); // 2 階層
    assert.equal(limitTargetOf("/api"), null); // /api/ で始まらない
  });
});

describe("clientKey", () => {
  test("Cloudflare が付ける接続元 IP を使い、X-Forwarded-For は見ない", () => {
    const req = new Request("https://example.com/", {
      headers: { "cf-connecting-ip": "198.51.100.7", "x-forwarded-for": "203.0.113.1" },
    });
    assert.equal(clientKey(req), "198.51.100.7");
  });
});

describe("rateLimited", () => {
  test("API のエラーと同じ形の 429 を返す", async () => {
    const res = rateLimited();
    assert.equal(res.status, 429);
    assert.equal(res.headers.get("retry-after"), "60");
    assert.deepEqual(await res.json(), { error: "rate_limited" });
  });
});

describe("toServerRequest", () => {
  // body はストリームで一度しか読めないので、テストごとに作る
  const original = () =>
    new Request("https://short-link-develop.example.workers.dev/api/v1/links?x=1", {
      method: "POST",
      headers: {
        cookie: "CF_Authorization=secret",
        "cf-access-jwt-assertion": "jwt",
        "x-forwarded-for": "203.0.113.1",
        "content-type": "application/json",
      },
      body: JSON.stringify({ url: "https://example.com/" }),
    });

  test("パスとクエリを保って Play の宛先に向ける", () => {
    assert.equal(toServerRequest(original()).url, `${SERVER_ORIGIN}/api/v1/links?x=1`);
  });

  test("メソッドと body をそのまま渡す", async () => {
    const req = toServerRequest(original());
    assert.equal(req.method, "POST");
    assert.equal(await req.text(), JSON.stringify({ url: "https://example.com/" }));
  });

  test("認証情報と利用者の X-Forwarded-For を落とし、元のホストとスキームを X-Forwarded-* で渡す", () => {
    const req = toServerRequest(original());
    assert.equal(req.headers.get("cookie"), null);
    assert.equal(req.headers.get("cf-access-jwt-assertion"), null);
    assert.equal(req.headers.get("x-forwarded-for"), null);
    assert.equal(req.headers.get("content-type"), "application/json");
    assert.equal(req.headers.get("x-forwarded-host"), "short-link-develop.example.workers.dev");
    assert.equal(req.headers.get("x-forwarded-proto"), "https");
  });

  test("リダイレクトは追わない (短縮 URL の 302 をそのまま返す)", () => {
    assert.equal(toServerRequest(original()).redirect, "manual");
  });
});

describe("serverUnavailable", () => {
  test("API のエラーと同じ形の 502 を返す", async () => {
    const res = serverUnavailable();
    assert.equal(res.status, 502);
    assert.deepEqual(await res.json(), { error: "server_unavailable" });
  });
});
