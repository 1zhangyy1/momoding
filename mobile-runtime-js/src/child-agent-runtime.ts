import {
  AgentHarness,
  InMemorySessionStorage,
  Session,
  type AgentHarnessEvent,
  type AgentTool,
  type AgentToolResult,
  type ExecutionEnv,
} from "@earendil-works/pi-agent-core";
import type {
  Api,
  AssistantMessage,
  Model,
  Models,
} from "@earendil-works/pi-ai";
import { CHILD_ANALYSIS_SYSTEM_PROMPT } from "./system-prompts.js";

export const DELEGATE_TOOL_NAME = "delegate";
export const MAX_CHILDREN_PER_PARENT_TURN = 3;

export type PiChildAgentState =
  | "running"
  | "completed"
  | "failed"
  | "cancelled";

export interface PiChildBinding {
  parentTaskId: string;
  parentToolCallId: string;
  childId: string;
  childName: string;
}

export interface PiChildEventEnvelope extends PiChildBinding {
  eventOrdinal: number;
  event: unknown;
}

export interface PiChildAgentSnapshot extends PiChildBinding {
  instruction: string;
  state: PiChildAgentState;
  resultSummary: string | null;
  resultText: string | null;
  resultTruncated: boolean;
  terminalReason: string | null;
  stopReason: string | null;
  model: string | null;
  turnCount: number;
  inputTokens: number;
  outputTokens: number;
  cacheReadTokens: number;
  cacheWriteTokens: number;
  contextTokens: number;
  costUsd: number;
  eventTypes: string[];
  eventCount: number;
}

interface PiChildAgentRecord extends PiChildAgentSnapshot {
  harness: AgentHarness;
  unsubscribe: () => void;
  abortReason: string | null;
  abortPromise: Promise<unknown> | null;
}

export interface PiChildAgentManagerOptions {
  parentTaskId: string;
  env: ExecutionEnv;
  model: Model<Api>;
  createModels: (binding: PiChildBinding) => Models;
  onEvent?: (event: PiChildEventEnvelope) => void;
  maxChildrenPerTurn?: number;
}

export class PiChildAgentManager {
  private readonly children = new Map<string, PiChildAgentRecord>();
  private readonly maxChildrenPerTurn: number;
  private nextChildId = 1;
  private issuedThisParentTurn = 0;

  constructor(private readonly options: PiChildAgentManagerOptions) {
    const max = options.maxChildrenPerTurn ?? MAX_CHILDREN_PER_PARENT_TURN;
    if (!Number.isSafeInteger(max) || max < 1 || max > MAX_CHILDREN_PER_PARENT_TURN) {
      throw new Error("PI_MOBILE_CHILD_LIMIT_INVALID");
    }
    this.maxChildrenPerTurn = max;
  }

  beginParentTurn(): void {
    if (this.runningCount() !== 0) {
      throw new Error("PI_MOBILE_CHILD_PARENT_TURN_OVERLAP");
    }
    this.issuedThisParentTurn = 0;
  }

  delegateTool(): AgentTool {
    return {
      name: DELEGATE_TOOL_NAME,
      label: "Delegate analysis",
      description: [
        "Delegate one bounded read-only analysis task to an isolated Pi child AgentHarness.",
        "The child has no file, terminal, attention, or nested delegation tools.",
        `At most ${this.maxChildrenPerTurn} delegate calls may run in one parent turn.`,
      ].join(" "),
      parameters: {
        type: "object",
        properties: {
          name: {
            type: "string",
            minLength: 1,
            maxLength: 64,
            pattern: "^[A-Za-z0-9][A-Za-z0-9 _.-]{0,63}$",
          },
          task: { type: "string", minLength: 1, maxLength: 8192 },
        },
        required: ["name", "task"],
        additionalProperties: false,
      } as AgentTool["parameters"],
      executionMode: "parallel",
      execute: async (toolCallId, params, signal) =>
        await this.runChild(
          toolCallId,
          params as { name: string; task: string },
          signal,
        ),
    };
  }

  snapshots(): PiChildAgentSnapshot[] {
    return [...this.children.values()].map((child) => this.snapshot(child));
  }

  cancel(childId: string, reason = "user_cancelled"): boolean {
    const child = this.children.get(childId);
    if (child === undefined || child.state !== "running") return false;
    void this.requestAbort(child, reason).catch(() => undefined);
    return true;
  }

  async cancelAll(reason = "parent_stopped"): Promise<number> {
    const running = [...this.children.values()].filter((child) => child.state === "running");
    await Promise.all(running.map(async (child) => {
      await this.requestAbort(child, reason);
    }));
    return running.length;
  }

