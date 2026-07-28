import { execFileSync } from "node:child_process";
import { readFile } from "node:fs/promises";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const projectDir = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const assetDir = resolve(projectDir, "../android-app/app/src/main/assets/pi-runtime");
const bundlePath = resolve(assetDir, "pi-mobile.js");
const manifestPath = resolve(assetDir, "manifest.json");

const firstBundle = await readFile(bundlePath);
const firstManifest = await readFile(manifestPath);

execFileSync(process.execPath, [resolve(projectDir, "scripts/build.mjs")], {
  cwd: projectDir,
  stdio: "pipe",
});

const secondBundle = await readFile(bundlePath);
const secondManifest = await readFile(manifestPath);

if (!firstBundle.equals(secondBundle)) {
  throw new Error("Pi mobile bundle is not byte-for-byte reproducible");
}
if (!firstManifest.equals(secondManifest)) {
  throw new Error("Pi mobile manifest is not byte-for-byte reproducible");
}

console.log("Verified byte-for-byte reproducible bundle and manifest");
