import {
  EventStream,
  type AssistantMessage,
  type AssistantMessageEvent,
  type Context,
  type ImageContent,
  type Model,
  type Models,
  type Provider,
  type SimpleStreamOptions,
  type ToolCall,
  type Usage,
} from "@earendil-works/pi-ai";
import type { PiChildBinding } from "../child-agent-runtime.js";

export interface NativeProviderRequest {
  id: string;
  kind: "openrouter_chat_stream";
  modelId: string;
  messages: unknown[];
  tools?: unknown[];
  maxTokens?: number;
  parentTaskId?: string;
  parentToolCallId?: string;
  childId?: string;
  childName?: string;
}

export interface NativeProviderCancellation {
  id: string;
  kind: "cancel_openrouter_stream";
}

interface ToolCallScratch extends ToolCall {
  partialArgs: string;
  streamIndex: number;
}

interface PendingProvider {
  request: NativeProviderRequest;
  stream: NativeAssistantMessageEventStream;
  output: AssistantMessage;
  signal?: AbortSignal;
  abortListener?: () => void;
  textBlock: { type: "text"; text: string } | null;
  toolCalls: Map<number, ToolCallScratch>;
  hasFinishReason: boolean;
  finished: boolean;
  webRequests: number | null;
  webSearchRequests: number | null;
  webFetchRequests: number | null;
  webSources: Map<string, ProviderWebSource>;
  webActivityStarted: boolean;
  webActivityTerminal: boolean;
  hooks: OpenRouterNativeBridgeHooks;
}

export interface ProviderWebSource {
  url: string;
  title: string;
  domain: string;
  startIndex?: number;
  endIndex?: number;
}

export interface ProviderWebActivityEvent {
  type: "provider_web_activity";
  state: "running" | "completed" | "failed" | "cancelled";
  requestId: string;
  responseId?: string;
  searchRequests?: number;
  fetchRequests?: number;
  webRequests?: number;
  sources: ProviderWebSource[];
  childName?: string;
}