  evictTerminal(childIds: string[]): string[] {
    const evicted: string[] = [];
    for (const childId of [...new Set(childIds)]) {
      const child = this.children.get(childId);
      if (child === undefined) continue;
      if (child.state === "running") {
        throw new Error("PI_MOBILE_CHILD_STILL_RUNNING");
      }
      child.unsubscribe();
      this.children.delete(childId);
      evicted.push(childId);
    }
    return evicted;
  }

  close(reason = "runtime_rebuilt"): void {
    for (const child of this.children.values()) {
      if (child.state === "running") {
        void this.requestAbort(child, reason)
          .catch(() => undefined)
          .finally(child.unsubscribe);
      } else {
        child.unsubscribe();
      }
    }
  }

  private async runChild(
    parentToolCallId: string,
    params: { name: string; task: string },
    signal?: AbortSignal,
  ): Promise<AgentToolResult<PiChildAgentSnapshot>> {
    if (this.issuedThisParentTurn >= this.maxChildrenPerTurn) {
      throw new Error("PI_MOBILE_CHILD_LIMIT_REACHED");
    }
    const childName = requireChildName(params.name);
    const instruction = requireInstruction(params.task);
    const childId = `child-${this.nextChildId++}`;
    const binding: PiChildBinding = {
      parentTaskId: this.options.parentTaskId,
      parentToolCallId,
      childId,
      childName,
    };
    const session = new Session(
      new InMemorySessionStorage({
        metadata: {
          id: `pi-mobile-${this.options.parentTaskId}-${childId}`,
          createdAt: "1970-01-01T00:00:00.000Z",
        },
      }),
    );
    const harness = new AgentHarness({
      env: this.options.env,
      session,
      models: this.options.createModels(binding),
      model: this.options.model,
      tools: [],
      activeToolNames: [],
      systemPrompt: CHILD_ANALYSIS_SYSTEM_PROMPT,
    });
    const record: PiChildAgentRecord = {
      ...binding,
      instruction,
      state: "running",
      resultSummary: null,
      resultText: null,
      resultTruncated: false,
      terminalReason: null,
      stopReason: null,
      model: null,
      turnCount: 0,
      inputTokens: 0,
      outputTokens: 0,
      cacheReadTokens: 0,
      cacheWriteTokens: 0,
      contextTokens: 0,
      costUsd: 0,
      eventTypes: [],
      eventCount: 0,
      harness,
      unsubscribe: () => undefined,
      abortReason: null,
      abortPromise: null,
    };
    record.unsubscribe = harness.subscribe((event) => this.recordEvent(record, event));
    this.children.set(childId, record);
    this.issuedThisParentTurn += 1;

    const abortListener = () => {
      if (record.state !== "running") return;
      void this.requestAbort(record, "parent_stopped").catch(() => undefined);
    };
    if (signal?.aborted) abortListener();
    else signal?.addEventListener("abort", abortListener, { once: true });

    try {
      const message = await harness.prompt(instruction);
      applyMessageDetails(record, message);
      if (record.abortReason !== null || signal?.aborted) {
        record.state = "cancelled";
        record.terminalReason = record.abortReason ?? "parent_stopped";
      } else if (message.stopReason === "error" || message.stopReason === "aborted") {
        record.state = "failed";
        record.terminalReason = safeErrorMessage(
          message.errorMessage ?? `Child Provider stopped with ${message.stopReason}`,
        );
      } else {
        record.state = "completed";
        record.resultSummary = shortSummary(message);
      }
    } catch (error: unknown) {
      if (record.abortReason !== null || signal?.aborted) {
        record.state = "cancelled";
        record.terminalReason = record.abortReason ?? "parent_stopped";
      } else {
        record.state = "failed";
        record.terminalReason = safeErrorMessage(error);
      }
    } finally {
      signal?.removeEventListener("abort", abortListener);
      if (record.abortPromise !== null) {
        try {
          await record.abortPromise;
        } catch {
          // The child state above already exposes cancellation/failure. Keep the native Pi
          // subscription alive through abort settlement so its terminal event is not lost.
        }
      }
      record.unsubscribe();
    }

    const snapshot = this.snapshot(record);
    const result: AgentToolResult<PiChildAgentSnapshot> = {
      content: [{ type: "text", text: JSON.stringify(toolResultSummary(snapshot)) }],
      details: snapshot,
    };
    if (snapshot.state === "cancelled" && signal?.aborted) {
      return { ...result, terminate: true };
    }
    if (snapshot.state !== "completed") {
      throw new Error(
        `PI_MOBILE_CHILD_${snapshot.state.toUpperCase()} ${JSON.stringify(toolResultSummary(snapshot))}`,
      );
    }
    return result;
  }

