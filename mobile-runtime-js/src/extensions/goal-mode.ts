import type {
  AgentHarness,
  AgentTool,
  AgentToolResult,
  Session,
  SessionTreeEntry,
} from "@earendil-works/pi-agent-core";

export type TaskGoalLifecycleState =
  | "active"
  | "paused"
  | "blocked"
  | "limited"
  | "failed"
  | "achieved"
  | "cleared";

export type TaskGoalTargetState = "paused" | "limited" | "failed" | "cleared";

export interface TaskGoalSnapshot {
  goalId: string;
  instruction: string;
  state: TaskGoalLifecycleState;
  progressSummary: string | null;
  progressMarker: string | null;
  terminalReason: string | null;
  generation: number;
  startedAtMillis: number;
  preGoalActiveToolNames: string[];
}

export interface GoalExtensionState {
  taskId: string | null;
  harness: AgentHarness;
  session: Session;
  goal: TaskGoalSnapshot | null;
}

export const TASK_GOAL_PROGRESS_TOOL_NAME = "task_goal_progress";
export const TASK_GOAL_COMPLETE_TOOL_NAME = "task_goal_complete";
export const GOAL_STATE_ENTRY_TYPE = "pi_mobile_task_goal";
export const GOAL_CONTINUATION_CONTROL_ENTRY_TYPE = "pi_mobile_goal_continuation";
export const GOAL_TOOL_NAMES = [
  TASK_GOAL_PROGRESS_TOOL_NAME,
  TASK_GOAL_COMPLETE_TOOL_NAME,
];

export function createGoalTools(getState: () => GoalExtensionState): AgentTool[] {
  return [
    {
      name: TASK_GOAL_PROGRESS_TOOL_NAME,
      label: "Report goal progress",
      description: "Persist one concise progress checkpoint for the active user-created goal when more work remains.",
      parameters: {
        type: "object",
        properties: {
          summary: { type: "string", minLength: 1, maxLength: 4096 },
          progressMarker: { type: "string", minLength: 1, maxLength: 128 },
        },
        required: ["summary", "progressMarker"],
        additionalProperties: false,
      } as AgentTool["parameters"],
      executionMode: "sequential",
      execute: async (_toolCallId, params) => {
        const state = getState();
        const goal = requireActiveTaskGoal(state);
        const progress = requireTaskGoalProgress(params);
        state.goal = { ...goal, ...progress, terminalReason: null };
        await state.session.appendCustomEntry(GOAL_STATE_ENTRY_TYPE, {
          action: "progress",
          ...state.goal,
        });
        return goalToolResult(TASK_GOAL_PROGRESS_TOOL_NAME, state.goal);
      },
    },
    {
      name: TASK_GOAL_COMPLETE_TOOL_NAME,
      label: "Complete goal",
      description: "Persist the terminal outcome of the active user-created goal as achieved, blocked, or failed.",
      parameters: {
        type: "object",
        properties: {
          summary: { type: "string", minLength: 1, maxLength: 4096 },
          terminalReason: { type: "string", enum: ["achieved", "blocked", "failed"] },
        },
        required: ["summary", "terminalReason"],
        additionalProperties: false,
      } as AgentTool["parameters"],
      executionMode: "sequential",
      execute: async (_toolCallId, params) => {
        const state = getState();
        const goal = requireActiveTaskGoal(state);
        const completion = requireTaskGoalCompletion(params);
        const terminalState = completion.terminalReason as "achieved" | "blocked" | "failed";
        state.goal = {
          ...goal,
          state: terminalState,
          progressSummary: completion.summary,
          terminalReason: completion.terminalReason,
        };
        await state.session.appendCustomEntry(GOAL_STATE_ENTRY_TYPE, {
          action: "complete",
          ...state.goal,
        });
        await state.harness.setActiveTools(goal.preGoalActiveToolNames);
        return goalToolResult(TASK_GOAL_COMPLETE_TOOL_NAME, state.goal);
      },
    },
  ];
}

export async function startGoal(
  state: GoalExtensionState,
  goalId: string,
  instruction: string,
  generation: number,
  startedAtMillis: number,
): Promise<string> {
  const exactActiveTools = activeToolNames(state).filter((name) => !GOAL_TOOL_NAMES.includes(name));
  const goalTools = availableGoalToolNames(state);
  const goal: TaskGoalSnapshot = {
    goalId,
    instruction,
    state: "active",
    progressSummary: null,
    progressMarker: null,
    terminalReason: null,
    generation,
    startedAtMillis,
    preGoalActiveToolNames: exactActiveTools,
  };
  await state.session.appendCustomEntry(GOAL_STATE_ENTRY_TYPE, { action: "create", ...goal });
  await state.harness.setActiveTools([...exactActiveTools, ...goalTools]);
  state.goal = goal;
  return prepareGoalContinuation(state, goal, 0, "start");
}

