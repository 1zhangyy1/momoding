import type { AgentToolResult } from "@earendil-works/pi-agent-core";
import { sha256 } from "../sha256.js";
import type { NativeToolExecutor } from "../tools/android-tool-registry.js";
import type {
  PiRegisterToolHttpExecution,
  PiRegisterToolWorkerEvent,
  PiRegisterToolWorkerTransport,
} from "./pi-register-tool-extension.js";

const NATIVE_KIND = "android_extension_package";
const NATIVE_TOOL = "extension_package_execute_pi";

interface ActiveInvocation {
  generation: string;
  outerToolCallId: string;
  packageId: string;
  packageDigest: string;
}

export function createNativePiRegisterToolTransport(
  executeNativeTool: NativeToolExecutor,
): PiRegisterToolWorkerTransport {
  const active = new Map<string, ActiveInvocation>();

  const call = async (
    toolCallId: string,
    argumentsValue: Record<string, unknown>,
    signal?: AbortSignal,
  ): Promise<Record<string, unknown>> => {
    const result = await executeNativeTool(
      NATIVE_KIND,
      NATIVE_TOOL,
      toolCallId,
      argumentsValue,
      signal,
    );
    const envelope = nativeEnvelope(result);
    if (envelope.isError || envelope.value?.ok !== true) {
      throw new Error(
        typeof envelope.value?.errorCode === "string"
          ? envelope.value.errorCode
          : "EXTENSION_PACKAGE_WORKER_UNAVAILABLE",
      );
    }
    return envelope.value;
  };

  const requireActive = (invocationId: string, generation: string): ActiveInvocation => {
    const invocation = active.get(invocationId);
    if (invocation === undefined || invocation.generation !== generation) {
      throw new Error("EXTENSION_PACKAGE_WORKER_PROTOCOL_MISMATCH");
    }
    return invocation;
  };

  const acceptEvent = (
    value: Record<string, unknown>,
    prior?: ActiveInvocation,
  ): PiRegisterToolWorkerEvent => {
    const event = requireRecord(value.event, "EXTENSION_PACKAGE_WORKER_PROTOCOL_MISMATCH") as
      unknown as PiRegisterToolWorkerEvent;
    if (typeof event.invocationId !== "string" || typeof event.generation !== "string" ||
        !Number.isSafeInteger(event.seq) || event.seq < 0) {
      throw new Error("EXTENSION_PACKAGE_WORKER_PROTOCOL_MISMATCH");
    }
    const invocation = prior ?? active.get(event.invocationId);
    if (invocation === undefined || invocation.generation !== event.generation) {
      throw new Error("EXTENSION_PACKAGE_WORKER_PROTOCOL_MISMATCH");
    }
    if (event.type === "complete" || event.type === "error") active.delete(event.invocationId);
    return event;
  };

  return {
    start: async (request, signal) => {
      const value = await call(request.outerToolCallId, {
        action: "start",
        packageId: request.packageId,
        packageDigest: request.packageDigest,
        outerToolCallId: request.outerToolCallId,
        toolName: request.toolName,
        invocationArguments: request.arguments,
      }, signal);
      const event = requireRecord(value.event, "EXTENSION_PACKAGE_WORKER_PROTOCOL_MISMATCH");
      const invocationId = requireString(event.invocationId);
      const generation = requireString(event.generation);
      const invocation = {
        generation,
        outerToolCallId: request.outerToolCallId,
        packageId: request.packageId,
        packageDigest: request.packageDigest,
      };
      if (active.has(invocationId)) throw new Error("EXTENSION_PACKAGE_WORKER_PROTOCOL_MISMATCH");
      active.set(invocationId, invocation);
      return acceptEvent(value, invocation);
    },
    next: async (request, signal) => {
      const invocation = requireActive(request.invocationId, request.generation);
      return acceptEvent(await call(nativeCallId(invocation, "next", request.seq), {
        action: "next",
        ...request,
      }, signal));
    },
    resume: async (request, signal) => {
      const invocation = requireActive(request.invocationId, request.generation);
      return acceptEvent(await call(nativeCallId(invocation, "resume", request.seq), {
        action: "resume",
        ...request,
      }, signal));
    },
    authorizeHostCall: async (request, signal) => {
      const invocation = requireActive(request.invocationId, request.generation);
      if (request.packageId !== invocation.packageId ||
          request.packageDigest !== invocation.packageDigest) {
        throw new Error("EXTENSION_PACKAGE_WORKER_PROTOCOL_MISMATCH");
      }
      await call(nativeCallId(invocation, "authorize-host", request.seq), {
        action: "authorize_host_call",
        invocationId: request.invocationId,
        generation: request.generation,
        seq: request.seq,
        packageId: request.packageId,
        packageDigest: request.packageDigest,
        name: request.name,
        targetTool: request.targetTool,
        ...(request.capability === null ? {} : { capability: request.capability }),
      }, signal);
    },
    waitForHostCallDeadline: async (request, signal) => {
      const invocation = requireActive(request.invocationId, request.generation);
      const cancelArguments = {
        action: "cancel_deadline",
        invocationId: request.invocationId,
        generation: request.generation,
        seq: request.seq,
      };
      const abort = () => {
        void call(
          nativeCallId(invocation, "cancel-deadline", request.seq),
          cancelArguments,
        ).catch(() => undefined);
      };
      signal?.addEventListener("abort", abort, { once: true });
      try {
        await call(nativeCallId(invocation, "deadline", request.seq), {
          action: "await_deadline",
          ...request,
        }, signal);
      } finally {
        signal?.removeEventListener("abort", abort);
      }
    },
    executeHttp: async (request, signal) => {
      const invocation = requireActive(request.invocationId, request.generation);
      if (request.packageId !== invocation.packageId ||
          request.packageDigest !== invocation.packageDigest) {
        throw new Error("EXTENSION_PACKAGE_WORKER_PROTOCOL_MISMATCH");
      }
      const value = await call(nativeCallId(invocation, "http", request.seq), {
        action: "http",
        invocationId: request.invocationId,
        generation: request.generation,
        seq: request.seq,
        packageId: request.packageId,
        packageDigest: request.packageDigest,
        request: request.request,
      }, signal);
      return requireRecord(
        value.execution,
        "EXTENSION_PACKAGE_HTTP_FAILED",
      ) as unknown as PiRegisterToolHttpExecution;
    },
    cancel: async (request) => {
      const invocation = active.get(request.invocationId);
      if (invocation === undefined || invocation.generation !== request.generation) return;
      active.delete(request.invocationId);
      await call(nativeCallId(invocation, "cancel", 0), {
        action: "cancel",
        ...request,
      });
    },
  };
}

function nativeCallId(
  invocation: ActiveInvocation,
  action: string,
  seq: number,
): string {
  return `ext:${sha256(invocation.outerToolCallId).slice(0, 24)}:${action}:${seq}`;
}

function nativeEnvelope(result: AgentToolResult<unknown>): {
  isError: boolean;
  value: Record<string, unknown> | null;
} {
  const envelope = isRecord(result.details) ? result.details : null;
  const value = envelope !== null && isRecord(envelope.details)
    ? envelope.details
    : envelope;
  return { isError: envelope?.isError === true, value };
}

function requireRecord(value: unknown, code: string): Record<string, unknown> {
  if (!isRecord(value)) throw new Error(code);
  return value;
}

function requireString(value: unknown): string {
  if (typeof value !== "string" || value.length < 1 || value.length > 128) {
    throw new Error("EXTENSION_PACKAGE_WORKER_PROTOCOL_MISMATCH");
  }
  return value;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}
