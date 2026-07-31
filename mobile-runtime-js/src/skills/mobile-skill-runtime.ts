import {
  ExecutionError,
  FileError,
  loadSkills,
  type ExecutionEnv,
  type FileInfo,
  type Skill,
} from "@earendil-works/pi-agent-core";
import { sha256 } from "../sha256.js";

export interface PiMobileSkillResource {
  name: string;
  description: string;
  content: string;
  contentSha256: string;
  disableModelInvocation: boolean;
}

interface PiMobileSkillParseSuccess {
  ok: true;
  parseId: number;
  phase: "completed";
  resource: PiMobileSkillResource;
  availability: "available" | "unavailable";
  diagnosticCode: string | null;
  diagnosticMessage: string | null;
}

interface PiMobileSkillParseFailure {
  ok: false;
  parseId: number;
  phase: "failed";
  errorCode: string;
  errorMessage: string;
}

export type PiMobileSkillParseStatus =
  | { ok: true; parseId: number; phase: "parsing" }
  | PiMobileSkillParseSuccess
  | PiMobileSkillParseFailure;

export const SKILL_INVOCATION_CONTROL_ENTRY_TYPE = "pi_mobile_skill_invocation";

const SKILL_DISCOVERY_SENTINEL = "__candidate__";
const SKILL_ROOT = "/mobile-skills";
const MAX_SKILL_DOCUMENT_UTF16_UNITS = 65_536;
const MAX_SKILL_RESOURCES = 64;
const SKILL_NAME_PATTERN = /^[a-z0-9]+(?:-[a-z0-9]+)*$/;

let nextSkillParseId = 1;
let skillParseStatus: PiMobileSkillParseStatus | null = null;

export function beginSkillDocumentParse(rawContent: string): PiMobileSkillParseStatus {
  if (
    typeof rawContent !== "string" ||
    rawContent.length < 1 ||
    rawContent.length > MAX_SKILL_DOCUMENT_UTF16_UNITS ||
    rawContent.includes("\u0000")
  ) {
    throw new Error("PI_MOBILE_SKILL_DOCUMENT_INVALID");
  }
  if (skillParseStatus?.phase === "parsing") {
    throw new Error("PI_MOBILE_SKILL_PARSE_BUSY");
  }
  const parseId = nextSkillParseId++;
  skillParseStatus = { ok: true, parseId, phase: "parsing" };
  queueMicrotask(() => {
    void parseSingleSkillDocument(parseId, rawContent);
  });
  return skillParseStatus;
}

export function currentSkillDocumentParse(parseId: number): PiMobileSkillParseStatus {
  if (!Number.isSafeInteger(parseId) || parseId < 1) {
    throw new Error("PI_MOBILE_SKILL_PARSE_ID_INVALID");
  }
  if (skillParseStatus === null || skillParseStatus.parseId !== parseId) {
    throw new Error("PI_MOBILE_SKILL_PARSE_NOT_FOUND");
  }
  return skillParseStatus;
}

export function clearSkillDocumentParse(parseId: number): void {
  if (!Number.isSafeInteger(parseId) || parseId < 1) {
    throw new Error("PI_MOBILE_SKILL_PARSE_ID_INVALID");
  }
  if (skillParseStatus === null || skillParseStatus.parseId !== parseId) {
    throw new Error("PI_MOBILE_SKILL_PARSE_NOT_FOUND");
  }
  skillParseStatus = null;
}

export function closeSkillDocumentParse(): void {
  skillParseStatus = null;
}

export function requirePiMobileSkillResources(value: unknown): PiMobileSkillResource[] {
  if (!Array.isArray(value) || value.length > MAX_SKILL_RESOURCES) {
    throw new Error("PI_MOBILE_SKILL_RESOURCES_INVALID");
  }
  const resources = value.map((candidate) => requirePiMobileSkillResource(candidate));
  if (new Set(resources.map((resource) => resource.name)).size !== resources.length) {
    throw new Error("PI_MOBILE_SKILL_NAME_DUPLICATED");
  }
  return resources.sort((left, right) => left.name < right.name ? -1 : left.name > right.name ? 1 : 0);
}

export function toPiSkills(resources: PiMobileSkillResource[]): Skill[] {
  return resources.map((resource) => ({
    name: resource.name,
    description: resource.description,
    content: resource.content,
    filePath: skillFilePath(resource.name),
    disableModelInvocation: resource.disableModelInvocation,
  }));
}

export function resourcesFromPiSkills(skills: Skill[]): PiMobileSkillResource[] {
  return requirePiMobileSkillResources(skills.map(mobileSkillResource));
}

