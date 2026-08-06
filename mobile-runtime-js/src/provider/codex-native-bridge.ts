import {
  createAssistantMessageEventStream,
  type AssistantMessage,
  type AssistantMessageEventStream,
  type Context,
  type Model,
  type SimpleStreamOptions,
} from "@earendil-works/pi-ai";
import {
  convertResponsesMessages,
  convertResponsesTools,
  processResponsesStream,
} from "@earendil-works/pi-ai/api/openai-responses-shared";
import type { PiChildBinding } from "../child-agent-runtime.js";

const CODEX_TOOL_CALL_PROVIDERS = new Set(["openai", "openai-codex", "opencode"]);

export interface NativeCodexRequest {
  id: string;
  kind: "codex_responses_stream";
  modelId: string;
  sessionId?: string;
  body: Record<string, unknown>;
  parentTaskId?: string;
  parentToolCallId?: string;
  childId?: string;
  childName?: string;
}

export interface NativeCodexCancellation {
  id: string;
  kind: "cancel_codex_responses_stream";
}

interface PendingCodexProvider {
  request: NativeCodexRequest;
  model: Model<"openai-codex-responses">;
  stream: AssistantMessageEventStream;
  output: AssistantMessage;
  events: NativeAsyncEventQueue;
  signal?: AbortSignal;
  abortListener?: () => void;
  finished: boolean;
  hooks: CodexNativeBridgeHooks;
}

export interface CodexNativeBridgeState {
  stopRequested: boolean;
  providerError: string | null;
  codexProviderOutbox: NativeCodexRequest[];
  codexProviderCancellationOutbox: NativeCodexCancellation[];
  pendingCodexProviders: Map<string, PendingCodexProvider>;
  nextProviderRequestId: number;
  providerRequestsIssued: number;
  providerRequestsCompleted: number;
  providerRequestsFailed: number;
  providerCancellationsIssued: number;
  childProviderRequestsIssued: number;
  childProviderRequestsCompleted: number;
  childProviderRequestsFailed: number;
  childProviderCancellationsIssued: number;
  lateProviderRequestsAfterStop: number;
}

export interface CodexNativeBridgeHooks {
  consumeLiveContext: (messages: unknown[]) => void;
  updateTerminal: () => void;
}

class NativeAsyncEventQueue implements AsyncIterable<never> {
  private readonly values: never[] = [];
  private readonly waiters: Array<{
    resolve: (result: IteratorResult<never>) => void;
    reject: (reason: unknown) => void;
  }> = [];
  private ended = false;
  private failure: Error | null = null;

  push(value: unknown): void {
    if (this.ended) throw new Error("PI_MOBILE_CODEX_EVENT_AFTER_TERMINAL");
    const waiter = this.waiters.shift();
    if (waiter === undefined) this.values.push(value as never);
    else waiter.resolve({ value: value as never, done: false });
  }

  end(): void {
    if (this.ended) return;
    this.ended = true;
    this.waiters.splice(0).forEach(({ resolve }) =>
      resolve({ value: undefined as never, done: true }));
  }

  fail(error: Error): void {
    if (this.ended) return;
    this.failure = error;
    this.ended = true;
    this.waiters.splice(0).forEach(({ reject }) => reject(error));
  }

  async *[Symbol.asyncIterator](): AsyncIterator<never> {
    while (true) {
      if (this.values.length > 0) {
        yield this.values.shift()!;
      } else if (this.failure !== null) {
        throw this.failure;
      } else if (this.ended) {
        return;
      } else {
        const result = await new Promise<IteratorResult<never>>((resolve, reject) =>
          this.waiters.push({ resolve, reject }));
        if (result.done) return;
        yield result.value;
      }
    }
  }
}

