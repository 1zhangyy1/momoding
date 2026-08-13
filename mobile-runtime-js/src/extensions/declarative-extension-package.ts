import type { AgentTool, AgentToolResult } from "@earendil-works/pi-agent-core";
import { sha256 } from "../sha256.js";
import type { NativeToolExecutor } from "../tools/android-tool-registry.js";
import {
  requirePiRegisterToolPackageSnapshot,
  type PiRegisterToolPackageSnapshot,
} from "./pi-register-tool-extension.js";
import {
  createConnectorProxyTool,
  type ConnectorToolSnapshot,
} from "./connector-extension.js";
import type { MobileExtensionDescriptor } from "./mobile-extension-host.js";

export type DeclarativeExtensionToolType =
  | "android-tool-alias"
  | "connector-proxy"
  | "prompt-tool"
  | "javascript-tool";

export interface ExtensionToolSnapshot {
  type: DeclarativeExtensionToolType;
  name: string;
  description: string;
  targetTool?: string | null;
  prompt?: string | null;
  parameters?: AgentTool["parameters"] | null;
  parametersDigest?: string | null;
}

export interface DeclarativeExtensionPackageSnapshot {
  schemaVersion: 1;
  id: string;
  name: string;
  version: string;
  description: string;
  runtime: "declarative-v1" | "javascript-v1";
  entrypoint: string | null;
  tools: ExtensionToolSnapshot[];
  requiredCapabilities: string[];
  optionalCapabilities: string[];
  networkOrigins: string[];
  packageDigest: string;
}

export type ExtensionPackageSnapshot =
  | DeclarativeExtensionPackageSnapshot
  | PiRegisterToolPackageSnapshot;

const CAPABILITIES = new Set([
  "saf_folders", "photo_library", "calendar", "contacts", "location", "notifications",
  "accessibility_control", "screen_capture", "all_files", "shizuku_shell_uid",
]);

export const EXTENSION_ANDROID_TOOL_CAPABILITIES = new Map<string, string | null>([
  ["device_capabilities_get", null],
  ["device_media_list", "photo_library"],
  ["device_calendar", "calendar"],
  ["device_contacts", "contacts"],
  ["device_location", "location"],
  ["device_clipboard", null],
  ["device_notification", "notifications"],
  ["device_screen_capture", "screen_capture"],
  ["device_ui_inspect", "accessibility_control"],
  ["device_ui_action", "accessibility_control"],
  ["device_packages_list", "shizuku_shell_uid"],
  ["device_package_inspect", "shizuku_shell_uid"],
]);

const TOP_LEVEL_KEYS = [
  "description", "entrypoint", "id", "name", "networkOrigins", "optionalCapabilities",
  "packageDigest", "requiredCapabilities", "runtime", "schemaVersion", "tools", "version",
];
const TOOL_NAME = /^[a-z][a-z0-9_]{0,63}$/;
const PACKAGE_ID = /^[a-z][a-z0-9]*(?:[._-][a-z0-9]+)+$/;
const VERSION = /^[0-9]+\.[0-9]+\.[0-9]+(?:-[0-9A-Za-z.-]+)?$/;
const SHA256 = /^[0-9a-f]{64}$/;

export function requireExtensionPackageSnapshots(value: unknown): ExtensionPackageSnapshot[] {
  if (!Array.isArray(value) || value.length > 32) {
    throw new Error("PI_MOBILE_EXTENSION_PACKAGE_SET_INVALID");
  }
  const packages = value.map((candidate) =>
    isRecord(candidate) && candidate.schemaVersion === 2
      ? requirePiRegisterToolPackageSnapshot(candidate)
      : requirePackage(candidate)
  );
  requireUnique(packages.map((candidate) => candidate.id), "PI_MOBILE_EXTENSION_PACKAGE_ID_DUPLICATE");
  const toolNames = packages.flatMap((candidate) => candidate.tools.map((tool) => tool.name));
  requireUnique(toolNames, "PI_MOBILE_EXTENSION_PACKAGE_TOOL_DUPLICATE");
  return packages.sort((left, right) => left.id < right.id ? -1 : left.id > right.id ? 1 : 0);
}

