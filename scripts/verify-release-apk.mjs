#!/usr/bin/env node

import { execFileSync } from "node:child_process";
import { existsSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { readReleaseVersion } from "./lib/release-version.mjs";

const repositoryDir = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const { versionCode, versionName } = readReleaseVersion(repositoryDir);
const androidHome = process.env.ANDROID_HOME;
if (!androidHome) fail("ANDROID_HOME must point to the Android SDK");

const aapt = join(androidHome, "build-tools", "37.0.0", process.platform === "win32" ? "aapt.exe" : "aapt");
const defaultApk = join(
  repositoryDir,
  "android-app",
  "app",
  "build",
  "outputs",
  "apk",
  "release",
  "app-release-unsigned.apk",
);
const apk = resolve(process.argv[2] ?? defaultApk);
if (!existsSync(aapt)) fail(`aapt is missing: ${aapt}`);
if (!existsSync(apk)) fail(`release APK is missing: ${apk}`);

const run = (command, args) =>
  execFileSync(command, args, { encoding: "utf8", maxBuffer: 32 * 1024 * 1024 });

const badging = run(aapt, ["dump", "badging", apk]);
requireMatch(badging, /^package: name='app\.momoding' /m, "application ID");
requireMatch(badging, new RegExp(` versionCode='${versionCode}' `, "m"), "version code");
requireMatch(
  badging,
  new RegExp(` versionName='${escapeRegex(versionName)}' `, "m"),
  "version name",
);
requireMatch(badging, /^sdkVersion:'30'$/m, "minimum SDK");
requireMatch(badging, /^targetSdkVersion:'37'$/m, "target SDK");
requireMatch(badging, /^application-label:'Momoding'$/m, "application label");

const permissionDump = run(aapt, ["dump", "permissions", apk]);
const actualPermissions = [
  ...permissionDump.matchAll(/^uses-permission: name='([^']+)'/gm),
].map((match) => match[1]).sort();
const expectedPermissions = [
  "android.permission.ACCESS_COARSE_LOCATION",
  "android.permission.ACCESS_FINE_LOCATION",
  "android.permission.ACCESS_NETWORK_STATE",
  "android.permission.FOREGROUND_SERVICE",
  "android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION",
  "android.permission.INTERNET",
  "android.permission.MANAGE_EXTERNAL_STORAGE",
  "android.permission.POST_NOTIFICATIONS",
  "android.permission.REQUEST_INSTALL_PACKAGES",
  "android.permission.READ_CALENDAR",
  "android.permission.READ_CONTACTS",
  "android.permission.READ_EXTERNAL_STORAGE",
  "android.permission.READ_MEDIA_IMAGES",
  "android.permission.READ_MEDIA_VISUAL_USER_SELECTED",
  "android.permission.WRITE_CALENDAR",
  "android.permission.WRITE_CONTACTS",
  "moe.shizuku.manager.permission.API_V23",
].sort();
if (JSON.stringify(actualPermissions) !== JSON.stringify(expectedPermissions)) {
  fail(
    `release APK permission set changed\nExpected: ${expectedPermissions.join(", ")}\n` +
      `Actual: ${actualPermissions.join(", ")}`,
  );
}

const manifestTree = run(aapt, ["dump", "xmltree", apk, "AndroidManifest.xml"]);
const components = parseComponents(manifestTree);
const exported = components.filter((component) => component.exported);
const expectedExported = new Map([
  ["activity:app.momoding.app.MainActivity", null],
  ["activity:app.momoding.feature.share.ShareReceiverActivity", null],
  [
    "service:app.momoding.core.accessibility.MomodingAccessibilityService",
    "android.permission.BIND_ACCESSIBILITY_SERVICE",
  ],
  [
    "provider:rikka.shizuku.ShizukuProvider",
    "android.permission.INTERACT_ACROSS_USERS_FULL",
  ],
  ["receiver:androidx.profileinstaller.ProfileInstallReceiver", "android.permission.DUMP"],
]);
if (exported.length !== expectedExported.size) {
  fail(`release APK exported component count changed: ${formatComponents(exported)}`);
}
for (const component of exported) {
  const key = `${component.type}:${component.name}`;
  if (!expectedExported.has(key)) fail(`unexpected exported component: ${key}`);
  if (component.permission !== expectedExported.get(key)) {
    fail(`exported component permission changed: ${key}`);
  }
}
const entries = run("jar", ["tf", apk]).trim().split("\n");
for (const required of [
  "assets/pi-runtime/manifest.json",
  "assets/pi-runtime/pi-mobile.js",
]) {
  if (!entries.includes(required)) fail(`required release APK entry is missing: ${required}`);
}
const forbiddenEntry = entries.find((entry) =>
  /(?:^|\/)(?:.*(?:proot|alpine).*)$/i.test(entry) ||
  /\.(?:pem|key|jks|keystore|p12|pfx)$/i.test(entry),
);
if (forbiddenEntry) fail(`forbidden release APK entry found: ${forbiddenEntry}`);

console.log(
  `Verified release APK ${apk}: ${actualPermissions.length} permissions, ` +
    `${exported.length} reviewed exported components, no debug Linux-runtime artifacts.`,
);

function parseComponents(tree) {
  const components = [];
  let current = null;
  const flush = () => {
    if (current !== null) components.push(current);
    current = null;
  };

  for (const line of tree.split("\n")) {
    const element = /^ {6}E: (activity|activity-alias|provider|receiver|service)\b/.exec(line);
    if (element) {
      flush();
      current = {
        type: element[1],
        name: null,
        exported: false,
        permission: null,
      };
      continue;
    }
    if (/^ {6}E: /.test(line)) flush();
    if (current === null) continue;
    const name = /A: android:name[^=]*="([^"]+)" \(Raw:/.exec(line);
    if (name && current.name === null) current.name = name[1];
    if (/A: android:exported[^=]*=\(type 0x12\)0xffffffff/.test(line)) {
      current.exported = true;
    }
    const permission = /A: android:permission[^=]*="([^"]+)" \(Raw:/.exec(line);
    if (permission) current.permission = permission[1];
  }
  flush();
  return components.filter(({ name }) => name !== null);
}

function formatComponents(components) {
  return components.map(({ type, name }) => `${type}:${name}`).join(", ");
}

function requireMatch(value, pattern, label) {
  if (!pattern.test(value)) fail(`release APK ${label} changed`);
}

function escapeRegex(value) {
  return value.replace(/[.*+?^${}()|[\]\\]/gu, "\\$&");
}

function fail(message) {
  console.error(`ERROR: ${message}`);
  process.exit(1);
}
