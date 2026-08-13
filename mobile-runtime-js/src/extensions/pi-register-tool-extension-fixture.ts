import type { AgentTool, AgentToolResult } from "@earendil-works/pi-agent-core";
import { MobileExtensionHost } from "./mobile-extension-host.js";
import {
  createPiRegisterToolExtensionDescriptor,
  PiRegisterToolExecutionError,
  requirePiRegisterToolPackageSnapshot,
  type PiRegisterToolPackageSnapshot,
  type PiRegisterToolActivity,
  type PiRegisterToolHttpExecution,
  type PiRegisterToolWorkerEvent,
  type PiRegisterToolWorkerTransport,
} from "./pi-register-tool-extension.js";

export async function piRegisterToolExtensionContract(): Promise<Record<string, unknown>> {
  const trace: string[] = [];
  const transportTrace: string[] = [];
  const activity: PiRegisterToolActivity[] = [];
  const updates: AgentToolResult<unknown>[] = [];
  const transport = new FixtureTransport([
    event(0, "update", {
      update: textResult("Preparing", { step: 1 }),
    }),
    event(1, "update", {
      update: textResult("Checking", { step: 2 }),
    }),
    event(2, "host_call", {
      name: "capabilities",
      arguments: {},
      childToolCallId: "outer-1:extension:2",
    }),
    event(3, "host_call", {
      name: "calendar",
      arguments: { query: "today" },
      childToolCallId: "outer-1:extension:3",
    }),
    event(4, "complete", {
      result: textResult("Two host calls completed", { count: 2 }),
    }),
  ], transportTrace);
  const productTools = [
    fixtureProductTool("device_capabilities_get", trace, false),
    fixtureProductTool("device_calendar", trace, true),
  ];
  const host = new MobileExtensionHost([
    createPiRegisterToolExtensionDescriptor(packageSnapshot(), productTools, transport, {
      onActivity: (event) => activity.push(event),
    }),
  ]);
  const tools = host.composeTools(productTools);
  const tool = tools.find((candidate) => candidate.name === "fixture_calendar_summary")!;
  const result = await tool.execute(
    "outer-1",
    { query: "today" },
    undefined,
    (update) => updates.push(update),
  );

  const protocolError = await captureAsyncError(async () => {
    const invalidTransport = new FixtureTransport([
      event(1, "complete", { result: textResult("late", null) }),
    ], []);
    const invalidHost = new MobileExtensionHost([
      createPiRegisterToolExtensionDescriptor(packageSnapshot(), productTools, invalidTransport),
    ]);
    await invalidHost.composeTools([])[0]!.execute("outer-2", { query: "today" });
  });

  const httpTrace: string[] = [];
  const httpTransport = new FixtureTransport([
    event(0, "http_call", {
      request: {
        url: "https://status.example.test/v1/status",
        method: "GET",
        headers: { accept: "application/json" },
        body: null,
        credentialSlot: "status-api",
      },
    }),
    event(1, "complete", { result: textResult("Status fetched", { ok: true }) }),
  ], httpTrace, async () => ({
    response: {
      status: 200,
      ok: true,
      url: "https://status.example.test/v1/status",
      headers: { "content-type": "application/json" },
      body: "{\"status\":\"ok\"}",
      bodyEncoding: "utf8",
      redirected: false,
    },
    audit: {
      method: "GET",
      origin: "https://status.example.test",
      status: 200,
      responseBytes: 15,
      durationMillis: 8,
      redirects: 0,
    },
  }));
  const httpActivity: PiRegisterToolActivity[] = [];
  const httpResult = await (async () => {
    const httpHost = new MobileExtensionHost([
      createPiRegisterToolExtensionDescriptor(httpPackageSnapshot(), productTools, httpTransport, {
        onActivity: (event) => httpActivity.push(event),
      }),
    ]);
    return await httpHost.composeTools([])[0]!.execute("outer-3", { query: "today" });
  })();

  let httpTimeoutAbortSeen = false;
  let httpTimeoutResumeError: string | undefined;
  const httpTimeoutTransport: PiRegisterToolWorkerTransport = {
    start: async () => event(0, "http_call", {
      request: {
        url: "https://status.example.test/v1/slow",
        method: "GET",
        headers: {},
        body: null,
        credentialSlot: null,
      },
    }),
    next: async () => { throw new Error("unexpected next"); },
    resume: async (request) => {
      httpTimeoutResumeError = request.errorCode;
      return event(1, "error", { code: "EXTENSION_PACKAGE_HOST_TIMEOUT" });
    },
    executeHttp: async (_request, signal) => await new Promise((_resolve, reject) => {
      signal?.addEventListener("abort", () => {
        httpTimeoutAbortSeen = true;
        reject(signal.reason);
      }, { once: true });
    }),
    authorizeHostCall: async () => undefined,
    waitForHostCallDeadline: async () => undefined,
    cancel: async () => undefined,
  };
  const httpTimeoutHost = new MobileExtensionHost([
    createPiRegisterToolExtensionDescriptor(
      httpPackageSnapshot(),
      productTools,
      httpTimeoutTransport,
    ),
  ]);
  const httpTimeoutError = await captureExecutionError(
    () => httpTimeoutHost.composeTools([])[0]!.execute("outer-http-timeout", { query: "today" }),
  );

  const workerError = await captureExecutionError(async () => {
    const errorTransport = new FixtureTransport([
      event(0, "error", { code: "EXTENSION_PACKAGE_FAILED", partialEffects: true }),
    ], []);
    const errorHost = new MobileExtensionHost([
      createPiRegisterToolExtensionDescriptor(packageSnapshot(), productTools, errorTransport),
    ]);
    await errorHost.composeTools([])[0]!.execute("outer-4", { query: "today" });
  });
  const transportAfterEffectError = await captureExecutionError(async () => {
    let index = 0;
    const transportAfterEffect: PiRegisterToolWorkerTransport = {
      start: async () => event(0, "host_call", {
        name: "calendar",
        arguments: { query: "today" },
        childToolCallId: "outer-effect:extension:0",
      }),
      next: async () => { throw new Error("unexpected next"); },
      resume: async () => {
        index += 1;
        throw new Error("transport disconnected");
      },
      authorizeHostCall: async () => undefined,
      waitForHostCallDeadline,
      cancel: async () => undefined,
    };
    const effectHost = new MobileExtensionHost([
      createPiRegisterToolExtensionDescriptor(
        packageSnapshot(),
        [
          fixtureProductTool("device_capabilities_get", [], false),
          fixtureProductTool("device_calendar", [], true),
        ],
        transportAfterEffect,
      ),
    ]);
    await effectHost.composeTools([])[0]!.execute("outer-effect", { query: "today" });
    if (index !== 1) throw new Error("resume missing");
  });
  let invalidResumeError: string | undefined;
  const invalidTrace: string[] = [];
  const invalidArgumentsError = await captureExecutionError(async () => {
    const invalidTransport: PiRegisterToolWorkerTransport = {
      start: async () => event(0, "host_call", {
        name: "calendar",
        arguments: { query: 42 },
        childToolCallId: "outer-invalid:extension:0",
      }),
      next: async () => { throw new Error("unexpected next"); },
      resume: async (request) => {
        invalidResumeError = request.errorCode;
        return event(1, "error", { code: "EXTENSION_PACKAGE_HOST_CALL_FAILED" });
      },
      authorizeHostCall: async () => undefined,
      waitForHostCallDeadline,
      cancel: async () => undefined,
    };
    const invalidHost = new MobileExtensionHost([
      createPiRegisterToolExtensionDescriptor(
        packageSnapshot(),
        [
          fixtureProductTool("device_capabilities_get", invalidTrace, false),
          fixtureProductTool("device_calendar", invalidTrace, true),
        ],
        invalidTransport,
      ),
    ]);
    await invalidHost.composeTools([])[0]!.execute("outer-invalid", { query: "today" });
  });

  let releaseStart: ((value: PiRegisterToolWorkerEvent) => void) | null = null;
  let cancelled = false;
  const stopTransport: PiRegisterToolWorkerTransport = {
    start: () => new Promise((resolve) => { releaseStart = resolve; }),
    next: async () => { throw new Error("unexpected next"); },
    resume: async () => { throw new Error("unexpected resume"); },
    authorizeHostCall: async () => undefined,
    waitForHostCallDeadline,
    cancel: async () => { cancelled = true; },
  };
  const stopHost = new MobileExtensionHost([
    createPiRegisterToolExtensionDescriptor(packageSnapshot(), productTools, stopTransport),
  ]);
  const controller = new AbortController();
  const stopped = stopHost.composeTools([])[0]!.execute(
    "outer-stop",
    { query: "today" },
    controller.signal,
    (update) => updates.push(update),
  );
  controller.abort();
  releaseStart!(event(0, "complete", { result: textResult("must not surface", null) }));
  const stopError = await captureAsyncError(() => stopped);

  const timeoutUpdates: AgentToolResult<unknown>[] = [];
  let timeoutAbortSeen = false;
  let timeoutResumeError: string | undefined;
  const timeoutTransport: PiRegisterToolWorkerTransport = {
    start: async () => event(0, "host_call", {
      name: "calendar",
      arguments: { query: "today" },
      childToolCallId: "outer-timeout:extension:0",
    }),
    next: async () => { throw new Error("unexpected next"); },
    resume: async (request) => {
      timeoutResumeError = request.errorCode;
      return event(1, "error", { code: "EXTENSION_PACKAGE_HOST_TIMEOUT" });
    },
    authorizeHostCall: async () => undefined,
    waitForHostCallDeadline: async () => undefined,
    cancel: async () => undefined,
  };
  const timeoutTarget = fixturePendingProductTool("device_calendar", {
    onAbort: () => { timeoutAbortSeen = true; },
  });
  const timeoutHost = new MobileExtensionHost([
    createPiRegisterToolExtensionDescriptor(
      packageSnapshot(),
      [fixtureProductTool("device_capabilities_get", [], false), timeoutTarget],
      timeoutTransport,
    ),
  ]);
  const timeoutError = await captureExecutionError(() => timeoutHost.composeTools([])[0]!.execute(
    "outer-timeout",
    { query: "today" },
    undefined,
    (update) => timeoutUpdates.push(update),
  ));

  const stopHostUpdates: AgentToolResult<unknown>[] = [];
  let stopHostAbortSeen = false;
  let stopHostCancelled = false;
  let markStopHostStarted: (() => void) | null = null;
  const stopHostStarted = new Promise<void>((resolve) => { markStopHostStarted = resolve; });
  const stopHostTransport: PiRegisterToolWorkerTransport = {
    start: async () => event(0, "host_call", {
      name: "calendar",
      arguments: { query: "today" },
      childToolCallId: "outer-host-stop:extension:0",
    }),
    next: async () => { throw new Error("unexpected next"); },
    resume: async () => { throw new Error("unexpected resume"); },
    authorizeHostCall: async () => undefined,
    waitForHostCallDeadline,
    cancel: async () => { stopHostCancelled = true; },
  };
  const stopHostController = new AbortController();
  const stopNestedHost = new MobileExtensionHost([
    createPiRegisterToolExtensionDescriptor(
      packageSnapshot(),
      [
        fixtureProductTool("device_capabilities_get", [], false),
        fixturePendingProductTool("device_calendar", {
          onStart: () => markStopHostStarted?.(),
          onAbort: () => { stopHostAbortSeen = true; },
        }),
      ],
      stopHostTransport,
    ),
  ]);
  const stoppedHostCall = stopNestedHost.composeTools([])[0]!.execute(
    "outer-host-stop",
    { query: "today" },
    stopHostController.signal,
    (update) => stopHostUpdates.push(update),
  );
  await stopHostStarted;
  stopHostController.abort(new Error("EXTENSION_PACKAGE_STOPPED"));
  const stopHostError = await captureExecutionError(() => stoppedHostCall);

  const revokedTargetTrace: string[] = [];
  let revokedResumeError: string | undefined;
  const revokedHostTransport: PiRegisterToolWorkerTransport = {
    start: async () => event(0, "host_call", {
      name: "calendar",
      arguments: { query: "today" },
      childToolCallId: "outer-revoked:extension:0",
    }),
    next: async () => { throw new Error("unexpected next"); },
    resume: async (request) => {
      revokedResumeError = request.errorCode;
      return event(1, "error", { code: "EXTENSION_PACKAGE_NOT_ENABLED" });
    },
    authorizeHostCall: async () => { throw new Error("EXTENSION_PACKAGE_NOT_ENABLED"); },
    waitForHostCallDeadline,
    cancel: async () => undefined,
  };
  const revokedHost = new MobileExtensionHost([
    createPiRegisterToolExtensionDescriptor(
      packageSnapshot(),
      [
        fixtureProductTool("device_capabilities_get", revokedTargetTrace, false),
        fixtureProductTool("device_calendar", revokedTargetTrace, true),
      ],
      revokedHostTransport,
    ),
  ]);
  const revokedHostError = await captureExecutionError(
    () => revokedHost.composeTools([])[0]!.execute("outer-revoked", { query: "today" }),
  );

  return {
    tool: {
      name: tool.name,
      label: tool.label,
      description: tool.description,
      parameters: tool.parameters,
      executionMode: tool.executionMode,
      promptSnippet: (tool as AgentTool & { promptSnippet?: string | null }).promptSnippet,
      promptGuidelines: (tool as AgentTool & { promptGuidelines?: string[] }).promptGuidelines,
    },
    updates,
    result,
    trace,
    activity,
    transportTrace: transport.trace,
    protocolError,
    protocolCancelled: protocolError !== null,
    httpResult,
    httpTrace,
    httpActivity,
    httpTimeoutError,
    httpTimeoutAbortSeen,
    httpTimeoutResumeError,
    workerError,
    transportAfterEffectError,
    invalidArgumentsError,
    invalidResumeError,
    invalidTargetCalls: invalidTrace.length,
    runtimeError: captureError(() => requirePiRegisterToolPackageSnapshot({
      ...packageSnapshot(),
      schemaVersion: 1,
    })),
    stopError,
    cancelled,
    timeoutError,
    timeoutAbortSeen,
    timeoutResumeError,
    timeoutLateUpdateCount: timeoutUpdates.length,
    stopHostError,
    stopHostAbortSeen,
    stopHostCancelled,
    stopHostLateUpdateCount: stopHostUpdates.length,
    revokedHostError,
    revokedResumeError,
    revokedTargetCalls: revokedTargetTrace.length,
  };
}