export function skillResourceSetDigest(resources: PiMobileSkillResource[]): string {
  const canonical = resources.map((resource) => [
    `${resource.name.length}:`,
    resource.name,
    `${resource.description.length}:`,
    resource.description,
    resource.contentSha256,
    resource.disableModelInvocation ? "1" : "0",
  ].join("")).join("");
  return sha256(canonical);
}

export function isValidSkillName(name: string): boolean {
  return name.length >= 1 && name.length <= 64 && SKILL_NAME_PATTERN.test(name);
}

async function parseSingleSkillDocument(parseId: number, rawContent: string): Promise<void> {
  try {
    const discovery = await loadSkillPass(SKILL_DISCOVERY_SENTINEL, rawContent);
    if (discovery.skills.length !== 1) {
      finishSkillParseFailure(parseId, diagnosticErrorCode(discovery.diagnostics), "Skill metadata is invalid.");
      return;
    }
    const discoveredName = discovery.skills[0]!.name;
    if (discoveredName === SKILL_DISCOVERY_SENTINEL) {
      finishSkillParseFailure(parseId, "SKILL_NAME_REQUIRED", "Skill frontmatter must declare a name.");
      return;
    }
    if (!isValidSkillName(discoveredName)) {
      finishSkillParseFailure(parseId, "SKILL_NAME_INVALID", "Skill name is invalid.");
      return;
    }

    const authoritative = await loadSkillPass(discoveredName, rawContent);
    const expectedPath = skillFilePath(discoveredName);
    const skill = authoritative.skills[0];
    if (
      authoritative.skills.length !== 1 ||
      skill === undefined ||
      skill.name !== discoveredName ||
      skill.filePath !== expectedPath ||
      authoritative.diagnostics.length !== 0
    ) {
      finishSkillParseFailure(
        parseId,
        diagnosticErrorCode(authoritative.diagnostics),
        "Skill metadata is invalid.",
      );
      return;
    }
    if (skill.content.length > MAX_SKILL_DOCUMENT_UTF16_UNITS) {
      finishSkillParseFailure(parseId, "SKILL_CONTENT_TOO_LARGE", "Skill content is too large.");
      return;
    }

    const relativeReference = hasLocalRelativeReference(skill.content);
    if (skillParseStatus?.parseId !== parseId) return;
    skillParseStatus = {
      ok: true,
      parseId,
      phase: "completed",
      resource: mobileSkillResource(skill),
      availability: relativeReference ? "unavailable" : "available",
      diagnosticCode: relativeReference ? "RELATIVE_DEPENDENCY_UNSUPPORTED" : null,
      diagnosticMessage: relativeReference
        ? "Single-file import cannot use local relative Markdown or HTML references."
        : null,
    };
  } catch {
    finishSkillParseFailure(parseId, "SKILL_PARSE_FAILED", "Skill could not be parsed safely.");
  }
}

async function loadSkillPass(
  parentName: string,
  rawContent: string,
): ReturnType<typeof loadSkills> {
  const root = `${SKILL_ROOT}/${parentName}`;
  return await loadSkills(createSingleSkillExecutionEnv(root, rawContent), root);
}

function createSingleSkillExecutionEnv(root: string, rawContent: string): ExecutionEnv {
  const filePath = `${root}/SKILL.md`;
  const rootInfo: FileInfo = {
    name: root.slice(root.lastIndexOf("/") + 1),
    path: root,
    kind: "directory",
    size: 0,
    mtimeMs: 0,
  };
  const skillInfo: FileInfo = {
    name: "SKILL.md",
    path: filePath,
    kind: "file",
    size: rawContent.length,
    mtimeMs: 0,
  };
  const notFound = (path: string) => ({
    ok: false as const,
    error: new FileError("not_found", "Virtual Skill path was not found", path),
  });
  const unsupported = (operation: string) => ({
    ok: false as const,
    error: new FileError("not_supported", `Virtual Skill ${operation} is not supported`),
  });
  const fileInfo = async (path: string) => {
    if (path === root) {
      return { ok: true as const, value: rootInfo };
    }
    if (path === filePath) {
      return { ok: true as const, value: skillInfo };
    }
    return notFound(path);
  };
  return {
    cwd: root,
    absolutePath: async (path) => ({
      ok: true,
      value: path.startsWith("/") ? path : `${root}/${path}`,
    }),
    joinPath: async (parts) => ({ ok: true, value: parts.join("/").replace(/\/{2,}/g, "/") }),
    readTextFile: async (path) => path === filePath ? { ok: true, value: rawContent } : notFound(path),
    readTextLines: async (path, options) => path === filePath
      ? { ok: true, value: rawContent.split(/\r?\n/).slice(0, options?.maxLines) }
      : notFound(path),
    readBinaryFile: async () => unsupported("binary read"),
    writeFile: async () => unsupported("write"),
    appendFile: async () => unsupported("append"),
    fileInfo,
    listDir: async (path) => path === root ? { ok: true, value: [skillInfo] } : notFound(path),
    canonicalPath: async (path) => {
      const info = await fileInfo(path);
      return info.ok ? { ok: true, value: path } : info;
    },
    exists: async (path) => ({ ok: true, value: (await fileInfo(path)).ok }),
    createDir: async () => unsupported("create directory"),
    remove: async () => unsupported("remove"),
    createTempDir: async () => unsupported("temporary directory"),
    createTempFile: async () => unsupported("temporary file"),
    exec: async () => ({
      ok: false,
      error: new ExecutionError("shell_unavailable", "Virtual Skill shell is unavailable"),
    }),
    cleanup: async () => undefined,
  } as ExecutionEnv;
}

