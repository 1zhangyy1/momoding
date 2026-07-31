import {
  AgentHarness,
  Session,
  type AgentHarnessEvent,
  type AgentTool,
  type AgentToolResult,
  type ExecutionEnv,
  type SessionTreeEntry,
} from "@earendil-works/pi-agent-core";
import {
  type AssistantMessage,
  type Context,
  type ImageContent,
  type Model,
  type Models,
  type Provider,
  type SimpleStreamOptions,
} from "@earendil-works/pi-ai";
import {
  MAX_CHILDREN_PER_PARENT_TURN,
  PiChildAgentManager,
  type PiChildBinding,
  type PiChildEventEnvelope,
} from "./child-agent-runtime.js";
import {
  PLAN_ALLOWED_TOOL_NAMES,
  TASK_PLAN_UPDATE_TOOL_NAME,
  createPlanUpdateTool,
  enterPlanMode,
  exitPlanMode,
  preparePlanImplementation,
  recordInitialPlanMode,
  restorePlanExtensionState,
  type TaskPlanSnapshot,
} from "./extensions/plan-mode.js";
import {
  GOAL_TOOL_NAMES,
  continueGoal,
  createGoalTools,
  requireGoalContinuation,
  requireGoalIdentity,
  requireMatchingGoal,
  restoreGoalExtensionState,
  startGoal,
  transitionGoalState,
  type TaskGoalSnapshot,
  type TaskGoalTargetState,
} from "./extensions/goal-mode.js";
import { sha256 } from "./sha256.js";
import {
  GOAL_MODE_SYSTEM_PROMPT,
  MOMODING_TASK_SYSTEM_PROMPT,
  PLAN_MODE_SYSTEM_PROMPT,
} from "./system-prompts.js";
import {
  SKILL_INVOCATION_CONTROL_ENTRY_TYPE,
  isValidSkillName,
  requirePiMobileSkillResources,
  resourcesFromPiSkills,
  skillResourceSetDigest,
  toPiSkills,
  type PiMobileSkillResource,
} from "./skills/mobile-skill-runtime.js";
import {
  closeOpenRouterNativeBridge,
  completeOpenRouterRequest,
  createOpenRouterNativeStream,
  drainOpenRouterCancellations,
  drainOpenRouterRequests,
  failOpenRouterRequest,
  modelsForProvider,
  pushOpenRouterChunk,
  type NativeProviderCancellation,
  type NativeProviderRequest,
  type OpenRouterNativeBridgeState,
} from "./provider/openrouter-native-bridge.js";
import {
  createAndroidFixtureTool,
  createAndroidProductTools,
  type NativeToolRequest,
  type NativeToolRequestKind,
} from "./tools/android-tool-registry.js";
import {
  LiveOnlySessionStorage,
  TEXT_ATTACHMENT_CONTROL_ENTRY_TYPE,
  clearLiveToolContext,
  consumeLiveToolContext,
  createLiveTaskContext,
  expireLiveToolContext,
  promptWithTextAttachments,
  registerLiveToolResult,
  registerRuntimeImages,
  rehydrateImageReferences,
  rehydrateLiveToolContext,
  requireNativeToolContent,
  requireRuntimeImageInputs,
  requireRuntimeTextAttachmentInputs,
  requireTaskInput,
  requireTextAttachmentControlData,
  sanitizeImagesForAndroid,
  toPiImages,
  type LiveTaskContextState,
  type PiRuntimeImageInput,
  type PiRuntimeTextAttachmentInput,
} from "./attachments/live-task-context.js";

export type { PiMobileSkillResource } from "./skills/mobile-skill-runtime.js";
export {
  requireRuntimeImageInputs,
  requireRuntimeTextAttachmentInputs,
} from "./attachments/live-task-context.js";
export type {
  PiRuntimeImageInput,
  PiRuntimeTextAttachmentInput,
} from "./attachments/live-task-context.js";

export type NativeOpenRouterScenarioKind =
  | "text"
  | "tool"
  | "provider_error"
  | "stop";

type NativeOpenRouterRunKind = NativeOpenRouterScenarioKind | "prompt";

export interface PiChildEventAck extends PiChildBinding {
  throughEventOrdinal: number;
  throughDigest: string;
}

interface PendingTool {
  request: NativeToolRequest;
  resolve: (value: AgentToolResult<unknown>) => void;
  reject: (reason: unknown) => void;
  signal?: AbortSignal;
  abortListener?: () => void;
}

const NATIVE_TOOL_RESULT_MARKER = Symbol("pi-mobile-native-tool-result");

interface NativeToolResultEnvelope {
  [NATIVE_TOOL_RESULT_MARKER]: true;
  details: unknown;
  isError: boolean;
}

interface NativeScenarioState
  extends OpenRouterNativeBridgeState, LiveTaskContextState {
  kind: NativeOpenRouterRunKind;
  harness: AgentHarness;
  session: Session;
  taskId: string | null;
  unsubscribe: () => void;
  phase: string;
  terminal: boolean;
  promptSettled: boolean;
  turnCount: number;
  runEventStartIndex: number;
  sessionEntries: unknown[];
  stopCompleted: boolean;
  promptError: string | null;
  stopError: string | null;
  commandError: string | null;
  finalText: string | null;
  events: unknown[];
  eventTypes: string[];
  toolOutbox: NativeToolRequest[];
  pendingTools: Map<string, PendingTool>;
  pendingAttachedTaskMessages: number;
  attachedTaskMessageQueue: Promise<void>;
  nextToolRequestId: number;
  toolRequestsIssued: number;
  toolRequestsResolved: number;
  toolExecutionsStarted: number;
  toolExecutionsEnded: number;
  lateToolStartsAfterStop: number;
  planMode: boolean;
  prePlanActiveToolNames: string[] | null;
  latestPlan: TaskPlanSnapshot | null;
  planTransitionPending: boolean;
  goal: TaskGoalSnapshot | null;
  goalTransitionPending: boolean;
  resourceSetDigest: string;
  resourceSetTrusted: boolean;
  resourceTransitionPending: boolean;
  resourceUpdateCount: number;
  childAgents: PiChildAgentManager | null;
  childEventOutbox: PiChildEventEnvelope[];
  childEventAckHighWater: Map<string, number>;
}

const PROVIDER_ID = "openrouter";
const PROVIDER_BASE_URL = "https://openrouter.ai/api/v1";
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
  "resources_update",
  "abort",
  "settled",
]);

let nativeScenarioState: NativeScenarioState | null = null;

export function startNativeOpenRouterScenario(
  kind: NativeOpenRouterScenarioKind,
  modelId: string,
  env: ExecutionEnv,
): Record<string, unknown> {
  return startNativeOpenRouterRun(
    kind,
    `Run native OpenRouter scenario ${kind}`,
    modelId,
    env,
    kind === "tool",
  );
}

export function startNativeOpenRouterPrompt(
  prompt: string,
  modelId: string,
  env: ExecutionEnv,
): Record<string, unknown> {
  requirePrompt(prompt);
  return startNativeOpenRouterRun("prompt", prompt, modelId, env, false);
}

export function startNativeOpenRouterTaskSession(
  taskId: string,
  prompt: string,
  modelId: string,
  env: ExecutionEnv,
  sessionId = `phone-local-task-${taskId}`,
  planMode = false,
  skillResources: PiMobileSkillResource[] = [],
  imageInputs: PiRuntimeImageInput[] = [],
  textAttachmentInputs: PiRuntimeTextAttachmentInput[] = [],
): Record<string, unknown> {
  requireTaskId(taskId);
  const images = requireRuntimeImageInputs(imageInputs);
  const textAttachments = requireRuntimeTextAttachmentInputs(textAttachmentInputs);
  requireTaskInput(prompt, images, textAttachments);
  requireSessionId(sessionId);
  return startNativeOpenRouterRun(
    "prompt",
    prompt,
    modelId,
    env,
    false,
    taskId,
    sessionId,
    [],
    0,
    planMode,
    requirePiMobileSkillResources(skillResources),
    images,
    textAttachments,
  );
}

