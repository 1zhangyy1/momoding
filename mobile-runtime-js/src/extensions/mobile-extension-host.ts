import type {
  AgentHarness,
  AgentTool,
  ContextEvent,
  ContextResult,
  ToolResultEvent,
  ToolResultPatch,
} from "@earendil-works/pi-agent-core";

export const MOBILE_EXTENSION_SUPPORTED_EVENTS = [
  "context",
  "tool_result",
] as const;

export type MobileExtensionEventType =
  typeof MOBILE_EXTENSION_SUPPORTED_EVENTS[number];

type MobileExtensionEventMap = {
  context: ContextEvent;
  tool_result: ToolResultEvent;
};

type MobileExtensionResultMap = {
  context: ContextResult | undefined;
  tool_result: ToolResultPatch | undefined;
};

export type MobileExtensionEventHandler<TType extends MobileExtensionEventType> = (
  event: MobileExtensionEventMap[TType],
) => MobileExtensionResultMap[TType] | Promise<MobileExtensionResultMap[TType]>;

export interface MobileExtensionApi {
  registerTool(tool: AgentTool): void;
  on<TType extends MobileExtensionEventType>(
    type: TType,
    handler: MobileExtensionEventHandler<TType>,
  ): void;
}

export type MobileExtensionDisposer = () => void;
export type MobileExtensionFactory = (
  api: MobileExtensionApi,
) => void | MobileExtensionDisposer;

export interface MobileExtensionDescriptor {
  id: string;
  version: string;
  source: "builtin" | "package";
  factory: MobileExtensionFactory;
}

export interface MobileExtensionStatus {
  id: string;
  version: string;
  source: "builtin" | "package";
  status: "loaded" | "attached" | "disposed";
  toolNames: string[];
  eventTypes: MobileExtensionEventType[];
}

export interface MobileExtensionHostSnapshot {
  status: "loaded" | "attached" | "disposed";
  supportedEventTypes: MobileExtensionEventType[];
  extensionToolNames: string[];
  extensions: MobileExtensionStatus[];
}

type MobileExtensionHarnessHooks = Pick<AgentHarness, "on">;

interface ExtensionRecord {
  descriptor: MobileExtensionDescriptor;
  toolNames: string[];
  eventTypes: MobileExtensionEventType[];
  dispose?: MobileExtensionDisposer;
}

interface RegisteredHandler<TType extends MobileExtensionEventType> {
  extensionId: string;
  handler: MobileExtensionEventHandler<TType>;
}

const EXTENSION_ID_PATTERN = /^[a-z][a-z0-9._-]{0,79}$/;
const PACKAGE_EXTENSION_ID_PATTERN = /^pkg\.[a-z][a-z0-9]*(?:[._-][a-z0-9]+)+$/;
const MAX_VERSION_LENGTH = 80;

/**
 * Static Android-safe subset of Pi Coding Agent's extension registration model.
 * Factories run synchronously before the Harness is created; only native Pi tools
 * and two composable Harness hooks are supported. Dynamic module loading and
 * runtime registration are intentionally outside this host.
 */
export class MobileExtensionHost {
  private lifecycle: "loading" | "loaded" | "attached" | "disposed" = "loading";
  private readonly records: ExtensionRecord[] = [];
  private readonly extensionTools: AgentTool[] = [];
  private readonly contextHandlers: RegisteredHandler<"context">[] = [];
  private readonly toolResultHandlers: RegisteredHandler<"tool_result">[] = [];
  private harnessUnsubscribers: MobileExtensionDisposer[] = [];

  constructor(descriptors: readonly MobileExtensionDescriptor[]) {
    validateDescriptors(descriptors);
    try {
      for (const descriptor of descriptors) this.load(descriptor);
      this.lifecycle = "loaded";
    } catch (error) {
      this.rollbackFactories();
      this.lifecycle = "disposed";
      throw error;
    }
  }

  composeTools(legacyTools: readonly AgentTool[]): AgentTool[] {
    this.requireUsable();
    const tools = [...legacyTools, ...this.extensionTools];
    validateUniqueToolNames(tools);
    return tools;
  }

  createActiveToolSnapshot(
    tools: readonly AgentTool[],
    requestedNames: readonly string[],
  ): string[] {
    this.requireUsable();
    validateUniqueToolNames(tools);
    const names = [...requestedNames];
    const duplicateNames = duplicates(names);
    if (duplicateNames.length > 0) {
      throw new Error(
        `PI_MOBILE_EXTENSION_ACTIVE_TOOL_DUPLICATE ${duplicateNames.join(",")}`,
      );
    }
    const available = new Set(tools.map((tool) => tool.name));
    const unknownNames = names.filter((name) => !available.has(name));
    if (unknownNames.length > 0) {
      throw new Error(
        `PI_MOBILE_EXTENSION_ACTIVE_TOOL_UNKNOWN ${unknownNames.join(",")}`,
      );
    }
    return names;
  }

