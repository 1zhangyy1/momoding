import {
  AgentHarness,
  InMemorySessionStorage,
  Session,
  type AgentHarnessEvent,
  type AgentTool,
  type AgentToolResult,
  type ExecutionEnv,
} from "@earendil-works/pi-agent-core";
import {
  fauxAssistantMessage,
  fauxProvider,
  fauxToolCall,
  type AssistantMessage,
  type Models,
  type Provider,
} from "@earendil-works/pi-ai";

export type FakeScenarioKind =
  | "tool_success"
  | "tool_error"
  | "provider_error"
  | "stop_before_tool";

interface NativeToolRequest {
  id: string;
  kind: "mock_tool";
  toolCallId: string;
  toolName: string;
  arguments: Record<string, unknown>;
}

interface PendingNativeToolRequest {
  request: NativeToolRequest;
  resolve: (value: AgentToolResult<unknown>) => void;
  reject: (reason: unknown) => void;
  signal?: AbortSignal;
  abortListener?: () => void;
}

interface ScenarioState {
  kind: FakeScenarioKind;
  harness: AgentHarness;
  unsubscribe: () => void;
  phase: string;
  terminal: boolean;
  promptSettled: boolean;
  stopRequested: boolean;
  stopCompleted: boolean;
  promptError: string | null;
  stopError: string | null;
  finalText: string | null;
  events: unknown[];
  eventTypes: string[];
  nativeOutbox: NativeToolRequest[];
  pendingNative: Map<string, PendingNativeToolRequest>;
  nextRequestId: number;
  toolRequestsIssued: number;
  toolRequestsResolved: number;
  toolRequestsRejected: number;
  toolExecutionsStarted: number;
  toolExecutionsEnded: number;
  toolErrors: number;
  lateToolStartsAfterStop: number;
  releaseProvider: ((message: AssistantMessage) => void) | null;
}

const SCENARIO_TOOL_NAME = "mobile_fixture_echo";
const RECORDED_EVENT_TYPES = new Set([
  "agent_start",
  "agent_end",
  "turn_start",
  "turn_end",
  "message_start",
  "message_update",
  "message_end",
  "tool_execution_start",
  "tool_execution_update",
  "tool_execution_end",
  "queue_update",
  "abort",
  "settled",
]);

let scenarioState: ScenarioState | null = null;

export function startFakeScenario(kind: FakeScenarioKind, env: ExecutionEnv): Record<string, unknown> {
  if (scenarioState !== null && !scenarioState.terminal) {
    throw new Error("PI_MOBILE_SCENARIO_ALREADY_RUNNING");
  }
  scenarioState?.unsubscribe();

  const faux = fauxProvider({
    api: "phone-local-faux",
    provider: "phone-local-faux",
    models: [{
      id: "phone-local-faux-1",
      name: "Phone-local Faux",
      reasoning: false,
      input: ["text"],
      contextWindow: 4096,
      maxTokens: 256,
    }],
  });
  const model = faux.getModel();
  const models = modelsForProvider(faux.provider);
  const session = new Session(
    new InMemorySessionStorage({
      metadata: {
        id: `phone-local-l0-${kind}`,
        createdAt: "1970-01-01T00:00:00.000Z",
      },
    }),
  );

  let state: ScenarioState;
  const tool: AgentTool = {
    name: SCENARIO_TOOL_NAME,
    label: "Mobile fixture echo",
    description: "Returns a deterministic Android mock result.",
    parameters: {
      type: "object",
      properties: {
        text: { type: "string", minLength: 1, maxLength: 128 },
      },
      required: ["text"],
      additionalProperties: false,
    } as AgentTool["parameters"],
    executionMode: "sequential",
    execute: async (toolCallId, params, signal) =>
      await requestNativeTool(state, toolCallId, params as Record<string, unknown>, signal),
  };
  const harness = new AgentHarness({
    env,
    session,
    models,
    model,
    tools: [tool],
    activeToolNames: [SCENARIO_TOOL_NAME],
    systemPrompt: "Phone-local Pi L0 deterministic fake-provider gate",
  });
  state = {
    kind,
    harness,
    unsubscribe: () => undefined,
    phase: "starting",
    terminal: false,
    promptSettled: false,
    stopRequested: false,
    stopCompleted: false,
    promptError: null,
    stopError: null,
    finalText: null,
    events: [],
    eventTypes: [],
    nativeOutbox: [],
    pendingNative: new Map(),
    nextRequestId: 1,
    toolRequestsIssued: 0,
    toolRequestsResolved: 0,
    toolRequestsRejected: 0,
    toolExecutionsStarted: 0,
    toolExecutionsEnded: 0,
    toolErrors: 0,
    lateToolStartsAfterStop: 0,
    releaseProvider: null,
  };
  state.unsubscribe = harness.subscribe((event) => recordEvent(state, event));
  scenarioState = state;

  switch (kind) {
    case "tool_success":
    case "tool_error":
      faux.setResponses([
        fauxAssistantMessage(
          fauxToolCall(SCENARIO_TOOL_NAME, { text: kind }),
          { stopReason: "toolUse", timestamp: 1 },
        ),
        fauxAssistantMessage(
          kind === "tool_success" ? "Android mock tool complete" : "Android mock tool error observed",
          { timestamp: 2 },
        ),
      ]);
      break;
    case "provider_error":
      faux.setResponses([
        fauxAssistantMessage("", {
          stopReason: "error",
          errorMessage: "Phone-local fake provider failure",
          timestamp: 3,
        }),
      ]);
      break;
    case "stop_before_tool":
      faux.setResponses([
        async () => await new Promise<AssistantMessage>((resolve) => {
          state.phase = "provider_waiting";
          state.releaseProvider = resolve;
        }),
      ]);
      break;
  }

  state.phase = "running";
  queueMicrotask(() => {
    void harness.prompt(`Run phone-local scenario ${kind}`)
      .then((message) => {
        state.finalText = assistantText(message);
        state.phase = "settled";
      })
      .catch((error: unknown) => {
        state.promptError = errorMessage(error);
        state.phase = "failed";
      })
      .finally(() => {
        state.promptSettled = true;
        updateTerminal(state);
      });
  });
  return scenarioStatus();
}