export function startNativeOpenRouterTaskSkillSession(
  taskId: string,
  skillName: string,
  additionalInstructions: string | undefined,
  modelId: string,
  env: ExecutionEnv,
  sessionId = `phone-local-task-${taskId}`,
  skillResources: PiMobileSkillResource[] = [],
): Record<string, unknown> {
  requireTaskId(taskId);
  requireSessionId(sessionId);
  const resources = requirePiMobileSkillResources(skillResources);
  startNativeOpenRouterRun(
    "prompt",
    null,
    modelId,
    env,
    false,
    taskId,
    sessionId,
    [],
    0,
    false,
    resources,
  );
  return invokeNativeOpenRouterTaskSkill(skillName, additionalInstructions);
}

export function restoreNativeOpenRouterTaskSession(
  taskId: string,
  sessionId: string,
  turnCount: number,
  entries: unknown,
  modelId: string,
  env: ExecutionEnv,
  skillResources: PiMobileSkillResource[] = [],
  imageInputs: PiRuntimeImageInput[] = [],
): Record<string, unknown> {
  requireTaskId(taskId);
  requireSessionId(sessionId);
  const images = requireRuntimeImageInputs(imageInputs);
  const restoredEntries = requireSessionEntries(rehydrateImageReferences(entries, images));
  if (!Number.isSafeInteger(turnCount) || turnCount < 0) {
    throw new Error("PI_MOBILE_TASK_SESSION_TURN_COUNT_INVALID");
  }
  return startNativeOpenRouterRun(
    "prompt",
    null,
    modelId,
    env,
    false,
    taskId,
    sessionId,
    restoredEntries,
    turnCount,
    false,
    requirePiMobileSkillResources(skillResources),
    images,
  );
}

export function continueNativeOpenRouterTaskPrompt(
  prompt: string,
  imageInputs: PiRuntimeImageInput[] = [],
  textAttachmentInputs: PiRuntimeTextAttachmentInput[] = [],
): Record<string, unknown> {
  const images = requireRuntimeImageInputs(imageInputs);
  const textAttachments = requireRuntimeTextAttachmentInputs(textAttachmentInputs);
  requireTaskInput(prompt, images, textAttachments);
  const state = requireNativeTaskSession();
  if (!state.terminal) {
    throw new Error("PI_MOBILE_TASK_SESSION_BUSY");
  }
  if (!state.resourceSetTrusted) {
    throw new Error("PI_MOBILE_SKILL_RESOURCES_UNTRUSTED");
  }
  resetTaskRun(state);
  registerRuntimeImages(state, images);
  queueHarnessPrompt(state, prompt, toPiImages(images), textAttachments);
  return nativeOpenRouterScenarioStatus();
}

export function setNativeOpenRouterTaskResources(
  skillResources: PiMobileSkillResource[],
): Record<string, unknown> {
  const state = requireSettledNativeTaskSession();
  const resources = requirePiMobileSkillResources(skillResources);
  const nextDigest = skillResourceSetDigest(resources);
  if (nextDigest === state.resourceSetDigest) return nativeOpenRouterScenarioStatus();
  state.resourceTransitionPending = true;
  state.terminal = false;
  state.phase = "updating_resources";
  queueMicrotask(() => {
    void applyNativeOpenRouterTaskResources(state, resources, nextDigest);
  });
  return nativeOpenRouterScenarioStatus();
}

export function invokeNativeOpenRouterTaskSkill(
  skillName: string,
  additionalInstructions?: string,
): Record<string, unknown> {
  const state = requireSettledNativeTaskSession();
  if (state.planMode) throw new Error("PI_MOBILE_SKILL_PLAN_MODE_CONFLICT");
  if (state.goal?.state === "active") throw new Error("PI_MOBILE_SKILL_GOAL_CONFLICT");
  if (!isValidSkillName(skillName)) throw new Error("PI_MOBILE_SKILL_NAME_INVALID");
  if (
    additionalInstructions !== undefined &&
    (additionalInstructions.length > 65_536 || additionalInstructions.includes("\u0000"))
  ) {
    throw new Error("PI_MOBILE_SKILL_INSTRUCTIONS_INVALID");
  }
  if (!(state.harness.getResources().skills ?? []).some((skill) => skill.name === skillName)) {
    throw new Error("PI_MOBILE_SKILL_NOT_ENABLED");
  }
  resetTaskRun(state);
  queueHarnessSkill(state, skillName, additionalInstructions);
  return nativeOpenRouterScenarioStatus();
}

export function steerNativeOpenRouterTask(
  text: string,
  imageInputs: PiRuntimeImageInput[] = [],
  textAttachmentInputs: PiRuntimeTextAttachmentInput[] = [],
): Record<string, unknown> {
  return queueNativeOpenRouterTaskMessage("steer", text, imageInputs, textAttachmentInputs);
}

export function followUpNativeOpenRouterTask(
  text: string,
  imageInputs: PiRuntimeImageInput[] = [],
  textAttachmentInputs: PiRuntimeTextAttachmentInput[] = [],
): Record<string, unknown> {
  return queueNativeOpenRouterTaskMessage("follow_up", text, imageInputs, textAttachmentInputs);
}

export function nativeOpenRouterTaskSessionSnapshot(): Record<string, unknown> {
  const state = requireNativeTaskSession();
  if (!state.terminal) {
    throw new Error("PI_MOBILE_TASK_SESSION_SNAPSHOT_BUSY");
  }
  return {
    taskId: state.taskId,
    turnCount: state.turnCount,
    entries: sanitizeImagesForAndroid(
      state.sessionEntries,
      state.imageAttachmentIdsByData,
      true,
    ),
    planMode: state.planMode,
    activeToolNames: activeToolNames(state),
    prePlanActiveToolNames: state.prePlanActiveToolNames,
    latestPlan: state.latestPlan,
    goal: state.goal,
    childAgents: state.childAgents?.snapshots() ?? [],
  };
}

export function cancelNativeOpenRouterChildAgent(
  childId: string,
): Record<string, unknown> {
  const state = requireNativeTaskSession();
  const childAgents = state.childAgents;
  if (childAgents === null) throw new Error("PI_MOBILE_CHILD_RUNTIME_MISSING");
  const accepted = childAgents.cancel(requireChildId(childId));
  return { accepted, status: nativeOpenRouterScenarioStatus() };
}

export function acknowledgeNativeOpenRouterChildAgents(
  childIds: string[],
): Record<string, unknown> {
  const state = requireNativeTaskSession();
  const childAgents = state.childAgents;
  if (childAgents === null) throw new Error("PI_MOBILE_CHILD_RUNTIME_MISSING");
  if (childIds.length < 1 || childIds.length > MAX_CHILDREN_PER_PARENT_TURN) {
    throw new Error("PI_MOBILE_CHILD_ACK_INVALID");
  }
  const normalized = [...new Set(childIds.map((childId) => requireChildId(childId)))];
  if (state.childEventOutbox.some((event) => normalized.includes(event.childId))) {
    throw new Error("PI_MOBILE_CHILD_EVENTS_NOT_DRAINED");
  }
  return { evictedChildIds: childAgents.evictTerminal(normalized) };
}