export function extensionPackageSetDigest(packages: readonly ExtensionPackageSnapshot[]): string {
  const normalized = requireExtensionPackageSnapshots([...packages]);
  return sha256(normalized.map((candidate) =>
    `${candidate.id.length}:${candidate.id}:${candidate.packageDigest}`
  ).join(""));
}

export function createDeclarativeExtensionPackageDescriptors(
  packages: readonly DeclarativeExtensionPackageSnapshot[],
  productTools: readonly AgentTool[],
  connectorSnapshot: ConnectorToolSnapshot | null,
  executeNativeTool: NativeToolExecutor,
): MobileExtensionDescriptor[] {
  const normalized = packages.map(requirePackage);
  requireUnique(normalized.map((candidate) => candidate.id), "PI_MOBILE_EXTENSION_PACKAGE_ID_DUPLICATE");
  const products = new Map(productTools.map((tool) => [tool.name, tool]));
  return normalized.map((extensionPackage) => ({
    id: `pkg.${extensionPackage.id}`,
    version: extensionPackage.version,
    source: "package" as const,
    factory: (api) => {
      for (const declaration of extensionPackage.tools) {
        if (declaration.type === "connector-proxy" && connectorSnapshot === null) continue;
        api.registerTool(createPackageTool(
          extensionPackage,
          declaration,
          products,
          connectorSnapshot,
          executeNativeTool,
        ));
      }
    },
  }));
}

function createPackageTool(
  extensionPackage: DeclarativeExtensionPackageSnapshot,
  declaration: ExtensionToolSnapshot,
  products: ReadonlyMap<string, AgentTool>,
  connectorSnapshot: ConnectorToolSnapshot | null,
  executeNativeTool: NativeToolExecutor,
): AgentTool {
  if (declaration.type === "javascript-tool") {
    return {
      name: declaration.name,
      label: extensionPackage.name,
      description: declaration.description,
      parameters: declaration.parameters!,
      executionMode: "sequential",
      execute: async (toolCallId, params, signal, onUpdate) => {
        const result = await executeNativeTool(
          "android_extension_package",
          "extension_package_execute_javascript",
          toolCallId,
          {
            packageId: extensionPackage.id,
            packageDigest: extensionPackage.packageDigest,
            extensionToolName: declaration.name,
            type: declaration.type,
            description: declaration.description,
            parametersDigest: declaration.parametersDigest!,
            invocationArguments: params,
          },
          signal,
        );
        const details = nativeDetails(result);
        if (details.isError || details.value?.ok !== true) {
          const code = typeof details.value?.errorCode === "string"
            ? details.value.errorCode
            : "PI_MOBILE_EXTENSION_JAVASCRIPT_FAILED";
          throw new Error(code);
        }
        if (details.value.kind !== "host-call") return result;
        const hostToolName = details.value.hostToolName;
        const hostArguments = details.value.arguments;
        if (typeof hostToolName !== "string" || !isRecord(hostArguments)) {
          throw new Error("PI_MOBILE_EXTENSION_HOST_CALL_INVALID");
        }
        const hostDeclaration = extensionPackage.tools.find((candidate) =>
          candidate.name === hostToolName &&
          (candidate.type === "android-tool-alias" || candidate.type === "connector-proxy")
        );
        if (hostDeclaration === undefined) {
          throw new Error("PI_MOBILE_EXTENSION_HOST_CALL_NOT_DECLARED");
        }
        const target = createPackageTool(
          extensionPackage,
          hostDeclaration,
          products,
          connectorSnapshot,
          executeNativeTool,
        );
        return target.execute(`${toolCallId}:host`, hostArguments, signal, onUpdate);
      },
    };
  }
  if (declaration.type === "prompt-tool") {
    return {
      name: declaration.name,
      label: extensionPackage.name,
      description: declaration.description,
      parameters: {
        type: "object",
        properties: {},
        required: [],
        additionalProperties: false,
      } as AgentTool["parameters"],
      executionMode: "sequential",
      execute: async (toolCallId, _params, signal) => {
        await authorize(extensionPackage, declaration, toolCallId, executeNativeTool, signal);
        return textResult(extensionPackage, declaration.prompt!);
      },
    };
  }

  let target: AgentTool | null = null;
  if (declaration.type === "android-tool-alias") {
    target = products.get(declaration.targetTool!) ?? null;
    if (target === null || !EXTENSION_ANDROID_TOOL_CAPABILITIES.has(declaration.targetTool!)) {
      throw new Error("PI_MOBILE_EXTENSION_PACKAGE_ALIAS_TARGET_MISSING");
    }
  } else if (connectorSnapshot !== null) {
    target = createConnectorProxyTool(connectorSnapshot, executeNativeTool);
  }

  return {
    name: declaration.name,
    label: extensionPackage.name,
    description: declaration.description,
    parameters: target?.parameters ?? CONNECTOR_UNAVAILABLE_PARAMETERS,
    executionMode: "sequential",
    execute: async (toolCallId, params, signal, onUpdate) => {
      await authorize(extensionPackage, declaration, toolCallId, executeNativeTool, signal);
      if (target === null) throw new Error("PI_MOBILE_EXTENSION_CONNECTOR_NOT_CONFIGURED");
      return target.execute(toolCallId, params, signal, onUpdate);
    },
  };
}