class FixtureTransport implements PiRegisterToolWorkerTransport {
  private index = 0;

  constructor(
    private readonly events: PiRegisterToolWorkerEvent[],
    readonly trace: string[],
    private readonly httpExecutor?: () => Promise<PiRegisterToolHttpExecution>,
  ) {}

  async start(): Promise<PiRegisterToolWorkerEvent> {
    this.trace.push("start");
    return this.take();
  }

  async next(request: { seq: number }): Promise<PiRegisterToolWorkerEvent> {
    this.trace.push(`next:${request.seq}`);
    return this.take();
  }

  async resume(request: {
    seq: number;
    result?: object;
    errorCode?: string;
  }): Promise<PiRegisterToolWorkerEvent> {
    this.trace.push(`resume:${request.seq}:${request.errorCode ?? resultKind(request.result)}`);
    return this.take();
  }

  async authorizeHostCall(request: { seq: number; name: string }): Promise<void> {
    this.trace.push(`authorize:${request.seq}:${request.name}`);
  }

  async executeHttp(): Promise<PiRegisterToolHttpExecution> {
    this.trace.push("http");
    if (this.httpExecutor === undefined) throw new Error("PI_MOBILE_EXTENSION_HTTP_UNAVAILABLE");
    return await this.httpExecutor();
  }

