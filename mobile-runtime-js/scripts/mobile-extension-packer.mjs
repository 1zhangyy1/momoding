import { createHash, randomUUID } from "node:crypto";
import { constants } from "node:fs";
import { lstat, mkdir, mkdtemp, open, opendir, readFile, realpath, rename, rm, writeFile } from "node:fs/promises";
import { basename, dirname, extname, join, resolve, sep } from "node:path";
import { fileURLToPath } from "node:url";

import { build } from "esbuild";
import ts from "typescript";

const PROFILE = "momoding-pi-mobile-v1";
const MANIFEST = "momoding-extension.json";
const SOURCE_LOCK = "momoding-source-lock.json";
const ARTIFACT_MARKER = ".momoding-mobile-artifact.json";
const ARTIFACT_MARKER_CONTENT = `${JSON.stringify({ owner: "momoding-mobile-extension-packer", schemaVersion: 1 })}\n`;
const MAX_FILES = 256;
const MAX_SOURCE_NODES = 512;
const MAX_TOTAL_BYTES = 8 * 1024 * 1024;
const MAX_JS_BYTES = 256 * 1024;
const MAX_JS_TOTAL_BYTES = 1024 * 1024;
const MAX_RESOURCE_BYTES = 64 * 1024;
const EXTENSION_ID = /^[a-z][a-z0-9]*(?:[._-][a-z0-9]+)+$/;
const TOOL_NAME = /^[a-z][a-z0-9_]{0,63}$/;
const VERSION = /^[0-9]+\.[0-9]+\.[0-9]+(?:-[0-9A-Za-z.-]+)?$/;
const SAFE_GIT_REPOSITORY = /^https:\/\/[a-z0-9]+(?:[.-][a-z0-9]+)*(?::[1-9][0-9]{0,4})?(?:\/[A-Za-z0-9._~!$&'()*+,;=:@-]+)*\/?$/;
const CAPABILITIES = new Set([
  "saf_folders", "photo_library", "calendar", "contacts", "location", "notifications",
  "accessibility_control", "screen_capture", "all_files", "shizuku_shell_uid",
]);
const HOST_TOOL_CAPABILITIES = new Map([
  ["device_capabilities_get", null],
  ["device_media_list", "photo_library"],
  ["device_calendar", "calendar"],
  ["device_contacts", "contacts"],
  ["device_location", "location"],
  ["device_clipboard", null],
  ["device_notification", "notifications"],
  ["device_ui_inspect", "accessibility_control"],
  ["device_ui_action", "accessibility_control"],
  ["device_packages_list", "shizuku_shell_uid"],
  ["device_package_inspect", "shizuku_shell_uid"],
]);
const METHODS = new Set(["GET", "HEAD", "POST", "PUT", "PATCH", "DELETE"]);
const PACKER_VERSIONS = Object.freeze({
  node: "22.22.3",
  typescript: "5.9.3",
  esbuild: "0.27.2",
});

export class MobileExtensionPackError extends Error {
  constructor(code, compatibility, detail) {
    super(code);
    this.name = "MobileExtensionPackError";
    this.code = code;
    this.compatibility = compatibility;
    this.detail = detail;
  }
}

export async function packMobileExtension({
  sourceDir,
  outputDir,
  source = { kind: "local" },
  _testBeforeCommit,
}) {
  requireNodeVersion();
  const root = await realpath(resolve(sourceDir));
  const output = await requireSafeArtifactOutput(root, resolve(outputDir));
  const files = await collectSourceFiles(root);
  const packageJson = parseJsonFile(files, "package.json");
  const mobile = parseJsonFile(files, "momoding-mobile.json");
  const profile = validateProfile(packageJson, mobile);
  const sourceAnalysis = analyzeSources(root, files, profile);
  const bundle = await bundleSources(
    root,
    profile.source.entrypoint,
    sourceAnalysis.transformed,
    sourceAnalysis.moduleResolutions,
  );
  if (Buffer.byteLength(bundle) > MAX_JS_BYTES) {
    fail("EXTENSION_PACKAGE_JAVASCRIPT_MODULE_TOO_LARGE", "rejected", "Bundled entrypoint exceeds 256 KiB.");
  }

  const sourceLock = buildSourceLock(packageJson, files, source);
  const payload = new Map([
    ["dist/index.js", Buffer.from(bundle)],
    [SOURCE_LOCK, Buffer.from(`${JSON.stringify(sourceLock, null, 2)}\n`)],
    [ARTIFACT_MARKER, Buffer.from(ARTIFACT_MARKER_CONTENT)],
  ]);
  for (const [path, content] of files) {
    if (/^README(?:\.[A-Za-z0-9._-]+)?$/i.test(path) || path.startsWith("resources/")) {
      payload.set(path, content);
    }
  }
  requireArtifactBounds(payload, false);

  const manifestWithoutDigest = {
    schemaVersion: 2,
    id: profile.package.id,
    name: profile.package.name,
    version: profile.package.version,
    description: profile.package.description,
    runtime: "pi-register-tool-v1",
    entrypoint: "dist/index.js",
    tools: profile.tools.map((tool) => ({ type: "pi-register-tool", ...tool })),
    hostTools: profile.hostTools,
    requiredCapabilities: [...profile.capabilities.required].sort(),
    optionalCapabilities: [...profile.capabilities.optional].sort(),
    httpPolicy: {
      origins: [...profile.https.origins].sort(),
      methods: [...profile.https.methods].sort(),
      credentialSlots: [...profile.credentials].sort((left, right) => compareUnicode(left.slot, right.slot)),
    },
  };
  const packageDigest = extensionPackageDigest(manifestWithoutDigest, payload);
  const manifest = { ...manifestWithoutDigest, packageDigest };
  payload.set(MANIFEST, Buffer.from(`${JSON.stringify(manifest, null, 2)}\n`));
  requireArtifactBounds(payload, true);

  if (_testBeforeCommit !== undefined) await _testBeforeCommit(output.path);
  await replaceArtifactDirectory(output, payload);
  return { manifest, sourceLock, files: [...payload.keys()].sort() };
}

export async function diagnoseMobileExtensionSource(sourceDir) {
  try {
    const root = await realpath(resolve(sourceDir));
    const files = await collectSourceFiles(root);
    const packageJson = parseJsonFile(files, "package.json");
    const mobile = parseJsonFile(files, "momoding-mobile.json");
    const profile = validateProfile(packageJson, mobile);
    analyzeSources(root, files, profile);
    return {
      compatibility: "build_required",
      code: "EXTENSION_PACKAGE_MOBILE_BUILD_REQUIRED",
      packageId: profile.package.id,
      message: "Compatible Pi source. Build a deterministic Momoding mobile artifact before installing.",
    };
  } catch (error) {
    if (error instanceof MobileExtensionPackError) {
      return {
        compatibility: error.compatibility,
        code: error.code,
        packageId: null,
        message: error.detail,
      };
    }
    return {
      compatibility: "rejected",
      code: "EXTENSION_PACKAGE_MOBILE_SOURCE_INVALID",
      packageId: null,
      message: "The source package could not be read safely.",
    };
  }
}

async function collectSourceFiles(root) {
  const files = new Map();
  let totalBytes = 0;
  let nodeCount = 1;
  async function visit(directory, prefix, depth) {
    if (depth > 12) fail("EXTENSION_PACKAGE_DEPTH_EXCEEDED", "rejected", "The source tree is too deep.");
    requireContainedRealPath(root, await realpath(directory));
    const entries = [];
    const stream = await opendir(directory);
    for await (const entry of stream) {
      nodeCount += 1;
      if (nodeCount > MAX_SOURCE_NODES) {
        fail("EXTENSION_PACKAGE_NODE_LIMIT_EXCEEDED", "rejected", "The source tree has too many files or directories.");
      }
      entries.push(entry);
    }
    for (const entry of entries.sort((left, right) => compareUnicode(left.name, right.name))) {
      if ([".git", "node_modules", "dist"].includes(entry.name)) continue;
      const path = prefix ? `${prefix}/${entry.name}` : entry.name;
      requireRelativePath(path);
      const absolute = join(directory, entry.name);
      const info = await lstat(absolute);
      if (info.isSymbolicLink()) fail("EXTENSION_PACKAGE_SYMLINK_UNSUPPORTED", "rejected", "Symlinks are not allowed.");
      requireContainedRealPath(root, await realpath(absolute));
      if (info.isDirectory()) {
        await visit(absolute, path, depth + 1);
      } else if (info.isFile()) {
        if (files.size >= MAX_FILES) fail("EXTENSION_PACKAGE_FILE_LIMIT_EXCEEDED", "rejected", "Too many source files.");
        if (info.size > 4 * 1024 * 1024) fail("EXTENSION_PACKAGE_FILE_TOO_LARGE", "rejected", "A source file is too large.");
        const handle = await open(absolute, constants.O_RDONLY | constants.O_NOFOLLOW);
        let content;
        try {
          const opened = await handle.stat();
          if (!opened.isFile() || opened.dev !== info.dev || opened.ino !== info.ino) {
            fail("EXTENSION_PACKAGE_SOURCE_CHANGED", "rejected", "The source tree changed while it was being packed.");
          }
          content = await handle.readFile();
        } finally {
          await handle.close();
        }
        totalBytes += content.length;
        if (totalBytes > MAX_TOTAL_BYTES) fail("EXTENSION_PACKAGE_TOO_LARGE", "rejected", "The source package is too large.");
        files.set(path, content);
      } else fail("EXTENSION_PACKAGE_SOURCE_TYPE_UNSUPPORTED", "rejected", "Only regular files and directories are supported.");
    }
  }
  await visit(root, "", 1);
  return files;
}

async function requireSafeArtifactOutput(root, requestedOutput) {
  if (dirname(requestedOutput) === requestedOutput) {
    fail("EXTENSION_PACKAGE_MOBILE_OUTPUT_INVALID", "rejected", "Output must be a dedicated artifact directory.");
  }
  requireOutsideSource(root, requestedOutput);
  await mkdir(dirname(requestedOutput), { recursive: true });
  const parent = await realpath(dirname(requestedOutput));
  const output = join(parent, basename(requestedOutput));
  requireOutsideSource(root, output);
  let info;
  try {
    info = await lstat(output);
  } catch (error) {
    if (error?.code === "ENOENT") return { path: output, expected: null };
    throw error;
  }
  if (!info.isDirectory() || info.isSymbolicLink() || await realpath(output) !== output) {
    fail("EXTENSION_PACKAGE_MOBILE_OUTPUT_INVALID", "rejected", "Existing output must be a real artifact directory.");
  }
  let marker;
  try {
    marker = await readFile(join(output, ARTIFACT_MARKER), "utf8");
  } catch {
    fail("EXTENSION_PACKAGE_MOBILE_OUTPUT_NOT_OWNED", "rejected", "Refusing to replace a directory not created by this packer.");
  }
  if (marker !== ARTIFACT_MARKER_CONTENT) {
    fail("EXTENSION_PACKAGE_MOBILE_OUTPUT_NOT_OWNED", "rejected", "Refusing to replace a directory not created by this packer.");
  }
  return { path: output, expected: { dev: info.dev, ino: info.ino } };
}

async function replaceArtifactDirectory(target, payload) {
  const { path: output, expected } = target;
  const parent = dirname(output);
  const temporary = await mkdtemp(join(parent, ".momoding-pack-"));
  const backup = join(parent, `.momoding-backup-${randomUUID()}`);
  let movedExisting = false;
  try {
    for (const [path, content] of [...payload].sort(([left], [right]) => compareUnicode(left, right))) {
      const target = join(temporary, path);
      await mkdir(dirname(target), { recursive: true });
      await writeFile(target, content, { flag: "wx" });
    }
    try {
      await rename(output, backup);
      movedExisting = true;
    } catch (error) {
      if (error?.code !== "ENOENT") throw error;
    }
    if (!movedExisting && expected !== null) {
      fail("EXTENSION_PACKAGE_MOBILE_OUTPUT_CHANGED", "rejected", "The owned output changed before commit.");
    }
    if (movedExisting) {
      try {
        await requireMovedArtifactIdentity(backup, expected);
      } catch (error) {
        await restoreMovedArtifact(backup, output);
        movedExisting = false;
        throw error;
      }
    }
    try {
      await rename(temporary, output);
    } catch (error) {
      if (movedExisting) await rename(backup, output);
      throw error;
    }
    if (movedExisting) {
      await requireMovedArtifactIdentity(backup, expected);
      await rm(backup, { recursive: true, force: false });
    }
  } finally {
    await rm(temporary, { recursive: true, force: true });
  }
}

async function requireMovedArtifactIdentity(path, expected) {
  if (expected === null) {
    fail("EXTENSION_PACKAGE_MOBILE_OUTPUT_CHANGED", "rejected", "An unowned output appeared before commit.");
  }
  const info = await lstat(path);
  if (!info.isDirectory() || info.isSymbolicLink() || info.dev !== expected.dev || info.ino !== expected.ino ||
      await realpath(path) !== path || await readFile(join(path, ARTIFACT_MARKER), "utf8") !== ARTIFACT_MARKER_CONTENT
  ) {
    fail("EXTENSION_PACKAGE_MOBILE_OUTPUT_CHANGED", "rejected", "The owned output changed before commit.");
  }
}

async function restoreMovedArtifact(backup, output) {
  try {
    await rename(backup, output);
  } catch {
    // Never delete an unverified backup. A concurrent writer owns recovery from this rare conflict.
  }
}

function requireOutsideSource(root, output) {
  if (output === root || output.startsWith(`${root}${sep}`) || root.startsWith(`${output}${sep}`)) {
    fail("EXTENSION_PACKAGE_MOBILE_OUTPUT_INVALID", "rejected", "Output must be outside and not above the source tree.");
  }
}

function requireContainedRealPath(root, actual) {
  if (actual !== root && !actual.startsWith(`${root}${sep}`)) {
    fail("EXTENSION_PACKAGE_PATH_INVALID", "rejected", "A source path escaped the package root.");
  }
}

function parseJsonFile(files, path) {
  const content = files.get(path);
  if (!content) fail("EXTENSION_PACKAGE_MOBILE_PROFILE_MISSING", "host_shim_required", `${path} is required.`);
  try {
    return JSON.parse(new TextDecoder("utf-8", { fatal: true }).decode(content));
  } catch {
    fail("EXTENSION_PACKAGE_MOBILE_PROFILE_INVALID", "rejected", `${path} is not strict UTF-8 JSON.`);
  }
}

function validateProfile(packageJson, mobile) {
  object(packageJson, "package.json");
  object(mobile, "momoding-mobile.json");
  exactKeys(mobile, ["profileVersion", "package", "source", "tools", "hostTools", "capabilities", "https", "credentials"]);
  if (mobile.profileVersion !== 1) fail("EXTENSION_PACKAGE_MOBILE_PROFILE_UNSUPPORTED", "unsupported", "Only profileVersion 1 is supported.");
  exactKeys(mobile.package, ["id", "name", "version", "description"]);
  string(mobile.package.id, 3, 76);
  if (!EXTENSION_ID.test(mobile.package.id)) fail("EXTENSION_PACKAGE_ID_INVALID", "rejected", "Package id is invalid.");
  string(mobile.package.name, 1, 80);
  string(mobile.package.version, 1, 40);
  if (!VERSION.test(mobile.package.version) || mobile.package.version !== packageJson.version) {
    fail("EXTENSION_PACKAGE_VERSION_INVALID", "rejected", "Profile and package versions must match.");
  }
  string(mobile.package.description, 1, 512);
  exactKeys(mobile.source, ["entrypoint"]);
  string(mobile.source.entrypoint, 1, 256);
  requireRelativePath(mobile.source.entrypoint.replace(/^\.\//, ""));
  const extensions = packageJson.pi?.extensions;
  if (!Array.isArray(extensions) || extensions.length !== 1 || extensions[0] !== mobile.source.entrypoint) {
    fail("EXTENSION_PACKAGE_MOBILE_PI_ENTRYPOINT_INVALID", "rejected", "Pi and mobile entrypoints must match exactly.");
  }
  if (!Array.isArray(mobile.tools) || mobile.tools.length < 1 || mobile.tools.length > 16) {
    fail("EXTENSION_PACKAGE_TOOL_LIMIT_INVALID", "rejected", "Profile must declare 1 to 16 Tools.");
  }
  mobile.tools.forEach(validateProfileTool);
  unique(mobile.tools.map((tool) => tool.name), "EXTENSION_PACKAGE_TOOL_NAME_DUPLICATED");
  exactKeys(mobile.capabilities, ["required", "optional"]);
  stringArray(mobile.capabilities.required, 10);
  stringArray(mobile.capabilities.optional, 10);
  unique(mobile.capabilities.required, "EXTENSION_PACKAGE_CAPABILITY_DUPLICATED");
  unique(mobile.capabilities.optional, "EXTENSION_PACKAGE_CAPABILITY_DUPLICATED");
  for (const capability of [...mobile.capabilities.required, ...mobile.capabilities.optional]) {
    if (!CAPABILITIES.has(capability)) fail("EXTENSION_PACKAGE_CAPABILITY_UNKNOWN", "rejected", `Unknown capability: ${capability}`);
  }
  if (mobile.capabilities.required.some((value) => mobile.capabilities.optional.includes(value))) {
    fail("EXTENSION_PACKAGE_CAPABILITY_CONFLICT", "rejected", "Required and optional capabilities overlap.");
  }
  if (!Array.isArray(mobile.hostTools) || mobile.hostTools.length > 16) fail("EXTENSION_PACKAGE_HOST_TOOL_LIMIT_INVALID", "rejected", "Too many Host Tools.");
  for (const host of mobile.hostTools) {
    exactKeys(host, ["name", "targetTool", "capability"]);
    string(host.name, 1, 64);
    if (!TOOL_NAME.test(host.name)) fail("EXTENSION_PACKAGE_HOST_TOOL_INVALID", "rejected", "Host Tool alias is invalid.");
    string(host.targetTool, 1, 80);
    if (!HOST_TOOL_CAPABILITIES.has(host.targetTool)) fail("EXTENSION_PACKAGE_ALIAS_TARGET_UNSUPPORTED", "host_shim_required", `Host Tool ${host.targetTool} is not available.`);
    const expected = HOST_TOOL_CAPABILITIES.get(host.targetTool);
    if (host.capability !== expected || (expected !== null && !mobile.capabilities.required.includes(expected))) {
      fail("EXTENSION_PACKAGE_MOBILE_CAPABILITY_UNDECLARED", "rejected", `Host Tool ${host.name} does not match its required capability.`);
    }
  }
  unique(mobile.hostTools.map((host) => host.name), "EXTENSION_PACKAGE_HOST_TOOL_DUPLICATED");
  exactKeys(mobile.https, ["origins", "methods"]);
  stringArray(mobile.https.origins, 8);
  stringArray(mobile.https.methods, 6);
  unique(mobile.https.origins, "EXTENSION_PACKAGE_NETWORK_ORIGIN_DUPLICATED");
  unique(mobile.https.methods, "EXTENSION_PACKAGE_HTTP_POLICY_INVALID");
  for (const origin of mobile.https.origins) requireHttpsOrigin(origin);
  for (const method of mobile.https.methods) if (!METHODS.has(method)) fail("EXTENSION_PACKAGE_HTTP_POLICY_INVALID", "rejected", `Unsupported HTTP method: ${method}`);
  if (!Array.isArray(mobile.credentials) || mobile.credentials.length > 8) fail("EXTENSION_PACKAGE_HTTP_POLICY_INVALID", "rejected", "Too many credential slots.");
  for (const credential of mobile.credentials) {
    exactKeys(credential, ["slot", "origin", "placement"]);
    string(credential.slot, 1, 64);
    if (!/^[a-z][a-z0-9._-]{0,63}$/.test(credential.slot) || credential.placement !== "authorization_bearer" || !mobile.https.origins.includes(credential.origin)) {
      fail("EXTENSION_PACKAGE_HTTP_POLICY_INVALID", "rejected", "Credential binding is invalid.");
    }
  }
  unique(mobile.credentials.map((item) => item.slot), "EXTENSION_PACKAGE_HTTP_POLICY_INVALID");
  return mobile;
}

function validateProfileTool(tool) {
  exactKeys(tool, ["name", "label", "description", "parameters", "promptSnippet", "promptGuidelines", "executionMode"]);
  string(tool.name, 1, 64);
  if (!TOOL_NAME.test(tool.name)) fail("EXTENSION_PACKAGE_TOOL_NAME_INVALID", "rejected", "Tool name is invalid.");
  string(tool.label, 1, 80);
  string(tool.description, 1, 512);
  if (tool.promptSnippet !== null) string(tool.promptSnippet, 1, 512);
  stringArray(tool.promptGuidelines, 16, 512);
  if (tool.executionMode !== "sequential") fail("EXTENSION_PACKAGE_MOBILE_PI_API_UNSUPPORTED", "unsupported", "Only sequential execution is supported.");
  validateJsonSchema(tool.parameters);
}

function analyzeSources(root, files, profile) {
  const entry = profile.source.entrypoint.replace(/^\.\//, "");
  if (!files.has(entry)) fail("EXTENSION_PACKAGE_ENTRYPOINT_MISSING", "rejected", "Source entrypoint is missing.");
  const sourceFiles = [...files].filter(([path]) => [".ts", ".tsx", ".js", ".mjs"].includes(extname(path)));
  const sourcePaths = new Set(sourceFiles.map(([path]) => resolve(root, path)));
  const transformed = new Map();
  const moduleResolutions = new Map();
  const registered = [];
  for (const [path, bytes] of sourceFiles) {
    let source;
    try {
      source = new TextDecoder("utf-8", { fatal: true }).decode(bytes);
    } catch {
      fail("EXTENSION_PACKAGE_JAVASCRIPT_UTF8_INVALID", "rejected", `${path} is not UTF-8.`);
    }
    const sourceFile = ts.createSourceFile(path, source, ts.ScriptTarget.ES2022, true, path.endsWith(".tsx") ? ts.ScriptKind.TSX : ts.ScriptKind.TS);
    if (sourceFile.parseDiagnostics.length) fail("EXTENSION_PACKAGE_MOBILE_SOURCE_INVALID", "rejected", `${path} does not parse.`);
    const factoryParameter = path === entry ? requireStaticDefaultFactory(sourceFile) : null;
    validateImportsAndApis(sourceFile, factoryParameter, root, path, sourcePaths, moduleResolutions);
    const schemaCalls = new Set();
    if (factoryParameter !== null) collectRegisteredTools(sourceFile, factoryParameter, registered, schemaCalls);
    validateBuildTimeTypeUsage(sourceFile, schemaCalls);
    transformed.set(resolve(root, path), printTransformed(sourceFile, schemaCalls));
  }
  if (registered.length !== profile.tools.length) fail("EXTENSION_PACKAGE_MOBILE_DECLARATION_MISMATCH", "rejected", "Registered Tool count does not match the profile.");
  const expected = [...profile.tools].sort((a, b) => compareUnicode(a.name, b.name));
  const actual = registered.sort((a, b) => compareUnicode(a.name, b.name));
  if (canonicalJson(expected) !== canonicalJson(actual)) fail("EXTENSION_PACKAGE_MOBILE_DECLARATION_MISMATCH", "rejected", "Registered Tool metadata does not match the profile.");
  if (!transformed.has(resolve(root, entry))) {
    fail("EXTENSION_PACKAGE_ENTRYPOINT_MISSING", "rejected", "Source entrypoint must be a JavaScript or TypeScript module.");
  }
  return { transformed, moduleResolutions };
}

function validateImportsAndApis(sourceFile, factoryParameter, root, sourcePath, sourcePaths, moduleResolutions) {
  function visit(node) {
    if (ts.isImportEqualsDeclaration(node)) {
      fail("EXTENSION_PACKAGE_MOBILE_NODE_API_UNSUPPORTED", "unsupported", "Import-equals declarations are not supported.");
    }
    if (ts.isImportDeclaration(node) || ts.isExportDeclaration(node) && node.moduleSpecifier !== undefined) {
      if (!ts.isStringLiteral(node.moduleSpecifier)) {
        fail("EXTENSION_PACKAGE_MOBILE_SOURCE_INVALID", "rejected", "Module specifiers must be static strings.");
      }
      const specifier = node.moduleSpecifier.text;
      if (specifier.startsWith("node:") || ["fs", "path", "child_process", "os", "net", "tls", "http", "https"].includes(specifier)) {
        fail("EXTENSION_PACKAGE_MOBILE_NODE_API_UNSUPPORTED", "unsupported", `Node module ${specifier} is not supported.`);
      }
      if (specifier.startsWith(".")) {
        const resolved = resolveRelativeSourceModule(root, sourcePath, specifier, sourcePaths);
        moduleResolutions.set(moduleResolutionKey(resolve(root, sourcePath), specifier), resolved);
      }
      else if (specifier === "@momoding/sdk") {
        // Kept external: the isolated Worker provides this virtual module.
      }
      else if (specifier === "@earendil-works/pi-ai") {
        if (!ts.isImportDeclaration(node)) fail("EXTENSION_PACKAGE_MOBILE_PI_API_UNSUPPORTED", "unsupported", "Pi helper re-exports are not supported.");
        const names = node.importClause?.namedBindings?.elements?.map((item) => item.name.text) ?? [];
        if (names.length !== 1 || names[0] !== "Type") fail("EXTENSION_PACKAGE_MOBILE_PI_API_UNSUPPORTED", "unsupported", "Only the Pi Type helper is supported at build time.");
      } else if (specifier === "@earendil-works/pi-coding-agent") {
        if (!ts.isImportDeclaration(node)) fail("EXTENSION_PACKAGE_MOBILE_PI_API_UNSUPPORTED", "unsupported", "Pi coding-agent re-exports are not supported.");
        if (!node.importClause?.isTypeOnly) fail("EXTENSION_PACKAGE_MOBILE_PI_API_UNSUPPORTED", "unsupported", "Pi coding-agent imports must be type-only.");
      } else fail("EXTENSION_PACKAGE_MOBILE_NODE_API_UNSUPPORTED", "unsupported", `Runtime dependency ${specifier} is not supported.`);
    }
    if (ts.isCallExpression(node)) {
      if (node.expression.kind === ts.SyntaxKind.ImportKeyword) fail("EXTENSION_PACKAGE_MOBILE_NODE_API_UNSUPPORTED", "unsupported", "Dynamic import is not supported.");
      if (factoryParameter !== null && ts.isPropertyAccessExpression(node.expression) && ts.isIdentifier(node.expression.expression) && node.expression.expression.text === factoryParameter) {
        const api = node.expression.name.text;
        if (api !== "registerTool") fail("EXTENSION_PACKAGE_MOBILE_PI_API_UNSUPPORTED", "host_shim_required", `Pi API ${api} needs a Host shim.`);
      }
    }
    if (isUnsupportedRuntimeReference(node)) {
      fail("EXTENSION_PACKAGE_MOBILE_NODE_API_UNSUPPORTED", "unsupported", "A desktop JavaScript global is not available on Android.");
    }
    ts.forEachChild(node, visit);
  }
  visit(sourceFile);
}

function resolveRelativeSourceModule(root, sourcePath, specifier, sourcePaths) {
  if (!(specifier.startsWith("./") || specifier.startsWith("../")) ||
      specifier.includes("\\") || specifier.includes("\0") || specifier.includes("?") || specifier.includes("#")) {
    fail("EXTENSION_PACKAGE_PATH_INVALID", "rejected", "Relative import path is invalid.");
  }
  const base = resolve(root, dirname(sourcePath), specifier);
  if (base !== root && !base.startsWith(`${root}${sep}`)) {
    fail("EXTENSION_PACKAGE_PATH_INVALID", "rejected", "A relative import escaped the collected source tree.");
  }
  const candidates = extname(base)
    ? [base]
    : [base, `${base}.ts`, `${base}.tsx`, `${base}.js`, `${base}.mjs`,
      join(base, "index.ts"), join(base, "index.tsx"), join(base, "index.js"), join(base, "index.mjs")];
  const matches = candidates.filter((candidate, index) =>
    candidates.indexOf(candidate) === index && sourcePaths.has(candidate));
  if (matches.length !== 1) {
    fail(
      "EXTENSION_PACKAGE_MOBILE_SOURCE_INVALID",
      "rejected",
      matches.length === 0
        ? "A relative import does not name a collected JavaScript or TypeScript module."
        : "A relative import is ambiguous within the collected source modules.",
    );
  }
  return matches[0];
}

function moduleResolutionKey(importer, specifier) {
  return `${importer}\0${specifier}`;
}

function isUnsupportedRuntimeReference(node) {
  const unsupported = new Set([
    "process", "Buffer", "require", "__dirname", "__filename", "WebSocket", "EventSource",
    "XMLHttpRequest",
  ]);
  if (ts.isPropertyAccessExpression(node) && ts.isIdentifier(node.expression) &&
      node.expression.text === "globalThis" && unsupported.has(node.name.text)) return true;
  if (ts.isElementAccessExpression(node) && ts.isIdentifier(node.expression) &&
      node.expression.text === "globalThis" && ts.isStringLiteral(node.argumentExpression) &&
      unsupported.has(node.argumentExpression.text)) return true;
  if (!ts.isIdentifier(node) || !unsupported.has(node.text)) return false;
  const parent = node.parent;
  if (isDeclarationOrNonValueName(node, parent) ||
      ts.isPropertyAccessExpression(parent) && parent.name === node ||
      ts.isPropertyAssignment(parent) && parent.name === node ||
      ts.isMethodDeclaration(parent) && parent.name === node ||
      ts.isPropertyDeclaration(parent) && parent.name === node ||
      ts.isImportSpecifier(parent) || ts.isImportClause(parent) || ts.isNamespaceImport(parent) ||
      ts.isTypeReferenceNode(parent) || ts.isTypeAliasDeclaration(parent) ||
      ts.isInterfaceDeclaration(parent)) return false;
  return true;
}

function isDeclarationOrNonValueName(node, parent) {
  return ((ts.isVariableDeclaration(parent) || ts.isParameter(parent) || ts.isBindingElement(parent) ||
      ts.isFunctionDeclaration(parent) || ts.isFunctionExpression(parent) ||
      ts.isClassDeclaration(parent) || ts.isClassExpression(parent) ||
      ts.isInterfaceDeclaration(parent) || ts.isTypeAliasDeclaration(parent) ||
      ts.isTypeParameterDeclaration(parent) || ts.isEnumDeclaration(parent) ||
      ts.isEnumMember(parent) || ts.isModuleDeclaration(parent) ||
      ts.isPropertySignature(parent) || ts.isMethodSignature(parent) ||
      ts.isLabeledStatement(parent)) && parent.name === node) ||
    ((ts.isBreakStatement(parent) || ts.isContinueStatement(parent)) && parent.label === node);
}

function collectRegisteredTools(sourceFile, factoryParameter, output, schemaCalls) {
  function visit(node) {
    if (ts.isCallExpression(node) && ts.isPropertyAccessExpression(node.expression) &&
      ts.isIdentifier(node.expression.expression) && node.expression.expression.text === factoryParameter &&
      node.expression.name.text === "registerTool") {
      const objectNode = node.arguments[0];
      if (!objectNode || !ts.isObjectLiteralExpression(objectNode)) fail("EXTENSION_PACKAGE_MOBILE_PI_API_UNSUPPORTED", "host_shim_required", "registerTool must receive a static object literal.");
      const allowed = new Set(["name", "label", "description", "parameters", "promptSnippet", "promptGuidelines", "executionMode", "execute"]);
      const values = new Map();
      for (const property of objectNode.properties) {
        if (ts.isSpreadAssignment(property) || !property.name) fail("EXTENSION_PACKAGE_MOBILE_PI_API_UNSUPPORTED", "host_shim_required", "Spread/computed Tool declarations are not supported.");
        const name = propertyName(property.name);
        if (!allowed.has(name) || values.has(name)) fail("EXTENSION_PACKAGE_MOBILE_PI_API_UNSUPPORTED", "unsupported", `Unsupported Tool field: ${name}`);
        values.set(name, property);
      }
      for (const name of ["name", "label", "description", "parameters", "execute"]) if (!values.has(name)) fail("EXTENSION_PACKAGE_MOBILE_DECLARATION_MISMATCH", "rejected", `Tool field ${name} is required.`);
      const execute = values.get("execute");
      if (!(ts.isMethodDeclaration(execute) || (ts.isPropertyAssignment(execute) && (ts.isFunctionExpression(execute.initializer) || ts.isArrowFunction(execute.initializer))))) {
        fail("EXTENSION_PACKAGE_MOBILE_PI_API_UNSUPPORTED", "host_shim_required", "Tool execute must be a local function.");
      }
      output.push({
        name: staticString(initializer(values.get("name"))),
        label: staticString(initializer(values.get("label"))),
        description: staticString(initializer(values.get("description"))),
        parameters: validatedRootSchema(
          evaluateTypeBox(initializer(values.get("parameters")), schemaCalls).schema,
        ),
        promptSnippet: values.has("promptSnippet") ? staticNullableString(initializer(values.get("promptSnippet"))) : null,
        promptGuidelines: values.has("promptGuidelines") ? staticStringArray(initializer(values.get("promptGuidelines"))) : [],
        executionMode: values.has("executionMode") ? staticString(initializer(values.get("executionMode"))) : "sequential",
      });
    }
    ts.forEachChild(node, visit);
  }
  visit(sourceFile);
}

function validateBuildTimeTypeUsage(sourceFile, schemaCalls) {
  function visit(node) {
    if (ts.isIdentifier(node) && node.text === "Type") {
      const parent = node.parent;
      const imported = ts.isImportSpecifier(parent) && parent.name === node &&
        parent.propertyName === undefined &&
        parent.parent?.parent?.parent?.moduleSpecifier?.text === "@earendil-works/pi-ai";
      const schemaReference = ts.isPropertyAccessExpression(parent) && parent.expression === node &&
        ts.isCallExpression(parent.parent) && schemaCalls.has(parent.parent);
      if (!imported && !schemaReference) {
        fail("EXTENSION_PACKAGE_MOBILE_PI_API_UNSUPPORTED", "unsupported", "Pi Type is build-time only and may appear only in Tool parameters.");
      }
    }
    ts.forEachChild(node, visit);
  }
  visit(sourceFile);
}

function requireStaticDefaultFactory(sourceFile) {
  for (const statement of sourceFile.statements) {
    if (ts.isFunctionDeclaration(statement) && statement.modifiers?.some((item) => item.kind === ts.SyntaxKind.DefaultKeyword)) {
      const parameter = statement.parameters[0]?.name;
      if (statement.parameters.length !== 1 || !parameter || !ts.isIdentifier(parameter)) {
        fail("EXTENSION_PACKAGE_MOBILE_PI_API_UNSUPPORTED", "host_shim_required", "Default factory must take one static Pi parameter.");
      }
      return parameter.text;
    }
    if (ts.isExportAssignment(statement) && (ts.isArrowFunction(statement.expression) || ts.isFunctionExpression(statement.expression))) {
      const parameter = statement.expression.parameters[0]?.name;
      if (statement.expression.parameters.length !== 1 || !parameter || !ts.isIdentifier(parameter)) {
        fail("EXTENSION_PACKAGE_MOBILE_PI_API_UNSUPPORTED", "host_shim_required", "Default factory must take one static Pi parameter.");
      }
      return parameter.text;
    }
  }
  fail("EXTENSION_PACKAGE_MOBILE_PI_API_UNSUPPORTED", "host_shim_required", "Entrypoint must export one static default factory.");
}

function printTransformed(sourceFile, schemaCalls) {
  const transformer = (context) => {
    const visit = (node) => {
      if (ts.isImportDeclaration(node) && ["@earendil-works/pi-ai", "@earendil-works/pi-coding-agent"].includes(node.moduleSpecifier.text)) return undefined;
      if (isTypeBoxCall(node)) {
        if (!schemaCalls.has(node)) {
          fail("EXTENSION_PACKAGE_MOBILE_PI_API_UNSUPPORTED", "unsupported", "Pi Type is build-time only and may appear only in Tool parameters.");
        }
        return jsonExpression(evaluateTypeBox(node).schema);
      }
      return ts.visitEachChild(node, visit, context);
    };
    return (node) => ts.visitNode(node, visit);
  };
  const result = ts.transform(sourceFile, [transformer]);
  try {
    return ts.createPrinter({ newLine: ts.NewLineKind.LineFeed, removeComments: true }).printFile(result.transformed[0]);
  } finally {
    result.dispose();
  }
}

async function bundleSources(root, entrypoint, transformed, moduleResolutions) {
  let result;
  try {
    result = await build({
      absWorkingDir: root,
      entryPoints: [entrypoint.replace(/^\.\//, "")],
      bundle: true,
      write: false,
      format: "esm",
      platform: "neutral",
      target: "es2020",
      charset: "utf8",
      legalComments: "none",
      sourcemap: false,
      minify: false,
      treeShaking: true,
      tsconfigRaw: { compilerOptions: { useDefineForClassFields: true } },
      plugins: [{
        name: "momoding-mobile-static-profile",
        setup(plugin) {
          plugin.onResolve({ filter: /.*/ }, ({ path, importer, kind }) => {
            if (path === "@momoding/sdk") return { path, external: true };
            if (kind === "entry-point") {
              const resolved = resolve(root, path);
              if (!transformed.has(resolved)) {
                fail("EXTENSION_PACKAGE_MOBILE_SOURCE_INVALID", "rejected", "The entrypoint escaped the analyzed source set.");
              }
              return { path: resolved, namespace: "file" };
            }
            const resolved = moduleResolutions.get(moduleResolutionKey(resolve(importer), path));
            if (resolved === undefined || !transformed.has(resolved)) {
              fail("EXTENSION_PACKAGE_MOBILE_SOURCE_INVALID", "rejected", "A bundled module escaped the analyzed source set.");
            }
            return { path: resolved, namespace: "file" };
          });
          plugin.onLoad({ filter: /.*/, namespace: "file" }, async ({ path }) => {
            const contents = transformed.get(path);
            if (contents === undefined) fail("EXTENSION_PACKAGE_MOBILE_SOURCE_INVALID", "rejected", "A bundled module escaped the analyzed source set.");
            return { contents, loader: extname(path).includes("ts") ? "ts" : "js" };
          });
        },
      }],
    });
  } catch (error) {
    if (error instanceof MobileExtensionPackError) throw error;
    fail("EXTENSION_PACKAGE_MOBILE_BUILD_FAILED", "rejected", "The deterministic bundle could not be produced.");
  }
  if (result.outputFiles.length !== 1) fail("EXTENSION_PACKAGE_MOBILE_BUILD_FAILED", "rejected", "The bundle output is ambiguous.");
  return `${result.outputFiles[0].text.trimEnd()}\n`;
}

function evaluateTypeBox(node, schemaCalls) {
  if (!isTypeBoxCall(node)) fail("EXTENSION_PACKAGE_MOBILE_PI_API_UNSUPPORTED", "host_shim_required", "Parameters must use the supported static Type constructors.");
  schemaCalls?.add(node);
  const name = node.expression.name.text;
  if (name === "Optional") {
    if (node.arguments.length !== 1) fail("EXTENSION_PACKAGE_MOBILE_PI_API_UNSUPPORTED", "host_shim_required", "Type.Optional shape is invalid.");
    return { ...evaluateTypeBox(node.arguments[0], schemaCalls), optional: true };
  }
  const optionsIndex = ["Object", "Array"].includes(name) ? 1 : name === "Literal" || name === "Union" ? -1 : 0;
  const options = optionsIndex >= 0 && node.arguments[optionsIndex] ? staticJson(node.arguments[optionsIndex]) : {};
  let schema;
  switch (name) {
    case "Object": {
      const propertiesNode = node.arguments[0];
      if (!propertiesNode || !ts.isObjectLiteralExpression(propertiesNode)) fail("EXTENSION_PACKAGE_MOBILE_PI_API_UNSUPPORTED", "host_shim_required", "Type.Object properties must be static.");
      const properties = {};
      const required = [];
      for (const property of propertiesNode.properties) {
        if (!ts.isPropertyAssignment(property)) fail("EXTENSION_PACKAGE_MOBILE_PI_API_UNSUPPORTED", "host_shim_required", "Schema properties must be static.");
        const key = propertyName(property.name);
        const value = evaluateTypeBox(property.initializer, schemaCalls);
        properties[key] = value.schema;
        if (!value.optional) required.push(key);
      }
      schema = { ...options, type: "object", properties };
      if (required.length) schema.required = required;
      break;
    }
    case "String": schema = { ...options, type: "string" }; break;
    case "Number": schema = { ...options, type: "number" }; break;
    case "Integer": schema = { ...options, type: "integer" }; break;
    case "Boolean": schema = { ...options, type: "boolean" }; break;
    case "Literal": schema = { const: staticJson(node.arguments[0]) }; break;
    case "Union": {
      const array = node.arguments[0];
      if (!array || !ts.isArrayLiteralExpression(array)) fail("EXTENSION_PACKAGE_MOBILE_PI_API_UNSUPPORTED", "host_shim_required", "Type.Union must be static.");
      schema = { anyOf: array.elements.map((item) => evaluateTypeBox(item, schemaCalls).schema) };
      break;
    }
    case "Array": schema = { ...options, type: "array", items: evaluateTypeBox(node.arguments[0], schemaCalls).schema }; break;
    default: fail("EXTENSION_PACKAGE_MOBILE_PI_API_UNSUPPORTED", "host_shim_required", `Type.${name} is not in Mobile Profile v1.`);
  }
  return { schema, optional: false };
}

function validatedRootSchema(schema) {
  validateJsonSchema(schema);
  return schema;
}

function validateJsonSchema(schema) {
  object(schema, "Tool parameters");
  if (schema.type !== "object" || schema.additionalProperties !== false || typeof schema.properties !== "object" || Array.isArray(schema.properties)) {
    fail("EXTENSION_PACKAGE_TOOL_SCHEMA_INVALID", "rejected", "Tool parameters must be a strict object schema.");
  }
  if (Buffer.byteLength(JSON.stringify(schema)) > 16 * 1024) fail("EXTENSION_PACKAGE_TOOL_SCHEMA_INVALID", "rejected", "Tool schema is too large.");
  validateSchemaNode(schema, 1);
}

function validateSchemaNode(schema, depth) {
  object(schema, "Schema node");
  if (depth > 8) fail("EXTENSION_PACKAGE_TOOL_SCHEMA_INVALID", "rejected", "Tool schema is too deep.");
  if (Object.hasOwn(schema, "const")) {
    exactSchemaKeys(schema, ["const"]);
    if (!["string", "number", "boolean"].includes(typeof schema.const) ||
        typeof schema.const === "number" && !Number.isFinite(schema.const)) {
      fail("EXTENSION_PACKAGE_TOOL_SCHEMA_INVALID", "rejected", "Schema literal must be primitive.");
    }
    return;
  }
  if (Object.hasOwn(schema, "anyOf")) {
    exactSchemaKeys(schema, ["anyOf"]);
    if (!Array.isArray(schema.anyOf) || schema.anyOf.length < 1 || schema.anyOf.length > 16) {
      fail("EXTENSION_PACKAGE_TOOL_SCHEMA_INVALID", "rejected", "Schema union is invalid.");
    }
    schema.anyOf.forEach((child) => validateSchemaNode(child, depth + 1));
    return;
  }
  if (schema.type === "object") {
    allowedSchemaKeys(schema, ["type", "properties", "required", "additionalProperties", "description"]);
    if (schema.additionalProperties !== false || !schema.properties || Array.isArray(schema.properties) || Object.keys(schema.properties).length > 16) {
      fail("EXTENSION_PACKAGE_TOOL_SCHEMA_INVALID", "rejected", "Object schema is invalid.");
    }
    for (const [name, child] of Object.entries(schema.properties)) {
      if (!/^[a-z][a-zA-Z0-9_]{0,63}$/.test(name)) fail("EXTENSION_PACKAGE_TOOL_SCHEMA_INVALID", "rejected", "Schema property name is invalid.");
      validateSchemaNode(child, depth + 1);
    }
    const required = schema.required ?? [];
    if (!Array.isArray(required) || new Set(required).size !== required.length || required.some((name) => typeof name !== "string" || !Object.hasOwn(schema.properties, name))) {
      fail("EXTENSION_PACKAGE_TOOL_SCHEMA_INVALID", "rejected", "Schema required list is invalid.");
    }
  } else if (schema.type === "array") {
    allowedSchemaKeys(schema, ["type", "items", "description", "minItems", "maxItems"]);
    validateSchemaNode(schema.items, depth + 1);
    boundedIntegerOption(schema.minItems, 0, 64);
    boundedIntegerOption(schema.maxItems, 0, 64);
    if (schema.minItems !== undefined && schema.maxItems !== undefined && schema.minItems > schema.maxItems) fail("EXTENSION_PACKAGE_TOOL_SCHEMA_INVALID", "rejected", "Array bounds are invalid.");
  } else if (["string", "number", "integer", "boolean"].includes(schema.type)) {
    allowedSchemaKeys(schema, ["type", "description", "enum", "minLength", "maxLength", "minimum", "maximum"]);
    if (schema.type === "string") {
      boundedIntegerOption(schema.minLength, 0, 4096);
      boundedIntegerOption(schema.maxLength, 0, 4096);
      if (schema.minLength !== undefined && schema.maxLength !== undefined && schema.minLength > schema.maxLength) fail("EXTENSION_PACKAGE_TOOL_SCHEMA_INVALID", "rejected", "String bounds are invalid.");
    } else if (schema.minLength !== undefined || schema.maxLength !== undefined) fail("EXTENSION_PACKAGE_TOOL_SCHEMA_INVALID", "rejected", "Length bounds require a string.");
    if (!["number", "integer"].includes(schema.type) && (schema.minimum !== undefined || schema.maximum !== undefined)) fail("EXTENSION_PACKAGE_TOOL_SCHEMA_INVALID", "rejected", "Numeric bounds require a number.");
    for (const value of [schema.minimum, schema.maximum]) if (value !== undefined && (typeof value !== "number" || !Number.isFinite(value))) fail("EXTENSION_PACKAGE_TOOL_SCHEMA_INVALID", "rejected", "Numeric bound is invalid.");
    if (schema.minimum !== undefined && schema.maximum !== undefined && schema.minimum > schema.maximum) fail("EXTENSION_PACKAGE_TOOL_SCHEMA_INVALID", "rejected", "Numeric bounds are invalid.");
    if (schema.enum !== undefined) {
      if (!Array.isArray(schema.enum) || schema.enum.length < 1 || schema.enum.length > 16) fail("EXTENSION_PACKAGE_TOOL_SCHEMA_INVALID", "rejected", "Schema enum is invalid.");
      const keys = schema.enum.map((value) => enumSemanticKey(schema.type, value));
      if (new Set(keys).size !== keys.length) fail("EXTENSION_PACKAGE_TOOL_SCHEMA_INVALID", "rejected", "Schema enum values must be unique.");
    }
  } else fail("EXTENSION_PACKAGE_TOOL_SCHEMA_INVALID", "rejected", "Schema type is unsupported.");
  if (schema.description !== undefined) string(schema.description, 1, 256);
}

function allowedSchemaKeys(value, allowed) {
  if (Object.keys(value).some((key) => !allowed.includes(key))) fail("EXTENSION_PACKAGE_TOOL_SCHEMA_INVALID", "rejected", "Schema keyword is unsupported.");
}

function exactSchemaKeys(value, keys) {
  if (canonicalJson(Object.keys(value).sort()) !== canonicalJson([...keys].sort())) fail("EXTENSION_PACKAGE_TOOL_SCHEMA_INVALID", "rejected", "Schema node contains unknown fields.");
}

function boundedIntegerOption(value, minimum, maximum) {
  if (value !== undefined && (!Number.isSafeInteger(value) || value < minimum || value > maximum)) fail("EXTENSION_PACKAGE_TOOL_SCHEMA_INVALID", "rejected", "Schema integer bound is invalid.");
}

function enumSemanticKey(type, value) {
  if (type === "string") {
    if (typeof value !== "string") fail("EXTENSION_PACKAGE_TOOL_SCHEMA_INVALID", "rejected", "Schema enum type is invalid.");
    return `string:${value}`;
  }
  if (type === "boolean") {
    if (typeof value !== "boolean") fail("EXTENSION_PACKAGE_TOOL_SCHEMA_INVALID", "rejected", "Schema enum type is invalid.");
    return `boolean:${value}`;
  }
  if (type === "integer") {
    if (!Number.isSafeInteger(value)) fail("EXTENSION_PACKAGE_TOOL_SCHEMA_INVALID", "rejected", "Schema integer enum is unsafe.");
    return `integer:${Object.is(value, -0) ? 0 : value}`;
  }
  if (typeof value !== "number" || !Number.isFinite(value)) fail("EXTENSION_PACKAGE_TOOL_SCHEMA_INVALID", "rejected", "Schema number enum is invalid.");
  return `number:${Object.is(value, -0) ? 0 : value}`;
}

function buildSourceLock(packageJson, files, source) {
  string(packageJson.name, 1, 214);
  string(packageJson.version, 1, 40);
  const kind = source.kind;
  if (!["local", "npm", "git"].includes(kind)) fail("EXTENSION_PACKAGE_MOBILE_SOURCE_LOCK_INVALID", "rejected", "Unknown source lock kind.");
  const lock = {
    schemaVersion: 1,
    profile: PROFILE,
    kind,
    packageName: packageJson.name,
    packageVersion: packageJson.version,
    sourceDigest: sourceTreeDigest(files),
    registryIntegrity: null,
    repositoryUrl: null,
    revision: null,
    packer: PACKER_VERSIONS,
  };
  if (kind === "npm") {
    if (typeof source.integrity !== "string" || !/^sha512-[A-Za-z0-9+/]{86}==$/.test(source.integrity)) fail("EXTENSION_PACKAGE_MOBILE_SOURCE_LOCK_INVALID", "rejected", "npm requires one complete registry sha512 integrity value.");
    lock.registryIntegrity = source.integrity;
  }
  if (kind === "git") {
    if (!isSafeHttpsRepository(source.repositoryUrl) || !/^[0-9a-f]{40}$/.test(source.revision ?? "")) fail("EXTENSION_PACKAGE_MOBILE_SOURCE_LOCK_INVALID", "rejected", "Git requires a safe HTTPS repository URL and full commit revision.");
    lock.repositoryUrl = source.repositoryUrl;
    lock.revision = source.revision;
  }
  return lock;
}

function requireArtifactBounds(files, manifestIncluded) {
  if (files.size + (manifestIncluded ? 0 : 1) > MAX_FILES) fail("EXTENSION_PACKAGE_FILE_LIMIT_EXCEEDED", "rejected", "Artifact has too many files.");
  let total = 0;
  let jsTotal = 0;
  for (const [path, content] of files) {
    requireRelativePath(path);
    total += content.length;
    if (path.startsWith("dist/") && path.endsWith(".js")) {
      if (content.length > MAX_JS_BYTES) fail("EXTENSION_PACKAGE_JAVASCRIPT_MODULE_TOO_LARGE", "rejected", "A JS module is too large.");
      jsTotal += content.length;
    } else if (path === MANIFEST) {
      if (content.length > MAX_RESOURCE_BYTES) fail("EXTENSION_PACKAGE_MANIFEST_TOO_LARGE", "rejected", "The artifact manifest exceeds 64 KiB.");
    } else {
      if (content.length > MAX_RESOURCE_BYTES) fail("EXTENSION_PACKAGE_ARTIFACT_INVALID", "rejected", "A Worker resource exceeds 64 KiB.");
      try {
        new TextDecoder("utf-8", { fatal: true }).decode(content);
      } catch {
        fail("EXTENSION_PACKAGE_ARTIFACT_INVALID", "rejected", "Worker resources must be UTF-8 text.");
      }
    }
  }
  if (total > MAX_TOTAL_BYTES) fail("EXTENSION_PACKAGE_TOO_LARGE", "rejected", "Artifact is too large.");
  if (jsTotal > MAX_JS_TOTAL_BYTES) fail("EXTENSION_PACKAGE_JAVASCRIPT_TOO_LARGE", "rejected", "Artifact JS is too large.");
}

function extensionPackageDigest(manifest, files) {
  let text = `manifest:${canonicalJson(manifest)}`;
  for (const [path, content] of [...files].sort(([left], [right]) => compareUnicode(left, right))) {
    text += `\nfile:${path.length}:${path}:${content.length}:${sha256(content)}`;
  }
  return sha256(Buffer.from(text));
}

function sourceTreeDigest(files) {
  let text = "";
  for (const [path, content] of [...files].sort(([left], [right]) => compareUnicode(left, right))) {
    text += `file:${path.length}:${path}:${content.length}:${sha256(content)}\n`;
  }
  return sha256(Buffer.from(text));
}

function canonicalJson(value) {
  if (value === null || typeof value !== "object") return JSON.stringify(value);
  if (Array.isArray(value)) return `[${value.map(canonicalJson).join(", ")}]`;
  return `{${Object.keys(value).sort().map((key) => `${JSON.stringify(key)}:${canonicalJson(value[key])}`).join(", ")}}`;
}

function staticJson(node) {
  if (!node) return {};
  if (ts.isStringLiteral(node) || ts.isNoSubstitutionTemplateLiteral(node)) return node.text;
  if (ts.isNumericLiteral(node)) {
    const value = Number(node.text);
    if (!Number.isFinite(value) || !Number.isSafeInteger(value) && Number.isInteger(value)) fail("EXTENSION_PACKAGE_TOOL_SCHEMA_INVALID", "rejected", "Unsafe schema number.");
    return value;
  }
  if (node.kind === ts.SyntaxKind.TrueKeyword) return true;
  if (node.kind === ts.SyntaxKind.FalseKeyword) return false;
  if (node.kind === ts.SyntaxKind.NullKeyword) return null;
  if (ts.isPrefixUnaryExpression(node) && node.operator === ts.SyntaxKind.MinusToken && ts.isNumericLiteral(node.operand)) return -Number(node.operand.text);
  if (ts.isArrayLiteralExpression(node)) return node.elements.map(staticJson);
  if (ts.isObjectLiteralExpression(node)) {
    const result = {};
    for (const property of node.properties) {
      if (!ts.isPropertyAssignment(property)) fail("EXTENSION_PACKAGE_MOBILE_PI_API_UNSUPPORTED", "host_shim_required", "Schema options must be JSON literals.");
      result[propertyName(property.name)] = staticJson(property.initializer);
    }
    return result;
  }
  fail("EXTENSION_PACKAGE_MOBILE_PI_API_UNSUPPORTED", "host_shim_required", "Schema options must be JSON literals.");
}

function jsonExpression(value) {
  if (value === null) return ts.factory.createNull();
  if (typeof value === "string") return ts.factory.createStringLiteral(value);
  if (typeof value === "number") return ts.factory.createNumericLiteral(value);
  if (typeof value === "boolean") return value ? ts.factory.createTrue() : ts.factory.createFalse();
  if (Array.isArray(value)) return ts.factory.createArrayLiteralExpression(value.map(jsonExpression));
  return ts.factory.createObjectLiteralExpression(Object.entries(value).map(([key, child]) =>
    ts.factory.createPropertyAssignment(ts.factory.createStringLiteral(key), jsonExpression(child))), false);
}

function isTypeBoxCall(node) {
  return ts.isCallExpression(node) && ts.isPropertyAccessExpression(node.expression) && ts.isIdentifier(node.expression.expression) && node.expression.expression.text === "Type";
}

function initializer(property) {
  if (ts.isPropertyAssignment(property)) return property.initializer;
  if (ts.isMethodDeclaration(property)) return property;
  fail("EXTENSION_PACKAGE_MOBILE_PI_API_UNSUPPORTED", "host_shim_required", "Tool declaration must be static.");
}

function propertyName(node) {
  if (ts.isIdentifier(node) || ts.isStringLiteral(node) || ts.isNumericLiteral(node)) return node.text;
  fail("EXTENSION_PACKAGE_MOBILE_PI_API_UNSUPPORTED", "host_shim_required", "Computed property names are not supported.");
}

function staticString(node) {
  if (ts.isStringLiteral(node) || ts.isNoSubstitutionTemplateLiteral(node)) return node.text;
  fail("EXTENSION_PACKAGE_MOBILE_DECLARATION_MISMATCH", "rejected", "Tool metadata must be static strings.");
}

function staticNullableString(node) {
  if (node.kind === ts.SyntaxKind.NullKeyword) return null;
  return staticString(node);
}

function staticStringArray(node) {
  if (!ts.isArrayLiteralExpression(node)) fail("EXTENSION_PACKAGE_MOBILE_DECLARATION_MISMATCH", "rejected", "promptGuidelines must be a static array.");
  return node.elements.map(staticString);
}

function requireRelativePath(path) {
  if (!path || path.startsWith("/") || path.endsWith("/") || path.includes("\\") || path.includes("\0") || path.split("/").some((part) => !part || part === "." || part === ".." || part.length > 128) || path.length > 512) {
    fail("EXTENSION_PACKAGE_PATH_INVALID", "rejected", "Package path is invalid.");
  }
}

function requireHttpsOrigin(raw) {
  const canonical = canonicalHttpsOrigin(raw);
  if (canonical !== raw) {
    fail("EXTENSION_PACKAGE_NETWORK_ORIGIN_INVALID", "rejected", "HTTPS origin must not contain path, credentials, query, or fragment.");
  }
}

function canonicalHttpsOrigin(raw) {
  let url;
  try { url = new URL(raw); } catch { fail("EXTENSION_PACKAGE_NETWORK_ORIGIN_INVALID", "rejected", "HTTPS origin is invalid."); }
  const host = url.hostname.toLowerCase();
  if (url.protocol !== "https:" || url.username || url.password || url.pathname !== "/" || url.search || url.hash ||
      host.length > 253 || !/^[a-z0-9.-]+$/.test(host) || host.includes(":") || /^[0-9]+(?:\.[0-9]+){3}$/.test(host)) {
    fail("EXTENSION_PACKAGE_NETWORK_ORIGIN_INVALID", "rejected", "HTTPS origin is invalid.");
  }
  const port = url.port === "" ? "" : `:${url.port}`;
  return `https://${host}${port}`;
}

function isSafeHttpsRepository(raw) {
  return typeof raw === "string" && raw.length <= 512 && SAFE_GIT_REPOSITORY.test(raw);
}

function exactKeys(value, expected) {
  object(value, "object");
  if (canonicalJson(Object.keys(value).sort()) !== canonicalJson([...expected].sort())) fail("EXTENSION_PACKAGE_MOBILE_PROFILE_FIELDS_INVALID", "rejected", "Profile contains missing or unknown fields.");
}

function object(value, label) {
  if (!value || typeof value !== "object" || Array.isArray(value)) fail("EXTENSION_PACKAGE_MOBILE_PROFILE_INVALID", "rejected", `${label} must be an object.`);
}

function string(value, minimum, maximum) {
  if (typeof value !== "string" || value.length < minimum || value.length > maximum || value.includes("\0")) fail("EXTENSION_PACKAGE_MOBILE_PROFILE_INVALID", "rejected", "Profile string is invalid.");
}

function stringArray(value, maximum, itemMaximum = 255) {
  if (!Array.isArray(value) || value.length > maximum) fail("EXTENSION_PACKAGE_MOBILE_PROFILE_INVALID", "rejected", "Profile array is invalid.");
  value.forEach((item) => string(item, 1, itemMaximum));
}

function unique(values, code) {
  if (new Set(values).size !== values.length) fail(code, "rejected", "Profile values must be unique.");
}

function compareUnicode(left, right) {
  return left < right ? -1 : left > right ? 1 : 0;
}

function requireNodeVersion() {
  if (process.version !== `v${PACKER_VERSIONS.node}`) fail("EXTENSION_PACKAGE_MOBILE_PACKER_VERSION_MISMATCH", "rejected", `Use Node ${PACKER_VERSIONS.node}.`);
}

function sha256(value) {
  return createHash("sha256").update(value).digest("hex");
}

function fail(code, compatibility, detail) {
  throw new MobileExtensionPackError(code, compatibility, detail);
}

function parseCli(argv) {
  const options = { source: { kind: "local" } };
  for (let index = 0; index < argv.length; index += 2) {
    const key = argv[index];
    const value = argv[index + 1];
    if (!key?.startsWith("--") || value === undefined) throw new Error("Usage: --source DIR --output DIR [--kind local|npm|git ...]");
    if (key === "--source") options.sourceDir = value;
    else if (key === "--output") options.outputDir = value;
    else if (key === "--kind") options.source.kind = value;
    else if (key === "--integrity") options.source.integrity = value;
    else if (key === "--repository") options.source.repositoryUrl = value;
    else if (key === "--revision") options.source.revision = value;
    else throw new Error(`Unknown option: ${key}`);
  }
  if (!options.sourceDir || !options.outputDir) throw new Error("Usage: --source DIR --output DIR [--kind local|npm|git ...]");
  return options;
}

const isMain = process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url);
if (isMain) {
  try {
    const result = await packMobileExtension(parseCli(process.argv.slice(2)));
    process.stdout.write(`${JSON.stringify({ compatibility: "direct", ...result })}\n`);
  } catch (error) {
    if (error instanceof MobileExtensionPackError) {
      process.stderr.write(`${JSON.stringify({ compatibility: error.compatibility, code: error.code, message: error.detail })}\n`);
      process.exitCode = 2;
    } else throw error;
  }
}
