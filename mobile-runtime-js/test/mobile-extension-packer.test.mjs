import assert from "node:assert/strict";
import { mkdir, mkdtemp, readFile, readdir, rename, symlink, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import test from "node:test";

import {
  MobileExtensionPackError,
  diagnoseMobileExtensionSource,
  packMobileExtension,
} from "../scripts/mobile-extension-packer.mjs";

const root = resolve(dirname(fileURLToPath(import.meta.url)), "fixtures/mobile-extension-packer");

test("packs all compatible Pi fixtures into deterministic schema v2 artifacts", async () => {
  for (const id of ["official-register-tool", "async-progress-abort", "state", "host-call", "network"]) {
    const temp = await mkdtemp(join(tmpdir(), `momoding-packer-${id}-`));
    const first = join(temp, "one");
    const second = join(temp, "two");
    const a = await packMobileExtension({ sourceDir: join(root, id), outputDir: first });
    const b = await packMobileExtension({ sourceDir: join(root, id), outputDir: second });
    assert.equal(a.manifest.schemaVersion, 2);
    assert.equal(a.manifest.runtime, "pi-register-tool-v1");
    assert.equal(a.manifest.packageDigest, b.manifest.packageDigest);
    assert.deepEqual(await snapshot(first), await snapshot(second));
    assert.equal(a.sourceLock.kind, "local");
    assert.match(a.sourceLock.sourceDigest, /^[0-9a-f]{64}$/);
  }
});

test("diagnoses raw Profile source without executing Node or package scripts", async () => {
  const compatible = await diagnoseMobileExtensionSource(join(root, "official-register-tool"));
  assert.equal(compatible.compatibility, "build_required");

  const unsupported = await diagnoseMobileExtensionSource(join(root, "unsupported-node"));
  assert.equal(unsupported.compatibility, "unsupported");
  assert.equal(unsupported.code, "EXTENSION_PACKAGE_MOBILE_NODE_API_UNSUPPORTED");

  const temp = await mkdtemp(join(tmpdir(), "momoding-packer-script-"));
  await copyFixture("official-register-tool", temp);
  const marker = join(temp, "script-ran");
  const packageJson = JSON.parse(await readFile(join(temp, "package.json"), "utf8"));
  packageJson.scripts = { prepare: `touch ${marker}` };
  await writeFile(join(temp, "package.json"), `${JSON.stringify(packageJson, null, 2)}\n`);
  await packMobileExtension({ sourceDir: temp, outputDir: `${temp}-output` });
  await assert.rejects(readFile(marker));

  const literal = await mkdtemp(join(tmpdir(), "momoding-packer-literal-"));
  await copyFixture("official-register-tool", literal);
  const literalSource = await readFile(join(literal, "index.ts"), "utf8");
  await writeFile(
    join(literal, "index.ts"),
    `${literalSource}\nconst harmless = "business process. registerProvider( WebSocket"; // require(\n`,
  );
  const literalResult = await packMobileExtension({ sourceDir: literal, outputDir: `${literal}-output` });
  assert.equal(literalResult.manifest.runtime, "pi-register-tool-v1");
});

test("rejects Node packages, declaration drift, and incomplete provenance locks", async () => {
  await assert.rejects(
    packMobileExtension({ sourceDir: join(root, "unsupported-node"), outputDir: join(tmpdir(), "unsupported-output") }),
    (error) => error instanceof MobileExtensionPackError && error.compatibility === "unsupported",
  );

  const temp = await mkdtemp(join(tmpdir(), "momoding-packer-drift-"));
  await copyFixture("official-register-tool", temp);
  const source = await readFile(join(temp, "index.ts"), "utf8");
  await writeFile(join(temp, "index.ts"), source.replace("Fixture echo", "Changed at runtime"));
  await assert.rejects(
    packMobileExtension({ sourceDir: temp, outputDir: `${temp}-output` }),
    (error) => error.code === "EXTENSION_PACKAGE_MOBILE_DECLARATION_MISMATCH",
  );
  await assert.rejects(
    packMobileExtension({ sourceDir: join(root, "official-register-tool"), outputDir: `${temp}-npm`, source: { kind: "npm" } }),
    (error) => error.code === "EXTENSION_PACKAGE_MOBILE_SOURCE_LOCK_INVALID",
  );
  await assert.rejects(
    packMobileExtension({ sourceDir: join(root, "official-register-tool"), outputDir: `${temp}-git`, source: { kind: "git", repositoryUrl: "https://example.test/repo", revision: "main" } }),
    (error) => error.code === "EXTENSION_PACKAGE_MOBILE_SOURCE_LOCK_INVALID",
  );

  const imageHost = await mkdtemp(join(tmpdir(), "momoding-packer-image-host-"));
  await copyFixture("host-call", imageHost);
  const profilePath = join(imageHost, "momoding-mobile.json");
  const profile = JSON.parse(await readFile(profilePath, "utf8"));
  profile.hostTools[1] = {
    name: "calendar",
    targetTool: "device_screen_capture",
    capability: "screen_capture",
  };
  profile.capabilities.required = ["screen_capture"];
  await writeFile(profilePath, `${JSON.stringify(profile, null, 2)}\n`);
  await assert.rejects(
    packMobileExtension({ sourceDir: imageHost, outputDir: `${imageHost}-output` }),
    (error) => error.code === "EXTENSION_PACKAGE_ALIAS_TARGET_UNSUPPORTED" &&
      error.compatibility === "host_shim_required",
  );
});

test("bundles only analyzed in-package modules and ignores project tsconfig discovery", async () => {
  const temp = await mkdtemp(join(tmpdir(), "momoding-packer-modules-"));
  const source = join(temp, "source");
  await mkdir(source);
  await copyFixture("official-register-tool", source);
  await writeFile(join(source, "helper.ts"), "export const helper = 'inside';\n");
  const entrypoint = await readFile(join(source, "index.ts"), "utf8");
  await writeFile(
    join(source, "index.ts"),
    `import { helper } from "./helper";\n${entrypoint.replace(
      "export default function officialRegisterTool(pi: ExtensionAPI): void {",
      "export default function officialRegisterTool(pi: ExtensionAPI): void {\n  void helper;",
    )}`,
  );
  await writeFile(join(temp, "outside-tsconfig.json"), "{ this is deliberately invalid json\n");
  await writeFile(join(source, "tsconfig.json"), '{"extends":"../outside-tsconfig.json"}\n');
  const packed = await packMobileExtension({ sourceDir: source, outputDir: join(temp, "artifact") });
  assert.equal(packed.manifest.runtime, "pi-register-tool-v1");

  for (const [name, statement] of [
    ["outside-import", 'import secret from "../../secret.json";\nvoid secret;'],
    ["outside-export", 'export { default as secret } from "../../secret.json";'],
    ["inside-json", 'import config from "./config.json";\nvoid config;'],
    ["data-url", 'import value from "data:text/javascript,export default 1";\nvoid value;'],
  ]) {
    const candidate = join(temp, name);
    await mkdir(candidate);
    await copyFixture("official-register-tool", candidate);
    await writeFile(join(candidate, "config.json"), '{"value":"inside but not an executable module"}\n');
    await writeFile(join(candidate, "index.ts"), `${statement}\n${entrypoint}`);
    await assert.rejects(
      packMobileExtension({ sourceDir: candidate, outputDir: join(temp, `${name}-artifact`) }),
      (error) => error instanceof MobileExtensionPackError &&
        ["EXTENSION_PACKAGE_PATH_INVALID", "EXTENSION_PACKAGE_MOBILE_SOURCE_INVALID", "EXTENSION_PACKAGE_MOBILE_NODE_API_UNSUPPORTED"].includes(error.code),
      name,
    );
  }
});

test("owns output replacement and bounds the complete source tree", async () => {
  const temp = await mkdtemp(join(tmpdir(), "momoding-packer-output-"));
  const source = join(temp, "source");
  await mkdir(source);
  await copyFixture("official-register-tool", source);
  const occupied = join(temp, "occupied");
  await mkdir(occupied);
  await writeFile(join(occupied, "keep.txt"), "keep");
  await assert.rejects(
    packMobileExtension({ sourceDir: source, outputDir: occupied }),
    (error) => error.code === "EXTENSION_PACKAGE_MOBILE_OUTPUT_NOT_OWNED",
  );
  assert.equal(await readFile(join(occupied, "keep.txt"), "utf8"), "keep");

  const owned = join(temp, "owned");
  const first = await packMobileExtension({ sourceDir: source, outputDir: owned });
  const second = await packMobileExtension({ sourceDir: source, outputDir: owned });
  assert.equal(first.manifest.packageDigest, second.manifest.packageDigest);

  const appeared = join(temp, "appeared");
  await assert.rejects(
    packMobileExtension({
      sourceDir: source,
      outputDir: appeared,
      _testBeforeCommit: async () => {
        await mkdir(appeared);
        await writeFile(join(appeared, "keep.txt"), "appeared");
      },
    }),
    (error) => error.code === "EXTENSION_PACKAGE_MOBILE_OUTPUT_CHANGED",
  );
  assert.equal(await readFile(join(appeared, "keep.txt"), "utf8"), "appeared");

  const parked = join(temp, "parked-owned");
  await assert.rejects(
    packMobileExtension({
      sourceDir: source,
      outputDir: owned,
      _testBeforeCommit: async () => {
        await rename(owned, parked);
        await mkdir(owned);
        await writeFile(join(owned, "keep.txt"), "swapped");
      },
    }),
    (error) => error.code === "EXTENSION_PACKAGE_MOBILE_OUTPUT_CHANGED",
  );
  assert.equal(await readFile(join(owned, "keep.txt"), "utf8"), "swapped");
  assert.match(await readFile(join(parked, ".momoding-mobile-artifact.json"), "utf8"), /momoding-mobile-extension-packer/);

  const crowded = join(temp, "crowded");
  await mkdir(crowded);
  await copyFixture("official-register-tool", crowded);
  for (let index = 0; index < 510; index += 1) await mkdir(join(crowded, `empty-${index}`));
  await assert.rejects(
    packMobileExtension({ sourceDir: crowded, outputDir: join(temp, "crowded-output") }),
    (error) => error.code === "EXTENSION_PACKAGE_NODE_LIMIT_EXCEEDED",
  );

  const linked = join(temp, "linked");
  await mkdir(linked);
  await copyFixture("official-register-tool", linked);
  await symlink(join(linked, "index.ts"), join(linked, "linked.ts"));
  await assert.rejects(
    packMobileExtension({ sourceDir: linked, outputDir: join(temp, "linked-output") }),
    (error) => error.code === "EXTENSION_PACKAGE_SYMLINK_UNSUPPORTED",
  );
});

test("rejects every packer Android runtime contract drift before emitting direct", async () => {
  const cases = [
    ["const-null", (profile) => {
      profile.tools[0].parameters = strictObject({ value: { const: null } }, ["value"]);
    }],
    ["enum-type", (profile) => {
      profile.tools[0].parameters = strictObject({ value: { type: "string", enum: [1] } }, ["value"]);
    }],
    ["enum-duplicate", (profile) => {
      profile.tools[0].parameters = strictObject({ value: { type: "number", enum: [0, -0] } }, ["value"]);
    }],
    ["id-77", (profile) => { profile.package.id = `a.${"b".repeat(75)}`; }],
    ["description-513", (profile) => { profile.package.description = "d".repeat(513); }],
    ["prompt-513", (profile) => { profile.tools[0].promptSnippet = "p".repeat(513); }],
    ["uppercase-origin", (profile) => {
      profile.https.origins = ["https://API.example.test"];
      profile.credentials = [];
    }],
    ["default-port-origin", (profile) => {
      profile.https.origins = ["https://api.example.test:443"];
      profile.credentials = [];
    }],
    ["ip-origin", (profile) => {
      profile.https.origins = ["https://8.8.8.8"];
      profile.credentials = [];
    }],
  ];
  for (const [name, mutate] of cases) {
    const temp = await mkdtemp(join(tmpdir(), `momoding-packer-${name}-`));
    await copyFixture("official-register-tool", temp);
    await mutateProfile(temp, mutate);
    await assert.rejects(
      packMobileExtension({ sourceDir: temp, outputDir: `${temp}-output` }),
      (error) => error instanceof MobileExtensionPackError,
      name,
    );
  }

  const utf8 = await mkdtemp(join(tmpdir(), "momoding-packer-utf8-schema-"));
  await copyFixture("official-register-tool", utf8);
  await mutateProfile(utf8, (profile) => {
    profile.tools[0].parameters = strictObject(Object.fromEntries(
      Array.from({ length: 16 }, (_, index) => [
        `field${index}`,
        { anyOf: Array.from({ length: 16 }, () => ({ type: "string", description: "界".repeat(256) })) },
      ]),
    ), []);
  });
  await assert.rejects(
    packMobileExtension({ sourceDir: utf8, outputDir: `${utf8}-output` }),
    (error) => error.code === "EXTENSION_PACKAGE_TOOL_SCHEMA_INVALID",
  );

  const websocket = await mkdtemp(join(tmpdir(), "momoding-packer-websocket-"));
  await copyFixture("official-register-tool", websocket);
  await writeFile(join(websocket, "index.ts"), `${await readFile(join(websocket, "index.ts"), "utf8")}\nnew WebSocket("wss://example.test");\n`);
  await assert.rejects(
    packMobileExtension({ sourceDir: websocket, outputDir: `${websocket}-output` }),
    (error) => error.code === "EXTENSION_PACKAGE_MOBILE_NODE_API_UNSUPPORTED",
  );

  const crossScope = await mkdtemp(join(tmpdir(), "momoding-packer-cross-scope-"));
  await copyFixture("official-register-tool", crossScope);
  await writeFile(
    join(crossScope, "index.ts"),
    `${await readFile(join(crossScope, "index.ts"), "utf8")}\nfunction harmless() { const process = "local"; return process; }\nconst leaked = process.env.SECRET;\nvoid harmless; void leaked;\n`,
  );
  await assert.rejects(
    packMobileExtension({ sourceDir: crossScope, outputDir: `${crossScope}-output` }),
    (error) => error.code === "EXTENSION_PACKAGE_MOBILE_NODE_API_UNSUPPORTED",
  );

  for (const [name, runtimeType] of [
    ["runtime-pi-type", "const runtimeSchema = Type.String();\nvoid runtimeSchema;"],
    ["shadowed-pi-type", "function runtime() { const Type = { String: () => 'runtime' }; return Type.String(); }\nvoid runtime;"],
  ]) {
    const candidate = await mkdtemp(join(tmpdir(), `momoding-packer-${name}-`));
    await copyFixture("official-register-tool", candidate);
    await writeFile(
      join(candidate, "index.ts"),
      `${await readFile(join(candidate, "index.ts"), "utf8")}\n${runtimeType}\n`,
    );
    await assert.rejects(
      packMobileExtension({ sourceDir: candidate, outputDir: `${candidate}-output` }),
      (error) => error.code === "EXTENSION_PACKAGE_MOBILE_PI_API_UNSUPPORTED",
      name,
    );
  }

  const resource = await mkdtemp(join(tmpdir(), "momoding-packer-resource-"));
  await copyFixture("official-register-tool", resource);
  await mkdir(join(resource, "resources"));
  await writeFile(join(resource, "resources", "large.txt"), "r".repeat(64 * 1024 + 1));
  await assert.rejects(
    packMobileExtension({ sourceDir: resource, outputDir: `${resource}-output` }),
    (error) => error.code === "EXTENSION_PACKAGE_ARTIFACT_INVALID",
  );
});

test("requires exact npm and safe Git provenance fields", async () => {
  const temp = await mkdtemp(join(tmpdir(), "momoding-packer-provenance-"));
  await copyFixture("official-register-tool", temp);
  await assert.rejects(
    packMobileExtension({ sourceDir: temp, outputDir: `${temp}-bad-sri`, source: { kind: "npm", integrity: "sha512-abc" } }),
    (error) => error.code === "EXTENSION_PACKAGE_MOBILE_SOURCE_LOCK_INVALID",
  );
  await assert.rejects(
    packMobileExtension({ sourceDir: temp, outputDir: `${temp}-bad-git`, source: { kind: "git", repositoryUrl: "https://user:secret@example.test/repo", revision: "a".repeat(40) } }),
    (error) => error.code === "EXTENSION_PACKAGE_MOBILE_SOURCE_LOCK_INVALID",
  );
  for (const [index, repositoryUrl] of [
    "https://bücher.example/repo",
    "https://a_b.example/repo",
    "https://example.test/%zz",
  ].entries()) {
    await assert.rejects(
      packMobileExtension({ sourceDir: temp, outputDir: `${temp}-bad-git-${index}`, source: { kind: "git", repositoryUrl, revision: "a".repeat(40) } }),
      (error) => error.code === "EXTENSION_PACKAGE_MOBILE_SOURCE_LOCK_INVALID",
    );
  }
  const npm = await packMobileExtension({
    sourceDir: temp,
    outputDir: `${temp}-npm`,
    source: { kind: "npm", integrity: `sha512-${"A".repeat(86)}==` },
  });
  assert.equal(npm.sourceLock.kind, "npm");
  const git = await packMobileExtension({
    sourceDir: temp,
    outputDir: `${temp}-git`,
    source: { kind: "git", repositoryUrl: "https://example.test/repo.git", revision: "a".repeat(40) },
  });
  assert.equal(git.sourceLock.kind, "git");
});

async function snapshot(directory, prefix = "") {
  const result = {};
  for (const entry of await readdir(directory, { withFileTypes: true })) {
    const path = prefix ? `${prefix}/${entry.name}` : entry.name;
    if (entry.isDirectory()) Object.assign(result, await snapshot(join(directory, entry.name), path));
    else result[path] = await readFile(join(directory, entry.name), "utf8");
  }
  return result;
}

async function copyFixture(id, destination) {
  for (const name of ["package.json", "momoding-mobile.json", "index.ts"]) {
    await writeFile(join(destination, name), await readFile(join(root, id, name)));
  }
}

async function mutateProfile(directory, mutate) {
  const path = join(directory, "momoding-mobile.json");
  const profile = JSON.parse(await readFile(path, "utf8"));
  mutate(profile);
  await writeFile(path, `${JSON.stringify(profile, null, 2)}\n`);
}

function strictObject(properties, required) {
  return { type: "object", properties, required, additionalProperties: false };
}
