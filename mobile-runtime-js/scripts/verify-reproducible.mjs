import { execFileSync } from "node:child_process";
import { mkdtemp, readFile, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const projectDir = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const assetDir = resolve(projectDir, "../android-app/app/src/main/assets/pi-runtime");
const trackedBundle = await readFile(join(assetDir, "pi-mobile.js"));
const trackedManifest = await readFile(join(assetDir, "manifest.json"));
const manifest = JSON.parse(trackedManifest.toString("utf8"));

if (!/^[0-9a-f]{40}$/.test(manifest.buildRevision)) {
  throw new Error("Tracked Pi manifest has an invalid buildRevision");
}

const temporaryRoot = await mkdtemp(join(tmpdir(), "momoding-pi-repro-"));
const firstOutput = join(temporaryRoot, "first");
const secondOutput = join(temporaryRoot, "second");

try {
  buildInto(firstOutput);
  buildInto(secondOutput);

  const firstBundle = await readFile(join(firstOutput, "pi-mobile.js"));
  const firstManifest = await readFile(join(firstOutput, "manifest.json"));
  const secondBundle = await readFile(join(secondOutput, "pi-mobile.js"));
  const secondManifest = await readFile(join(secondOutput, "manifest.json"));

  if (!firstBundle.equals(secondBundle) || !firstManifest.equals(secondManifest)) {
    throw new Error("Pi mobile bundle is not byte-for-byte reproducible");
  }
  if (!firstBundle.equals(trackedBundle) || !firstManifest.equals(trackedManifest)) {
    throw new Error(
      "Tracked Pi runtime is stale; run npm run build and commit both generated assets",
    );
  }

  console.log("Verified tracked Pi runtime from current sources without modifying the checkout");
} finally {
  await rm(temporaryRoot, { recursive: true, force: true });
}

function buildInto(outputDirectory) {
  execFileSync(process.execPath, [resolve(projectDir, "scripts/build.mjs")], {
    cwd: projectDir,
    env: {
      ...process.env,
      PI_MOBILE_OUTPUT_DIR: outputDirectory,
      SOURCE_REVISION: manifest.buildRevision,
    },
    stdio: "pipe",
  });
}