export interface OpenRouterNativeBridgeState {
  stopRequested: boolean;
  providerError: string | null;
  providerOutbox: NativeProviderRequest[];
  providerCancellationOutbox: NativeProviderCancellation[];
  pendingProviders: Map<string, PendingProvider>;
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

export interface OpenRouterNativeBridgeHooks {
  consumeLiveContext: (messages: unknown[]) => void;
  updateTerminal: () => void;
  recordWebActivityEvent: (event: ProviderWebActivityEvent) => void;
}

class NativeAssistantMessageEventStream extends EventStream<
  AssistantMessageEvent,
  AssistantMessage
> {
  constructor() {
    super(
      (event) => event.type === "done" || event.type === "error",
      (event) => {
        if (event.type === "done") return event.message;
        if (event.type === "error") return event.error;
        throw new Error("PI_MOBILE_PROVIDER_STREAM_MISSING_RESULT");
      },
    );
  }
}

export function createOpenRouterNativeStream(
  state: OpenRouterNativeBridgeState,
  model: Model<"openai-completions">,
  context: Context,
  options: SimpleStreamOptions | undefined,
  hooks: OpenRouterNativeBridgeHooks,
  childBinding?: PiChildBinding,
): NativeAssistantMessageEventStream {
  const stream = new NativeAssistantMessageEventStream();
  const output = initialAssistantMessage(model);
  stream.push({ type: "start", partial: output });
  if (state.stopRequested || options?.signal?.aborted) {
    output.stopReason = "aborted";
    output.errorMessage = "OpenRouter request was cancelled";
    stream.push({ type: "error", reason: "aborted", error: output });
    stream.end();
    state.lateProviderRequestsAfterStop += 1;
    return stream;
  }

  const messages = toOpenRouterMessages(context);
  hooks.consumeLiveContext(messages);
  const request: NativeProviderRequest = {
    id: `provider-${state.nextProviderRequestId++}`,
    kind: "openrouter_chat_stream",
    modelId: model.id,
    messages,
    ...(context.tools && context.tools.length > 0
      ? {
          tools: context.tools.map((tool) => ({
            type: "function",
            function: {
              name: tool.name,
              description: tool.description,
              parameters: tool.parameters,
            },
          })),
        }
      : {}),
    ...(options?.maxTokens !== undefined ? { maxTokens: options.maxTokens } : {}),
    ...(childBinding ?? {}),
  };
  const pending: PendingProvider = {
    request,
    stream,
    output,
    signal: options?.signal,
    textBlock: null,
    toolCalls: new Map(),
    hasFinishReason: false,
    finished: false,
    webRequests: null,
    webSearchRequests: null,
    webFetchRequests: null,
    webSources: new Map(),
    webActivityStarted: false,
    webActivityTerminal: false,
    hooks,
  };
  if (options?.signal !== undefined) {
    const abortListener = () => {
      if (!state.pendingProviders.has(request.id)) return;
      state.providerCancellationOutbox.push({
        id: request.id,
        kind: "cancel_openrouter_stream",
      });
      if (request.childId === undefined) state.providerCancellationsIssued += 1;
      else state.childProviderCancellationsIssued += 1;
      failPendingProvider(
        state,
        pending,
        "OpenRouter request was cancelled",
        true,
        hooks.updateTerminal,
      );
    };
    pending.abortListener = abortListener;
    options.signal.addEventListener("abort", abortListener);
  }
  state.pendingProviders.set(request.id, pending);
  state.providerOutbox.push(request);
  if (request.childId === undefined) state.providerRequestsIssued += 1;
  else state.childProviderRequestsIssued += 1;
  return stream;
}

export function drainOpenRouterRequests(
  state: OpenRouterNativeBridgeState,
): NativeProviderRequest[] {
  return state.providerOutbox.splice(0);
}

export function drainOpenRouterCancellations(
  state: OpenRouterNativeBridgeState,
): NativeProviderCancellation[] {
  return state.providerCancellationOutbox.splice(0);
}

export function pushOpenRouterChunk(
  state: OpenRouterNativeBridgeState,
  requestId: string,
  chunk: unknown,
  updateTerminal: () => void,
): boolean {
  const pending = requirePendingProvider(state, requestId);
  try {
    applyOpenRouterChunk(pending, chunk);
  } catch {
    failPendingProvider(
      state,
      pending,
      "OpenRouter returned an invalid stream",
      false,
      updateTerminal,
    );
  }
  return state.pendingProviders.has(requestId);
}

export function completeOpenRouterRequest(
  state: OpenRouterNativeBridgeState,
  requestId: string,
  generationId: string | undefined,
  updateTerminal: () => void,
): void {
  const pending = requirePendingProvider(state, requestId);
  if (generationId !== undefined && generationId.length > 0) {
    pending.output.responseId ||= generationId;
  }
  if (!pending.hasFinishReason) {
    failPendingProvider(
      state,
      pending,
      "OpenRouter stream ended without finish_reason",
      false,
      updateTerminal,
    );
    return;
  }
  finishBlocks(pending);
  if (pending.output.content.length === 0) {
    failPendingProvider(
      state,
      pending,
      "The model returned no response. Try again.",
      false,
      updateTerminal,
    );
    return;
  }
  emitWebActivityTerminal(pending, "completed");
  pending.finished = true;
  clearProviderAbort(pending);
  state.pendingProviders.delete(requestId);
  if (pending.request.childId === undefined) state.providerRequestsCompleted += 1;
  else state.childProviderRequestsCompleted += 1;
  pending.stream.push({
    type: "done",
    reason: pending.output.stopReason as "stop" | "length" | "toolUse",
    message: pending.output,
  });
  pending.stream.end();
  updateTerminal();
}

export function failOpenRouterRequest(
  state: OpenRouterNativeBridgeState,
  requestId: string,
  safeMessage: string,
  updateTerminal: () => void,
): void {
  failPendingProvider(
    state,
    requirePendingProvider(state, requestId),
    safeMessage,
    false,
    updateTerminal,
  );
}

export function closeOpenRouterNativeBridge(
  state: OpenRouterNativeBridgeState,
): void {
  for (const pending of state.pendingProviders.values()) {
    clearProviderAbort(pending);
    if (!pending.finished) {
      pending.output.stopReason = "aborted";
      pending.output.errorMessage = "Phone-local Provider runtime closed";
      pending.stream.push({ type: "error", reason: "aborted", error: pending.output });
      pending.stream.end();
    }
  }
  state.pendingProviders.clear();
  state.providerOutbox.length = 0;
  state.providerCancellationOutbox.length = 0;
}

export function modelsForProvider(provider: Provider): Models {
  const models = provider.getModels();
  return {
    getProviders: () => [provider],
    getProvider: (id) => id === provider.id ? provider : undefined,
    getModels: (providerId) =>
      providerId === undefined || providerId === provider.id ? models : [],
    getModel: (providerId, modelId) =>
      providerId === provider.id
        ? models.find((model) => model.id === modelId)
        : undefined,
    refresh: async () => undefined,
    getAuth: async () => ({ auth: {}, source: "Android Keystore" }),
    stream: (model, context, options) => provider.stream(model, context, options),
    complete: async (model, context, options) =>
      await provider.stream(model, context, options).result(),
    streamSimple: (model, context, options) =>
      provider.streamSimple(model, context, options),
    completeSimple: async (model, context, options) =>
      await provider.streamSimple(model, context, options).result(),
  };
}

function applyOpenRouterChunk(pending: PendingProvider, value: unknown): void {
  if (!isRecord(value)) throw new Error("chunk must be an object");
  if (typeof value.id === "string" && value.id.length > 0) {
    pending.output.responseId ||= value.id;
  }
  if (
    typeof value.model === "string" &&
    value.model.length > 0 &&
    value.model !== pending.output.model
  ) {
    pending.output.responseModel ||= value.model;
  }
  if (isRecord(value.usage)) {
    pending.output.usage = parseUsage(value.usage);
    captureWebToolUsage(pending, value.usage);
  }
  const choice = Array.isArray(value.choices) && isRecord(value.choices[0])
    ? value.choices[0]
    : undefined;
  if (choice === undefined) return;
  if (isRecord(choice.message)) {
    captureWebSearchAnnotations(pending, choice.message.annotations);
  }
  if (typeof choice.finish_reason === "string" && choice.finish_reason.length > 0) {
    pending.output.stopReason = mapFinishReason(choice.finish_reason);
    pending.hasFinishReason = true;
  }
  if (!isRecord(choice.delta)) return;
  const delta = choice.delta;
  captureWebSearchAnnotations(pending, delta.annotations);
  if (typeof delta.content === "string" && delta.content.length > 0) {
    const block = ensureTextBlock(pending);
    block.text += delta.content;
    pending.stream.push({
      type: "text_delta",
      contentIndex: pending.output.content.indexOf(block),
      delta: delta.content,
      partial: pending.output,
    });
  }
  if (Array.isArray(delta.tool_calls)) {
    for (const candidate of delta.tool_calls) {
      if (!isRecord(candidate) || !Number.isInteger(candidate.index)) {
        throw new Error("tool call index is invalid");
      }
      const streamIndex = candidate.index as number;
      const block = ensureToolCallBlock(pending, streamIndex, candidate);
      if (typeof candidate.id === "string" && candidate.id.length > 0) {
        block.id ||= candidate.id;
      }
      const functionDelta = isRecord(candidate.function) ? candidate.function : undefined;
      if (typeof functionDelta?.name === "string" && functionDelta.name.length > 0) {
        block.name ||= functionDelta.name;
      }
      const argumentsDelta = typeof functionDelta?.arguments === "string"
        ? functionDelta.arguments
        : "";
      block.partialArgs += argumentsDelta;
      block.arguments = parsePartialArguments(block.partialArgs);
      pending.stream.push({
        type: "toolcall_delta",
        contentIndex: pending.output.content.indexOf(block),
        delta: argumentsDelta,
        partial: pending.output,
      });
    }
  }
}

function ensureTextBlock(
  pending: PendingProvider,
): { type: "text"; text: string } {
  if (pending.textBlock !== null) return pending.textBlock;
  const block = { type: "text" as const, text: "" };
  pending.textBlock = block;
  pending.output.content.push(block);
  pending.stream.push({
    type: "text_start",
    contentIndex: pending.output.content.indexOf(block),
    partial: pending.output,
  });
  return block;
}

function ensureToolCallBlock(
  pending: PendingProvider,
  streamIndex: number,
  candidate: Record<string, unknown>,
): ToolCallScratch {
  const existing = pending.toolCalls.get(streamIndex);
  if (existing !== undefined) return existing;
  const functionDelta = isRecord(candidate.function) ? candidate.function : undefined;
  const block: ToolCallScratch = {
    type: "toolCall",
    id: typeof candidate.id === "string" ? candidate.id : "",
    name: typeof functionDelta?.name === "string" ? functionDelta.name : "",
    arguments: {},
    partialArgs: "",
    streamIndex,
  };
  pending.toolCalls.set(streamIndex, block);
  pending.output.content.push(block);
  pending.stream.push({
    type: "toolcall_start",
    contentIndex: pending.output.content.indexOf(block),
    partial: pending.output,
  });
  return block;
}

function finishBlocks(pending: PendingProvider): void {
  for (const block of pending.output.content) {
    const contentIndex = pending.output.content.indexOf(block);
    if (block.type === "text") {
      pending.stream.push({
        type: "text_end",
        contentIndex,
        content: block.text,
        partial: pending.output,
      });
    } else if (block.type === "toolCall") {
      const scratch = block as ToolCallScratch;
      if (scratch.id.length === 0 || scratch.name.length === 0) {
        throw new Error("OpenRouter tool call identity is missing");
      }
      scratch.arguments = JSON.parse(scratch.partialArgs) as Record<string, unknown>;
      delete (scratch as Partial<ToolCallScratch>).partialArgs;
      delete (scratch as Partial<ToolCallScratch>).streamIndex;
      pending.stream.push({
        type: "toolcall_end",
        contentIndex,
        toolCall: scratch,
        partial: pending.output,
      });
    }
  }
}

function failPendingProvider(
  state: OpenRouterNativeBridgeState,
  pending: PendingProvider,
  safeMessage: string,
  aborted: boolean,
  updateTerminal: () => void,
): void {
  if (pending.finished) return;
  pending.finished = true;
  emitWebActivityTerminal(pending, aborted ? "cancelled" : "failed");
  clearProviderAbort(pending);
  state.pendingProviders.delete(pending.request.id);
  if (pending.request.childId === undefined) {
    state.providerRequestsFailed += 1;
    state.providerError = safeMessage;
  } else {
    state.childProviderRequestsFailed += 1;
  }
  for (const block of pending.output.content) {
    if (block.type === "toolCall") {
      delete (block as Partial<ToolCallScratch>).partialArgs;
      delete (block as Partial<ToolCallScratch>).streamIndex;
    }
  }
  pending.output.stopReason = aborted ? "aborted" : "error";
  pending.output.errorMessage = safeMessage;
  pending.stream.push({
    type: "error",
    reason: aborted ? "aborted" : "error",
    error: pending.output,
  });
  pending.stream.end();
  updateTerminal();
}

function captureWebToolUsage(
  pending: PendingProvider,
  usage: Record<string, unknown>,
): void {
  const current = usage.server_tool_use_details;
  const legacy = usage.server_tool_use;
  if (current === undefined && legacy === undefined) return;
  if (current !== undefined && !isRecord(current)) {
    throw new Error("OpenRouter server tool usage details are invalid");
  }
  if (legacy !== undefined && !isRecord(legacy)) {
    throw new Error("OpenRouter server tool usage is invalid");
  }
  pending.webSearchRequests = captureWebRequestCount(
    current,
    legacy,
    "web_search_requests",
    MAX_WEB_SEARCH_REQUESTS,
  ) ?? pending.webSearchRequests;
  pending.webFetchRequests = captureWebRequestCount(
    current,
    legacy,
    "web_fetch_requests",
    MAX_WEB_FETCH_REQUESTS,
  ) ?? pending.webFetchRequests;
  pending.webRequests = captureWebRequestCount(
    current,
    legacy,
    "tool_calls_executed",
    MAX_WEB_REQUESTS,
  ) ?? pending.webRequests;
  if (
    (pending.webRequests ?? 0) > 0 ||
    (pending.webSearchRequests ?? 0) > 0 ||
    (pending.webFetchRequests ?? 0) > 0
  ) {
    emitWebActivityRunning(pending);
  }
}

function captureWebRequestCount(
  current: unknown,
  legacy: unknown,
  field: "web_search_requests" | "web_fetch_requests" | "tool_calls_executed",
  maximum: number,
): number | null {
  const currentValue = isRecord(current) ? current[field] : undefined;
  const legacyValue = isRecord(legacy) ? legacy[field] : undefined;
  if (
    currentValue !== undefined && legacyValue !== undefined &&
    nonNegativeInteger(currentValue) !== nonNegativeInteger(legacyValue)
  ) {
    throw new Error(`OpenRouter ${field} usage fields disagree`);
  }
  const value = currentValue ?? legacyValue;
  if (value === undefined) return null;
  const requests = nonNegativeInteger(value);
  if (requests > maximum) throw new Error(`OpenRouter ${field} usage exceeds request budget`);
  return requests;
}

function captureWebSearchAnnotations(
  pending: PendingProvider,
  annotations: unknown,
): void {
  if (annotations === undefined) return;
  if (!Array.isArray(annotations)) {
    throw new Error("OpenRouter annotations are invalid");
  }
  for (const annotation of annotations) {
    if (!isRecord(annotation) || typeof annotation.type !== "string") {
      throw new Error("OpenRouter annotation is invalid");
    }
    if (annotation.type !== "url_citation") continue;
    if (!isRecord(annotation.url_citation)) {
      throw new Error("OpenRouter URL citation is invalid");
    }
    const citation = annotation.url_citation;
    const url = typeof citation.url === "string" ? citation.url.trim() : "";
    const domain = sourceDomain(url);
    if (url.length === 0 || url.length > MAX_SOURCE_URL_CHARS || domain === null) {
      throw new Error("OpenRouter URL citation URL is invalid");
    }
    if (citation.title !== undefined && typeof citation.title !== "string") {
      throw new Error("OpenRouter URL citation title is invalid");
    }
    if (citation.content !== undefined && typeof citation.content !== "string") {
      throw new Error("OpenRouter URL citation content is invalid");
    }
    const startIndex = optionalNonNegativeInteger(citation.start_index);
    const endIndex = optionalNonNegativeInteger(citation.end_index);
    if (startIndex !== undefined && endIndex !== undefined && endIndex < startIndex) {
      throw new Error("OpenRouter URL citation range is invalid");
    }
    if (!pending.webSources.has(url) && pending.webSources.size < MAX_WEB_SOURCES) {
      const rawTitle = typeof citation.title === "string" ? citation.title.trim() : "";
      pending.webSources.set(url, {
        url,
        title: (rawTitle || domain).slice(0, MAX_SOURCE_TITLE_CHARS),
        domain,
        ...(startIndex === undefined ? {} : { startIndex }),
        ...(endIndex === undefined ? {} : { endIndex }),
      });
    }
  }
  if (pending.webSources.size > 0) emitWebActivityRunning(pending);
}

function emitWebActivityRunning(pending: PendingProvider): void {
  if (pending.webActivityStarted) return;
  pending.webActivityStarted = true;
  pending.hooks.recordWebActivityEvent(webActivityEvent(pending, "running"));
}

function emitWebActivityTerminal(
  pending: PendingProvider,
  state: "completed" | "failed" | "cancelled",
): void {
  if (pending.webActivityTerminal) return;
  if (
    !pending.webActivityStarted &&
    ((pending.webRequests ?? 0) > 0 ||
      (pending.webSearchRequests ?? 0) > 0 ||
      (pending.webFetchRequests ?? 0) > 0 ||
      pending.webSources.size > 0)
  ) {
    emitWebActivityRunning(pending);
  }
  if (!pending.webActivityStarted) return;
  pending.webActivityTerminal = true;
  pending.hooks.recordWebActivityEvent(webActivityEvent(pending, state));
}

function webActivityEvent(
  pending: PendingProvider,
  state: ProviderWebActivityEvent["state"],
): ProviderWebActivityEvent {
  return {
    type: "provider_web_activity",
    state,
    requestId: pending.request.id,
    ...(pending.output.responseId === undefined
      ? {}
      : { responseId: pending.output.responseId }),
    ...(pending.webRequests === null
      ? {}
      : { webRequests: pending.webRequests }),
    ...(pending.webSearchRequests === null
      ? {}
      : { searchRequests: pending.webSearchRequests }),
    ...(pending.webFetchRequests === null
      ? {}
      : { fetchRequests: pending.webFetchRequests }),
    sources: [...pending.webSources.values()],
    ...(pending.request.childName === undefined ? {} : { childName: pending.request.childName }),
  };
}

function sourceDomain(url: string): string | null {
  const match = /^https?:\/\/([^/?#\s]+)(?:[/?#]|$)/i.exec(url);
  return match === null ? null : match[1].toLowerCase();
}

function optionalNonNegativeInteger(value: unknown): number | undefined {
  return value === undefined ? undefined : nonNegativeInteger(value);
}

function toOpenRouterMessages(context: Context): unknown[] {
  const messages: unknown[] = [];
  if (context.systemPrompt !== undefined && context.systemPrompt.length > 0) {
    messages.push({ role: "system", content: context.systemPrompt });
  }
  for (let index = 0; index < context.messages.length; index += 1) {
    const message = context.messages[index];
    if (message.role === "user") {
      messages.push({ role: "user", content: openRouterUserContent(message.content) });
    } else if (message.role === "assistant") {
      const text = message.content
        .filter((block) => block.type === "text")
        .map((block) => block.text)
        .join("");
      const toolCalls = message.content
        .filter((block): block is ToolCall => block.type === "toolCall")
        .map((block) => ({
          id: block.id,
          type: "function",
          function: {
            name: block.name,
            arguments: JSON.stringify(block.arguments),
          },
        }));
      messages.push({
        role: "assistant",
        content: text.length > 0 ? text : null,
        ...(toolCalls.length > 0 ? { tool_calls: toolCalls } : {}),
      });
    } else {
      const imageBlocks: unknown[] = [];
      let toolIndex = index;
      for (
        ;
        toolIndex < context.messages.length &&
        context.messages[toolIndex].role === "toolResult";
        toolIndex += 1
      ) {
        const toolMessage = context.messages[toolIndex];
        if (toolMessage.role !== "toolResult") break;
        const text = toolMessage.content
          .filter((block) => block.type === "text")
          .map((block) => block.text)
          .join("\n");
        const images = toolMessage.content.filter(
          (block): block is ImageContent => block.type === "image",
        );
        messages.push({
          role: "tool",
          tool_call_id: toolMessage.toolCallId,
          name: toolMessage.toolName,
          content: text.length > 0
            ? text
            : images.length > 0
              ? "(see attached image)"
              : "(no tool output)",
        });
        for (const block of images) {
          imageBlocks.push({
            type: "image_url",
            image_url: { url: `data:${block.mimeType};base64,${block.data}` },
          });
        }
      }
      index = toolIndex - 1;
      if (imageBlocks.length > 0) {
        messages.push({
          role: "user",
          content: [
            { type: "text", text: "Attached image(s) from tool result:" },
            ...imageBlocks,
          ],
        });
      }
    }
  }
  return messages;
}

function openRouterUserContent(
  content: string | Array<{ type: string; text?: string; data?: string; mimeType?: string }>,
): unknown {
  if (typeof content === "string") return content;
  if (content.every((block) => block.type === "text")) {
    return content.map((block) => block.text ?? "").join("");
  }
  const parts: unknown[] = [];
  for (const block of content) {
    if (block.type === "text" && typeof block.text === "string") {
      if (block.text.length > 0) parts.push({ type: "text", text: block.text });
      continue;
    }
    if (
      block.type === "image" &&
      typeof block.data === "string" &&
      typeof block.mimeType === "string" &&
      OPENROUTER_IMAGE_MIME_TYPES.has(block.mimeType)
    ) {
      parts.push({
        type: "image_url",
        image_url: { url: `data:${block.mimeType};base64,${block.data}` },
      });
      continue;
    }
    throw new Error("PI_MOBILE_OPENROUTER_USER_CONTENT_UNSUPPORTED");
  }
  return parts;
}

function initialAssistantMessage(model: Model<"openai-completions">): AssistantMessage {
  return {
    role: "assistant",
    content: [],
    api: model.api,
    provider: model.provider,
    model: model.id,
    usage: zeroUsage(),
    stopReason: "stop",
    timestamp: Date.now(),
  };
}

function parseUsage(value: Record<string, unknown>): Usage {
  const input = nonNegativeInteger(value.prompt_tokens);
  const output = nonNegativeInteger(value.completion_tokens);
  const total = value.total_tokens === undefined
    ? input + output
    : nonNegativeInteger(value.total_tokens);
  return {
    input,
    output,
    cacheRead: 0,
    cacheWrite: 0,
    totalTokens: total,
    cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0, total: 0 },
  };
}

function zeroUsage(): Usage {
  return {
    input: 0,
    output: 0,
    cacheRead: 0,
    cacheWrite: 0,
    totalTokens: 0,
    cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0, total: 0 },
  };
}

function mapFinishReason(value: string): "stop" | "length" | "toolUse" {
  switch (value) {
    case "stop":
      return "stop";
    case "length":
      return "length";
    case "tool_calls":
    case "tool_use":
      return "toolUse";
    default:
      throw new Error("OpenRouter finish_reason is unsupported");
  }
}

function requirePendingProvider(
  state: OpenRouterNativeBridgeState,
  requestId: string,
): PendingProvider {
  const pending = state.pendingProviders.get(requestId);
  if (pending === undefined) {
    throw new Error(`PI_MOBILE_NATIVE_PROVIDER_REQUEST_NOT_FOUND ${requestId}`);
  }
  return pending;
}

function clearProviderAbort(pending: PendingProvider): void {
  if (pending.signal !== undefined && pending.abortListener !== undefined) {
    pending.signal.removeEventListener("abort", pending.abortListener);
  }
}

function parsePartialArguments(value: string): Record<string, unknown> {
  try {
    const parsed = JSON.parse(value) as unknown;
    return isRecord(parsed) ? parsed : {};
  } catch {
    return {};
  }
}

function nonNegativeInteger(value: unknown): number {
  if (!Number.isSafeInteger(value) || (value as number) < 0) {
    throw new Error("OpenRouter usage value is invalid");
  }
  return value as number;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

const OPENROUTER_IMAGE_MIME_TYPES = new Set([
  "image/jpeg",
  "image/png",
  "image/webp",
  "image/gif",
]);

const MAX_WEB_SEARCH_REQUESTS = 3;
const MAX_WEB_FETCH_REQUESTS = 3;
const MAX_WEB_REQUESTS = 5;
const MAX_WEB_SOURCES = 15;
const MAX_SOURCE_URL_CHARS = 2_048;
const MAX_SOURCE_TITLE_CHARS = 240;