export function createCodexNativeStream(
  state: CodexNativeBridgeState,
  model: Model<"openai-codex-responses">,
  context: Context,
  options: SimpleStreamOptions | undefined,
  hooks: CodexNativeBridgeHooks,
  childBinding?: PiChildBinding,
): AssistantMessageEventStream {
  const stream = createAssistantMessageEventStream();
  const output = initialAssistantMessage(model);
  stream.push({ type: "start", partial: output });
  if (state.stopRequested || options?.signal?.aborted) {
    output.stopReason = "aborted";
    output.errorMessage = "Codex request was cancelled";
    stream.push({ type: "error", reason: "aborted", error: output });
    stream.end();
    state.lateProviderRequestsAfterStop += 1;
    return stream;
  }

  const body = buildRequestBody(model, context, options);
  hooks.consumeLiveContext(body.input as unknown[]);
  const request: NativeCodexRequest = {
    id: `provider-${state.nextProviderRequestId++}`,
    kind: "codex_responses_stream",
    modelId: model.id,
    ...(options?.sessionId ? { sessionId: options.sessionId } : {}),
    body,
    ...(childBinding ?? {}),
  };
  const pending: PendingCodexProvider = {
    request,
    model,
    stream,
    output,
    events: new NativeAsyncEventQueue(),
    signal: options?.signal,
    finished: false,
    hooks,
  };
  if (options?.signal !== undefined) {
    const abortListener = () => {
      if (!state.pendingCodexProviders.has(request.id)) return;
      state.codexProviderCancellationOutbox.push({
        id: request.id,
        kind: "cancel_codex_responses_stream",
      });
      if (request.childId === undefined) state.providerCancellationsIssued += 1;
      else state.childProviderCancellationsIssued += 1;
      pending.events.fail(new Error("Codex request was cancelled"));
    };
    pending.abortListener = abortListener;
    options.signal.addEventListener("abort", abortListener);
  }
  state.pendingCodexProviders.set(request.id, pending);
  state.codexProviderOutbox.push(request);
  if (request.childId === undefined) state.providerRequestsIssued += 1;
  else state.childProviderRequestsIssued += 1;
  queueMicrotask(() => void processCodexEvents(state, pending));
  return stream;
}

export function drainCodexRequests(state: CodexNativeBridgeState): NativeCodexRequest[] {
  return state.codexProviderOutbox.splice(0);
}

export function drainCodexCancellations(
  state: CodexNativeBridgeState,
): NativeCodexCancellation[] {
  return state.codexProviderCancellationOutbox.splice(0);
}

export function pushCodexEvent(
  state: CodexNativeBridgeState,
  requestId: string,
  value: unknown,
): void {
  const pending = requirePending(state, requestId);
  try {
    pending.events.push(normalizeCodexEvent(value));
  } catch {
    pending.events.fail(new Error("Codex returned an invalid stream"));
  }
}

export function completeCodexRequest(
  state: CodexNativeBridgeState,
  requestId: string,
): void {
  requirePending(state, requestId).events.end();
}

export function failCodexRequest(
  state: CodexNativeBridgeState,
  requestId: string,
  safeMessage: string,
): void {
  requirePending(state, requestId).events.fail(new Error(safeMessage));
}

export function closeCodexNativeBridge(state: CodexNativeBridgeState): void {
  for (const pending of state.pendingCodexProviders.values()) {
    clearAbort(pending);
    pending.events.fail(new Error("Phone-local Codex runtime closed"));
  }
  state.pendingCodexProviders.clear();
  state.codexProviderOutbox.length = 0;
  state.codexProviderCancellationOutbox.length = 0;
}

function buildRequestBody(
  model: Model<"openai-codex-responses">,
  context: Context,
  options: SimpleStreamOptions | undefined,
): Record<string, unknown> {
  const body: Record<string, unknown> = {
    model: model.id,
    store: false,
    stream: true,
    instructions: context.systemPrompt || "You are a helpful assistant.",
    input: convertResponsesMessages(model, context, CODEX_TOOL_CALL_PROVIDERS, {
      includeSystemPrompt: false,
    }),
    text: { verbosity: "low" },
    include: ["reasoning.encrypted_content"],
    tool_choice: "auto",
    parallel_tool_calls: true,
  };
  if (options?.sessionId) body.prompt_cache_key = options.sessionId.slice(0, 64);
  if (context.tools && context.tools.length > 0) {
    body.tools = convertResponsesTools(context.tools, { strict: null });
  }
  return body;
}