function requireChildId(value: string): string {
  const trimmed = value.trim();
  if (!/^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$/.test(trimmed)) {
    throw new Error("PI_MOBILE_CHILD_ID_INVALID");
  }
  return trimmed;
}

export function setNativeOpenRouterTaskPlanMode(
  enabled: boolean,
): Record<string, unknown> {
  const state = requireSettledNativeTaskSession();
  if (enabled && state.goal?.state === "active") {
    throw new Error("PI_MOBILE_GOAL_PLAN_MODE_CONFLICT");
  }
  if (state.planMode === enabled) return nativeOpenRouterScenarioStatus();
  beginPlanTransition(state);
  queueMicrotask(() => {
    void applyPlanModeTransition(state, enabled);
  });
  return nativeOpenRouterScenarioStatus();
}

export function implementNativeOpenRouterTaskPlan(
  planDigest: string,
): Record<string, unknown> {
  const state = requireSettledNativeTaskSession();
  const plan = state.latestPlan;
  if (!state.planMode || plan === null) {
    throw new Error("PI_MOBILE_PLAN_NOT_READY");
  }
  if (!/^[0-9a-f]{64}$/.test(planDigest) || plan.planDigest !== planDigest) {
    throw new Error("PI_MOBILE_PLAN_DIGEST_STALE");
  }
  beginPlanTransition(state);
  queueMicrotask(() => {
    void applyImplementPlan(state, plan);
  });
  return nativeOpenRouterScenarioStatus();
}

export function startNativeOpenRouterTaskGoal(
  goalId: string,
  instruction: string,
  generation: number,
  startedAtMillis: number,
): Record<string, unknown> {
  const state = requireSettledNativeTaskSession();
  requireGoalIdentity(goalId, instruction, generation, startedAtMillis);
  if (state.planMode) throw new Error("PI_MOBILE_GOAL_PLAN_MODE_CONFLICT");
  if (state.goal?.state === "active") {
    throw new Error("PI_MOBILE_GOAL_ALREADY_ACTIVE");
  }
  if (state.goal !== null && generation <= state.goal.generation) {
    throw new Error("PI_MOBILE_GOAL_GENERATION_STALE");
  }
  beginGoalTransition(state);
  queueMicrotask(() => {
    void applyStartGoal(state, goalId, instruction.trim(), generation, startedAtMillis);
  });
  return nativeOpenRouterScenarioStatus();
}

export function continueNativeOpenRouterTaskGoal(
  goalId: string,
  generation: number,
  turnIndex: number,
  resume: boolean,
): Record<string, unknown> {
  const state = requireSettledNativeTaskSession();
  requireGoalContinuation(goalId, generation, turnIndex);
  const goal = requireMatchingGoal(state, goalId, generation);
  if (resume) {
    if (goal.state !== "paused" && goal.state !== "blocked") {
      throw new Error("PI_MOBILE_GOAL_NOT_RESUMABLE");
    }
  } else if (goal.state !== "active") {
    throw new Error("PI_MOBILE_GOAL_NOT_ACTIVE");
  }
  if (state.planMode) throw new Error("PI_MOBILE_GOAL_PLAN_MODE_CONFLICT");
  beginGoalTransition(state);
  queueMicrotask(() => {
    void applyContinueGoal(state, goal, turnIndex, resume);
  });
  return nativeOpenRouterScenarioStatus();
}

export function setNativeOpenRouterTaskGoalState(
  goalId: string,
  generation: number,
  targetState: "paused" | "limited" | "failed" | "cleared",
): Record<string, unknown> {
  const state = requireSettledNativeTaskSession();
  const goal = requireMatchingGoal(state, goalId, generation);
  if (!(["paused", "limited", "failed", "cleared"] as const).includes(targetState)) {
    throw new Error("PI_MOBILE_GOAL_STATE_INVALID");
  }
  if (targetState === "paused" && goal.state !== "active") {
    throw new Error("PI_MOBILE_GOAL_NOT_ACTIVE");
  }
  if (state.planMode) throw new Error("PI_MOBILE_GOAL_PLAN_MODE_CONFLICT");
  beginGoalTransition(state);
  queueMicrotask(() => {
    void applyGoalStateTransition(state, goal, targetState);
  });
  return nativeOpenRouterScenarioStatus();
}

