import { createHash } from "node:crypto";
import { execFileSync } from "node:child_process";
import { mkdir, readFile, readdir, writeFile } from "node:fs/promises";
import { dirname, join, relative, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { build } from "esbuild";

const scriptDir = dirname(fileURLToPath(import.meta.url));
const projectDir = resolve(scriptDir, "..");
const repositoryDir = resolve(projectDir, "..");
const outputDir = resolve(repositoryDir, "android-app/app/src/main/assets/pi-runtime");
const bundlePath = join(outputDir, "pi-mobile.js");
const manifestPath = join(outputDir, "manifest.json");
const packageJson = JSON.parse(await readFile(join(projectDir, "package.json"), "utf8"));
const packageLock = JSON.parse(await readFile(join(projectDir, "package-lock.json"), "utf8"));
const quickJsPrelude = await readFile(join(projectDir, "src/quickjs-prelude.js"), "utf8");

assertExactToolchain();

const piVersion = packageJson.dependencies["@earendil-works/pi-agent-core"];
const lockedPiVersion = packageLock.packages["node_modules/@earendil-works/pi-agent-core"]?.version;
if (piVersion !== "0.80.6" || lockedPiVersion !== piVersion) {
  throw new Error(`Pi version must be exactly 0.80.6; package=${piVersion}, lock=${lockedPiVersion}`);
}

const revision = await resolveBuildRevision();
if (!/^[0-9a-f]{40}$/.test(revision)) {
  throw new Error(`Invalid build revision: ${revision}`);
}

await mkdir(outputDir, { recursive: true });
await build({
  entryPoints: [join(projectDir, "src/index.ts")],
  outfile: bundlePath,
  bundle: true,
  format: "iife",
  globalName: "PiMobileRuntimeBundle",
  platform: "browser",
  target: "es2020",
  minify: false,
  sourcemap: false,
  legalComments: "none",
  charset: "utf8",
  banner: {
    js: quickJsPrelude,
  },
  plugins: [
    {
      name: "narrow-pi-ai-compat",
      setup(esbuild) {
        esbuild.onResolve(
          { filter: /^@earendil-works\/pi-ai\/compat$/ },
          () => ({ path: join(projectDir, "src/pi-ai-compat.ts") }),
        );
      },
    },
  ],
  define: {
    __PI_VERSION__: JSON.stringify(piVersion),
    __BUNDLE_SCHEMA_VERSION__: JSON.stringify("1"),
    __BUILD_REVISION__: JSON.stringify(revision),
  },
});

const rawBundle = await readFile(bundlePath, "utf8");
if (/(?<![A-Za-z0-9_$])(?:0[xX][0-9a-fA-F_]+|0[bB][01_]+|0[oO][0-7_]+|[0-9][0-9_]*)n(?![A-Za-z0-9_$])/.test(
  rawBundle,
)) {
  throw new Error("Bundle contains a BigInt literal unsupported by Zipline QuickJS");
}
const bundle = await readFile(bundlePath);
const manifest = {
  schemaVersion: 1,
  runtime: "earendil-works/pi AgentHarness",
  piVersion,
  bundleFile: "pi-mobile.js",
  bundleSha256: sha256(bundle),
  sourceSha256: await sourceHash(),
  buildRevision: revision,
  buildTarget: "es2020",
  compatibilityTransforms: [
    "pi-agent-core-compat-import:narrow-browser-shim",
    "pi-tool-validation:fail-closed-json-schema-subset",
  ],
};
await writeFile(manifestPath, `${JSON.stringify(manifest, null, 2)}\n`);

console.log(`Built ${relative(repositoryDir, bundlePath)} (${bundle.length} bytes)`);
console.log(`SHA-256 ${manifest.bundleSha256}`);

function assertExactToolchain() {
  const nodeVersion = process.version.slice(1);
  const npmVersion = execFileSync("npm", ["--version"], { encoding: "utf8" }).trim();
  if (nodeVersion !== packageJson.engines.node) {
    throw new Error(`Node ${packageJson.engines.node} required, found ${nodeVersion}`);
  }
  if (npmVersion !== packageJson.engines.npm) {
    throw new Error(`npm ${packageJson.engines.npm} required, found ${npmVersion}`);
  }
}

async function resolveBuildRevision() {
  const declaredRevision = process.env.SOURCE_REVISION?.trim();
  if (declaredRevision) return declaredRevision;

  try {
    const publicSource = JSON.parse(
      await readFile(join(repositoryDir, ".public-source.json"), "utf8"),
    );
    if (typeof publicSource.sourceRevision !== "string") {
      throw new Error(".public-source.json sourceRevision must be a string");
    }
    return publicSource.sourceRevision;
  } catch (error) {
    if (error?.code !== "ENOENT") throw error;
  }

  return execFileSync("git", ["rev-parse", "HEAD"], {
    cwd: repositoryDir,
    encoding: "utf8",
  }).trim();
}

async function sourceHash() {
  const files = [
    "package.json",
    "package-lock.json",
    "tsconfig.json",
    ...(await listFiles(join(projectDir, "src"))),
    ...(await listFiles(join(projectDir, "scripts"))),
  ].sort();
  const hash = createHash("sha256");
  for (const file of files) {
    const absolute = file.startsWith("/") ? file : join(projectDir, file);
    hash.update(relative(projectDir, absolute));
    hash.update("\0");
    hash.update(await readFile(absolute));
    hash.update("\0");
  }
  return hash.digest("hex");
}

async function listFiles(directory) {
  const entries = await readdir(directory, { withFileTypes: true });
  const files = [];
  for (const entry of entries) {
    const path = join(directory, entry.name);
    if (entry.isDirectory()) files.push(...await listFiles(path));
    else if (entry.isFile()) files.push(path);
  }
  return files;
}

function sha256(value) {
  return createHash("sha256").update(value).digest("hex");
}
