#!/usr/bin/env node

import { execFileSync } from "node:child_process";
import { createHash } from "node:crypto";
import { existsSync, readFileSync } from "node:fs";
import { basename, dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const repositoryDir = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const androidHome = process.env.ANDROID_HOME;
if (!androidHome) fail("ANDROID_HOME must point to the Android SDK");

const buildToolsDir = join(androidHome, "build-tools", "37.0.0");
const apksigner = join(
  buildToolsDir,
  process.platform === "win32" ? "apksigner.bat" : "apksigner",
);
const apk = resolve(process.argv[2] ?? join(repositoryDir, "dist", "momoding-0.1.0-alpha.1.apk"));
const checksumFile = resolve(process.argv[3] ?? `${apk}.sha256`);
const certificateFile = join(repositoryDir, "android-app", "release-signing-certificate.sha256");

for (const [label, path] of [
  ["apksigner", apksigner],
  ["signed APK", apk],
  ["checksum", checksumFile],
  ["release certificate digest", certificateFile],
]) {
  if (!existsSync(path)) fail(`${label} is missing: ${path}`);
}

const run = (command, args) =>
  execFileSync(command, args, { encoding: "utf8", maxBuffer: 32 * 1024 * 1024 });

run(process.execPath, [join(repositoryDir, "scripts", "verify-release-apk.mjs"), apk]);
const signerOutput = run(apksigner, ["verify", "--verbose", "--print-certs", apk]);
requireMatch(signerOutput, /Verified using v3 scheme \(APK Signature Scheme v3\): true/, "v3 signature");

const signerDigest = normalizeDigest(
  /(?:Signer #1|V3\.0 Signer): certificate SHA-256 digest: ([0-9a-f:]+)/i.exec(signerOutput)?.[1] ?? "",
);
const expectedDigest = normalizeDigest(readFileSync(certificateFile, "utf8"));
if (!signerDigest) fail("apksigner did not report a signer certificate SHA-256 digest");
if (signerDigest !== expectedDigest) {
  fail(`signer certificate changed: expected ${expectedDigest}, got ${signerDigest}`);
}

const checksumLine = readFileSync(checksumFile, "utf8").trim();
const checksumMatch = /^([0-9a-f]{64})\s+\*?([^\r\n]+)$/i.exec(checksumLine);
if (!checksumMatch) fail(`invalid SHA-256 checksum file: ${checksumFile}`);
if (checksumMatch[2] !== basename(apk)) {
  fail(`checksum names ${checksumMatch[2]} instead of ${basename(apk)}`);
}
const actualChecksum = createHash("sha256").update(readFileSync(apk)).digest("hex");
if (actualChecksum !== checksumMatch[1].toLowerCase()) {
  fail(`APK checksum changed: expected ${checksumMatch[1]}, got ${actualChecksum}`);
}

console.log(
  `Verified signed release APK: certificate ${expectedDigest}, SHA-256 ${actualChecksum}.`,
);

function normalizeDigest(value) {
  return value.replaceAll(":", "").trim().toLowerCase();
}

function requireMatch(value, pattern, label) {
  if (!pattern.test(value)) fail(`signed release APK is missing its ${label}`);
}

function fail(message) {
  console.error(`ERROR: ${message}`);
  process.exit(1);
}