function startNativeOpenRouterRun(
  kind: NativeOpenRouterRunKind,
  prompt: string | null,
  modelId: string,
  env: ExecutionEnv,
  enableFixtureTool: boolean,
  taskId: string | null = null,
  sessionId = `phone-local-native-provider-${kind}`,
  restoredEntries: SessionTreeEntry[] = [],
  restoredTurnCount = 0,
  initialPlanMode = false,
  initialSkillResources: PiMobileSkillResource[] = [],
  initialRuntimeImages: PiRuntimeImageInput[] = [],
  initialTextAttachments: PiRuntimeTextAttachmentInput[] = [],
): Record<string, unknown> {
  if (nativeScenarioState !== null && !nativeScenarioState.terminal) {
    throw new Error("PI_MOBILE_NATIVE_PROVIDER_SCENARIO_ALREADY_RUNNING");
  }
  closeNativeOpenRouterScenario();
  requireModelId(modelId);

  let state: NativeScenarioState;
  const model: Model<"openai-completions"> = {
    id: modelId,
    name: modelId,
    api: "openai-completions",
    provider: PROVIDER_ID,
    baseUrl: PROVIDER_BASE_URL,
    reasoning: false,
    input: ["text", "image"],
    cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0 },
    contextWindow: 128_000,
    maxTokens: 4_096,
  };
  const provider: Provider<"openai-completions"> = {
    id: PROVIDER_ID,
    name: "OpenRouter",
    baseUrl: PROVIDER_BASE_URL,
    auth: {
      apiKey: {
        name: "Android Keystore managed OpenRouter credential",
        resolve: async () => ({ auth: {}, source: "Android Keystore" }),
      },
    },
    getModels: () => [model],
    stream: (streamModel, context, options) =>
      createNativeProviderStream(state, streamModel, context, options),
    streamSimple: (streamModel, context, options) =>
      createNativeProviderStream(state, streamModel, context, options),
  };
  const models = modelsForProvider(provider);
  const normalizedSkillResources = requirePiMobileSkillResources(initialSkillResources);
  const childEventOutbox: PiChildEventEnvelope[] = [];
  const childAgents = taskId === null
    ? null
    : new PiChildAgentManager({
        parentTaskId: taskId,
        env,
        model,
        createModels: (binding) => childModelsForProvider(state, provider, binding),
        onEvent: (event) => childEventOutbox.push(event),
      });
  const liveTaskContext = createLiveTaskContext(initialRuntimeImages);
  const session = new Session(
    new LiveOnlySessionStorage(
      {
        entries: restoredEntries,
        metadata: {
          id: sessionId,
          createdAt: "1970-01-01T00:00:00.000Z",
        },
      },
      liveTaskContext,
    ),
  );
  const restoredPlan = restorePlanExtensionState(restoredEntries);
  const executeNativeTool = (
    toolKind: NativeToolRequestKind,
    toolName: string,
    toolCallId: string,
    parameters: Record<string, unknown>,
    signal?: AbortSignal,
  ) => requestNativeTool(
    state,
    toolKind,
    toolName,
    toolCallId,
    parameters,
    signal,
  );
  const fixtureTool = createAndroidFixtureTool(executeNativeTool);
  const productTools: AgentTool[] = kind === "prompt" && taskId !== null
    ? [
        childAgents!.delegateTool(),
        ...createAndroidProductTools(executeNativeTool),
        createPlanUpdateTool(() => state),
        ...createGoalTools(() => state),
      ]
    : [];
  const tools = enableFixtureTool ? [fixtureTool] : productTools;
  const defaultActiveToolNames = tools
    .map((candidate) => candidate.name)
    .filter((name) => name !== TASK_PLAN_UPDATE_TOOL_NAME && !GOAL_TOOL_NAMES.includes(name));
  const restoredGoal = restoreGoalExtensionState(restoredEntries);
  const planMode = taskId !== null && (initialPlanMode || restoredPlan.planMode);
  if (planMode && restoredGoal?.state === "active") {
    throw new Error("PI_MOBILE_GOAL_PLAN_MODE_CONFLICT");
  }
  const prePlanActiveToolNames = planMode
    ? restoredPlan.prePlanActiveToolNames ?? defaultActiveToolNames
    : null;
  const initialActiveToolNames = planMode
    ? PLAN_ALLOWED_TOOL_NAMES.filter((name) => tools.some((tool) => tool.name === name))
    : restoredPlan.activeToolNames?.filter((name) =>
      name !== TASK_PLAN_UPDATE_TOOL_NAME &&
      (restoredGoal?.state === "active" || !GOAL_TOOL_NAMES.includes(name)) &&
      tools.some((tool) => tool.name === name)
    ) ?? (
      restoredGoal?.state === "active"
        ? [...defaultActiveToolNames, ...GOAL_TOOL_NAMES]
        : defaultActiveToolNames
    );
  const harness = new AgentHarness({
    env,
    session,
    models,
    model,
    tools,
    activeToolNames: initialActiveToolNames,
    resources: { skills: toPiSkills(normalizedSkillResources) },
    systemPrompt: kind === "prompt"
      ? () => state.planMode
        ? `${MOMODING_TASK_SYSTEM_PROMPT} ${PLAN_MODE_SYSTEM_PROMPT}`
        : state.goal?.state === "active"
          ? `${MOMODING_TASK_SYSTEM_PROMPT} ${GOAL_MODE_SYSTEM_PROMPT} Active goal: ${state.goal.instruction}`
          : MOMODING_TASK_SYSTEM_PROMPT
      : "Phone-local native OpenRouter Provider bridge gate",
  });
  const releaseNativeResultHook = harness.on("tool_result", (event) => {
    const envelope = event.details as Partial<NativeToolResultEnvelope> | null;
    if (envelope?.[NATIVE_TOOL_RESULT_MARKER] !== true) return undefined;
    return {
      details: envelope.details,
      isError: envelope.isError === true,
    };
  });
  const releaseLiveImageContextHook = harness.on("context", (event) => ({
    messages: rehydrateLiveToolContext(
      event.messages,
      liveTaskContext,
    ) as Context["messages"],
  }));
  state = {
    kind,
    harness,
    session,
    taskId,
    unsubscribe: () => undefined,
    phase: prompt === null ? "settled" : "running",
    terminal: prompt === null,
    promptSettled: prompt === null,
    turnCount: restoredTurnCount,
    runEventStartIndex: 0,
    sessionEntries: restoredEntries,
    ...liveTaskContext,
    stopRequested: false,
    stopCompleted: false,
    promptError: null,
    providerError: null,
    stopError: null,
    commandError: null,
    finalText: null,
    events: [],
    eventTypes: [],
    providerOutbox: [],
    providerCancellationOutbox: [],
    pendingProviders: new Map(),
    nextProviderRequestId: 1,
    providerRequestsIssued: 0,
    providerRequestsCompleted: 0,
    providerRequestsFailed: 0,
    providerCancellationsIssued: 0,
    childProviderRequestsIssued: 0,
    childProviderRequestsCompleted: 0,
    childProviderRequestsFailed: 0,
    childProviderCancellationsIssued: 0,
    lateProviderRequestsAfterStop: 0,
    toolOutbox: [],
    pendingTools: new Map(),
    pendingAttachedTaskMessages: 0,
    attachedTaskMessageQueue: Promise.resolve(),
    nextToolRequestId: 1,
    toolRequestsIssued: 0,
    toolRequestsResolved: 0,
    toolExecutionsStarted: 0,
    toolExecutionsEnded: 0,
    lateToolStartsAfterStop: 0,
    planMode,
    prePlanActiveToolNames,
    latestPlan: restoredPlan.latestPlan,
    planTransitionPending: false,
    goal: restoredGoal,
    goalTransitionPending: false,
    resourceSetDigest: skillResourceSetDigest(normalizedSkillResources),
    resourceSetTrusted: true,
    resourceTransitionPending: false,
    resourceUpdateCount: 0,
    childAgents,
    childEventOutbox,
    childEventAckHighWater: new Map<string, number>(),
  };
  const releaseEventSubscription = harness.subscribe((event) => recordEvent(state, event));
  state.unsubscribe = () => {
    releaseEventSubscription();
    releaseNativeResultHook();
    releaseLiveImageContextHook();
  };
  nativeScenarioState = state;
  if (prompt !== null) {
    if (planMode && initialPlanMode && !restoredPlan.planMode) {
      queueMicrotask(() => {
        void initializePlanModeAndPrompt(
          state,
          prompt,
          toPiImages(initialRuntimeImages),
          initialTextAttachments,
        );
      });
    } else {
      queueHarnessPrompt(state, prompt, toPiImages(initialRuntimeImages), initialTextAttachments);
    }
  }
  return nativeOpenRouterScenarioStatus();
}

function requireSessionEntries(value: unknown): SessionTreeEntry[] {
  if (!Array.isArray(value)) {
    throw new Error("PI_MOBILE_TASK_SESSION_ENTRIES_INVALID");
  }
  if (value.length === 0) {
    throw new Error("PI_MOBILE_TASK_SESSION_ENTRIES_EMPTY");
  }
  const ids = new Set<string>();
  for (const entry of value) {
    if (
      entry === null ||
      typeof entry !== "object" ||
      Array.isArray(entry)
    ) {
      throw new Error("PI_MOBILE_TASK_SESSION_ENTRY_INVALID");
    }
    const candidate = entry as Record<string, unknown>;
    if (
      typeof candidate.id !== "string" ||
      candidate.id.length === 0 ||
      typeof candidate.type !== "string" ||
      candidate.type.length === 0 ||
      typeof candidate.timestamp !== "string" ||
      candidate.timestamp.length === 0 ||
      (
        candidate.parentId !== null &&
        candidate.parentId !== undefined &&
        typeof candidate.parentId !== "string"
      )
    ) {
      throw new Error("PI_MOBILE_TASK_SESSION_ENTRY_INVALID");
    }
    if (ids.has(candidate.id)) {
      throw new Error("PI_MOBILE_TASK_SESSION_ENTRY_ID_DUPLICATED");
    }
    if (
      typeof candidate.parentId === "string" &&
      !ids.has(candidate.parentId)
    ) {
      throw new Error("PI_MOBILE_TASK_SESSION_PARENT_INVALID");
    }
    ids.add(candidate.id);
    if (candidate.type === "custom" && candidate.customType === TEXT_ATTACHMENT_CONTROL_ENTRY_TYPE) {
      requireTextAttachmentControlData(candidate.data);
    }
  }
  return value as SessionTreeEntry[];
}

function requireSessionId(value: string): void {
  if (typeof value !== "string" || value.trim().length === 0) {
    throw new Error("PI_MOBILE_TASK_SESSION_ID_INVALID");
  }
}