  attach(harness: MobileExtensionHarnessHooks): void {
    if (this.lifecycle === "attached") {
      throw new Error("PI_MOBILE_EXTENSION_HOST_ALREADY_ATTACHED");
    }
    if (this.lifecycle !== "loaded") {
      throw new Error(`PI_MOBILE_EXTENSION_HOST_NOT_ATTACHABLE ${this.lifecycle}`);
    }

    const unsubscribers: MobileExtensionDisposer[] = [];
    try {
      if (this.contextHandlers.length > 0) {
        unsubscribers.push(harness.on("context", (event) => this.emitContext(event)));
      }
      if (this.toolResultHandlers.length > 0) {
        unsubscribers.push(
          harness.on("tool_result", (event) => this.emitToolResult(event)),
        );
      }
    } catch (error) {
      for (const unsubscribe of [...unsubscribers].reverse()) unsubscribe();
      throw new Error(
        `PI_MOBILE_EXTENSION_ATTACH_FAILED ${safeErrorMessage(error)}`,
      );
    }
    this.harnessUnsubscribers = unsubscribers;
    this.lifecycle = "attached";
  }

  snapshot(): MobileExtensionHostSnapshot {
    const status = this.lifecycle === "loading" ? "loaded" : this.lifecycle;
    return {
      status,
      supportedEventTypes: [...MOBILE_EXTENSION_SUPPORTED_EVENTS],
      extensionToolNames: this.extensionTools.map((tool) => tool.name),
      extensions: this.records.map((record) => ({
        id: record.descriptor.id,
        version: record.descriptor.version,
        source: record.descriptor.source,
        status,
        toolNames: [...record.toolNames],
        eventTypes: [...record.eventTypes],
      })),
    };
  }

  dispose(): void {
    if (this.lifecycle === "disposed") return;
    const errors: string[] = [];
    for (const unsubscribe of [...this.harnessUnsubscribers].reverse()) {
      try {
        unsubscribe();
      } catch (error) {
        errors.push(safeErrorMessage(error));
      }
    }
    this.harnessUnsubscribers = [];
    for (const record of [...this.records].reverse()) {
      try {
        record.dispose?.();
      } catch (error) {
        errors.push(`${record.descriptor.id}:${safeErrorMessage(error)}`);
      }
    }
    this.lifecycle = "disposed";
    if (errors.length > 0) {
      throw new Error(`PI_MOBILE_EXTENSION_DISPOSE_FAILED ${errors.join("|")}`);
    }
  }

  private load(descriptor: MobileExtensionDescriptor): void {
    const record: ExtensionRecord = {
      descriptor,
      toolNames: [],
      eventTypes: [],
    };
    let registrationOpen = true;
    const api: MobileExtensionApi = {
      registerTool: (tool) => {
        requireOpenRegistration(registrationOpen, descriptor.id);
        validateTool(tool, descriptor.id);
        if (this.extensionTools.some((candidate) => candidate.name === tool.name)) {
          throw new Error(`PI_MOBILE_EXTENSION_TOOL_COLLISION ${tool.name}`);
        }
        this.extensionTools.push(tool);
        record.toolNames.push(tool.name);
      },
      on: <TType extends MobileExtensionEventType>(
        type: TType,
        handler: MobileExtensionEventHandler<TType>,
      ) => {
        requireOpenRegistration(registrationOpen, descriptor.id);
        this.registerHandler(descriptor.id, type, handler);
        if (!record.eventTypes.includes(type)) record.eventTypes.push(type);
      },
    };

    let result: void | MobileExtensionDisposer;
    try {
      result = descriptor.factory(api);
    } catch (error) {
      registrationOpen = false;
      throw new Error(
        `PI_MOBILE_EXTENSION_LOAD_FAILED ${descriptor.id}:${safeErrorMessage(error)}`,
      );
    }
    registrationOpen = false;
    if (isPromiseLike(result)) {
      throw new Error(`PI_MOBILE_EXTENSION_ASYNC_FACTORY_UNSUPPORTED ${descriptor.id}`);
    }
    if (result !== undefined && typeof result !== "function") {
      throw new Error(`PI_MOBILE_EXTENSION_DISPOSER_INVALID ${descriptor.id}`);
    }
    record.dispose = result === undefined ? undefined : result;
    this.records.push(record);
  }

  private registerHandler<TType extends MobileExtensionEventType>(
    extensionId: string,
    type: TType,
    handler: MobileExtensionEventHandler<TType>,
  ): void {
    if (typeof handler !== "function") {
      throw new Error(`PI_MOBILE_EXTENSION_HANDLER_INVALID ${extensionId}:${type}`);
    }
    if (type === "context") {
      this.contextHandlers.push({
        extensionId,
        handler: handler as unknown as MobileExtensionEventHandler<"context">,
      });
      return;
    }
    if (type === "tool_result") {
      this.toolResultHandlers.push({
        extensionId,
        handler: handler as unknown as MobileExtensionEventHandler<"tool_result">,
      });
      return;
    }
    throw new Error(`PI_MOBILE_EXTENSION_EVENT_UNSUPPORTED ${String(type)}`);
  }

