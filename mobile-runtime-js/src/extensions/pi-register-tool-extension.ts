import type {
  AgentTool,
  AgentToolResult,
  AgentToolUpdateCallback,
} from "@earendil-works/pi-agent-core";
import { validateToolArguments } from "../pi-ai-compat.js";
import type { MobileExtensionDescriptor } from "./mobile-extension-host.js";
import {
  emitPiRegisterToolActivity,
  piRegisterHttpOrigin,
  requirePiRegisterHttpExecution,
  requirePiRegisterHttpPolicy,
  requirePiRegisterHttpRequest,
  type PiRegisterToolActivity,
  type PiRegisterToolExtensionOptions,
  type PiRegisterToolHttpAudit,
  type PiRegisterToolHttpExecution,
  type PiRegisterToolHttpPolicy,
  type PiRegisterToolHttpRequest,
  type PiRegisterToolHttpResponse,
} from "./pi-register-tool-http.js";
export type {
  PiRegisterToolActivity,
  PiRegisterToolExtensionOptions,
  PiRegisterToolHttpAudit,
  PiRegisterToolHttpExecution,
  PiRegisterToolHttpPolicy,
  PiRegisterToolHttpRequest,
  PiRegisterToolHttpResponse,
} from "./pi-register-tool-http.js";