async function initializePlanModeAndPrompt(
  state: NativeScenarioState,
  prompt: string,
  images: ImageContent[],
  textAttachments: PiRuntimeTextAttachmentInput[],
): Promise<void> {
  try {
    await recordInitialPlanMode(state);
    state.sessionEntries = await state.session.getEntries();
  } catch (error: unknown) {
    state.promptError = safeErrorMessage(error);
    state.phase = "failed";
    state.promptSettled = true;
    updateTerminal(state);
    return;
  }
  await runHarnessPrompt(state, prompt, images, textAttachments);
}

function beginPlanTransition(state: NativeScenarioState): void {
  state.planTransitionPending = true;
  state.phase = "plan_transition";
  state.terminal = false;
  state.promptSettled = false;
  state.commandError = null;
}

async function applyPlanModeTransition(
  state: NativeScenarioState,
  enabled: boolean,
): Promise<void> {
  try {
    if (enabled) {
      await enterPlanMode(state);
    } else {
      await exitPlanMode(state, "exit");
    }
    state.sessionEntries = await state.session.getEntries();
    state.phase = "settled";
  } catch (error: unknown) {
    state.commandError = safeErrorMessage(error);
    state.phase = "failed";
  } finally {
    state.planTransitionPending = false;
    state.promptSettled = true;
    updateTerminal(state);
  }
}

async function applyImplementPlan(
  state: NativeScenarioState,
  plan: TaskPlanSnapshot,
): Promise<void> {
  try {
    const implementationPrompt = await preparePlanImplementation(state, plan);
    state.sessionEntries = await state.session.getEntries();
    state.planTransitionPending = false;
    resetTaskRun(state);
    queueHarnessPrompt(state, implementationPrompt);
  } catch (error: unknown) {
    state.commandError = safeErrorMessage(error);
    state.phase = "failed";
    state.planTransitionPending = false;
    state.promptSettled = true;
    updateTerminal(state);
  }
}

function beginGoalTransition(state: NativeScenarioState): void {
  state.goalTransitionPending = true;
  state.phase = "goal_transition";
  state.terminal = false;
  state.promptSettled = false;
  state.commandError = null;
}

async function applyStartGoal(
  state: NativeScenarioState,
  goalId: string,
  instruction: string,
  generation: number,
  startedAtMillis: number,
): Promise<void> {
  try {
    const prompt = await startGoal(
      state,
      goalId,
      instruction,
      generation,
      startedAtMillis,
    );
    await queuePreparedGoalPrompt(state, prompt);
  } catch (error: unknown) {
    failGoalTransition(state, error);
  }
}

async function applyContinueGoal(
  state: NativeScenarioState,
  prior: TaskGoalSnapshot,
  turnIndex: number,
  resume: boolean,
): Promise<void> {
  try {
    const prompt = await continueGoal(state, prior, turnIndex, resume);
    await queuePreparedGoalPrompt(state, prompt);
  } catch (error: unknown) {
    failGoalTransition(state, error);
  }
}

async function applyGoalStateTransition(
  state: NativeScenarioState,
  prior: TaskGoalSnapshot,
  targetState: TaskGoalTargetState,
): Promise<void> {
  try {
    await transitionGoalState(state, prior, targetState);
    state.sessionEntries = await state.session.getEntries();
    state.phase = "settled";
    state.goalTransitionPending = false;
    state.promptSettled = true;
    updateTerminal(state);
  } catch (error: unknown) {
    failGoalTransition(state, error);
  }
}

async function queuePreparedGoalPrompt(
  state: NativeScenarioState,
  prompt: string,
): Promise<void> {
  state.sessionEntries = await state.session.getEntries();
  state.goalTransitionPending = false;
  resetTaskRun(state);
  queueHarnessPrompt(state, prompt);
}

function failGoalTransition(state: NativeScenarioState, error: unknown): void {
  state.commandError = safeErrorMessage(error);
  state.phase = "failed";
  state.goalTransitionPending = false;
  state.promptSettled = true;
  updateTerminal(state);
}

function activeToolNames(state: NativeScenarioState): string[] {
  return state.harness.getActiveTools().map((tool) => tool.name);
}

function requireSettledNativeTaskSession(): NativeScenarioState {
  const state = requireNativeTaskSession();
  if (
    !state.terminal ||
    !state.promptSettled ||
    state.planTransitionPending ||
    state.goalTransitionPending ||
    state.resourceTransitionPending
  ) {
    throw new Error("PI_MOBILE_TASK_SESSION_BUSY");
  }
  if (
    state.pendingProviders.size > 0 ||
    state.pendingTools.size > 0 ||
    state.providerOutbox.length > 0 ||
    state.providerCancellationOutbox.length > 0 ||
    state.toolOutbox.length > 0
  ) {
    throw new Error("PI_MOBILE_TASK_SESSION_PENDING_OUTPUT");
  }
  if (!state.resourceSetTrusted) {
    throw new Error("PI_MOBILE_SKILL_RESOURCES_UNTRUSTED");
  }
  return state;
}

function queueHarnessPrompt(
  state: NativeScenarioState,
  prompt: string,
  images: ImageContent[] = [],
  textAttachments: PiRuntimeTextAttachmentInput[] = [],
): void {
  queueMicrotask(() => {
    void runHarnessPrompt(state, prompt, images, textAttachments);
  });
}

function queueHarnessSkill(
  state: NativeScenarioState,
  skillName: string,
  additionalInstructions?: string,
): void {
  queueMicrotask(() => {
    void runHarnessSkill(state, skillName, additionalInstructions);
  });
}

async function runHarnessPrompt(
  state: NativeScenarioState,
  prompt: string,
  images: ImageContent[] = [],
  textAttachments: PiRuntimeTextAttachmentInput[] = [],
): Promise<void> {
  try {
    const effectivePrompt = await promptWithTextAttachments(
      state.session,
      prompt,
      textAttachments,
    );
    const message = await state.harness.prompt(effectivePrompt, { images });
    state.finalText = assistantText(message);
    state.phase = "settled";
  } catch (error: unknown) {
    state.promptError = safeErrorMessage(error);
    state.phase = "failed";
  } finally {
    try {
      state.sessionEntries = await state.session.getEntries();
    } catch (error: unknown) {
      state.promptError ??= safeErrorMessage(error);
      state.phase = "failed";
    }
    state.turnCount += 1;
    state.promptSettled = true;
    updateTerminal(state);
  }
}

async function runHarnessSkill(
  state: NativeScenarioState,
  skillName: string,
  additionalInstructions?: string,
): Promise<void> {
  try {
    await state.session.appendCustomEntry(SKILL_INVOCATION_CONTROL_ENTRY_TYPE, {
      kind: "skill_invocation",
      name: skillName,
      additionalInstructions: additionalInstructions ?? null,
    });
    const message = await state.harness.skill(skillName, additionalInstructions);
    state.finalText = assistantText(message);
    state.phase = "settled";
  } catch (error: unknown) {
    state.promptError = safeErrorMessage(error);
    state.phase = "failed";
  } finally {
    try {
      state.sessionEntries = await state.session.getEntries();
    } catch (error: unknown) {
      state.promptError ??= safeErrorMessage(error);
      state.phase = "failed";
    }
    state.turnCount += 1;
    state.promptSettled = true;
    updateTerminal(state);
  }
}