  async waitForHostCallDeadline(
    request: { timeoutMillis: number },
    signal?: AbortSignal,
  ): Promise<void> {
    await waitForHostCallDeadline(request, signal);
  }

  async cancel(): Promise<void> {
    this.trace.push("cancel");
  }

  private take(): PiRegisterToolWorkerEvent {
    const value = this.events[this.index];
    if (value === undefined) throw new Error("fixture event missing");
    this.index += 1;
    return value;
  }
}

function resultKind(result: object | undefined): string {
  if (result === undefined) return "missing";
  const content = (result as Record<string, unknown>).content;
  if (Array.isArray(content) && content[0] !== null && typeof content[0] === "object") {
    const type = (content[0] as Record<string, unknown>).type;
    if (typeof type === "string") return type;
  }
  return "result";
}

function httpPackageSnapshot(): PiRegisterToolPackageSnapshot {
  return {
    ...packageSnapshot(),
    httpPolicy: {
      origins: ["https://status.example.test"],
      methods: ["GET"],
      credentialSlots: [{
        slot: "status-api",
        origin: "https://status.example.test",
        placement: "authorization_bearer",
      }],
    },
  };
}

function packageSnapshot(): PiRegisterToolPackageSnapshot {
  return {
    schemaVersion: 2,
    id: "fixtures.host-call",
    name: "Sequential Host call fixture",
    version: "1.0.0",
    description: "A focused PXP-7B fixture.",
    runtime: "pi-register-tool-v1",
    entrypoint: "dist/index.js",
    tools: [{
      type: "pi-register-tool",
      name: "fixture_calendar_summary",
      label: "Fixture calendar summary",
      description: "Read capabilities and calendar through declared Host tools.",
      parameters: {
        type: "object",
        properties: { query: { type: "string" } },
        required: ["query"],
        additionalProperties: false,
      } as AgentTool["parameters"],
      promptSnippet: null,
      promptGuidelines: [],
      executionMode: "sequential",
    }],
    hostTools: [
      { name: "capabilities", targetTool: "device_capabilities_get", capability: null },
      { name: "calendar", targetTool: "device_calendar", capability: "calendar" },
    ],
    requiredCapabilities: ["calendar"],
    optionalCapabilities: [],
    httpPolicy: { origins: [], methods: [], credentialSlots: [] },
    packageDigest: "a".repeat(64),
  };
}