function finishSkillParseFailure(parseId: number, errorCode: string, errorMessage: string): void {
  if (skillParseStatus?.parseId !== parseId) return;
  skillParseStatus = { ok: false, parseId, phase: "failed", errorCode, errorMessage };
}

function diagnosticErrorCode(diagnostics: Array<{ code: string }>): string {
  const first = diagnostics[0]?.code;
  return first === "parse_failed" ? "SKILL_PARSE_FAILED" : "SKILL_METADATA_INVALID";
}

function skillFilePath(name: string): string {
  return `${SKILL_ROOT}/${name}/SKILL.md`;
}

function mobileSkillResource(skill: Skill): PiMobileSkillResource {
  return {
    name: skill.name,
    description: skill.description,
    content: skill.content,
    contentSha256: sha256(skill.content),
    disableModelInvocation: skill.disableModelInvocation === true,
  };
}

function hasLocalRelativeReference(content: string): boolean {
  const markdownTargets = content.matchAll(/!?\[[^\]]*\]\(\s*([^\s)]+)(?:\s+[^)]*)?\)/g);
  for (const match of markdownTargets) {
    if (isLocalRelativeTarget(match[1] ?? "")) return true;
  }
  const markdownDefinitions = content.matchAll(/^\s*\[[^\]]+\]:\s*(\S+)/gm);
  for (const match of markdownDefinitions) {
    if (isLocalRelativeTarget(match[1] ?? "")) return true;
  }
  const htmlTargets = content.matchAll(/\b(?:href|src)\s*=\s*["']([^"']+)["']/gi);
  for (const match of htmlTargets) {
    if (isLocalRelativeTarget(match[1] ?? "")) return true;
  }
  return false;
}

function isLocalRelativeTarget(rawTarget: string): boolean {
  const target = rawTarget.trim().replace(/^<|>$/g, "");
  return target.length > 0 &&
    !target.startsWith("/") &&
    !target.startsWith("#") &&
    !target.startsWith("//") &&
    !/^[a-z][a-z0-9+.-]*:/i.test(target);
}

function requirePiMobileSkillResource(value: unknown): PiMobileSkillResource {
  if (!isRecord(value)) throw new Error("PI_MOBILE_SKILL_RESOURCE_INVALID");
  const name = value.name;
  const description = value.description;
  const content = value.content;
  const contentSha256 = value.contentSha256;
  const disableModelInvocation = value.disableModelInvocation;
  if (typeof name !== "string" || !isValidSkillName(name)) {
    throw new Error("PI_MOBILE_SKILL_NAME_INVALID");
  }
  if (
    typeof description !== "string" ||
    description.trim().length < 1 ||
    description.length > 1024 ||
    description.includes("\u0000")
  ) {
    throw new Error("PI_MOBILE_SKILL_DESCRIPTION_INVALID");
  }
  if (
    typeof content !== "string" ||
    content.length > MAX_SKILL_DOCUMENT_UTF16_UNITS ||
    content.includes("\u0000")
  ) {
    throw new Error("PI_MOBILE_SKILL_CONTENT_INVALID");
  }
  if (
    typeof contentSha256 !== "string" ||
    !/^[0-9a-f]{64}$/.test(contentSha256) ||
    contentSha256 !== sha256(content)
  ) {
    throw new Error("PI_MOBILE_SKILL_CONTENT_DIGEST_INVALID");
  }
  if (typeof disableModelInvocation !== "boolean") {
    throw new Error("PI_MOBILE_SKILL_VISIBILITY_INVALID");
  }
  return { name, description, content, contentSha256, disableModelInvocation };
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}
