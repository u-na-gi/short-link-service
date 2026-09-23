#!/usr/bin/env bun
/**
 * Play の dev サーバを起動して runn のシナリオを流し、終わったらサーバを止める。
 *
 * 既にサーバが上がっていれば起動は省き、停止もしない (開発中の sbt run を殺さないため)。
 */
import { mkdir } from "node:fs/promises";
import { openSync } from "node:fs";
import { dirname, join } from "node:path";

const repoRoot = join(import.meta.dir, "..");
const serverDir = join(repoRoot, "url-shortener-server");
const logPath = join(serverDir, "logs/e2e-server.log");

const baseUrl = Bun.env.E2E_BASE_URL ?? "http://localhost:9000";
const scenarios = Bun.env.E2E_SCENARIOS ?? join(repoRoot, "tests/scenarios/*.yml");
// dev モードは最初のリクエストでコンパイルが走るので、待ち時間は長めに取る
const bootTimeoutMs = Number(Bun.env.E2E_BOOT_TIMEOUT ?? 300) * 1000;

/** サーバが応答するか。起動直後はコンパイル中で応答しないので、失敗は false として扱う */
async function alive(): Promise<boolean> {
  try {
    const res = await fetch(new URL("/", baseUrl), { signal: AbortSignal.timeout(5000) });
    return res.ok;
  } catch {
    return false;
  }
}

async function tailLog(lines = 30): Promise<string> {
  const log = Bun.file(logPath);
  if (!(await log.exists())) return "";
  return (await log.text()).split("\n").slice(-lines).join("\n");
}

async function startServer(): Promise<Bun.Subprocess> {
  console.log(`starting play dev server (log: ${logPath})`);
  await mkdir(dirname(logPath), { recursive: true });
  // sbt の出力はそのままだと E2E の結果を埋めてしまうので、まとめてログへ逃がす
  const fd = openSync(logPath, "w");
  const server = Bun.spawn(["sbt", "run"], {
    cwd: serverDir,
    stdin: "ignore",
    stdout: fd,
    stderr: fd,
  });

  const deadline = Date.now() + bootTimeoutMs;
  while (!(await alive())) {
    if (server.exitCode !== null) {
      throw new Error(`server が起動前に終了しました:\n${await tailLog()}`);
    }
    if (Date.now() > deadline) {
      server.kill();
      throw new Error(
        `server が ${bootTimeoutMs / 1000}s 以内に応答しませんでした:\n${await tailLog()}`,
      );
    }
    await Bun.sleep(2000);
  }
  console.log(`server is up at ${baseUrl}`);
  return server;
}

async function stopServer(server: Bun.Subprocess): Promise<void> {
  console.log(`stopping play dev server (pid ${server.pid})`);
  server.kill();
  await server.exited;
}

/**
 * グロブは runn 自身が展開するので、シェルを挟まずそのまま渡す。
 *
 * 既定で step ごとの結果を出し (--verbose)、落ちたステップだけ HTTP のやり取りを
 * 丸ごと見せる (--debug-on-failure)。成功時も全部見たいなら `--debug` を足す。
 */
async function runScenarios(): Promise<number> {
  const args = ["run", scenarios, "--verbose", "--debug-on-failure", ...Bun.argv.slice(2)];
  console.log(`runn ${args.join(" ")}`);
  const runn = Bun.spawn(["runn", ...args], {
    cwd: repoRoot,
    env: { ...Bun.env, E2E_BASE_URL: baseUrl },
    stdin: "ignore",
    stdout: "inherit",
    stderr: "inherit",
  });
  return await runn.exited;
}

if (!Bun.which("runn")) {
  console.error("runn が見つかりません。devcontainer を Rebuild してください");
  process.exit(1);
}

let server: Bun.Subprocess | undefined;
if (await alive()) {
  console.log(`server already running at ${baseUrl}`);
} else {
  server = await startServer();
  // 中断されたときも起動したサーバは片付ける
  for (const signal of ["SIGINT", "SIGTERM"] as const) {
    process.on(signal, () => {
      server?.kill();
      process.exit(130);
    });
  }
}

try {
  process.exitCode = await runScenarios();
} finally {
  if (server) await stopServer(server);
}
