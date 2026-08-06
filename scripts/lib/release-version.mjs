import { readFileSync } from "node:fs";
import { join } from "node:path";

export function readReleaseVersion(repositoryDir) {
  const file = join(repositoryDir, "version.properties");
  const values = Object.fromEntries(
    readFileSync(file, "utf8")
      .split(/\r?\n/u)
      .map((line) => line.trim())
      .filter((line) => line && !line.startsWith("#"))
      .map((line) => {
        const separator = line.indexOf("=");
        if (separator <= 0) throw new Error(`Invalid release version line: ${line}`);
        return [line.slice(0, separator).trim(), line.slice(separator + 1).trim()];
      }),
  );
  const versionCode = Number.parseInt(values.VERSION_CODE ?? "", 10);
  const versionName = values.VERSION_NAME ?? "";
  if (!Number.isSafeInteger(versionCode) || versionCode <= 0) {
    throw new Error("VERSION_CODE must be a positive integer");
  }
  if (!/^\d+\.\d+\.\d+(?:-[0-9A-Za-z.-]+)?$/u.test(versionName)) {
    throw new Error("VERSION_NAME must be a semantic version");
  }
  return {
    versionCode,
    versionName,
    apkName: `momoding-${versionName}.apk`,
  };
}