export async function continueGoal(
  state: GoalExtensionState,
  prior: TaskGoalSnapshot,
  turnIndex: number,
  resume: boolean,
): Promise<string> {
  let goal = prior;
  if (resume) {
    const goalTools = availableGoalToolNames(state);
    await state.harness.setActiveTools([...prior.preGoalActiveToolNames, ...goalTools]);
    goal = { ...prior, state: "active", terminalReason: null };
    await state.session.appendCustomEntry(GOAL_STATE_ENTRY_TYPE, { action: "resume", ...goal });
    state.goal = goal;
  }
  return prepareGoalContinuation(state, goal, turnIndex, resume ? "resume" : "continue");
}

export async function transitionGoalState(
  state: GoalExtensionState,
  prior: TaskGoalSnapshot,
  targetState: TaskGoalTargetState,
): Promise<TaskGoalSnapshot> {
  const goal: TaskGoalSnapshot = {
    ...prior,
    state: targetState,
    terminalReason: targetState === "limited"
      ? "limit_reached"
      : targetState === "failed"
        ? "turn_failed"
        : null,
  };
  await state.session.appendCustomEntry(GOAL_STATE_ENTRY_TYPE, {
    action: targetState,
    ...goal,
  });
  await state.harness.setActiveTools(prior.preGoalActiveToolNames);
  state.goal = goal;
  return goal;
}

export function restoreGoalExtensionState(entries: SessionTreeEntry[]): TaskGoalSnapshot | null {
  let latest: TaskGoalSnapshot | null = null;
  for (const entry of entries) {
    if (entry.type !== "custom" || entry.customType !== GOAL_STATE_ENTRY_TYPE) continue;
    latest = parseTaskGoalSnapshot(entry.data) ?? latest;
  }
  return latest;
}

export function requireGoalIdentity(
  goalId: string,
  instruction: string,
  generation: number,
  startedAtMillis: number,
): void {
  if (!/^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$/.test(goalId)) {
    throw new Error("PI_MOBILE_GOAL_ID_INVALID");
  }
  const normalized = instruction.trim();
  if (normalized.length < 1 || normalized.length > 4096) {
    throw new Error("PI_MOBILE_GOAL_INSTRUCTION_INVALID");
  }
  if (!Number.isSafeInteger(generation) || generation < 1) {
    throw new Error("PI_MOBILE_GOAL_GENERATION_INVALID");
  }
  if (!Number.isSafeInteger(startedAtMillis) || startedAtMillis < 0) {
    throw new Error("PI_MOBILE_GOAL_STARTED_AT_INVALID");
  }
}

export function requireGoalContinuation(
  goalId: string,
  generation: number,
  turnIndex: number,
): void {
  requireGoalIdentity(goalId, "goal", generation, 0);
  if (!Number.isSafeInteger(turnIndex) || turnIndex < 0 || turnIndex > 10_000) {
    throw new Error("PI_MOBILE_GOAL_TURN_INDEX_INVALID");
  }
}

export function requireMatchingGoal(
  state: GoalExtensionState,
  goalId: string,
  generation: number,
): TaskGoalSnapshot {
  const goal = state.goal;
  if (goal === null || goal.goalId !== goalId) throw new Error("PI_MOBILE_GOAL_NOT_FOUND");
  if (goal.generation !== generation) throw new Error("PI_MOBILE_GOAL_GENERATION_STALE");
  return goal;
}

async function prepareGoalContinuation(
  state: GoalExtensionState,
  goal: TaskGoalSnapshot,
  turnIndex: number,
  trigger: "start" | "continue" | "resume",
): Promise<string> {
  const taskId = state.taskId;
  if (taskId === null) throw new Error("PI_MOBILE_GOAL_TASK_MISSING");
  const controlId = await state.session.appendCustomEntry(
    GOAL_CONTINUATION_CONTROL_ENTRY_TYPE,
    {
      kind: "goal_continuation",
      taskId,
      goalId: goal.goalId,
      generation: goal.generation,
      turnIndex,
      trigger,
    },
  );
  return [
    `[momoding:goal-continuation control=${controlId}]`,
    `Continue the exact user-created goal: ${goal.instruction}`,
    goal.progressSummary === null ? "No prior progress checkpoint." : `Prior progress: ${goal.progressSummary}`,
    `goalId=${goal.goalId}`,
    `generation=${goal.generation}`,
    `turnIndex=${turnIndex}`,
  ].join("\n");
}