function nativeDetails(result: AgentToolResult<unknown>): {
  isError: boolean;
  value: Record<string, unknown> | null;
} {
  const envelope = isRecord(result.details) ? result.details : null;
  const value = envelope !== null && isRecord(envelope.details)
    ? envelope.details
    : envelope;
  return { isError: envelope?.isError === true, value };
}

async function authorize(
  extensionPackage: DeclarativeExtensionPackageSnapshot,
  declaration: ExtensionToolSnapshot,
  toolCallId: string,
  executeNativeTool: NativeToolExecutor,
  signal?: AbortSignal,
): Promise<void> {
  const result = await executeNativeTool(
    "android_extension_package",
    "extension_package_authorize",
    toolCallId,
    {
      packageId: extensionPackage.id,
      packageDigest: extensionPackage.packageDigest,
      extensionToolName: declaration.name,
      type: declaration.type,
      description: declaration.description,
      ...(declaration.targetTool == null ? {} : { targetTool: declaration.targetTool }),
      ...(declaration.prompt == null ? {} : { prompt: declaration.prompt }),
    },
    signal,
  );
  const details = nativeDetails(result);
  if (details.isError || details.value?.ok !== true) {
    const code = typeof details.value?.errorCode === "string"
      ? details.value.errorCode
      : "PI_MOBILE_EXTENSION_PACKAGE_NOT_AUTHORIZED";
    throw new Error(code);
  }
}

function textResult(
  extensionPackage: DeclarativeExtensionPackageSnapshot,
  prompt: string,
): AgentToolResult<unknown> {
  return {
    content: [{ type: "text", text: prompt }],
    details: {
      ok: true,
      packageId: extensionPackage.id,
      packageDigest: extensionPackage.packageDigest,
      kind: "prompt-tool",
    },
  };
}

