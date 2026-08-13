import type {
  AgentHarness,
  AgentTool,
  ContextEvent,
  ContextResult,
  ToolResultEvent,
  ToolResultPatch,
} from "@earendil-works/pi-agent-core";
import {
  MobileExtensionHost,
  type MobileExtensionApi,
  type MobileExtensionDescriptor,
  type MobileExtensionEventHandler,
  type MobileExtensionEventType,
} from "./mobile-extension-host.js";

export async function mobileExtensionHostContract(): Promise<Record<string, unknown>> {
  const eventTrace: string[] = [];
  const contextTrace: string[] = [];
  const disposeTrace: string[] = [];
  let capturedApi: MobileExtensionApi | null = null;

  const host = new MobileExtensionHost([
    descriptor("fixture.alpha", (api) => {
      capturedApi = api;
      api.registerTool(fixtureTool("fixture_alpha"));
      api.on("context", (event) => {
        contextTrace.push("alpha");
        return { messages: event.messages.slice(1) };
      });
      api.on("tool_result", (event) => {
        eventTrace.push("alpha");
        return { details: { alpha: true, original: event.details } };
      });
      return () => disposeTrace.push("alpha");
    }),
    descriptor("fixture.beta", (api) => {
      api.registerTool(fixtureTool("fixture_beta"));
      api.on("context", (event) => {
        contextTrace.push(`beta:${event.messages.length}`);
        return { messages: [...event.messages] };
      });
      api.on("tool_result", (event) => {
        const sawAlpha = (event.details as { alpha?: boolean } | null)?.alpha === true;
        eventTrace.push(`beta:${sawAlpha}`);
        return { isError: true, terminate: true };
      });
      return () => disposeTrace.push("beta");
    }),
  ]);
  const legacyTool = fixtureTool("legacy_tool");
  const tools = host.composeTools([legacyTool]);
  const activeToolNames = host.createActiveToolSnapshot(
    tools,
    ["legacy_tool", "fixture_beta"],
  );
  const loaded = host.snapshot();
  const lateRegistrationError = captureError(() => {
    if (capturedApi === null) throw new Error("fixture API missing");
    capturedApi.registerTool(fixtureTool("fixture_late"));
  });

  const harness = new FixtureHarness();
  host.attach(harness as unknown as Pick<AgentHarness, "on">);
  const attached = host.snapshot();
  const repeatedAttachError = captureError(() =>
    host.attach(harness as unknown as Pick<AgentHarness, "on">)
  );
  const contextPatch = await harness.emitContext({
    type: "context",
    messages: [
      { role: "user", content: "first", timestamp: 0 },
      { role: "user", content: "second", timestamp: 1 },
    ],
  });
  const toolResultPatch = await harness.emitToolResult({
    type: "tool_result",
    toolCallId: "fixture-call",
    toolName: "fixture_alpha",
    input: {},
    content: [{ type: "text", text: "fixture" }],
    details: { original: true },
    isError: false,
  });
  const attachedHandlerCount = harness.handlerCount();
  host.dispose();
  host.dispose();
  const disposed = host.snapshot();

  const rollbackTrace: string[] = [];
  const rollbackError = captureError(() => new MobileExtensionHost([
    descriptor("fixture.rollback", () => () => rollbackTrace.push("rollback")),
    descriptor("fixture.failure", () => {
      throw new Error("expected load failure");
    }),
  ]));
  const failingDisposeTrace: string[] = [];
  const failingDisposeHost = new MobileExtensionHost([
    descriptor("fixture.dispose-first", () => () => {
      failingDisposeTrace.push("first");
      throw new Error("expected dispose failure");
    }),
    descriptor("fixture.dispose-second", () => () => {
      failingDisposeTrace.push("second");
    }),
  ]);
  const failingDisposeError = captureError(() => failingDisposeHost.dispose());

  return {
    toolNames: tools.map((tool) => tool.name),
    activeToolNames,
    loaded,
    attached,
    disposed,
    eventTrace,
    contextTrace,
    contextPatch,
    toolResultPatch,
    attachedHandlerCount,
    disposedHandlerCount: harness.handlerCount(),
    disposeTrace,
    lateRegistrationError,
    repeatedAttachError,
    duplicateDescriptorError: captureError(() => new MobileExtensionHost([
      descriptor("fixture.duplicate", () => undefined),
      descriptor("fixture.duplicate", () => undefined),
    ])),
    duplicateExtensionToolError: captureError(() => new MobileExtensionHost([
      descriptor("fixture.one", (api) => api.registerTool(fixtureTool("same_tool"))),
      descriptor("fixture.two", (api) => api.registerTool(fixtureTool("same_tool"))),
    ])),
    legacyCollisionError: captureError(() => {
      const collisionHost = new MobileExtensionHost([
        descriptor("fixture.collision", (api) => {
          api.registerTool(fixtureTool("legacy_tool"));
        }),
      ]);
      collisionHost.composeTools([legacyTool]);
    }),
    duplicateActiveToolError: captureError(() => {
      const activeHost = new MobileExtensionHost([]);
      activeHost.createActiveToolSnapshot([legacyTool], ["legacy_tool", "legacy_tool"]);
    }),
    unknownActiveToolError: captureError(() => {
      const activeHost = new MobileExtensionHost([]);
      activeHost.createActiveToolSnapshot([legacyTool], ["missing_tool"]);
    }),
    asyncFactoryError: captureError(() => new MobileExtensionHost([{
      id: "fixture.async",
      version: "1.0.0",
      source: "builtin",
      factory: (() => Promise.resolve()) as unknown as MobileExtensionDescriptor["factory"],
    }])),
    rollbackError,
    rollbackTrace,
    failingDisposeError,
    failingDisposeTrace,
    failingDisposeStatus: failingDisposeHost.snapshot().status,
    disposedHostError: captureError(() => host.composeTools([])),
  };
}