function fixtureProductTool(name: string, trace: string[], requiresQuery: boolean): AgentTool {
  return {
    name,
    label: name,
    description: name,
    parameters: {
      type: "object",
      properties: requiresQuery ? { query: { type: "string" } } : {},
      ...(requiresQuery ? { required: ["query"] } : {}),
      additionalProperties: false,
    } as AgentTool["parameters"],
    execute: async (toolCallId) => {
      trace.push(`${name}:${toolCallId}`);
      return textResult(name, { name, toolCallId });
    },
  };
}

function fixturePendingProductTool(
  name: string,
  callbacks: { onAbort: () => void; onStart?: () => void },
): AgentTool {
  return {
    name,
    label: name,
    description: name,
    parameters: {
      type: "object",
      properties: { query: { type: "string" } },
      required: ["query"],
      additionalProperties: false,
    } as AgentTool["parameters"],
    execute: async (_toolCallId, _parameters, signal, onUpdate) => {
      callbacks.onStart?.();
      return await new Promise<AgentToolResult<unknown>>((_resolve, reject) => {
        const onAbort = () => {
          callbacks.onAbort();
          onUpdate?.(textResult("late", { late: true }));
          reject(new Error("EXTENSION_PACKAGE_STOPPED"));
        };
        if (signal?.aborted === true) onAbort();
        else signal?.addEventListener("abort", onAbort, { once: true });
      });
    },
  };
}

