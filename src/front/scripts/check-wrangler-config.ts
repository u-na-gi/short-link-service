// Checks that the per-env values in wrangler.jsonc match the Terraform outputs.
// The VPC Service ID and host name are written by hand in wrangler.jsonc, so they can drift when the Tunnel
// or domain is recreated. Deploying with a drift connects to a different Tunnel or serves on a host other than the public URL.
//
//   terraform -chdir=infra/terraform/envs/<env> output -json | bun run scripts/check-wrangler-config.ts <env>
//
// make deploy-front runs this before deploying.

import { text } from "node:stream/consumers";
import { unstable_readConfig } from "wrangler";

type Outputs = Record<string, { value: unknown } | undefined>;

const env = process.argv[2];
if (!env) {
  console.error("usage: check-wrangler-config.ts <env>  (pass Terraform output -json on stdin)");
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
    `service_id of SERVER in vpc_services (${serviceId}) differs from output vpc_service_id (${output("vpc_service_id")})`,
  );
}

const publicBaseUrl = output("public_base_url");
if (typeof publicBaseUrl !== "string") {
  console.error(
    `public_base_url is missing from the Terraform outputs (env: ${env}). Check that terraform apply has been run`,
  );
  process.exit(1);
}
const host = new URL(publicBaseUrl).host;
const patterns = (config.routes ?? []).map((r: string | { pattern: string }) =>
  typeof r === "string" ? r : r.pattern,
);
if (patterns.length !== 1 || patterns[0] !== host) {
  problems.push(
    `routes (${patterns.join(", ")}) differ from the host of output public_base_url (${host})`,
  );
}

// The Worker verifies Turnstile only in environments that have a widget
const turnstileOn = config.vars.TURNSTILE === "on";
const hasWidget = Boolean(output("turnstile_site_key"));
if (turnstileOn !== hasWidget) {
  problems.push(
    `vars.TURNSTILE (${config.vars.TURNSTILE ?? "unset"}) does not match whether a Turnstile widget exists (${hasWidget ? "yes" : "no"})`,
  );
}

if (problems.length > 0) {
  console.error(`env.${env} in wrangler.jsonc does not match the Terraform outputs:`);
  for (const p of problems) console.error(`  - ${p}`);
  process.exit(1);
}
console.log(`env.${env} in wrangler.jsonc matches the Terraform outputs`);