  private async emitContext(event: ContextEvent): Promise<ContextResult | undefined> {
    let messages = event.messages;
    let changed = false;
    for (const registration of this.contextHandlers) {
      let result: ContextResult | undefined;
      try {
        result = await registration.handler({ ...event, messages });
      } catch (error) {
        throw extensionEventError(registration.extensionId, event.type, error);
      }
      if (result?.messages !== undefined) {
        messages = result.messages;
        changed = true;
      }
    }
    return changed ? { messages } : undefined;
  }

  private async emitToolResult(
    event: ToolResultEvent,
  ): Promise<ToolResultPatch | undefined> {
    let current = { ...event };
    const patch: ToolResultPatch = {};
    let changed = false;
    for (const registration of this.toolResultHandlers) {
      let result: ToolResultPatch | undefined;
      try {
        result = await registration.handler(current);
      } catch (error) {
        throw extensionEventError(registration.extensionId, event.type, error);
      }
      if (result === undefined) continue;
      if (result.content !== undefined) {
        current = { ...current, content: result.content };
        patch.content = result.content;
        changed = true;
      }
      if (result.details !== undefined) {
        current = { ...current, details: result.details };
        patch.details = result.details;
        changed = true;
      }
      if (result.isError !== undefined) {
        current = { ...current, isError: result.isError };
        patch.isError = result.isError;
        changed = true;
      }
      if (result.terminate !== undefined) {
        patch.terminate = result.terminate;
        changed = true;
      }
    }
    return changed ? patch : undefined;
  }

  private rollbackFactories(): void {
    for (const record of [...this.records].reverse()) {
      try {
        record.dispose?.();
      } catch {
        // Preserve the original load failure while still attempting every cleanup.
      }
    }
  }

  private requireUsable(): void {
    if (this.lifecycle === "disposed") {
      throw new Error("PI_MOBILE_EXTENSION_HOST_DISPOSED");
    }
    if (this.lifecycle === "loading") {
      throw new Error("PI_MOBILE_EXTENSION_HOST_LOADING");
    }
  }
}

function validateDescriptors(descriptors: readonly MobileExtensionDescriptor[]): void {
  const ids: string[] = [];
  for (const descriptor of descriptors) {
    if (descriptor.source !== "builtin" && descriptor.source !== "package") {
      throw new Error(`PI_MOBILE_EXTENSION_SOURCE_UNSUPPORTED ${descriptor.id}`);
    }
    const idIsValid = descriptor.source === "package"
      ? descriptor.id.length <= 84 && PACKAGE_EXTENSION_ID_PATTERN.test(descriptor.id)
      : EXTENSION_ID_PATTERN.test(descriptor.id);
    if (!idIsValid) {
      throw new Error(`PI_MOBILE_EXTENSION_ID_INVALID ${descriptor.id}`);
    }
    if (
      descriptor.version.length === 0 ||
      descriptor.version.length > MAX_VERSION_LENGTH
    ) {
      throw new Error(`PI_MOBILE_EXTENSION_VERSION_INVALID ${descriptor.id}`);
    }
    if (typeof descriptor.factory !== "function") {
      throw new Error(`PI_MOBILE_EXTENSION_FACTORY_INVALID ${descriptor.id}`);
    }
    ids.push(descriptor.id);
  }
  const duplicateIds = duplicates(ids);
  if (duplicateIds.length > 0) {
    throw new Error(`PI_MOBILE_EXTENSION_ID_DUPLICATE ${duplicateIds.join(",")}`);
  }
}

function validateTool(tool: AgentTool, extensionId: string): void {
  if (tool === null || typeof tool !== "object" || typeof tool.name !== "string") {
    throw new Error(`PI_MOBILE_EXTENSION_TOOL_INVALID ${extensionId}`);
  }
  if (tool.name.length === 0) {
    throw new Error(`PI_MOBILE_EXTENSION_TOOL_NAME_INVALID ${extensionId}`);
  }
}

function validateUniqueToolNames(tools: readonly AgentTool[]): void {
  const duplicateNames = duplicates(tools.map((tool) => tool.name));
  if (duplicateNames.length > 0) {
    throw new Error(`PI_MOBILE_EXTENSION_TOOL_COLLISION ${duplicateNames.join(",")}`);
  }
}

function duplicates(values: readonly string[]): string[] {
  const seen = new Set<string>();
  const duplicateValues = new Set<string>();
  for (const value of values) {
    if (seen.has(value)) duplicateValues.add(value);
    else seen.add(value);
  }
  return [...duplicateValues];
}

function requireOpenRegistration(open: boolean, extensionId: string): void {
  if (!open) {
    throw new Error(`PI_MOBILE_EXTENSION_REGISTRATION_CLOSED ${extensionId}`);
  }
}

function extensionEventError(
  extensionId: string,
  eventType: MobileExtensionEventType,
  error: unknown,
): Error {
  return new Error(
    `PI_MOBILE_EXTENSION_EVENT_FAILED ${extensionId}:${eventType}:${safeErrorMessage(error)}`,
  );
}

function isPromiseLike(value: unknown): value is PromiseLike<unknown> {
  return value !== null &&
    typeof value === "object" &&
    "then" in value &&
    typeof value.then === "function";
}

function safeErrorMessage(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}