function requirePackage(value: unknown): DeclarativeExtensionPackageSnapshot {
  if (!isRecord(value) || keys(value).join("\n") !== TOP_LEVEL_KEYS.join("\n")) {
    throw new Error("PI_MOBILE_EXTENSION_PACKAGE_FIELDS_INVALID");
  }
  const runtime = value.runtime;
  const entrypoint = value.entrypoint;
  if (
    value.schemaVersion !== 1 ||
    (runtime !== "declarative-v1" && runtime !== "javascript-v1") ||
    (runtime === "declarative-v1"
      ? entrypoint !== null
      : !bounded(entrypoint, 1, 512) || !safeJavaScriptEntrypoint(entrypoint)) ||
    !bounded(value.id, 3, 80) || !PACKAGE_ID.test(value.id) ||
    !bounded(value.name, 1, 80) || !bounded(value.version, 1, 40) || !VERSION.test(value.version) ||
    !bounded(value.description, 1, 1_024) || typeof value.packageDigest !== "string" ||
    !SHA256.test(value.packageDigest)
  ) throw new Error("PI_MOBILE_EXTENSION_PACKAGE_INVALID");
  const required = requireStringList(value.requiredCapabilities, 10);
  const optional = requireStringList(value.optionalCapabilities, 10);
  const origins = requireStringList(value.networkOrigins, 8);
  requireUnique(required, "PI_MOBILE_EXTENSION_CAPABILITY_DUPLICATE");
  requireUnique(optional, "PI_MOBILE_EXTENSION_CAPABILITY_DUPLICATE");
  requireUnique(origins, "PI_MOBILE_EXTENSION_ORIGIN_DUPLICATE");
  if (
    [...required, ...optional].some((capability) => !CAPABILITIES.has(capability)) ||
    required.some((capability) => optional.includes(capability)) ||
    origins.some((origin) => !isHttpsOrigin(origin))
  ) throw new Error("PI_MOBILE_EXTENSION_PACKAGE_BOUNDARY_INVALID");
  if (!Array.isArray(value.tools) || value.tools.length < 1 || value.tools.length > 16) {
    throw new Error("PI_MOBILE_EXTENSION_PACKAGE_TOOLS_INVALID");
  }
  const tools = value.tools.map((tool) => requireTool(tool, required));
  requireUnique(tools.map((tool) => tool.name), "PI_MOBILE_EXTENSION_PACKAGE_TOOL_DUPLICATE");
  const javascriptToolCount = tools.filter((tool) => tool.type === "javascript-tool").length;
  if (
    (runtime === "declarative-v1" && javascriptToolCount !== 0) ||
    (runtime === "javascript-v1" && javascriptToolCount === 0)
  ) throw new Error("PI_MOBILE_EXTENSION_PACKAGE_RUNTIME_TOOLS_INVALID");
  return {
    schemaVersion: 1,
    id: value.id,
    name: value.name,
    version: value.version,
    description: value.description,
    runtime,
    entrypoint: runtime === "javascript-v1" ? entrypoint as string : null,
    tools,
    requiredCapabilities: [...required].sort(),
    optionalCapabilities: [...optional].sort(),
    networkOrigins: [...origins].sort(),
    packageDigest: value.packageDigest,
  };
}