const PI_REGISTER_TOOL_HOST_CAPABILITIES = new Map<string, string | null>([
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

const WORKER_PROTOCOL_ERROR = "EXTENSION_PACKAGE_WORKER_PROTOCOL_MISMATCH";
const HOST_CALL_TIMEOUT_MILLIS = 60_000;

export interface PiRegisterToolDeclaration {
  type: "pi-register-tool";
  name: string;
  label: string;
  description: string;
  parameters: AgentTool["parameters"];
  promptSnippet: string | null;
  promptGuidelines: string[];
  executionMode: "sequential";
}

export interface PiRegisterHostToolDeclaration {
  name: string;
  targetTool: string;
  capability: string | null;
}

export interface PiRegisterToolPackageSnapshot {
  schemaVersion: 2;
  id: string;
  name: string;
  version: string;
  description: string;
  runtime: "pi-register-tool-v1";
  entrypoint: string;
  tools: PiRegisterToolDeclaration[];
  hostTools: PiRegisterHostToolDeclaration[];
  requiredCapabilities: string[];
  optionalCapabilities: string[];
  httpPolicy: PiRegisterToolHttpPolicy;
  packageDigest: string;
}

export class PiRegisterToolExecutionError extends Error {
  constructor(
    readonly code: string,
    readonly partialEffects: boolean,
  ) {
    super(code);
    this.name = "PiRegisterToolExecutionError";
  }
}

export interface PiRegisterToolInvocationRef {
  invocationId: string;
  generation: string;
}

interface PiRegisterToolEventBase extends PiRegisterToolInvocationRef {
  seq: number;
}

export interface PiRegisterToolUpdateEvent extends PiRegisterToolEventBase {
  type: "update";
  update: AgentToolResult<unknown>;
}

export interface PiRegisterToolHostCallEvent extends PiRegisterToolEventBase {
  type: "host_call";
  name: string;
  arguments: Record<string, unknown>;
  childToolCallId: string;
}

export interface PiRegisterToolHttpCallEvent extends PiRegisterToolEventBase {
  type: "http_call";
  request: Record<string, unknown>;
}

export interface PiRegisterToolCompleteEvent extends PiRegisterToolEventBase {
  type: "complete";
  result: AgentToolResult<unknown>;
}

export interface PiRegisterToolErrorEvent extends PiRegisterToolEventBase {
  type: "error";
  code: string;
  partialEffects?: boolean;
}

export type PiRegisterToolWorkerEvent =
  | PiRegisterToolUpdateEvent
  | PiRegisterToolHostCallEvent
  | PiRegisterToolHttpCallEvent
  | PiRegisterToolCompleteEvent
  | PiRegisterToolErrorEvent;

export interface PiRegisterToolWorkerTransport {
  start(request: {
    packageId: string;
    packageDigest: string;
    outerToolCallId: string;
    toolName: string;
    arguments: Record<string, unknown>;
  }, signal?: AbortSignal): Promise<PiRegisterToolWorkerEvent>;
  next(request: PiRegisterToolInvocationRef & { seq: number }, signal?: AbortSignal):
    Promise<PiRegisterToolWorkerEvent>;
  resume(request: PiRegisterToolInvocationRef & {
    seq: number;
    result?: object;
    errorCode?: string;
  }, signal?: AbortSignal): Promise<PiRegisterToolWorkerEvent>;
  authorizeHostCall(request: PiRegisterToolInvocationRef & {
    seq: number;
    packageId: string;
    packageDigest: string;
    name: string;
    targetTool: string;
    capability: string | null;
  }, signal?: AbortSignal): Promise<void>;
  waitForHostCallDeadline(
    request: PiRegisterToolInvocationRef & { seq: number; timeoutMillis: number },
    signal?: AbortSignal,
  ): Promise<void>;
  executeHttp?(request: PiRegisterToolInvocationRef & {
    seq: number;
    packageId: string;
    packageDigest: string;
    httpPolicy: PiRegisterToolHttpPolicy;
    request: PiRegisterToolHttpRequest;
  }, signal?: AbortSignal): Promise<PiRegisterToolHttpExecution>;
  cancel(request: PiRegisterToolInvocationRef): Promise<void>;
}

export function createPiRegisterToolExtensionDescriptor(
  value: unknown,
  productTools: readonly AgentTool[],
  transport: PiRegisterToolWorkerTransport,
  options: PiRegisterToolExtensionOptions = {},
): MobileExtensionDescriptor {
  const extensionPackage = requirePiRegisterToolPackageSnapshot(value);
  const productToolByName = new Map(productTools.map((tool) => [tool.name, tool]));
  if (productToolByName.size !== productTools.length) {
    throw new Error("PI_MOBILE_EXTENSION_PRODUCT_TOOL_DUPLICATE");
  }
  const hostToolByName = new Map(extensionPackage.hostTools.map((tool) => [tool.name, tool]));
  for (const hostTool of extensionPackage.hostTools) {
    if (!productToolByName.has(hostTool.targetTool)) {
      throw new Error("PI_MOBILE_EXTENSION_HOST_CALL_TARGET_MISSING");
    }
  }
  return {
    id: `pkg.${extensionPackage.id}`,
    version: extensionPackage.version,
    source: "package",
    factory: (pi) => {
      for (const declaration of extensionPackage.tools) {
        pi.registerTool(createPackageTool(
          extensionPackage,
          declaration,
          hostToolByName,
          productToolByName,
          transport,
          options,
        ));
      }
    },
  };
}

export function requirePiRegisterToolPackageSnapshot(
  value: unknown,
): PiRegisterToolPackageSnapshot {
  const record = requireRecord(value, "PI_MOBILE_EXTENSION_V2_PACKAGE_INVALID");
  requireExactKeys(record, [
    "description", "entrypoint", "hostTools", "httpPolicy", "id", "name",
    "optionalCapabilities", "packageDigest", "requiredCapabilities", "runtime", "schemaVersion",
    "tools", "version",
  ], "PI_MOBILE_EXTENSION_V2_PACKAGE_INVALID");
  if (record.schemaVersion !== 2 || record.runtime !== "pi-register-tool-v1") {
    throw new Error("PI_MOBILE_EXTENSION_V2_RUNTIME_UNSUPPORTED");
  }
  const id = requirePatternString(
    record.id,
    /^[a-z][a-z0-9]*(?:[._-][a-z0-9]+)+$/,
    76,
    "PI_MOBILE_EXTENSION_V2_PACKAGE_INVALID",
  );
  const tools = requireArray(record.tools, 1, 16, "PI_MOBILE_EXTENSION_V2_TOOL_SET_INVALID")
    .map(requireToolDeclaration);
  requireUnique(tools.map((tool) => tool.name), "PI_MOBILE_EXTENSION_V2_TOOL_DUPLICATE");
  const requiredCapabilities = requireCapabilities(record.requiredCapabilities);
  const optionalCapabilities = requireCapabilities(record.optionalCapabilities);
  if (requiredCapabilities.some((capability) => optionalCapabilities.includes(capability))) {
    throw new Error("PI_MOBILE_EXTENSION_V2_CAPABILITY_DUPLICATE");
  }
  const hostTools = requireArray(record.hostTools, 0, 16, "PI_MOBILE_EXTENSION_V2_HOST_TOOL_SET_INVALID")
    .map((hostTool) => requireHostToolDeclaration(hostTool, requiredCapabilities));
  requireUnique(hostTools.map((tool) => tool.name), "PI_MOBILE_EXTENSION_V2_HOST_TOOL_DUPLICATE");
  return {
    schemaVersion: 2,
    id,
    name: requireString(record.name, 1, 80, "PI_MOBILE_EXTENSION_V2_PACKAGE_INVALID"),
    version: requirePatternString(
      record.version,
      /^[0-9]+\.[0-9]+\.[0-9]+(?:-[0-9A-Za-z.-]+)?$/,
      40,
      "PI_MOBILE_EXTENSION_V2_PACKAGE_INVALID",
    ),
    description: requireString(
      record.description,
      1,
      512,
      "PI_MOBILE_EXTENSION_V2_PACKAGE_INVALID",
    ),
    runtime: "pi-register-tool-v1",
    entrypoint: requireString(record.entrypoint, 1, 256, "PI_MOBILE_EXTENSION_V2_PACKAGE_INVALID"),
    tools,
    hostTools,
    requiredCapabilities,
    optionalCapabilities,
    httpPolicy: requirePiRegisterHttpPolicy(record.httpPolicy),
    packageDigest: requirePatternString(
      record.packageDigest,
      /^[0-9a-f]{64}$/,
      64,
      "PI_MOBILE_EXTENSION_V2_PACKAGE_INVALID",
    ),
  };
}

function createPackageTool(
  extensionPackage: PiRegisterToolPackageSnapshot,
  declaration: PiRegisterToolDeclaration,
  hostToolByName: ReadonlyMap<string, PiRegisterHostToolDeclaration>,
  productToolByName: ReadonlyMap<string, AgentTool>,
  transport: PiRegisterToolWorkerTransport,
  options: PiRegisterToolExtensionOptions,
): AgentTool {
  return {
    name: declaration.name,
    label: declaration.label,
    description: declaration.description,
    parameters: declaration.parameters,
    executionMode: declaration.executionMode,
    promptSnippet: declaration.promptSnippet,
    promptGuidelines: [...declaration.promptGuidelines],
    execute: async (outerToolCallId, params, signal, onUpdate) => {
      requireNotAborted(signal);
      let invocation: PiRegisterToolInvocationRef | null = null;
      let expectedSeq = 0;
      let terminal = false;
      let cancelRequested = false;
      let externalCallStarted = false;
      const cancel = async () => {
        if (invocation === null || terminal || cancelRequested) return;
        cancelRequested = true;
        try {
          await transport.cancel(invocation);
        } catch {
          // Cancellation is a terminal fence. Never mask the original tool error
          // or create an unhandled rejection from an AbortSignal listener.
        }
      };
      const abortListener = () => { void cancel(); };
      signal?.addEventListener("abort", abortListener, { once: true });
      try {
        let event = requireEvent(await transport.start({
          packageId: extensionPackage.id,
          packageDigest: extensionPackage.packageDigest,
          outerToolCallId,
          toolName: declaration.name,
          arguments: requireArguments(params),
        }, signal));
        invocation = { invocationId: event.invocationId, generation: event.generation };
        while (true) {
          requireNotAborted(signal);
          requireEventIdentity(event, invocation, expectedSeq);
          expectedSeq += 1;
          if (event.type === "update") {
            requireUpdate(event.update);
            onUpdate?.(event.update);
            event = requireEvent(await transport.next({ ...invocation, seq: event.seq }, signal));
            continue;
          }
          if (event.type === "host_call") {
            const hostDeclaration = hostToolByName.get(event.name);
            if (hostDeclaration === undefined) {
              throw new Error("PI_MOBILE_EXTENSION_HOST_CALL_NOT_DECLARED");
            }
            const target = productToolByName.get(hostDeclaration.targetTool);
            if (target === undefined) {
              throw new Error("PI_MOBILE_EXTENSION_HOST_CALL_TARGET_MISSING");
            }
            requireChildToolCallId(event.childToolCallId);
            let result: AgentToolResult<unknown> | undefined;
            let errorCode: string | undefined;
            try {
              await transport.authorizeHostCall({
                ...invocation,
                seq: event.seq,
                packageId: extensionPackage.id,
                packageDigest: extensionPackage.packageDigest,
                name: hostDeclaration.name,
                targetTool: hostDeclaration.targetTool,
                capability: hostDeclaration.capability,
              }, signal);
              requireNotAborted(signal);
              emitPiRegisterToolActivity(options, {
                kind: "host_tool",
                phase: "started",
                toolCallId: outerToolCallId,
                seq: event.seq,
                packageId: extensionPackage.id,
                name: hostDeclaration.name,
                targetTool: hostDeclaration.targetTool,
              });
              const preparedArguments = target.prepareArguments === undefined
                ? event.arguments
                : target.prepareArguments(event.arguments);
              const validatedArguments = validateToolArguments(target, {
                type: "toolCall",
                id: event.childToolCallId,
                name: target.name,
                arguments: requireArguments(preparedArguments),
              }) as Record<string, unknown>;
              result = await executeHostToolWithDeadline({
                event,
                invocation,
                onStarted: () => { externalCallStarted = true; },
                onUpdate,
                outerSignal: signal,
                parameters: validatedArguments,
                target,
                transport,
              });
              requireResult(result, "PI_MOBILE_EXTENSION_HOST_RESULT_INVALID");
              emitPiRegisterToolActivity(options, {
                kind: "host_tool",
                phase: "completed",
                toolCallId: outerToolCallId,
                seq: event.seq,
                packageId: extensionPackage.id,
                name: hostDeclaration.name,
                targetTool: hostDeclaration.targetTool,
              });
            } catch (error) {
              errorCode = safeErrorCode(error, "PI_MOBILE_EXTENSION_HOST_CALL_FAILED");
              emitPiRegisterToolActivity(options, {
                kind: "host_tool",
                phase: "failed",
                toolCallId: outerToolCallId,
                seq: event.seq,
                packageId: extensionPackage.id,
                name: hostDeclaration.name,
                targetTool: hostDeclaration.targetTool,
                code: errorCode,
              });
            }
            requireNotAborted(signal);
            event = requireEvent(await transport.resume({
              ...invocation,
              seq: event.seq,
              ...(result === undefined ? {} : { result }),
              ...(errorCode === undefined ? {} : { errorCode }),
            }, signal));
            continue;
          }
          if (event.type === "http_call") {
            const currentInvocation = invocation;
            if (currentInvocation === null) throw new Error(WORKER_PROTOCOL_ERROR);
            const request = requirePiRegisterHttpRequest(event.request, extensionPackage.httpPolicy);
            const executeHttp = transport.executeHttp;
            if (executeHttp === undefined) throw new Error("PI_MOBILE_EXTENSION_HTTP_UNAVAILABLE");
            let result: PiRegisterToolHttpResponse | undefined;
            let errorCode: string | undefined;
            emitPiRegisterToolActivity(options, {
              kind: "https",
              phase: "started",
              toolCallId: outerToolCallId,
              seq: event.seq,
              packageId: extensionPackage.id,
              method: request.method,
              origin: piRegisterHttpOrigin(request.url),
            });
            try {
              const execution = requirePiRegisterHttpExecution(await executeHttpWithDeadline({
                event,
                invocation: currentInvocation,
                onStarted: () => { externalCallStarted = true; },
                outerSignal: signal,
                run: (childSignal) => executeHttp.call(transport, {
                  ...currentInvocation,
                  seq: event.seq,
                  packageId: extensionPackage.id,
                  packageDigest: extensionPackage.packageDigest,
                  httpPolicy: extensionPackage.httpPolicy,
                  request,
                }, childSignal),
                transport,
              }), request, extensionPackage.httpPolicy);
              result = execution.response;
              emitPiRegisterToolActivity(options, {
                kind: "https",
                phase: "completed",
                toolCallId: outerToolCallId,
                seq: event.seq,
                packageId: extensionPackage.id,
                ...execution.audit,
              });
            } catch (error) {
              errorCode = safeErrorCode(error, "PI_MOBILE_EXTENSION_HTTP_FAILED");
              emitPiRegisterToolActivity(options, {
                kind: "https",
                phase: "failed",
                toolCallId: outerToolCallId,
                seq: event.seq,
                packageId: extensionPackage.id,
                method: request.method,
                origin: piRegisterHttpOrigin(request.url),
                code: errorCode,
              });
            }
            requireNotAborted(signal);
            event = requireEvent(await transport.resume({
              ...invocation,
              seq: event.seq,
              ...(result === undefined ? {} : { result }),
              ...(errorCode === undefined ? {} : { errorCode }),
            }, signal));
            continue;
          }
          terminal = true;
          if (event.type === "error") {
            throw new PiRegisterToolExecutionError(
              requireErrorCode(event.code),
              event.partialEffects === true,
            );
          }
          return requireResult(event.result, "PI_MOBILE_EXTENSION_RESULT_INVALID");
        }
      } catch (error) {
        if (error instanceof PiRegisterToolExecutionError) {
          if (!externalCallStarted || error.partialEffects) throw error;
          throw new PiRegisterToolExecutionError(error.code, true);
        }
        throw new PiRegisterToolExecutionError(
          safeErrorCode(error, "EXTENSION_PACKAGE_WORKER_UNAVAILABLE"),
          externalCallStarted,
        );
      } finally {
        signal?.removeEventListener("abort", abortListener);
        if (!terminal) await cancel();
      }
    },
  } as AgentTool;
}

async function executeHostToolWithDeadline(options: {
  event: PiRegisterToolHostCallEvent;
  invocation: PiRegisterToolInvocationRef;
  onStarted: () => void;
  onUpdate: AgentToolUpdateCallback<unknown> | undefined;
  outerSignal: AbortSignal | undefined;
  parameters: Record<string, unknown>;
  target: AgentTool;
  transport: PiRegisterToolWorkerTransport;
}): Promise<AgentToolResult<unknown>> {
  requireNotAborted(options.outerSignal);
  const executionController = new AbortController();
  const deadlineController = new AbortController();
  let active = true;
  const abort = () => {
    active = false;
    const reason = options.outerSignal?.reason ?? new Error("EXTENSION_PACKAGE_STOPPED");
    executionController.abort(reason);
    deadlineController.abort(reason);
  };
  if (options.outerSignal?.aborted === true) abort();
  else options.outerSignal?.addEventListener("abort", abort, { once: true });
  try {
    const targetResult = Promise.resolve().then(() => {
      if (executionController.signal.aborted) throw new Error("EXTENSION_PACKAGE_STOPPED");
      options.onStarted();
      return options.target.execute(
        options.event.childToolCallId,
        options.parameters,
        executionController.signal,
        forwardHostUpdate(options.onUpdate, () => active),
      );
    });
    const deadline = options.transport.waitForHostCallDeadline({
      ...options.invocation,
      seq: options.event.seq,
      timeoutMillis: HOST_CALL_TIMEOUT_MILLIS,
    }, deadlineController.signal).then(
      () => terminateHostTool(new Error("EXTENSION_PACKAGE_HOST_TIMEOUT")),
      (error: unknown) => terminateHostTool(error),
    );
    return await Promise.race([targetResult, deadline]);
  } finally {
    active = false;
    deadlineController.abort(new Error("EXTENSION_PACKAGE_HOST_CALL_FINISHED"));
    options.outerSignal?.removeEventListener("abort", abort);
  }

  function terminateHostTool(error: unknown): never {
    if (active) {
      active = false;
      executionController.abort(error);
    }
    throw error;
  }
}

async function executeHttpWithDeadline(options: {
  event: PiRegisterToolHttpCallEvent;
  invocation: PiRegisterToolInvocationRef;
  onStarted: () => void;
  outerSignal: AbortSignal | undefined;
  run: (signal: AbortSignal) => Promise<PiRegisterToolHttpExecution>;
  transport: PiRegisterToolWorkerTransport;
}): Promise<PiRegisterToolHttpExecution> {
  requireNotAborted(options.outerSignal);
  const executionController = new AbortController();
  const deadlineController = new AbortController();
  let active = true;
  const abort = () => {
    active = false;
    const reason = options.outerSignal?.reason ?? new Error("EXTENSION_PACKAGE_STOPPED");
    executionController.abort(reason);
    deadlineController.abort(reason);
  };
  if (options.outerSignal?.aborted === true) abort();
  else options.outerSignal?.addEventListener("abort", abort, { once: true });
  try {
    const execution = Promise.resolve().then(() => {
      if (executionController.signal.aborted) throw new Error("EXTENSION_PACKAGE_STOPPED");
      options.onStarted();
      return options.run(executionController.signal);
    });
    const deadline = options.transport.waitForHostCallDeadline({
      ...options.invocation,
      seq: options.event.seq,
      timeoutMillis: HOST_CALL_TIMEOUT_MILLIS,
    }, deadlineController.signal).then(
      () => terminateHttp(new Error("EXTENSION_PACKAGE_HOST_TIMEOUT")),
      (error: unknown) => terminateHttp(error),
    );
    return await Promise.race([execution, deadline]);
  } finally {
    active = false;
    deadlineController.abort(new Error("EXTENSION_PACKAGE_HTTP_FINISHED"));
    options.outerSignal?.removeEventListener("abort", abort);
  }

  function terminateHttp(error: unknown): never {
    if (active) {
      active = false;
      executionController.abort(error);
    }
    throw error;
  }
}

function forwardHostUpdate(
  onUpdate: AgentToolUpdateCallback<unknown> | undefined,
  isActive: () => boolean,
): AgentToolUpdateCallback<unknown> | undefined {
  if (onUpdate === undefined) return undefined;
  return (update) => {
    if (!isActive()) return;
    onUpdate(requireResult(update, "PI_MOBILE_EXTENSION_HOST_UPDATE_INVALID"));
  };
}

function requireEvent(value: unknown): PiRegisterToolWorkerEvent {
  const event = requireRecord(value, WORKER_PROTOCOL_ERROR);
  const base = {
    invocationId: requireString(
      event.invocationId,
      1,
      128,
      WORKER_PROTOCOL_ERROR,
    ),
    generation: requireString(
      event.generation,
      1,
      128,
      WORKER_PROTOCOL_ERROR,
    ),
    seq: requireInteger(event.seq, 0, Number.MAX_SAFE_INTEGER, WORKER_PROTOCOL_ERROR),
  };
  if (event.type === "update") {
    requireExactKeys(event, ["generation", "invocationId", "seq", "type", "update"],
      WORKER_PROTOCOL_ERROR);
    return { ...base, type: "update", update: requireUpdate(event.update) };
  }
  if (event.type === "host_call") {
    requireExactKeys(event, [
      "arguments", "childToolCallId", "generation", "invocationId", "name", "seq", "type",
    ], WORKER_PROTOCOL_ERROR);
    return {
      ...base,
      type: "host_call",
      name: requirePatternString(
        event.name,
        /^[a-z][a-z0-9_]{0,63}$/,
        64,
        WORKER_PROTOCOL_ERROR,
      ),
      arguments: requireArguments(event.arguments),
      childToolCallId: requireChildToolCallId(event.childToolCallId),
    };
  }
  if (event.type === "http_call") {
    requireExactKeys(event, ["generation", "invocationId", "request", "seq", "type"],
      WORKER_PROTOCOL_ERROR);
    return { ...base, type: "http_call", request: requireRecord(
      event.request,
      WORKER_PROTOCOL_ERROR,
    ) };
  }
  if (event.type === "complete") {
    requireExactKeys(event, ["generation", "invocationId", "result", "seq", "type"],
      WORKER_PROTOCOL_ERROR);
    return {
      ...base,
      type: "complete",
      result: requireResult(event.result, "PI_MOBILE_EXTENSION_RESULT_INVALID"),
    };
  }
  if (event.type === "error") {
    if (event.partialEffects !== undefined && typeof event.partialEffects !== "boolean") {
      throw new Error(WORKER_PROTOCOL_ERROR);
    }
    requireExactKeys(
      event,
      event.partialEffects === undefined
        ? ["code", "generation", "invocationId", "seq", "type"]
        : ["code", "generation", "invocationId", "partialEffects", "seq", "type"],
      WORKER_PROTOCOL_ERROR,
    );
    return {
      ...base,
      type: "error",
      code: requireErrorCode(event.code),
      ...(event.partialEffects === true ? { partialEffects: true } : {}),
    };
  }
  throw new Error(WORKER_PROTOCOL_ERROR);
}

function requireEventIdentity(
  event: PiRegisterToolWorkerEvent,
  invocation: PiRegisterToolInvocationRef,
  expectedSeq: number,
): void {
  if (event.invocationId !== invocation.invocationId ||
      event.generation !== invocation.generation ||
      event.seq !== expectedSeq) {
    throw new Error(WORKER_PROTOCOL_ERROR);
  }
}

function requireToolDeclaration(value: unknown): PiRegisterToolDeclaration {
  const record = requireRecord(value, "PI_MOBILE_EXTENSION_V2_TOOL_INVALID");
  requireExactKeys(record, [
    "description", "executionMode", "label", "name", "parameters", "promptGuidelines",
    "promptSnippet", "type",
  ], "PI_MOBILE_EXTENSION_V2_TOOL_INVALID");
  if (record.type !== "pi-register-tool" || record.executionMode !== "sequential") {
    throw new Error("PI_MOBILE_EXTENSION_V2_TOOL_INVALID");
  }
  if (record.promptSnippet !== null && typeof record.promptSnippet !== "string") {
    throw new Error("PI_MOBILE_EXTENSION_V2_TOOL_INVALID");
  }
  const promptGuidelines = requireArray(
    record.promptGuidelines,
    0,
    16,
    "PI_MOBILE_EXTENSION_V2_TOOL_INVALID",
  ).map((value) => requireString(value, 1, 512, "PI_MOBILE_EXTENSION_V2_TOOL_INVALID"));
  const parameters = requireRecord(record.parameters, "PI_MOBILE_EXTENSION_V2_TOOL_INVALID");
  if (parameters.type !== "object" || parameters.additionalProperties !== false) {
    throw new Error("PI_MOBILE_EXTENSION_V2_TOOL_INVALID");
  }
  return {
    type: "pi-register-tool",
    name: requirePatternString(
      record.name,
      /^[a-z][a-z0-9_]{0,63}$/,
      64,
      "PI_MOBILE_EXTENSION_V2_TOOL_INVALID",
    ),
    label: requireString(record.label, 1, 80, "PI_MOBILE_EXTENSION_V2_TOOL_INVALID"),
    description: requireString(record.description, 1, 512, "PI_MOBILE_EXTENSION_V2_TOOL_INVALID"),
    parameters: parameters as unknown as AgentTool["parameters"],
    promptSnippet: record.promptSnippet === null
      ? null
      : requireString(record.promptSnippet, 1, 512, "PI_MOBILE_EXTENSION_V2_TOOL_INVALID"),
    promptGuidelines,
    executionMode: "sequential",
  };
}

function requireHostToolDeclaration(
  value: unknown,
  requiredCapabilities: readonly string[],
): PiRegisterHostToolDeclaration {
  const record = requireRecord(value, "PI_MOBILE_EXTENSION_V2_HOST_TOOL_INVALID");
  requireExactKeys(
    record,
    ["capability", "name", "targetTool"],
    "PI_MOBILE_EXTENSION_V2_HOST_TOOL_INVALID",
  );
  const targetTool = requirePatternString(
    record.targetTool,
    /^[a-z][a-z0-9_]{0,63}$/,
    64,
    "PI_MOBILE_EXTENSION_V2_HOST_TOOL_INVALID",
  );
  if (!PI_REGISTER_TOOL_HOST_CAPABILITIES.has(targetTool)) {
    throw new Error("PI_MOBILE_EXTENSION_V2_HOST_TOOL_INVALID");
  }
  const expectedCapability = PI_REGISTER_TOOL_HOST_CAPABILITIES.get(targetTool) ?? null;
  const capability = record.capability === null
    ? null
    : requirePatternString(
        record.capability,
        /^[a-z][a-z0-9_]{0,63}$/,
        64,
        "PI_MOBILE_EXTENSION_V2_HOST_TOOL_INVALID",
      );
  if (capability !== expectedCapability ||
      (capability !== null && !requiredCapabilities.includes(capability))) {
    throw new Error("PI_MOBILE_EXTENSION_V2_HOST_TOOL_INVALID");
  }
  return {
    name: requirePatternString(
      record.name,
      /^[a-z][a-z0-9_]{0,63}$/,
      64,
      "PI_MOBILE_EXTENSION_V2_HOST_TOOL_INVALID",
    ),
    targetTool,
    capability,
  };
}

function requireCapabilities(value: unknown): string[] {
  const capabilities = requireArray(
    value,
    0,
    16,
    "PI_MOBILE_EXTENSION_V2_CAPABILITY_SET_INVALID",
  ).map((item) => requirePatternString(
    item,
    /^[a-z][a-z0-9_]{0,63}$/,
    64,
    "PI_MOBILE_EXTENSION_V2_CAPABILITY_SET_INVALID",
  ));
  requireUnique(capabilities, "PI_MOBILE_EXTENSION_V2_CAPABILITY_DUPLICATE");
  return capabilities;
}

function requireResult<T>(
  value: unknown,
  code: string,
  maximumBytes = 32_768,
): AgentToolResult<T> {
  const result = requireRecord(value, code);
  if (!Array.isArray(result.content) || result.content.length < 1 || result.content.length > 16) {
    throw new Error(code);
  }
  const content = result.content.map((item) => {
    const record = requireRecord(item, code);
    requireExactKeys(record, ["text", "type"], code);
    if (record.type !== "text") throw new Error(code);
    return { type: "text" as const, text: requireString(record.text, 0, 32_768, code) };
  });
  if (!isJsonValue(result.details)) throw new Error(code);
  if (result.terminate !== undefined && typeof result.terminate !== "boolean") throw new Error(code);
  requireExactKeys(
    result,
    result.terminate === undefined ? ["content", "details"] : ["content", "details", "terminate"],
    code,
  );
  const normalized = {
    content,
    details: result.details as T,
    ...(result.terminate === true ? { terminate: true } : {}),
  };
  if (jsonByteLength(normalized) > maximumBytes) throw new Error(code);
  return normalized;
}

function requireUpdate(value: unknown): AgentToolResult<unknown> {
  return requireResult(value, "PI_MOBILE_EXTENSION_UPDATE_INVALID", 8_192);
}

function requireArguments(value: unknown): Record<string, unknown> {
  const argumentsValue = requireRecord(value, "PI_MOBILE_EXTENSION_ARGUMENTS_INVALID");
  if (!isJsonValue(argumentsValue) || jsonByteLength(argumentsValue) > 65_536) {
    throw new Error("PI_MOBILE_EXTENSION_ARGUMENTS_INVALID");
  }
  return argumentsValue;
}

function requireChildToolCallId(value: unknown): string {
  return requirePatternString(
    value,
    /^[A-Za-z0-9._:-]+$/,
    160,
    WORKER_PROTOCOL_ERROR,
  );
}

function requireErrorCode(value: unknown): string {
  return requirePatternString(
    value,
    /^[A-Z][A-Z0-9_]{2,127}$/,
    128,
    WORKER_PROTOCOL_ERROR,
  );
}

function requireNotAborted(signal: AbortSignal | undefined): void {
  if (signal?.aborted === true) throw new Error("EXTENSION_PACKAGE_STOPPED");
}

function safeErrorCode(error: unknown, fallback: string): string {
  if (error instanceof Error && /^[A-Z][A-Z0-9_]{2,127}$/.test(error.message)) {
    return error.message;
  }
  return fallback;
}

function requireRecord(value: unknown, code: string): Record<string, unknown> {
  if (value === null || typeof value !== "object" || Array.isArray(value)) throw new Error(code);
  return value as Record<string, unknown>;
}

function requireArray(
  value: unknown,
  minimum: number,
  maximum: number,
  code: string,
): unknown[] {
  if (!Array.isArray(value) || value.length < minimum || value.length > maximum) {
    throw new Error(code);
  }
  return value;
}

function requireString(value: unknown, minimum: number, maximum: number, code: string): string {
  if (typeof value !== "string" || value.length < minimum || value.length > maximum || value.includes("\0")) {
    throw new Error(code);
  }
  return value;
}

function requirePatternString(
  value: unknown,
  pattern: RegExp,
  maximum: number,
  code: string,
): string {
  const text = requireString(value, 1, maximum, code);
  if (!pattern.test(text)) throw new Error(code);
  return text;
}

function requireInteger(
  value: unknown,
  minimum: number,
  maximum: number,
  code: string,
): number {
  if (typeof value !== "number" || !Number.isSafeInteger(value) || value < minimum || value > maximum) {
    throw new Error(code);
  }
  return value;
}

function requireExactKeys(record: Record<string, unknown>, expected: string[], code: string): void {
  const actual = Object.keys(record).sort();
  const wanted = [...expected].sort();
  if (actual.length !== wanted.length || actual.some((key, index) => key !== wanted[index])) {
    throw new Error(code);
  }
}

function requireUnique(values: string[], code: string): void {
  if (new Set(values).size !== values.length) throw new Error(code);
}

function isJsonValue(value: unknown, depth = 0): boolean {
  if (depth > 12) return false;
  if (value === null || typeof value === "string" || typeof value === "boolean") return true;
  if (typeof value === "number") return Number.isFinite(value);
  if (Array.isArray(value)) return value.length <= 256 && value.every((item) => isJsonValue(item, depth + 1));
  if (typeof value !== "object") return false;
  const entries = Object.entries(value as Record<string, unknown>);
  return entries.length <= 256 && entries.every(([key, item]) =>
    key.length <= 256 && !key.includes("\0") && isJsonValue(item, depth + 1)
  );
}

function jsonByteLength(value: unknown): number {
  const encoded = JSON.stringify(value);
  if (encoded === undefined) return Number.POSITIVE_INFINITY;
  let bytes = 0;
  for (let index = 0; index < encoded.length; index += 1) {
    const code = encoded.charCodeAt(index);
    if (code <= 0x7f) bytes += 1;
    else if (code <= 0x7ff) bytes += 2;
    else if (code >= 0xd800 && code <= 0xdbff &&
        encoded.charCodeAt(index + 1) >= 0xdc00 && encoded.charCodeAt(index + 1) <= 0xdfff) {
      bytes += 4;
      index += 1;
    } else bytes += 3;
  }
  return bytes;
}