async function waitForHostCallDeadline(
  request: { timeoutMillis: number },
  signal?: AbortSignal,
): Promise<void> {
  await new Promise<void>((resolve, reject) => {
    const timer = setTimeout(resolve, request.timeoutMillis);
    const abort = () => {
      clearTimeout(timer);
      reject(signal?.reason ?? new Error("EXTENSION_PACKAGE_STOPPED"));
    };
    if (signal?.aborted === true) abort();
    else signal?.addEventListener("abort", abort, { once: true });
  });
}

function event(
  seq: number,
  type: PiRegisterToolWorkerEvent["type"],
  value: Record<string, unknown>,
): PiRegisterToolWorkerEvent {
  return {
    invocationId: "fixture-invocation",
    generation: "fixture-generation",
    seq,
    type,
    ...value,
  } as PiRegisterToolWorkerEvent;
}

function textResult(text: string, details: unknown): AgentToolResult<unknown> {
  return { content: [{ type: "text", text }], details };
}

async function captureAsyncError(block: () => Promise<unknown>): Promise<string | null> {
  try {
    await block();
    return null;
  } catch (error) {
    return error instanceof Error ? error.message : String(error);
  }
}

async function captureExecutionError(
  block: () => Promise<unknown>,
): Promise<Record<string, unknown> | null> {
  try {
    await block();
    return null;
  } catch (error) {
    return {
      message: error instanceof Error ? error.message : String(error),
      typed: error instanceof PiRegisterToolExecutionError,
      partialEffects: error instanceof PiRegisterToolExecutionError
        ? error.partialEffects
        : null,
    };
  }
}

function captureError(block: () => unknown): string | null {
  try {
    block();
    return null;
  } catch (error) {
    return error instanceof Error ? error.message : String(error);
  }
}