export function drainNativeRequests(): NativeToolRequest[] {
  const state = requireScenario();
  return state.nativeOutbox.splice(0);
}

export function resolveNativeRequest(requestId: string, result: unknown): Record<string, unknown> {
  const state = requireScenario();
  const pending = state.pendingNative.get(requestId);
  if (pending === undefined) throw new Error(`PI_MOBILE_NATIVE_REQUEST_NOT_FOUND ${requestId}`);
  clearPendingAbort(pending);
  state.pendingNative.delete(requestId);
  state.toolRequestsResolved += 1;
  pending.resolve({
    content: [{ type: "text", text: JSON.stringify(result) }],
    details: result,
  });
  return scenarioStatus();
}

export function rejectNativeRequest(requestId: string, message: string): Record<string, unknown> {
  const state = requireScenario();
  const pending = state.pendingNative.get(requestId);
  if (pending === undefined) throw new Error(`PI_MOBILE_NATIVE_REQUEST_NOT_FOUND ${requestId}`);
  clearPendingAbort(pending);
  state.pendingNative.delete(requestId);
  state.toolRequestsRejected += 1;
  pending.reject(new Error(message));
  return scenarioStatus();
}

export function abortFakeScenario(): Record<string, unknown> {
  const state = requireScenario();
  if (state.stopRequested) return scenarioStatus();
  state.stopRequested = true;
  state.phase = "stopping";
  const stopPromise = state.harness.abort();
  state.releaseProvider?.(
    fauxAssistantMessage(
      fauxToolCall(SCENARIO_TOOL_NAME, { text: "must-not-run-after-stop" }),
      { stopReason: "toolUse", timestamp: 4 },
    ),
  );
  state.releaseProvider = null;
  void stopPromise
    .then(() => {
      state.stopCompleted = true;
      state.phase = "stopped";
    })
    .catch((error: unknown) => {
      state.stopError = errorMessage(error);
      state.phase = "stop_failed";
    })
    .finally(() => updateTerminal(state));
  return scenarioStatus();
}

export function scenarioStatus(): Record<string, unknown> {
  const state = requireScenario();
  updateTerminal(state);
  const eventTypes = [...state.eventTypes];
  return {
    kind: state.kind,
    phase: state.phase,
    terminal: state.terminal,
    expectationMet: expectationMet(state),
    promptSettled: state.promptSettled,
    stopRequested: state.stopRequested,
    stopCompleted: state.stopCompleted,
    promptError: state.promptError,
    stopError: state.stopError,
    finalText: state.finalText,
    events: state.events,
    eventTypes,
    pendingNativeRequestCount: state.pendingNative.size,
    queuedNativeRequestCount: state.nativeOutbox.length,
    toolRequestsIssued: state.toolRequestsIssued,
    toolRequestsResolved: state.toolRequestsResolved,
    toolRequestsRejected: state.toolRequestsRejected,
    toolExecutionsStarted: state.toolExecutionsStarted,
    toolExecutionsEnded: state.toolExecutionsEnded,
    toolErrors: state.toolErrors,
    lateToolStartsAfterStop: state.lateToolStartsAfterStop,
    hasAgentStart: eventTypes.includes("agent_start"),
    hasSettled: eventTypes.includes("settled"),
    hasAbort: eventTypes.includes("abort"),
  };
}

export function closeFakeScenario(): void {
  const state = scenarioState;
  if (state === null) return;
  state.unsubscribe();
  for (const pending of state.pendingNative.values()) {
    clearPendingAbort(pending);
    pending.reject(new Error("PI_MOBILE_RUNTIME_CLOSED"));
  }
  state.pendingNative.clear();
  state.nativeOutbox.length = 0;
  scenarioState = null;
}