function requireTool(value: unknown, requiredCapabilities: readonly string[]): ExtensionToolSnapshot {
  if (!isRecord(value) || !bounded(value.type, 1, 40) || !bounded(value.name, 1, 64) ||
      !TOOL_NAME.test(value.name) || !bounded(value.description, 1, 512)) {
    throw new Error("PI_MOBILE_EXTENSION_PACKAGE_TOOL_INVALID");
  }
  if (value.type === "android-tool-alias") {
    requireExactKeys(value, ["description", "name", "targetTool", "type"]);
    if (!bounded(value.targetTool, 1, 80) || !EXTENSION_ANDROID_TOOL_CAPABILITIES.has(value.targetTool)) {
      throw new Error("PI_MOBILE_EXTENSION_PACKAGE_ALIAS_INVALID");
    }
    const capability = EXTENSION_ANDROID_TOOL_CAPABILITIES.get(value.targetTool);
    if (capability != null && !requiredCapabilities.includes(capability)) {
      throw new Error("PI_MOBILE_EXTENSION_PACKAGE_CAPABILITY_UNDECLARED");
    }
    return {
      type: value.type,
      name: value.name,
      description: value.description,
      targetTool: value.targetTool,
    };
  }
  if (value.type === "connector-proxy") {
    requireExactKeys(value, ["description", "name", "type"]);
    return { type: value.type, name: value.name, description: value.description };
  }
  if (value.type === "prompt-tool") {
    requireExactKeys(value, ["description", "name", "prompt", "type"]);
    if (!bounded(value.prompt, 1, 16 * 1_024)) {
      throw new Error("PI_MOBILE_EXTENSION_PACKAGE_PROMPT_INVALID");
    }
    return { type: value.type, name: value.name, description: value.description, prompt: value.prompt };
  }
  if (value.type === "javascript-tool") {
    requireExactKeys(value, ["description", "name", "parameters", "parametersDigest", "type"]);
    if (typeof value.parametersDigest !== "string" || !SHA256.test(value.parametersDigest)) {
      throw new Error("PI_MOBILE_EXTENSION_PACKAGE_TOOL_SCHEMA_INVALID");
    }
    return {
      type: value.type,
      name: value.name,
      description: value.description,
      parameters: requireToolSchema(value.parameters),
      parametersDigest: value.parametersDigest,
    };
  }
  throw new Error("PI_MOBILE_EXTENSION_PACKAGE_TOOL_TYPE_UNSUPPORTED");
}

function requireToolSchema(value: unknown): AgentTool["parameters"] {
  if (!isRecord(value) || JSON.stringify(value).length > 8 * 1_024) {
    throw new Error("PI_MOBILE_EXTENSION_PACKAGE_TOOL_SCHEMA_INVALID");
  }
  requireExactKeys(value, ["additionalProperties", "properties", "required", "type"]);
  if (value.type !== "object" || value.additionalProperties !== false || !isRecord(value.properties) ||
      Object.keys(value.properties).length > 16 || !Array.isArray(value.required)) {
    throw new Error("PI_MOBILE_EXTENSION_PACKAGE_TOOL_SCHEMA_INVALID");
  }
  const properties = value.properties;
  for (const [name, property] of Object.entries(properties)) {
    if (!/^[a-z][a-zA-Z0-9_]{0,63}$/.test(name)) {
      throw new Error("PI_MOBILE_EXTENSION_PACKAGE_TOOL_SCHEMA_INVALID");
    }
    requireSchemaProperty(property);
  }
  if (!value.required.every((name) => typeof name === "string" && name in properties)) {
    throw new Error("PI_MOBILE_EXTENSION_PACKAGE_TOOL_SCHEMA_INVALID");
  }
  requireUnique(value.required as string[], "PI_MOBILE_EXTENSION_PACKAGE_TOOL_SCHEMA_INVALID");
  return value as AgentTool["parameters"];
}

