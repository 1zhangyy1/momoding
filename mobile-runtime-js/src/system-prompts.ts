export type MomodingProviderKind = "openrouter" | "codex";

export type MomodingWorkspaceKind = "private_scratch" | "authorized_project";

interface MomodingTaskEnvironmentSnapshotV1 {
  version: 1;
  workspaceKind: MomodingWorkspaceKind;
  webSearchEnabled: boolean;
  webFetchEnabled: boolean;
  imageGenerationEnabled: boolean;
}

interface MomodingTaskEnvironmentSnapshotV2 {
  version: 2;
  workspaceKind: MomodingWorkspaceKind;
  webSearchEnabled: boolean;
  webFetchEnabled: boolean;
  imageGenerationEnabled: boolean;
  currentDateTime: string;
  timeZone: string;
}

export type MomodingTaskEnvironmentSnapshot =
  | MomodingTaskEnvironmentSnapshotV1
  | MomodingTaskEnvironmentSnapshotV2;

export interface MomodingTaskPromptContext {
  environment: MomodingTaskEnvironmentSnapshot;
  providerKind: MomodingProviderKind;
  skillCount: number;
  extensionCount: number;
  connectorEnabled: boolean;
  planMode: boolean;
  activeGoalInstruction?: string;
}

const TASK_ENVIRONMENT_V1_KEYS = [
  "imageGenerationEnabled",
  "version",
  "webFetchEnabled",
  "webSearchEnabled",
  "workspaceKind",
] as const;

const TASK_ENVIRONMENT_V2_KEYS = [
  "currentDateTime",
  "imageGenerationEnabled",
  "timeZone",
  "version",
  "webFetchEnabled",
  "webSearchEnabled",
  "workspaceKind",
] as const;

export const MOMODING_TASK_SYSTEM_PROMPT = [
  "You are Momoding, an action agent that lives on the user's phone.",
  "Carry the latest unresolved user outcome across turns. If missing information materially changes action or safety, call request_user_question once, not a prose-only question. After the answer, finish it. After Skip, do not guess or re-ask: do safe independent work or state the blocker.",
  "Outside Plan Mode, use run_command and run_tests directly for persistent /workspace work; workspace work needs no Android capability check.",
  "For phone work, call the narrowest relevant typed device tool first.",
  "Never default to device_capabilities_get; use it only for explicit inventory, file-grant discovery, multi-capability planning, or an unknown capability.",
  "On typed CAPABILITY_NOT_READY, request only that capability and retry the original tool once; stop after refusal, Stop, or unknown outcome.",
  "Tool JSON names and enums are exact wire contracts. On INVALID_ARGUMENTS, make at most one schema-based correction; never probe combinations.",
  "For provider Web Search or Fetch, let Activity show attempts. Distinguish fetched pages from search evidence; never claim verification after a failed fetch.",
  "Android owns permissions, approval, system consent, conflicts, and verification. Call a concrete protected Android tool directly and let Android ask. Use request_user_confirmation only when no concrete tool owns the decision. Never claim success before verification.",
  "Request approval, Auto approve, and Full access never grant OS permission or expand tools.",
  "Tool definitions and results are authoritative; never invent capability, handle, state, or side effect. On exact USER_DECLINED, say the user declined and no action ran; do not hedge.",
  "Use the latest user message's language for replies, questions, permission, and decline explanations. Act instead of teaching when action is available. After the requested result, stop; do not propose unrelated phone actions. Prefer short paragraphs or lists on a phone; use a table only when column comparison is essential, with plain cells.",
].join(" ");

export const PLAN_MODE_SYSTEM_PROMPT = [
  "PLAN MODE IS ACTIVE.",
  "Analyze the task and gather only the read-only context needed to make a concrete plan.",
  "Do not execute commands, tests, mutations, or any side-effecting action.",
  "After analysis, you MUST call task_plan_update exactly once with an explanation and 1 to 12 ordered steps.",
  "Do not claim that implementation has started. Wait for the user to choose Implement plan.",
].join(" ");

export const GOAL_MODE_SYSTEM_PROMPT = [
  "GOAL MODE IS ACTIVE.",
  "Keep advancing the exact active goal using the available tools.",
  "Before ending each turn, call exactly one of task_goal_progress or task_goal_complete.",
  "Use task_goal_progress when more work remains. Use task_goal_complete only for achieved, blocked, or failed terminal outcomes.",
  "Do not claim the goal is complete unless task_goal_complete succeeds.",
].join(" ");

export const CHILD_ANALYSIS_SYSTEM_PROMPT = [
  "You are a read-only child analysis agent working for Momoding.",
  "Return a concise factual result to the parent agent.",
  "You have no tools and must not claim to modify files, run commands, ask the user, or delegate again.",
].join(" ");

export function defaultMomodingTaskEnvironment(
  imageGenerationEnabled = false,
): MomodingTaskEnvironmentSnapshot {
  return {
    version: 1,
    workspaceKind: "private_scratch",
    webSearchEnabled: false,
    webFetchEnabled: false,
    imageGenerationEnabled,
  };
}

