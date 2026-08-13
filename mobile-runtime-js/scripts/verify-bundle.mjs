import { createHash } from "node:crypto";
import { readFile } from "node:fs/promises";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const projectDir = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const outputDir = resolve(projectDir, "../android-app/app/src/main/assets/pi-runtime");
const bundle = await readFile(resolve(outputDir, "pi-mobile.js"));
const text = bundle.toString("utf8");
const manifest = JSON.parse(await readFile(resolve(outputDir, "manifest.json"), "utf8"));

const failures = [];
const actualHash = createHash("sha256").update(bundle).digest("hex");
if (manifest.bundleSha256 !== actualHash) {
  failures.push(`manifest hash ${manifest.bundleSha256} != bundle hash ${actualHash}`);
}
if (manifest.piVersion !== "0.80.6") {
  failures.push(`unexpected Pi version ${manifest.piVersion}`);
}
if (!/^[0-9a-f]{40}$/.test(manifest.buildRevision)) {
  failures.push("buildRevision is not a full Git SHA");
}

const forbidden = [
  ["OpenRouter secret", /sk-or-v1-[A-Za-z0-9_-]+/],
  ["generic API secret", /\bsk-[A-Za-z0-9_-]{20,}\b/],
  ["macOS absolute user path", /\/Users\/[^/"'\\\s]+/],
  ["Linux absolute home path", /\/home\/[^/"'\\\s]+/],
  ["Windows absolute user path", /[A-Za-z]:\\\\Users\\\\/],
  [
    "static Node import",
    /(?:(?<![A-Za-z0-9_$])require\s*\(\s*|from\s+|import\s+)[\"']node:/,
  ],
  ["dynamic eval", /(?<![A-Za-z0-9_$])eval\s*\(/],
  ["dynamic Function constructor", /\bnew\s+Function\s*\(/],
  ["jiti runtime loader", /\bjiti\b/i],
  ["npm runtime loader", /\bnpm\s+(?:install|exec|run)\b/i],
  ["source map reference", /sourceMappingURL=/],
  [
    "BigInt literal unsupported by Zipline QuickJS",
    /(?<![A-Za-z0-9_$])(?:0[xX][0-9a-fA-F_]+|0[bB][01_]+|0[oO][0-7_]+|[0-9][0-9_]*)n(?![A-Za-z0-9_$])/,
  ],
];
for (const [label, pattern] of forbidden) {
  if (pattern.test(text)) failures.push(label);
}

for (const required of [
  "PiMobileRuntimeBundle",
  "bootstrapJson",
  "statusJson",
  "closeJson",
  "AgentHarness",
  "0.80.6",
]) {
  if (!text.includes(required)) failures.push(`missing marker ${required}`);
}

if (failures.length > 0) {
  throw new Error(`Bundle verification failed:\n- ${failures.join("\n- ")}`);
}

console.log(`Verified Pi ${manifest.piVersion} bundle ${actualHash}`);
console.log(
  "No embedded secret, absolute user path, dynamic runtime loader, static Node import, or source map",
);