class FixtureHarness {
  private readonly contextHandlers: MobileExtensionEventHandler<"context">[] = [];
  private readonly toolResultHandlers: MobileExtensionEventHandler<"tool_result">[] = [];

  on(
    type: "context",
    handler: MobileExtensionEventHandler<"context">,
  ): () => void;
  on(
    type: "tool_result",
    handler: MobileExtensionEventHandler<"tool_result">,
  ): () => void;
  on(
    type: MobileExtensionEventType,
    handler:
      | MobileExtensionEventHandler<"context">
      | MobileExtensionEventHandler<"tool_result">,
  ): () => void {
    if (type === "context") {
      const exactHandler = handler as unknown as MobileExtensionEventHandler<"context">;
      this.contextHandlers.push(exactHandler);
      return () => removeHandler(this.contextHandlers, exactHandler);
    }
    const exactHandler = handler as unknown as MobileExtensionEventHandler<"tool_result">;
    this.toolResultHandlers.push(exactHandler);
    return () => removeHandler(this.toolResultHandlers, exactHandler);
  }

  async emitContext(event: ContextEvent): Promise<ContextResult | undefined> {
    let result: ContextResult | undefined;
    for (const handler of this.contextHandlers) result = await handler(event);
    return result;
  }

  async emitToolResult(event: ToolResultEvent): Promise<ToolResultPatch | undefined> {
    let result: ToolResultPatch | undefined;
    for (const handler of this.toolResultHandlers) result = await handler(event);
    return result;
  }

  handlerCount(): number {
    return this.contextHandlers.length + this.toolResultHandlers.length;
  }
}

function descriptor(
  id: string,
  factory: MobileExtensionDescriptor["factory"],
): MobileExtensionDescriptor {
  return { id, version: "1.0.0", source: "builtin", factory };
}

function fixtureTool(name: string): AgentTool {
  return {
    name,
    label: name,
    description: `Deterministic ${name} fixture`,
    parameters: {
      type: "object",
      properties: {},
      additionalProperties: false,
    } as AgentTool["parameters"],
    execute: async () => ({
      content: [{ type: "text", text: name }],
      details: { name },
    }),
  };
}

function captureError(block: () => unknown): string | null {
  try {
    block();
    return null;
  } catch (error) {
    return error instanceof Error ? error.message : String(error);
  }
}

function removeHandler<T>(handlers: T[], handler: T): void {
  const index = handlers.indexOf(handler);
  if (index >= 0) handlers.splice(index, 1);
}