function requireSchemaProperty(value: unknown): void {
  if (!isRecord(value)) throw new Error("PI_MOBILE_EXTENSION_PACKAGE_TOOL_SCHEMA_INVALID");
  const allowed = new Set(["type", "description", "enum", "minLength", "maxLength", "minimum", "maximum"]);
  if (Object.keys(value).some((key) => !allowed.has(key)) ||
      !["string", "number", "integer", "boolean"].includes(String(value.type))) {
    throw new Error("PI_MOBILE_EXTENSION_PACKAGE_TOOL_SCHEMA_INVALID");
  }
  if (value.description !== undefined && !bounded(value.description, 1, 256)) {
    throw new Error("PI_MOBILE_EXTENSION_PACKAGE_TOOL_SCHEMA_INVALID");
  }
  const type = value.type as string;
  const integerBound = (candidate: unknown) =>
    typeof candidate === "number" && Number.isInteger(candidate) && candidate >= 0 && candidate <= 4_096;
  if (type === "string") {
    if (value.minLength !== undefined && !integerBound(value.minLength)) throwSchema();
    if (value.maxLength !== undefined && !integerBound(value.maxLength)) throwSchema();
    if (typeof value.minLength === "number" && typeof value.maxLength === "number" &&
        value.minLength > value.maxLength) throwSchema();
    if (value.minimum !== undefined || value.maximum !== undefined) throwSchema();
  } else {
    if (value.minLength !== undefined || value.maxLength !== undefined) throwSchema();
    if (type === "boolean" && (value.minimum !== undefined || value.maximum !== undefined)) throwSchema();
    if ((value.minimum !== undefined && (typeof value.minimum !== "number" || !Number.isFinite(value.minimum))) ||
        (value.maximum !== undefined && (typeof value.maximum !== "number" || !Number.isFinite(value.maximum))) ||
        (typeof value.minimum === "number" && typeof value.maximum === "number" &&
          value.minimum > value.maximum)) throwSchema();
  }
  if (value.enum !== undefined) {
    if (!Array.isArray(value.enum) || value.enum.length < 1 || value.enum.length > 16) throwSchema();
    for (const item of value.enum) {
      if (!schemaPrimitiveMatches(type, item)) throwSchema();
    }
    requireUnique(value.enum.map((item) => JSON.stringify(item)),
      "PI_MOBILE_EXTENSION_PACKAGE_TOOL_SCHEMA_INVALID");
  }
}

function schemaPrimitiveMatches(type: string, value: unknown): boolean {
  if (type === "string") return typeof value === "string";
  if (type === "boolean") return typeof value === "boolean";
  if (type === "integer") return typeof value === "number" && Number.isSafeInteger(value);
  return typeof value === "number" && Number.isFinite(value);
}

function throwSchema(): never {
  throw new Error("PI_MOBILE_EXTENSION_PACKAGE_TOOL_SCHEMA_INVALID");
}

function requireStringList(value: unknown, maximum: number): string[] {
  if (!Array.isArray(value) || value.length > maximum ||
      !value.every((item) => bounded(item, 1, 255))) {
    throw new Error("PI_MOBILE_EXTENSION_PACKAGE_LIST_INVALID");
  }
  return value as string[];
}

function requireExactKeys(value: Record<string, unknown>, expected: string[]): void {
  if (keys(value).join("\n") !== expected.join("\n")) {
    throw new Error("PI_MOBILE_EXTENSION_PACKAGE_TOOL_FIELDS_INVALID");
  }
}

function requireUnique(values: readonly string[], code: string): void {
  if (new Set(values).size !== values.length) throw new Error(code);
}

function bounded(value: unknown, minimum: number, maximum: number): value is string {
  return typeof value === "string" && value.length >= minimum && value.length <= maximum &&
    !value.includes("\u0000");
}

function isHttpsOrigin(value: string): boolean {
  return /^https:\/\/[A-Za-z0-9.-]+(?::[0-9]{1,5})?$/.test(value);
}

function safeJavaScriptEntrypoint(value: string): boolean {
  if (!value.startsWith("dist/") || !value.endsWith(".js") || value.includes("\\")) return false;
  const segments = value.split("/");
  return segments.length <= 12 && segments.every((segment) =>
    segment.length > 0 && segment.length <= 128 && segment !== "." && segment !== ".."
  );
}

function keys(value: Record<string, unknown>): string[] {
  return Object.keys(value).sort();
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

const CONNECTOR_UNAVAILABLE_PARAMETERS = {
  type: "object",
  properties: {
    action: { type: "string", enum: ["search", "describe", "call"] },
    query: { type: "string", minLength: 1, maxLength: 128 },
    tool: { type: "string", minLength: 1, maxLength: 80 },
    arguments: { type: "object" },
  },
  required: ["action"],
  additionalProperties: false,
} as AgentTool["parameters"];