async function processCodexEvents(
  state: CodexNativeBridgeState,
  pending: PendingCodexProvider,
): Promise<void> {
  try {
    await processResponsesStream(
      pending.events,
      pending.output,
      pending.stream,
      pending.model,
    );
    if (pending.signal?.aborted) throw new Error("Codex request was cancelled");
    if (pending.output.stopReason === "error" || pending.output.stopReason === "aborted") {
      throw new Error("Codex returned an error event");
    }
    pending.finished = true;
    settleCounters(state, pending, "completed");
    pending.stream.push({
      type: "done",
      reason: pending.output.stopReason as "stop" | "length" | "toolUse",
      message: pending.output,
    });
    pending.stream.end();
  } catch (error) {
    const aborted = pending.signal?.aborted === true;
    pending.output.stopReason = aborted ? "aborted" : "error";
    pending.output.errorMessage = aborted
      ? "Codex request was cancelled"
      : safeCodexError(error);
    if (!aborted) state.providerError = pending.output.errorMessage;
    settleCounters(state, pending, aborted ? "aborted" : "failed");
    pending.stream.push({
      type: "error",
      reason: pending.output.stopReason,
      error: pending.output,
    });
    pending.stream.end();
  } finally {
    clearAbort(pending);
    state.pendingCodexProviders.delete(pending.request.id);
    pending.hooks.updateTerminal();
  }
}

function initialAssistantMessage(
  model: Model<"openai-codex-responses">,
): AssistantMessage {
  return {
    role: "assistant",
    content: [],
    api: "openai-codex-responses",
    provider: model.provider,
    model: model.id,
    usage: {
      input: 0,
      output: 0,
      cacheRead: 0,
      cacheWrite: 0,
      totalTokens: 0,
      cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0, total: 0 },
    },
    stopReason: "stop",
    timestamp: Date.now(),
  };
}

function normalizeCodexEvent(value: unknown): unknown {
  if (!isRecord(value) || typeof value.type !== "string") {
    throw new Error("PI_MOBILE_CODEX_EVENT_INVALID");
  }
  if (value.type === "error" || value.type === "response.failed") {
    throw new Error("Codex returned an error event");
  }
  if (
    value.type === "response.done" ||
    value.type === "response.completed" ||
    value.type === "response.incomplete"
  ) {
    const response = isRecord(value.response) ? value.response : undefined;
    return {
      ...value,
      type: "response.completed",
      ...(response === undefined
        ? {}
        : {
            response: {
              ...response,
              status: normalizeResponseStatus(response.status),
            },
          }),
    };
  }
  return value;
}

function normalizeResponseStatus(value: unknown): string | undefined {
  return typeof value === "string" && [
    "completed",
    "incomplete",
    "failed",
    "cancelled",
    "queued",
    "in_progress",
  ].includes(value)
    ? value
    : undefined;
}

function settleCounters(
  state: CodexNativeBridgeState,
  pending: PendingCodexProvider,
  result: "completed" | "failed" | "aborted",
): void {
  if (result === "aborted") return;
  if (pending.request.childId === undefined) {
    if (result === "completed") state.providerRequestsCompleted += 1;
    else state.providerRequestsFailed += 1;
  } else if (result === "completed") {
    state.childProviderRequestsCompleted += 1;
  } else {
    state.childProviderRequestsFailed += 1;
  }
}

function clearAbort(pending: PendingCodexProvider): void {
  if (pending.signal !== undefined && pending.abortListener !== undefined) {
    pending.signal.removeEventListener("abort", pending.abortListener);
  }
  pending.abortListener = undefined;
}

function requirePending(
  state: CodexNativeBridgeState,
  requestId: string,
): PendingCodexProvider {
  const pending = state.pendingCodexProviders.get(requestId);
  if (pending === undefined || pending.finished) {
    throw new Error(`PI_MOBILE_CODEX_PROVIDER_REQUEST_NOT_FOUND ${requestId}`);
  }
  return pending;
}

function safeCodexError(error: unknown): string {
  if (error instanceof Error && error.message.startsWith("Codex ")) {
    return error.message.slice(0, 256);
  }
  return "Codex returned an invalid stream";
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}