function availableGoalToolNames(state: GoalExtensionState): string[] {
  const names = GOAL_TOOL_NAMES.filter((name) =>
    state.harness.getTools().some((tool) => tool.name === name)
  );
  if (names.length !== GOAL_TOOL_NAMES.length) {
    throw new Error("PI_MOBILE_GOAL_TOOLS_MISSING");
  }
  return names;
}

function activeToolNames(state: GoalExtensionState): string[] {
  return state.harness.getActiveTools().map((tool) => tool.name);
}

function requireActiveTaskGoal(state: GoalExtensionState): TaskGoalSnapshot {
  const goal = state.goal;
  if (goal === null || goal.state !== "active") throw new Error("PI_MOBILE_GOAL_NOT_ACTIVE");
  return goal;
}

function requireTaskGoalProgress(value: unknown): {
  progressSummary: string;
  progressMarker: string;
} {
  if (!isRecord(value)) throw new Error("PI_MOBILE_GOAL_PROGRESS_INVALID");
  const summary = typeof value.summary === "string" ? value.summary.trim() : "";
  const marker = typeof value.progressMarker === "string" ? value.progressMarker.trim() : "";
  if (summary.length < 1 || summary.length > 4096) {
    throw new Error("PI_MOBILE_GOAL_PROGRESS_SUMMARY_INVALID");
  }
  if (marker.length < 1 || marker.length > 128) {
    throw new Error("PI_MOBILE_GOAL_PROGRESS_MARKER_INVALID");
  }
  return { progressSummary: summary, progressMarker: marker };
}

function requireTaskGoalCompletion(value: unknown): {
  summary: string;
  terminalReason: "achieved" | "blocked" | "failed";
} {
  if (!isRecord(value)) throw new Error("PI_MOBILE_GOAL_COMPLETION_INVALID");
  const summary = typeof value.summary === "string" ? value.summary.trim() : "";
  const terminalReason = value.terminalReason;
  if (summary.length < 1 || summary.length > 4096) {
    throw new Error("PI_MOBILE_GOAL_COMPLETION_SUMMARY_INVALID");
  }
  if (terminalReason !== "achieved" && terminalReason !== "blocked" && terminalReason !== "failed") {
    throw new Error("PI_MOBILE_GOAL_TERMINAL_REASON_INVALID");
  }
  return { summary, terminalReason };
}

function goalToolResult(
  toolName: string,
  goal: TaskGoalSnapshot,
): AgentToolResult<unknown> {
  const details = { ok: true, kind: toolName, goal };
  return {
    content: [{ type: "text", text: JSON.stringify(details) }],
    details,
  };
}

function parseTaskGoalSnapshot(value: unknown): TaskGoalSnapshot | null {
  if (!isRecord(value)) return null;
  const goalId = typeof value.goalId === "string" ? value.goalId : "";
  const instruction = typeof value.instruction === "string" ? value.instruction.trim() : "";
  const state = value.state;
  const progressSummary = value.progressSummary;
  const progressMarker = value.progressMarker;
  const terminalReason = value.terminalReason;
  const generation = value.generation;
  const startedAtMillis = value.startedAtMillis;
  const preGoalActiveToolNames = stringArray(value.preGoalActiveToolNames);
  if (!/^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$/.test(goalId)) return null;
  if (instruction.length < 1 || instruction.length > 4096) return null;
  if (!isGoalLifecycleState(state)) return null;
  if (progressSummary !== null && (typeof progressSummary !== "string" || progressSummary.length > 4096)) {
    return null;
  }
  if (progressMarker !== null && (
    typeof progressMarker !== "string" ||
    progressMarker.length < 1 ||
    progressMarker.length > 128
  )) return null;
  if (terminalReason !== null && (typeof terminalReason !== "string" || terminalReason.length > 128)) {
    return null;
  }
  if (!Number.isSafeInteger(generation) || (generation as number) < 1) return null;
  if (!Number.isSafeInteger(startedAtMillis) || (startedAtMillis as number) < 0) return null;
  if (preGoalActiveToolNames === null) return null;
  return {
    goalId,
    instruction,
    state,
    progressSummary: progressSummary as string | null,
    progressMarker: progressMarker as string | null,
    terminalReason: terminalReason as string | null,
    generation: generation as number,
    startedAtMillis: startedAtMillis as number,
    preGoalActiveToolNames,
  };
}

function isGoalLifecycleState(value: unknown): value is TaskGoalLifecycleState {
  return typeof value === "string" && [
    "active",
    "paused",
    "blocked",
    "limited",
    "failed",
    "achieved",
    "cleared",
  ].includes(value);
}

function stringArray(value: unknown): string[] | null {
  if (!Array.isArray(value) || value.some((item) => typeof item !== "string")) return null;
  const names = value as string[];
  return names.length === new Set(names).size ? [...names] : null;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}
