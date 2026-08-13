import type {
  AgentHarness,
  AgentTool,
  Session,
  SessionTreeEntry,
} from "@earendil-works/pi-agent-core";
import { sha256 } from "../sha256.js";

export interface TaskPlanStep {
  id: string;
  text: string;
  status: "pending" | "in_progress" | "completed";
}

export interface TaskPlanSnapshot {
  explanation: string;
  steps: TaskPlanStep[];
  planDigest: string;
}

export interface PlanExtensionState {
  taskId: string | null;
  harness: AgentHarness;
  session: Session;
  planMode: boolean;
  prePlanActiveToolNames: string[] | null;
  latestPlan: TaskPlanSnapshot | null;
}

export interface RestoredPlanExtensionState {
  planMode: boolean;
  prePlanActiveToolNames: string[] | null;
  activeToolNames: string[] | null;
  latestPlan: TaskPlanSnapshot | null;
}

export const TASK_PLAN_UPDATE_TOOL_NAME = "task_plan_update";
export const PLAN_MODE_ENTRY_TYPE = "pi_mobile_plan_mode";
export const PLAN_SNAPSHOT_ENTRY_TYPE = "pi_mobile_task_plan";
export const PLAN_IMPLEMENT_CONTROL_ENTRY_TYPE = "pi_mobile_plan_implementation";

export const PLAN_ALLOWED_TOOL_NAMES = [
  "request_user_question",
  "request_user_confirmation",
  "device_capabilities_get",
  "device_files_list",
  "device_files_read",
  "device_media_list",
  "device_screen_capture",
  "device_location",
  "device_ui_inspect",
  "device_packages_list",
  "device_package_inspect",
  "attachment_read",
  TASK_PLAN_UPDATE_TOOL_NAME,
];

export function createPlanUpdateTool(
  getState: () => PlanExtensionState,
): AgentTool {
  return {
    name: TASK_PLAN_UPDATE_TOOL_NAME,
    label: "Update task plan",
    description: "Publish the complete structured implementation plan for user review without executing it.",
    parameters: {
      type: "object",
      properties: {
        explanation: { type: "string", minLength: 1, maxLength: 4096 },
        steps: {
          type: "array",
          minItems: 1,
          maxItems: 12,
          items: {
            type: "object",
            properties: {
              id: { type: "string", pattern: "^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$" },
              text: { type: "string", minLength: 1, maxLength: 1024 },
              status: { type: "string", enum: ["pending", "in_progress", "completed"] },
            },
            required: ["id", "text", "status"],
            additionalProperties: false,
          },
        },
      },
      required: ["explanation", "steps"],
      additionalProperties: false,
    } as AgentTool["parameters"],
    executionMode: "sequential",
    execute: async (_toolCallId, params) => {
      const state = getState();
      if (!state.planMode) throw new Error("PI_MOBILE_PLAN_MODE_REQUIRED");
      const plan = requireTaskPlan(params);
      state.latestPlan = plan;
      await state.session.appendCustomEntry(PLAN_SNAPSHOT_ENTRY_TYPE, plan);
      return {
        content: [{
          type: "text",
          text: JSON.stringify({
            ok: true,
            kind: TASK_PLAN_UPDATE_TOOL_NAME,
            ...plan,
          }),
        }],
        details: { ok: true, kind: TASK_PLAN_UPDATE_TOOL_NAME, ...plan },
      };
    },
  };
}

export async function recordInitialPlanMode(state: PlanExtensionState): Promise<void> {
  await state.session.appendCustomEntry(PLAN_MODE_ENTRY_TYPE, {
    enabled: true,
    prePlanActiveToolNames: state.prePlanActiveToolNames,
  });
  await state.harness.setActiveTools(activeToolNames(state.harness));
}

export async function enterPlanMode(state: PlanExtensionState): Promise<void> {
  const exactActiveTools = activeToolNames(state.harness);
  const planTools = PLAN_ALLOWED_TOOL_NAMES.filter((name) =>
    state.harness.getTools().some((tool) => tool.name === name)
  );
  if (!planTools.includes(TASK_PLAN_UPDATE_TOOL_NAME)) {
    throw new Error("PI_MOBILE_PLAN_TOOL_MISSING");
  }
  await state.session.appendCustomEntry(PLAN_MODE_ENTRY_TYPE, {
    enabled: true,
    prePlanActiveToolNames: exactActiveTools,
  });
  await state.harness.setActiveTools(planTools);
  state.prePlanActiveToolNames = exactActiveTools;
  state.planMode = true;
}

export async function exitPlanMode(
  state: PlanExtensionState,
  reason: "exit" | "implement",
): Promise<void> {
  const restore = state.prePlanActiveToolNames;
  if (restore === null) throw new Error("PI_MOBILE_PLAN_TOOL_SNAPSHOT_MISSING");
  const available = new Set(state.harness.getTools().map((tool) => tool.name));
  if (restore.some((name) => !available.has(name))) {
    throw new Error("PI_MOBILE_PLAN_TOOL_SNAPSHOT_STALE");
  }
  await state.harness.setActiveTools(restore);
  state.planMode = false;
  state.prePlanActiveToolNames = null;
  await state.session.appendCustomEntry(PLAN_MODE_ENTRY_TYPE, {
    enabled: false,
    reason,
    restoredActiveToolNames: restore,
    planDigest: state.latestPlan?.planDigest ?? null,
  });
}