export function requireMomodingTaskEnvironmentSnapshot(
  value: unknown,
): MomodingTaskEnvironmentSnapshot {
  if (!isRecord(value)) {
    throw new Error("PI_MOBILE_TASK_ENVIRONMENT_INVALID");
  }
  const expectedKeys = value.version === 1
    ? TASK_ENVIRONMENT_V1_KEYS
    : value.version === 2
      ? TASK_ENVIRONMENT_V2_KEYS
      : undefined;
  if (expectedKeys === undefined) {
    throw new Error("PI_MOBILE_TASK_ENVIRONMENT_VERSION_UNSUPPORTED");
  }
  const keys = Object.keys(value).sort();
  if (
    keys.length !== expectedKeys.length ||
    keys.some((key, index) => key !== expectedKeys[index])
  ) {
    throw new Error("PI_MOBILE_TASK_ENVIRONMENT_FIELDS_INVALID");
  }
  if (
    value.workspaceKind !== "private_scratch" &&
    value.workspaceKind !== "authorized_project"
  ) {
    throw new Error("PI_MOBILE_TASK_ENVIRONMENT_WORKSPACE_INVALID");
  }
  if (
    typeof value.webSearchEnabled !== "boolean" ||
    typeof value.webFetchEnabled !== "boolean" ||
    typeof value.imageGenerationEnabled !== "boolean"
  ) {
    throw new Error("PI_MOBILE_TASK_ENVIRONMENT_CAPABILITIES_INVALID");
  }
  if (value.version === 2) {
    if (
      !isBoundedString(value.currentDateTime, 64) ||
      !isBoundedString(value.timeZone, 64) ||
      !isOffsetDateTime(value.currentDateTime) ||
      !isSafeTimeZoneId(value.timeZone)
    ) {
      throw new Error("PI_MOBILE_TASK_ENVIRONMENT_CLOCK_INVALID");
    }
    return {
      version: 2,
      workspaceKind: value.workspaceKind,
      webSearchEnabled: value.webSearchEnabled,
      webFetchEnabled: value.webFetchEnabled,
      imageGenerationEnabled: value.imageGenerationEnabled,
      currentDateTime: value.currentDateTime,
      timeZone: value.timeZone,
    };
  }
  return {
    version: 1,
    workspaceKind: value.workspaceKind,
    webSearchEnabled: value.webSearchEnabled,
    webFetchEnabled: value.webFetchEnabled,
    imageGenerationEnabled: value.imageGenerationEnabled,
  };
}

export function buildMomodingTaskSystemPrompt(
  context: MomodingTaskPromptContext,
): string {
  requireBoundedCount(context.skillCount, "SKILL_COUNT");
  requireBoundedCount(context.extensionCount, "EXTENSION_COUNT");
  const environment = requireMomodingTaskEnvironmentSnapshot(context.environment);
  const workspace = environment.workspaceKind === "authorized_project"
    ? "/workspace is a private snapshot of the user-authorized project. Work in it directly; real-folder changes are complete only after Android file commit succeeds."
    : "/workspace is persistent App-private Scratch. Work in it directly; its changes stay private unless a separate Android file operation succeeds.";
  const provider = context.providerKind === "openrouter" ? "OpenRouter" : "Codex";
  const taskEnvironment = [
    `Current task environment v${environment.version}: ${workspace}`,
    ...(environment.version === 2
      ? [`Android local date/time=${environment.currentDateTime}; time zone=${environment.timeZone}. Use these facts for relative dates and local-time requests; do not probe the workspace clock. State the resolved ISO date; do not add a weekday unless a Tool result or source supplies it.`]
      : []),
    `Provider=${provider}; provider Web Search=${availability(environment.webSearchEnabled)}; provider Web Fetch=${availability(environment.webFetchEnabled)}; image generation=${availability(environment.imageGenerationEnabled)}.`,
    `Enabled Skills=${context.skillCount}; Extension packages=${context.extensionCount}; Connector tools=${availability(context.connectorEnabled)}.`,
    "Skill details and attachments are supplied separately when present; use only their task-scoped references. Do not infer live Android permission state from this summary.",
  ].join(" ");
  const mode = context.planMode
    ? PLAN_MODE_SYSTEM_PROMPT
    : context.activeGoalInstruction === undefined
      ? ""
      : `${GOAL_MODE_SYSTEM_PROMPT} Active goal: ${context.activeGoalInstruction}`;
  return [MOMODING_TASK_SYSTEM_PROMPT, taskEnvironment, mode]
    .filter((part) => part.length > 0)
    .join("\n\n");
}

function availability(enabled: boolean): "available" | "not configured" {
  return enabled ? "available" : "not configured";
}

function requireBoundedCount(value: number, field: string): void {
  if (!Number.isSafeInteger(value) || value < 0 || value > 256) {
    throw new Error(`PI_MOBILE_TASK_ENVIRONMENT_${field}_INVALID`);
  }
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function isBoundedString(value: unknown, maximumLength: number): value is string {
  return typeof value === "string" && value.length > 0 && value.length <= maximumLength;
}

function isOffsetDateTime(value: string): boolean {
  return /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,9})?(?:Z|[+-]\d{2}:\d{2})$/.test(value);
}

function isSafeTimeZoneId(value: string): boolean {
  return /^[A-Za-z0-9._+\-/:~]{1,64}$/.test(value);
}