async function applyNativeOpenRouterTaskResources(
  state: NativeScenarioState,
  resources: PiMobileSkillResource[],
  nextDigest: string,
): Promise<void> {
  const updateCountBefore = state.resourceUpdateCount;
  try {
    await state.harness.setResources({
      ...state.harness.getResources(),
      skills: toPiSkills(resources),
    });
    if (state.resourceUpdateCount !== updateCountBefore + 1) {
      throw new Error("PI_MOBILE_SKILL_RESOURCE_EVENT_MISSING");
    }
    const resourceEvent = state.events[state.events.length - 1];
    if (
      !isRecord(resourceEvent) ||
      resourceEvent.type !== "resources_update" ||
      resourceEvent.resourceSetDigest !== nextDigest
    ) {
      throw new Error("PI_MOBILE_SKILL_RESOURCE_EVENT_MISMATCH");
    }
    state.resourceSetDigest = nextDigest;
    state.phase = "settled";
  } catch (error: unknown) {
    state.resourceSetTrusted = false;
    state.commandError = safeErrorMessage(error);
    state.phase = "failed";
  } finally {
    state.resourceTransitionPending = false;
    updateTerminal(state);
  }
}

function queueNativeOpenRouterTaskMessage(
  mode: "steer" | "follow_up",
  text: string,
  imageInputs: PiRuntimeImageInput[],
  textAttachmentInputs: PiRuntimeTextAttachmentInput[],
): Record<string, unknown> {
  const images = requireRuntimeImageInputs(imageInputs);
  const textAttachments = requireRuntimeTextAttachmentInputs(textAttachmentInputs);
  requireTaskInput(text, images, textAttachments);
  const state = requireNativeTaskSession();
  if (state.terminal || state.promptSettled || state.stopRequested) {
    throw new Error("PI_MOBILE_TASK_SESSION_NOT_RUNNING");
  }
  registerRuntimeImages(state, images);
  if (textAttachments.length === 0) {
    const command = mode === "steer"
      ? state.harness.steer(text, { images: toPiImages(images) })
      : state.harness.followUp(text, { images: toPiImages(images) });
    void command.catch((error: unknown) => {
      state.commandError = safeErrorMessage(error);
    });
    return nativeOpenRouterScenarioStatus();
  }
  state.pendingAttachedTaskMessages += 1;
  const command = state.attachedTaskMessageQueue.then(async () => {
    const effectiveText = await promptWithTextAttachments(
      state.session,
      text,
      textAttachments,
    );
    if (mode === "steer") {
      await state.harness.steer(effectiveText, { images: toPiImages(images) });
    } else {
      await state.harness.followUp(effectiveText, { images: toPiImages(images) });
    }
  });
  state.attachedTaskMessageQueue = command.then(
    () => undefined,
    () => undefined,
  );
  void command.catch((error: unknown) => {
    state.commandError = safeErrorMessage(error);
  }).finally(() => {
    state.pendingAttachedTaskMessages -= 1;
    updateTerminal(state);
  });
  return nativeOpenRouterScenarioStatus();
}

function resetTaskRun(state: NativeScenarioState): void {
  if (
    state.pendingProviders.size > 0 ||
    state.pendingTools.size > 0 ||
    state.pendingAttachedTaskMessages > 0 ||
    state.providerOutbox.length > 0 ||
    state.providerCancellationOutbox.length > 0 ||
    state.toolOutbox.length > 0
  ) {
    throw new Error("PI_MOBILE_TASK_SESSION_PENDING_OUTPUT");
  }
  state.childAgents?.beginParentTurn();
  state.phase = "running";
  state.terminal = false;
  state.promptSettled = false;
  state.runEventStartIndex = state.events.length;
  state.stopRequested = false;
  state.stopCompleted = false;
  state.promptError = null;
  state.providerError = null;
  state.stopError = null;
  state.commandError = null;
  state.finalText = null;
  state.providerRequestsIssued = 0;
  state.providerRequestsCompleted = 0;
  state.providerRequestsFailed = 0;
  state.providerCancellationsIssued = 0;
  state.childProviderRequestsIssued = 0;
  state.childProviderRequestsCompleted = 0;
  state.childProviderRequestsFailed = 0;
  state.childProviderCancellationsIssued = 0;
  state.lateProviderRequestsAfterStop = 0;
  state.toolRequestsIssued = 0;
  state.toolRequestsResolved = 0;
  state.toolExecutionsStarted = 0;
  state.toolExecutionsEnded = 0;
  state.lateToolStartsAfterStop = 0;
}

export function drainNativeProviderRequests(): NativeProviderRequest[] {
  return drainOpenRouterRequests(requireNativeScenario());
}

export function drainNativeProviderCancellations(): NativeProviderCancellation[] {
  return drainOpenRouterCancellations(requireNativeScenario());
}

export function drainNativeOpenRouterChildEvents(): PiChildEventEnvelope[] {
  const state = requireNativeTaskSession();
  return state.childEventOutbox.splice(0);
}

/** Production delivery is two phase: a Room commit must succeed before Android acks. */
export function peekNativeOpenRouterChildEvents(): PiChildEventEnvelope[] {
  const state = requireNativeTaskSession();
  return state.childEventOutbox.map((envelope) => ({
    ...envelope,
    event: JSON.parse(JSON.stringify(envelope.event)) as unknown,
  }));
}

export function acknowledgeNativeOpenRouterChildEvents(
  acknowledgements: PiChildEventAck[],
): Record<string, unknown> {
  const state = requireNativeTaskSession();
  if (
    acknowledgements.length < 1 ||
    acknowledgements.length > MAX_CHILDREN_PER_PARENT_TURN
  ) {
    throw new Error("PI_MOBILE_CHILD_EVENT_ACK_INVALID");
  }
  const parents = new Set<string>();
  const removeIndexes = new Set<number>();
  const nextHighWater = new Map(state.childEventAckHighWater);
  for (const acknowledgement of acknowledgements) {
    const parentTaskId = acknowledgement.parentTaskId;
    const parentToolCallId = acknowledgement.parentToolCallId;
    const childId = acknowledgement.childId;
    const childName = acknowledgement.childName;
    if (
      parentTaskId !== state.taskId ||
      !/^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$/.test(parentToolCallId) ||
      !/^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$/.test(childId) ||
      !/^[A-Za-z0-9][A-Za-z0-9 _.-]{0,63}$/.test(childName) ||
      !Number.isSafeInteger(acknowledgement.throughEventOrdinal) ||
      acknowledgement.throughEventOrdinal < 0 ||
      !/^[a-f0-9]{64}$/.test(acknowledgement.throughDigest) ||
      parents.has(parentToolCallId)
    ) {
      throw new Error("PI_MOBILE_CHILD_EVENT_ACK_INVALID");
    }
    parents.add(parentToolCallId);
    const priorHighWater = state.childEventAckHighWater.get(parentToolCallId) ?? -1;
    if (acknowledgement.throughEventOrdinal <= priorHighWater) {
      throw new Error("PI_MOBILE_CHILD_EVENT_ACK_STALE");
    }
    const candidates = state.childEventOutbox
      .map((event, index) => ({ event, index }))
      .filter(({ event }) =>
        event.parentToolCallId === parentToolCallId &&
        event.eventOrdinal > priorHighWater &&
        event.eventOrdinal <= acknowledgement.throughEventOrdinal
      )
      .sort((left, right) => left.event.eventOrdinal - right.event.eventOrdinal);
    const expectedCount = acknowledgement.throughEventOrdinal - priorHighWater;
    if (candidates.length !== expectedCount) {
      throw new Error("PI_MOBILE_CHILD_EVENT_ACK_GAP");
    }
    candidates.forEach(({ event }, index) => {
      if (
        event.eventOrdinal !== priorHighWater + index + 1 ||
        event.parentTaskId !== parentTaskId ||
        event.childId !== childId ||
        event.childName !== childName
      ) {
        throw new Error("PI_MOBILE_CHILD_EVENT_ACK_BINDING_MISMATCH");
      }
    });
    const last = candidates[candidates.length - 1]?.event;
    if (
      last === undefined ||
      sha256(JSON.stringify(last.event)) !== acknowledgement.throughDigest
    ) {
      throw new Error("PI_MOBILE_CHILD_EVENT_ACK_DIGEST_MISMATCH");
    }
    candidates.forEach(({ index }) => removeIndexes.add(index));
    nextHighWater.set(parentToolCallId, acknowledgement.throughEventOrdinal);
  }
  [...removeIndexes]
    .sort((left, right) => right - left)
    .forEach((index) => state.childEventOutbox.splice(index, 1));
  state.childEventAckHighWater = nextHighWater;
  return { acknowledgedEventCount: removeIndexes.size };
}

