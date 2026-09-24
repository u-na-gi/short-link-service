// wrangler.jsonc の env ごとの値が、Terraform の output と合っているか確かめる。
// VPC Service の ID とホスト名は wrangler.jsonc に手で書いているので、Tunnel やドメインを作り直したときに
// ずれうる。ずれたまま deploy すると、別の Tunnel に繋ぎに行ったり、公開 URL と違うホストで配ったりする。
//
//   terraform -chdir=infra/terraform/envs/<env> output -json | bun run scripts/check-wrangler-config.ts <env>
//
// make deploy-front が deploy の前に流す。

import { text } from "node:stream/consumers";
import { unstable_readConfig } from "wrangler";

type Outputs = Record<string, { value: unknown } | undefined>;

const env = process.argv[2];
if (!env) {
  console.error(
    "usage: check-wrangler-config.ts <env>  (Terraform の output -json を標準入力で渡す)",
  );
  process.exit(2);
}

const outputs = JSON.parse(await text(process.stdin)) as Outputs;
const output = (key: string) => outputs[key]?.value;

const config = unstable_readConfig({ config: "wrangler.jsonc", env });
const problems: string[] = [];

const serviceId = config.vpc_services.find(
  (s: { binding: string; service_id: string }) => s.binding === "SERVER",
)?.service_id;
if (serviceId !== output("vpc_service_id")) {
  problems.push(
    `vpc_services の SERVER の service_id (${serviceId}) が output vpc_service_id (${output("vpc_service_id")}) と違う`,
  );
}

const publicBaseUrl = output("public_base_url");
if (typeof publicBaseUrl !== "string") {
  console.error(
    `Terraform の output に public_base_url がありません (env: ${env})。terraform apply 済みか確かめてください`,
  );
  process.exit(1);
}
const host = new URL(publicBaseUrl).host;
const patterns = (config.routes ?? []).map((r: string | { pattern: string }) =>
  typeof r === "string" ? r : r.pattern,
);
if (patterns.length !== 1 || patterns[0] !== host) {
  problems.push(
    `routes (${patterns.join(", ")}) が output public_base_url のホスト (${host}) と違う`,
  );
}

// Turnstile のウィジェットがある環境だけ Worker で検証する
const turnstileOn = config.vars.TURNSTILE === "on";
const hasWidget = Boolean(output("turnstile_site_key"));
if (turnstileOn !== hasWidget) {
  problems.push(
    `vars.TURNSTILE (${config.vars.TURNSTILE ?? "未設定"}) と Turnstile のウィジェットの有無 (${hasWidget ? "あり" : "なし"}) が合わない`,
  );
}

if (problems.length > 0) {
  console.error(`wrangler.jsonc の env.${env} が Terraform の output と合っていません:`);
  for (const p of problems) console.error(`  - ${p}`);
  process.exit(1);
}
console.log(`wrangler.jsonc の env.${env} は Terraform の output と合っています`);