export async function replacePlanModeToolSnapshot(
  state: PlanExtensionState,
  activeToolNames: string[],
): Promise<void> {
  if (!state.planMode || state.prePlanActiveToolNames === null) {
    throw new Error("PI_MOBILE_PLAN_TOOL_SNAPSHOT_MISSING");
  }
  await state.session.appendCustomEntry(PLAN_MODE_ENTRY_TYPE, {
    enabled: true,
    prePlanActiveToolNames: activeToolNames,
  });
  state.prePlanActiveToolNames = [...activeToolNames];
}

export async function preparePlanImplementation(
  state: PlanExtensionState,
  plan: TaskPlanSnapshot,
): Promise<string> {
  await exitPlanMode(state, "implement");
  const taskId = state.taskId;
  if (taskId === null) throw new Error("PI_MOBILE_PLAN_TASK_MISSING");
  const controlId = await state.session.appendCustomEntry(
    PLAN_IMPLEMENT_CONTROL_ENTRY_TYPE,
    {
      kind: "implement_plan",
      taskId,
      planDigest: plan.planDigest,
    },
  );
  return [
    `[momoding:implement-plan control=${controlId}]`,
    "Implement the exact approved plan below. Keep the user updated and use the available tools when needed.",
    canonicalPlanJson(plan.explanation, plan.steps),
    `planDigest=${plan.planDigest}`,
  ].join("\n");
}

export function restorePlanExtensionState(
  entries: SessionTreeEntry[],
): RestoredPlanExtensionState {
  let planMode = false;
  let prePlanActiveToolNames: string[] | null = null;
  let activeTools: string[] | null = null;
  let latestPlan: TaskPlanSnapshot | null = null;
  for (const entry of entries) {
    if (entry.type === "active_tools_change") {
      activeTools = [...entry.activeToolNames];
      continue;
    }
    if (entry.type !== "custom") continue;
    if (entry.customType === PLAN_MODE_ENTRY_TYPE && isRecord(entry.data)) {
      if (entry.data.enabled === true) {
        const prior = stringArray(entry.data.prePlanActiveToolNames);
        if (prior !== null) {
          planMode = true;
          prePlanActiveToolNames = prior;
        }
      } else if (entry.data.enabled === false) {
        planMode = false;
        prePlanActiveToolNames = null;
      }
    } else if (entry.customType === PLAN_SNAPSHOT_ENTRY_TYPE) {
      latestPlan = parseTaskPlanSnapshot(entry.data) ?? latestPlan;
    }
  }
  return { planMode, prePlanActiveToolNames, activeToolNames: activeTools, latestPlan };
}

function activeToolNames(harness: AgentHarness): string[] {
  return harness.getActiveTools().map((tool) => tool.name);
}

function requireTaskPlan(value: unknown): TaskPlanSnapshot {
  if (!isRecord(value)) throw new Error("PI_MOBILE_PLAN_INVALID");
  const explanation = typeof value.explanation === "string" ? value.explanation.trim() : "";
  if (explanation.length < 1 || explanation.length > 4096) {
    throw new Error("PI_MOBILE_PLAN_EXPLANATION_INVALID");
  }
  if (!Array.isArray(value.steps) || value.steps.length < 1 || value.steps.length > 12) {
    throw new Error("PI_MOBILE_PLAN_STEPS_INVALID");
  }
  const ids = new Set<string>();
  const steps = value.steps.map((candidate): TaskPlanStep => {
    if (!isRecord(candidate)) throw new Error("PI_MOBILE_PLAN_STEP_INVALID");
    const id = typeof candidate.id === "string" ? candidate.id : "";
    const text = typeof candidate.text === "string" ? candidate.text.trim() : "";
    const status = candidate.status;
    if (!/^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$/.test(id) || ids.has(id)) {
      throw new Error("PI_MOBILE_PLAN_STEP_ID_INVALID");
    }
    if (text.length < 1 || text.length > 1024) {
      throw new Error("PI_MOBILE_PLAN_STEP_TEXT_INVALID");
    }
    if (status !== "pending" && status !== "in_progress" && status !== "completed") {
      throw new Error("PI_MOBILE_PLAN_STEP_STATUS_INVALID");
    }
    ids.add(id);
    return { id, text, status };
  });
  const canonical = canonicalPlanJson(explanation, steps);
  return { explanation, steps, planDigest: sha256(canonical) };
}

function parseTaskPlanSnapshot(value: unknown): TaskPlanSnapshot | null {
  if (!isRecord(value) || typeof value.planDigest !== "string") return null;
  try {
    const plan = requireTaskPlan(value);
    return plan.planDigest === value.planDigest ? plan : null;
  } catch {
    return null;
  }
}

function canonicalPlanJson(explanation: string, steps: TaskPlanStep[]): string {
  return JSON.stringify({
    explanation,
    steps: steps.map((step) => ({ id: step.id, text: step.text, status: step.status })),
  });
}

function stringArray(value: unknown): string[] | null {
  if (!Array.isArray(value) || value.some((item) => typeof item !== "string")) return null;
  const names = value as string[];
  return names.length === new Set(names).size ? [...names] : null;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}