export function pushNativeProviderChunk(
  requestId: string,
  chunk: unknown,
): Record<string, unknown> {
  const state = requireNativeScenario();
  pushOpenRouterChunk(state, requestId, chunk, () => updateTerminal(state));
  return nativeOpenRouterScenarioStatus();
}

export function completeNativeProviderRequest(
  requestId: string,
  generationId?: string,
): Record<string, unknown> {
  const state = requireNativeScenario();
  completeOpenRouterRequest(
    state,
    requestId,
    generationId,
    () => updateTerminal(state),
  );
  return nativeOpenRouterScenarioStatus();
}

export function failNativeProviderRequest(
  requestId: string,
  safeMessage: string,
): Record<string, unknown> {
  const state = requireNativeScenario();
  failOpenRouterRequest(
    state,
    requestId,
    requireSafeProviderError(safeMessage),
    () => updateTerminal(state),
  );
  return nativeOpenRouterScenarioStatus();
}

export function drainNativeProviderToolRequests(): NativeToolRequest[] {
  const state = requireNativeScenario();
  return state.toolOutbox.splice(0);
}

export function resolveNativeProviderToolRequest(
  requestId: string,
  contentPayload: unknown,
  details: unknown = contentPayload,
  isError = false,
  content?: unknown,
): Record<string, unknown> {
  const state = requireNativeScenario();
  const pending = state.pendingTools.get(requestId);
  if (pending === undefined) {
    throw new Error(`PI_MOBILE_NATIVE_PROVIDER_TOOL_NOT_FOUND ${requestId}`);
  }
  const nativeContent = content === undefined
    ? [{ type: "text" as const, text: JSON.stringify(contentPayload) }]
    : requireNativeToolContent(content);
  registerLiveToolResult(
    state,
    pending.request,
    nativeContent,
    details,
    isError,
  );
  clearToolAbort(pending);
  state.pendingTools.delete(requestId);
  state.toolRequestsResolved += 1;
  pending.resolve({
    content: nativeContent,
    details: {
      [NATIVE_TOOL_RESULT_MARKER]: true,
      details,
      isError,
    } satisfies NativeToolResultEnvelope,
  });
  return nativeOpenRouterScenarioStatus();
}

export function abortNativeOpenRouterScenario(): Record<string, unknown> {
  const state = requireNativeScenario();
  if (state.stopRequested) return nativeOpenRouterScenarioStatus();
  state.stopRequested = true;
  state.phase = "stopping";
  void state.harness.abort()
    .then(() => {
      state.stopCompleted = true;
      state.phase = "stopped";
    })
    .catch((error: unknown) => {
      state.stopError = safeErrorMessage(error);
      state.phase = "stop_failed";
    })
    .finally(() => updateTerminal(state));
  return nativeOpenRouterScenarioStatus();
}

export function nativeOpenRouterScenarioStatus(): Record<string, unknown> {
  const state = requireNativeScenario();
  updateTerminal(state);
  const eventTypes = [...state.eventTypes];
  const runEvents = state.events.slice(state.runEventStartIndex);
  const runEventTypes = state.eventTypes.slice(state.runEventStartIndex);
  return {
    kind: state.kind,
    taskId: state.taskId,
    phase: state.phase,
    terminal: state.terminal,
    expectationMet: nativeExpectationMet(state),
    promptSettled: state.promptSettled,
    turnCount: state.turnCount,
    sessionEntryCount: state.sessionEntries.length,
    stopRequested: state.stopRequested,
    stopCompleted: state.stopCompleted,
    promptError: state.promptError,
    providerError: state.providerError,
    stopError: state.stopError,
    commandError: state.commandError,
    finalText: state.finalText,
    events: state.events,
    eventTypes,
    runEvents,
    runEventTypes,
    pendingProviderCount: state.pendingProviders.size,
    queuedProviderRequestCount: state.providerOutbox.length,
    queuedProviderCancellationCount: state.providerCancellationOutbox.length,
    providerRequestsIssued: state.providerRequestsIssued,
    providerRequestsCompleted: state.providerRequestsCompleted,
    providerRequestsFailed: state.providerRequestsFailed,
    providerCancellationsIssued: state.providerCancellationsIssued,
    childProviderRequestsIssued: state.childProviderRequestsIssued,
    childProviderRequestsCompleted: state.childProviderRequestsCompleted,
    childProviderRequestsFailed: state.childProviderRequestsFailed,
    childProviderCancellationsIssued: state.childProviderCancellationsIssued,
    lateProviderRequestsAfterStop: state.lateProviderRequestsAfterStop,
    pendingToolCount: state.pendingTools.size,
    queuedToolRequestCount: state.toolOutbox.length,
    toolRequestsIssued: state.toolRequestsIssued,
    toolRequestsResolved: state.toolRequestsResolved,
    toolExecutionsStarted: state.toolExecutionsStarted,
    toolExecutionsEnded: state.toolExecutionsEnded,
    lateToolStartsAfterStop: state.lateToolStartsAfterStop,
    planMode: state.planMode,
    activeToolNames: activeToolNames(state),
    prePlanActiveToolNames: state.prePlanActiveToolNames,
    latestPlan: state.latestPlan,
    planTransitionPending: state.planTransitionPending,
    goal: state.goal,
    goalTransitionPending: state.goalTransitionPending,
    resourceSetDigest: state.resourceSetDigest,
    resourceSetTrusted: state.resourceSetTrusted,
    resourceTransitionPending: state.resourceTransitionPending,
    resourceUpdateCount: state.resourceUpdateCount,
    skillNames: (state.harness.getResources().skills ?? []).map((skill) => skill.name),
    childAgents: state.childAgents?.snapshots() ?? [],
    queuedChildEventCount: state.childEventOutbox.length,
    hasAgentStart: eventTypes.includes("agent_start"),
    hasSettled: eventTypes.includes("settled"),
    hasAbort: eventTypes.includes("abort"),
  };
}

export function closeNativeOpenRouterScenario(): void {
  const state = nativeScenarioState;
  if (state === null) return;
  state.childAgents?.close("runtime_rebuilt");
  state.unsubscribe();
  closeOpenRouterNativeBridge(state);
  for (const pending of state.pendingTools.values()) {
    clearToolAbort(pending);
    pending.reject(new Error("PI_MOBILE_RUNTIME_CLOSED"));
  }
  state.pendingTools.clear();
  state.toolOutbox.length = 0;
  state.childEventOutbox.length = 0;
  nativeScenarioState = null;
}

function createNativeProviderStream(
  state: NativeScenarioState,
  model: Model<"openai-completions">,
  context: Context,
  options?: SimpleStreamOptions,
  childBinding?: PiChildBinding,
): ReturnType<typeof createOpenRouterNativeStream> {
  return createOpenRouterNativeStream(
    state,
    model,
    context,
    options,
    {
      consumeLiveContext: (messages) =>
        consumeLiveToolContext(messages, state),
      updateTerminal: () => updateTerminal(state),
    },
    childBinding,
  );
}

