#!/usr/bin/env node

import { execFileSync } from "node:child_process";
import { existsSync, readFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const repositoryDir = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const files = execFileSync("git", ["ls-files", "*.md"], {
  cwd: repositoryDir,
  encoding: "utf8",
}).trim().split("\n").filter(Boolean);

const missing = [];
for (const file of files) {
  const absoluteFile = resolve(repositoryDir, file);
  const markdown = readFileSync(absoluteFile, "utf8");
  for (const match of markdown.matchAll(/!?\[[^\]]*]\(([^)]+)\)/g)) {
    let target = match[1].trim();
    if (target.startsWith("<") && target.endsWith(">")) target = target.slice(1, -1);
    target = target.split(/\s+["']/)[0];
    const pathPart = target.split("#")[0];
    if (
      pathPart.length === 0 ||
      /^(?:https?:|mailto:|tel:|data:)/i.test(pathPart)
    ) continue;
    const decoded = decodeURIComponent(pathPart);
    if (!existsSync(resolve(dirname(absoluteFile), decoded))) {
      missing.push(`${file}: ${pathPart}`);
    }
  }
}

if (missing.length > 0) {
  console.error("ERROR: broken local Markdown links");
  for (const item of missing) console.error(item);
  process.exit(1);
}

console.log(`Verified local links in ${files.length} tracked Markdown files.`);