function requestNativeTool(
  state: ScenarioState,
  toolCallId: string,
  parameters: Record<string, unknown>,
  signal?: AbortSignal,
): Promise<AgentToolResult<unknown>> {
  if (state.stopRequested || signal?.aborted) {
    return Promise.reject(new Error("PI_MOBILE_TOOL_BLOCKED_AFTER_STOP"));
  }
  const request: NativeToolRequest = {
    id: `native-${state.nextRequestId++}`,
    kind: "mock_tool",
    toolCallId,
    toolName: SCENARIO_TOOL_NAME,
    arguments: parameters,
  };
  state.toolRequestsIssued += 1;
  return new Promise<AgentToolResult<unknown>>((resolve, reject) => {
    const pending: PendingNativeToolRequest = { request, resolve, reject, signal };
    if (signal !== undefined) {
      const abortListener = () => {
        if (!state.pendingNative.delete(request.id)) return;
        state.nativeOutbox = state.nativeOutbox.filter((candidate) => candidate.id !== request.id);
        reject(signal.reason ?? new Error("Operation aborted"));
      };
      pending.abortListener = abortListener;
      signal.addEventListener("abort", abortListener);
    }
    state.pendingNative.set(request.id, pending);
    state.nativeOutbox.push(request);
  });
}

function recordEvent(state: ScenarioState, event: AgentHarnessEvent): void {
  if (!RECORDED_EVENT_TYPES.has(event.type)) return;
  const copy = JSON.parse(JSON.stringify(event)) as unknown;
  state.events.push(copy);
  state.eventTypes.push(event.type);
  if (event.type === "tool_execution_start") {
    state.toolExecutionsStarted += 1;
    if (state.stopRequested) state.lateToolStartsAfterStop += 1;
  } else if (event.type === "tool_execution_end") {
    state.toolExecutionsEnded += 1;
    if (event.isError) state.toolErrors += 1;
  }
}

function updateTerminal(state: ScenarioState): void {
  state.terminal = state.promptSettled &&
    (!state.stopRequested || state.stopCompleted || state.stopError !== null) &&
    state.pendingNative.size === 0;
}

function expectationMet(state: ScenarioState): boolean {
  if (!state.terminal) return false;
  const types = state.eventTypes;
  const common = types.includes("agent_start") && types.includes("settled");
  switch (state.kind) {
    case "tool_success":
      return common &&
        state.toolRequestsIssued === 1 &&
        state.toolRequestsResolved === 1 &&
        state.toolExecutionsStarted === 1 &&
        state.toolExecutionsEnded === 1 &&
        state.toolErrors === 0 &&
        state.finalText === "Android mock tool complete";
    case "tool_error":
      return common &&
        state.toolRequestsIssued === 1 &&
        state.toolRequestsRejected === 1 &&
        state.toolExecutionsStarted === 1 &&
        state.toolExecutionsEnded === 1 &&
        state.toolErrors === 1 &&
        state.finalText === "Android mock tool error observed";
    case "provider_error":
      return common &&
        state.toolRequestsIssued === 0 &&
        state.events.some((event) =>
          isRecord(event) &&
          event.type === "message_end" &&
          isRecord(event.message) &&
          event.message.stopReason === "error");
    case "stop_before_tool":
      return common &&
        state.stopCompleted &&
        types.includes("abort") &&
        state.toolRequestsIssued === 0 &&
        state.toolExecutionsStarted === 0 &&
        state.lateToolStartsAfterStop === 0;
  }
}

function modelsForProvider(provider: Provider): Models {
  const models = provider.getModels();
  return {
    getProviders: () => [provider],
    getProvider: (id) => id === provider.id ? provider : undefined,
    getModels: (providerId) =>
      providerId === undefined || providerId === provider.id ? models : [],
    getModel: (providerId, modelId) =>
      providerId === provider.id ? models.find((model) => model.id === modelId) : undefined,
    refresh: async () => undefined,
    getAuth: async () => undefined,
    stream: (model, context, options) => provider.stream(model, context, options),
    complete: async (model, context, options) =>
      await provider.stream(model, context, options).result(),
    streamSimple: (model, context, options) => provider.streamSimple(model, context, options),
    completeSimple: async (model, context, options) =>
      await provider.streamSimple(model, context, options).result(),
  };
}

function assistantText(message: AssistantMessage): string {
  return message.content
    .filter((block) => block.type === "text")
    .map((block) => block.text)
    .join("");
}

function clearPendingAbort(pending: PendingNativeToolRequest): void {
  if (pending.signal !== undefined && pending.abortListener !== undefined) {
    pending.signal.removeEventListener("abort", pending.abortListener);
  }
}

function requireScenario(): ScenarioState {
  if (scenarioState === null) throw new Error("PI_MOBILE_SCENARIO_NOT_STARTED");
  return scenarioState;
}

function errorMessage(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}