function requestNativeTool(
  state: NativeScenarioState,
  kind: NativeToolRequest["kind"],
  toolName: string,
  toolCallId: string,
  parameters: Record<string, unknown>,
  signal?: AbortSignal,
): Promise<AgentToolResult<unknown>> {
  if (state.stopRequested || signal?.aborted) {
    return Promise.reject(new Error("PI_MOBILE_TOOL_BLOCKED_AFTER_STOP"));
  }
  const request: NativeToolRequest = {
    id: `native-tool-${state.nextToolRequestId++}`,
    kind,
    toolCallId,
    toolName,
    arguments: parameters,
  };
  state.toolRequestsIssued += 1;
  return new Promise<AgentToolResult<unknown>>((resolve, reject) => {
    const pending: PendingTool = { request, resolve, reject, signal };
    if (signal !== undefined) {
      const abortListener = () => {
        if (!state.pendingTools.delete(request.id)) return;
        state.toolOutbox = state.toolOutbox.filter((candidate) => candidate.id !== request.id);
        reject(signal.reason ?? new Error("Operation aborted"));
      };
      pending.abortListener = abortListener;
      signal.addEventListener("abort", abortListener);
    }
    state.pendingTools.set(request.id, pending);
    state.toolOutbox.push(request);
  });
}

function childModelsForProvider(
  state: NativeScenarioState,
  provider: Provider<"openai-completions">,
  binding: PiChildBinding,
): Models {
  const childProvider: Provider<"openai-completions"> = {
    ...provider,
    stream: (model, context, options) =>
      createNativeProviderStream(state, model, context, options, binding),
    streamSimple: (model, context, options) =>
      createNativeProviderStream(state, model, context, options, binding),
  };
  return modelsForProvider(childProvider);
}

function recordEvent(state: NativeScenarioState, event: AgentHarnessEvent): void {
  if (!RECORDED_EVENT_TYPES.has(event.type)) return;
  if (event.type === "resources_update") {
    const resources = resourcesFromPiSkills(event.resources.skills ?? []);
    const previousResources = resourcesFromPiSkills(event.previousResources.skills ?? []);
    state.resourceUpdateCount += 1;
    state.events.push({
      type: "resources_update",
      resourceSetDigest: skillResourceSetDigest(resources),
      previousResourceSetDigest: skillResourceSetDigest(previousResources),
      skillNames: resources.map((resource) => resource.name),
      previousSkillNames: previousResources.map((resource) => resource.name),
    });
  } else {
    state.events.push(
      sanitizeImagesForAndroid(
        expireLiveToolContext(
          JSON.parse(JSON.stringify(event)) as unknown,
          state,
        ),
        state.imageAttachmentIdsByData,
      ),
    );
  }
  state.eventTypes.push(event.type);
  if (event.type === "settled") {
    clearLiveToolContext(state);
  }
  if (event.type === "tool_execution_start") {
    state.toolExecutionsStarted += 1;
    if (state.stopRequested) state.lateToolStartsAfterStop += 1;
  } else if (event.type === "tool_execution_end") {
    state.toolExecutionsEnded += 1;
  }
}

function updateTerminal(state: NativeScenarioState): void {
  state.terminal = !state.planTransitionPending && !state.goalTransitionPending &&
    !state.resourceTransitionPending && state.promptSettled &&
    (!state.stopRequested || state.stopCompleted || state.stopError !== null) &&
    state.pendingProviders.size === 0 &&
    state.pendingTools.size === 0 &&
    state.pendingAttachedTaskMessages === 0;
}

function nativeExpectationMet(state: NativeScenarioState): boolean {
  if (!state.terminal) return false;
  const runEventTypes = state.eventTypes.slice(state.runEventStartIndex);
  const common = runEventTypes.includes("agent_start") &&
    runEventTypes.includes("settled");
  if (state.taskId !== null) {
    if (state.stopRequested) {
      return common &&
        state.stopCompleted &&
        state.lateProviderRequestsAfterStop === 0 &&
        state.lateToolStartsAfterStop === 0 &&
        runEventTypes.includes("abort");
    }
    return common &&
      state.promptError === null &&
      state.commandError === null &&
      state.providerRequestsCompleted >= 1 &&
      state.providerRequestsFailed === 0 &&
      state.finalText !== null;
  }
  switch (state.kind) {
    case "text":
      return common &&
        state.providerRequestsIssued === 1 &&
        state.providerRequestsCompleted === 1 &&
        state.providerRequestsFailed === 0 &&
        state.finalText === "Hello from Android native Provider";
    case "tool":
      return common &&
        state.providerRequestsIssued === 2 &&
        state.providerRequestsCompleted === 2 &&
        state.toolRequestsIssued === 1 &&
        state.toolRequestsResolved === 1 &&
        state.toolExecutionsStarted === 1 &&
        state.toolExecutionsEnded === 1 &&
        state.finalText === "Android native Provider tool complete";
    case "provider_error":
      return common &&
        state.providerRequestsIssued === 1 &&
        state.providerRequestsFailed === 1 &&
        state.toolRequestsIssued === 0;
    case "stop":
      return common &&
        state.stopCompleted &&
        state.providerRequestsIssued === 1 &&
        state.providerCancellationsIssued === 1 &&
        state.toolRequestsIssued === 0 &&
        state.lateProviderRequestsAfterStop === 0 &&
        state.lateToolStartsAfterStop === 0 &&
        state.eventTypes.includes("abort");
    case "prompt":
      return common &&
        state.promptError === null &&
        state.providerRequestsIssued === 1 &&
        state.providerRequestsCompleted === 1 &&
        state.providerRequestsFailed === 0 &&
        state.finalText !== null;
  }
}

function assistantText(message: AssistantMessage): string {
  return message.content
    .filter((block) => block.type === "text")
    .map((block) => block.text)
    .join("");
}

function requireNativeScenario(): NativeScenarioState {
  if (nativeScenarioState === null) {
    throw new Error("PI_MOBILE_NATIVE_PROVIDER_SCENARIO_NOT_STARTED");
  }
  return nativeScenarioState;
}

function requireNativeTaskSession(): NativeScenarioState {
  const state = requireNativeScenario();
  if (state.taskId === null) {
    throw new Error("PI_MOBILE_TASK_SESSION_NOT_STARTED");
  }
  return state;
}

function clearToolAbort(pending: PendingTool): void {
  if (pending.signal !== undefined && pending.abortListener !== undefined) {
    pending.signal.removeEventListener("abort", pending.abortListener);
  }
}

function requireModelId(value: string): void {
  if (!/^[A-Za-z0-9][A-Za-z0-9._-]{0,63}\/[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$/.test(value)) {
    throw new Error("PI_MOBILE_OPENROUTER_MODEL_ID_INVALID");
  }
}

function requireTaskId(value: string): void {
  if (!/^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$/.test(value)) {
    throw new Error("PI_MOBILE_TASK_ID_INVALID");
  }
}

function requirePrompt(value: string): void {
  if (value.trim().length === 0 || value.length > 65_536 || value.includes("\u0000")) {
    throw new Error("PI_MOBILE_PROMPT_INVALID");
  }
}

function requireSafeProviderError(value: string): string {
  if (value.length < 1 || value.length > 160 || /[\r\n\u0000-\u001f\u007f]/.test(value)) {
    throw new Error("PI_MOBILE_NATIVE_PROVIDER_ERROR_INVALID");
  }
  return value;
}

function safeErrorMessage(error: unknown): string {
  return error instanceof Error ? error.message : "Phone-local Provider operation failed";
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}