  private recordEvent(record: PiChildAgentRecord, event: AgentHarnessEvent): void {
    const envelope: PiChildEventEnvelope = {
      parentTaskId: record.parentTaskId,
      parentToolCallId: record.parentToolCallId,
      childId: record.childId,
      childName: record.childName,
      eventOrdinal: record.eventCount,
      event: JSON.parse(JSON.stringify(event)) as unknown,
    };
    if (!record.eventTypes.includes(event.type)) record.eventTypes.push(event.type);
    record.eventCount += 1;
    this.options.onEvent?.(envelope);
  }

  private snapshot(record: PiChildAgentRecord): PiChildAgentSnapshot {
    return {
      parentTaskId: record.parentTaskId,
      parentToolCallId: record.parentToolCallId,
      childId: record.childId,
      childName: record.childName,
      instruction: record.instruction,
      state: record.state,
      resultSummary: record.resultSummary,
      resultText: record.resultText,
      resultTruncated: record.resultTruncated,
      terminalReason: record.terminalReason,
      stopReason: record.stopReason,
      model: record.model,
      turnCount: record.turnCount,
      inputTokens: record.inputTokens,
      outputTokens: record.outputTokens,
      cacheReadTokens: record.cacheReadTokens,
      cacheWriteTokens: record.cacheWriteTokens,
      contextTokens: record.contextTokens,
      costUsd: record.costUsd,
      eventTypes: [...record.eventTypes],
      eventCount: record.eventCount,
    };
  }

  private runningCount(): number {
    return [...this.children.values()].filter((child) => child.state === "running").length;
  }

  private requestAbort(record: PiChildAgentRecord, reason: string): Promise<unknown> {
    const safeReason = requireReason(reason);
    record.abortReason ??= safeReason;
    if (record.abortPromise === null) record.abortPromise = record.harness.abort();
    return record.abortPromise;
  }
}

function requireChildName(value: string): string {
  const trimmed = value.trim();
  if (!/^[A-Za-z0-9][A-Za-z0-9 _.-]{0,63}$/.test(trimmed)) {
    throw new Error("PI_MOBILE_CHILD_NAME_INVALID");
  }
  return trimmed;
}

function requireInstruction(value: string): string {
  const trimmed = value.trim();
  if (trimmed.length < 1 || trimmed.length > 8192) {
    throw new Error("PI_MOBILE_CHILD_TASK_INVALID");
  }
  return trimmed;
}

function requireReason(value: string): string {
  const trimmed = value.trim();
  if (trimmed.length < 1 || trimmed.length > 256) {
    throw new Error("PI_MOBILE_CHILD_CANCEL_REASON_INVALID");
  }
  return trimmed;
}

function shortSummary(message: AssistantMessage): string {
  const text = assistantText(message);
  if (text.length === 0) return "(no child text result)";
  return text.length <= 2048 ? text : `${text.slice(0, 2047)}…`;
}

function assistantText(message: AssistantMessage): string {
  return message.content
    .filter((block) => block.type === "text")
    .map((block) => block.text)
    .join("")
    .trim();
}

function applyMessageDetails(record: PiChildAgentRecord, message: AssistantMessage): void {
  const text = assistantText(message);
  record.resultSummary = shortSummary(message);
  record.resultTruncated = text.length > 16_384;
  record.resultText = record.resultTruncated ? `${text.slice(0, 16_383)}…` : text;
  record.stopReason = message.stopReason;
  record.model = message.responseModel ?? message.model;
  record.turnCount = 1;
  record.inputTokens = message.usage.input;
  record.outputTokens = message.usage.output;
  record.cacheReadTokens = message.usage.cacheRead;
  record.cacheWriteTokens = message.usage.cacheWrite;
  record.contextTokens = message.usage.totalTokens;
  record.costUsd = message.usage.cost.total;
}

function safeErrorMessage(error: unknown): string {
  const message = error instanceof Error ? error.message : String(error);
  const trimmed = message.trim();
  if (trimmed.length === 0) return "PI_MOBILE_CHILD_FAILED";
  return trimmed.length <= 512 ? trimmed : `${trimmed.slice(0, 511)}…`;
}

function toolResultSummary(snapshot: PiChildAgentSnapshot): Record<string, unknown> {
  return {
    childId: snapshot.childId,
    childName: snapshot.childName,
    state: snapshot.state,
    resultSummary: snapshot.resultSummary,
    terminalReason: snapshot.terminalReason,
  };
}
