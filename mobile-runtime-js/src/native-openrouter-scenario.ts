import {
  closeCodexNativeBridge,
  completeCodexRequest,
  createCodexNativeStream,
  drainCodexCancellations,
  drainCodexRequests,
  failCodexRequest,
  pushCodexEvent,
  type CodexNativeBridgeState,
  type NativeCodexCancellation,
  type NativeCodexRequest,
} from "./provider/codex-native-bridge.js";
import {
  AgentHarness,
  Session,
  formatSkillsForSystemPrompt,
  type AgentHarnessEvent,
  type AgentTool,
  type AgentToolResult,
  type ExecutionEnv,
  type SessionTreeEntry,
} from "@earendil-works/pi-agent-core";
import {
  type AssistantMessage,
  type Api,
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
  replacePlanModeToolSnapshot,
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
import { createBuiltInMobileExtensionHost } from "./extensions/built-in-mobile-extensions.js";
import type { MobileExtensionHost } from "./extensions/mobile-extension-host.js";
import {
  CONNECTOR_SNAPSHOT_ENTRY_TYPE,
  connectorBinding,
  connectorExtensionToolNames,
  createConnectorExtension,
  requireConnectorToolSnapshot,
  requireMatchingConnectorRestore,
  restoreConnectorBinding,
  type ConnectorToolSnapshot,
} from "./extensions/connector-extension.js";
import {
  createDeclarativeExtensionPackageDescriptors,
  extensionPackageSetDigest,
  requireExtensionPackageSnapshots,
  type ExtensionPackageSnapshot,
} from "./extensions/declarative-extension-package.js";
import {
  createPiRegisterToolExtensionDescriptor,
  type PiRegisterToolActivity,
} from "./extensions/pi-register-tool-extension.js";
import { createNativePiRegisterToolTransport } from "./extensions/pi-register-tool-native-transport.js";
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
  type NativeProviderCancellation as OpenRouterNativeProviderCancellation,
  type NativeProviderRequest as OpenRouterNativeProviderRequest,
  type OpenRouterNativeBridgeState,
} from "./provider/openrouter-native-bridge.js";
import {
  createAndroidFixtureTool,
  createAndroidProductTools,
  createAndroidSkillResourceTool,
  SKILL_RESOURCE_TOOL_NAME,
  type NativeToolExecutor,
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
  extends OpenRouterNativeBridgeState, CodexNativeBridgeState, LiveTaskContextState {
  kind: NativeOpenRouterRunKind;
  providerKind: NativeProviderKind;
  modelId: string;
  providerBindingRecorded: boolean;
  connectorBindingRecorded: boolean;
  connectorSnapshot: ConnectorToolSnapshot | null;
  harness: AgentHarness;
  extensionHost: MobileExtensionHost;
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
  extensionSetDigest: string;
  extensionSetTrusted: boolean;
  childAgents: PiChildAgentManager | null;
  childEventOutbox: PiChildEventEnvelope[];
  childEventAckHighWater: Map<string, number>;
  executeNativeTool: NativeToolExecutor;
}

const PROVIDER_ID = "openrouter";
const PROVIDER_BASE_URL = "https://openrouter.ai/api/v1";
const CODEX_PROVIDER_ID = "openai-codex";
const CODEX_PROVIDER_BASE_URL = "https://chatgpt.com/backend-api";
const PROVIDER_BINDING_ENTRY_TYPE = "pi_mobile_provider_binding";
type NativeProviderKind = "openrouter" | "codex";
type NativeProviderRequest = OpenRouterNativeProviderRequest | NativeCodexRequest;
type NativeProviderCancellation =
  | OpenRouterNativeProviderCancellation
  | NativeCodexCancellation;
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

export function startNativeCodexScenario(
  kind: NativeOpenRouterScenarioKind,
  modelId: string,
  env: ExecutionEnv,
): Record<string, unknown> {
  return startNativeOpenRouterRun(
    kind,
    `Run native Codex scenario ${kind}`,
    modelId,
    env,
    kind === "tool",
    null,
    `phone-local-native-codex-${kind}`,
    [],
    0,
    false,
    [],
    [],
    [],
    false,
    "codex",
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
  imageGenerationEnabled = false,
  connectorToolSnapshot: ConnectorToolSnapshot | null = null,
  extensionPackages: ExtensionPackageSnapshot[] = [],
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
    imageGenerationEnabled,
    "openrouter",
    connectorToolSnapshot,
    requireExtensionPackageSnapshots(extensionPackages),
  );
}

export function startNativeCodexTaskSession(
  taskId: string,
  prompt: string,
  modelId: string,
  env: ExecutionEnv,
  sessionId = `phone-local-task-${taskId}`,
  planMode = false,
  skillResources: PiMobileSkillResource[] = [],
  textAttachmentInputs: PiRuntimeTextAttachmentInput[] = [],
  connectorToolSnapshot: ConnectorToolSnapshot | null = null,
  extensionPackages: ExtensionPackageSnapshot[] = [],
): Record<string, unknown> {
  requireTaskId(taskId);
  const textAttachments = requireRuntimeTextAttachmentInputs(textAttachmentInputs);
  requireTaskInput(prompt, [], textAttachments);
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
    [],
    textAttachments,
    false,
    "codex",
    connectorToolSnapshot,
    requireExtensionPackageSnapshots(extensionPackages),
  );
}

export function startNativeCodexTaskSkillSession(
  taskId: string,
  skillName: string,
  additionalInstructions: string | undefined,
  modelId: string,
  env: ExecutionEnv,
  sessionId = `phone-local-task-${taskId}`,
  skillResources: PiMobileSkillResource[] = [],
  connectorToolSnapshot: ConnectorToolSnapshot | null = null,
  extensionPackages: ExtensionPackageSnapshot[] = [],
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
    [],
    [],
    false,
    "codex",
    connectorToolSnapshot,
    requireExtensionPackageSnapshots(extensionPackages),
  );
  return invokeNativeOpenRouterTaskSkill(skillName, additionalInstructions);
}

export function startNativeOpenRouterTaskSkillSession(
  taskId: string,
  skillName: string,
  additionalInstructions: string | undefined,
  modelId: string,
  env: ExecutionEnv,
  sessionId = `phone-local-task-${taskId}`,
  skillResources: PiMobileSkillResource[] = [],
  imageGenerationEnabled = false,
  connectorToolSnapshot: ConnectorToolSnapshot | null = null,
  extensionPackages: ExtensionPackageSnapshot[] = [],
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
    [],
    [],
    imageGenerationEnabled,
    "openrouter",
    connectorToolSnapshot,
    requireExtensionPackageSnapshots(extensionPackages),
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
  imageGenerationEnabled = false,
  connectorToolSnapshot: ConnectorToolSnapshot | null = null,
  extensionPackages: ExtensionPackageSnapshot[] = [],
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
    [],
    imageGenerationEnabled,
    "openrouter",
    connectorToolSnapshot,
    requireExtensionPackageSnapshots(extensionPackages),
  );
}

export function restoreNativeCodexTaskSession(
  taskId: string,
  sessionId: string,
  turnCount: number,
  entries: unknown,
  modelId: string,
  env: ExecutionEnv,
  skillResources: PiMobileSkillResource[] = [],
  connectorToolSnapshot: ConnectorToolSnapshot | null = null,
  extensionPackages: ExtensionPackageSnapshot[] = [],
): Record<string, unknown> {
  requireTaskId(taskId);
  requireSessionId(sessionId);
  const restoredEntries = requireSessionEntries(entries);
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
    [],
    [],
    false,
    "codex",
    connectorToolSnapshot,
    requireExtensionPackageSnapshots(extensionPackages),
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
  imageGenerationEnabled = false,
  providerKind: NativeProviderKind = "openrouter",
  connectorToolSnapshot: ConnectorToolSnapshot | null = null,
  extensionPackages: ExtensionPackageSnapshot[] = [],
): Record<string, unknown> {
  if (nativeScenarioState !== null && !nativeScenarioState.terminal) {
    throw new Error("PI_MOBILE_NATIVE_PROVIDER_SCENARIO_ALREADY_RUNNING");
  }
  closeNativeOpenRouterScenario();
  requireModelId(modelId, providerKind);
  const normalizedConnectorSnapshot = requireConnectorToolSnapshot(connectorToolSnapshot);
  const normalizedExtensionPackages = requireExtensionPackageSnapshots(extensionPackages);
  if (normalizedConnectorSnapshot !== null && taskId === null) {
    throw new Error("PI_MOBILE_CONNECTOR_TASK_MISSING");
  }
  if (normalizedExtensionPackages.length > 0 && taskId === null) {
    throw new Error("PI_MOBILE_EXTENSION_PACKAGE_TASK_MISSING");
  }
  const restoredConnector = restoreConnectorBinding(restoredEntries);
  const isRestore = prompt === null && (restoredTurnCount > 0 || restoredEntries.length > 0);
  requireMatchingConnectorRestore(restoredConnector, normalizedConnectorSnapshot, isRestore);
  const restoredProviderBinding = providerBindingFromEntries(restoredEntries);
  if (
    restoredProviderBinding !== null &&
    (
      restoredProviderBinding.kind !== providerKind ||
      restoredProviderBinding.modelId !== modelId
    )
  ) {
    throw new Error("PI_MOBILE_SESSION_PROVIDER_BINDING_MISMATCH");
  }
  if (
    taskId !== null &&
    restoredEntries.length > 0 &&
    providerKind === "codex" &&
    restoredProviderBinding === null
  ) {
    throw new Error("PI_MOBILE_SESSION_PROVIDER_BINDING_MISSING");
  }

  let state: NativeScenarioState;
  const model: Model<Api> = providerKind === "codex" ? {
    id: modelId,
    name: modelId,
    api: "openai-codex-responses",
    provider: CODEX_PROVIDER_ID,
    baseUrl: CODEX_PROVIDER_BASE_URL,
    reasoning: true,
    input: ["text", "image"],
    cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0 },
    contextWindow: 128_000,
    maxTokens: 4_096,
  } : {
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
  const provider: Provider = {
    id: providerKind === "codex" ? CODEX_PROVIDER_ID : PROVIDER_ID,
    name: providerKind === "codex" ? "Codex" : "OpenRouter",
    baseUrl: providerKind === "codex" ? CODEX_PROVIDER_BASE_URL : PROVIDER_BASE_URL,
    auth: {
      apiKey: {
        name: `Android Keystore managed ${providerKind === "codex" ? "Codex" : "OpenRouter"} credential`,
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
        ...createAndroidProductTools(executeNativeTool, {
          imageGenerationEnabled,
          skillResourceEnabled: normalizedSkillResources.length > 0,
        }),
        createPlanUpdateTool(() => state),
        ...createGoalTools(() => state),
      ]
    : [];
  const piRegisterTransport = createNativePiRegisterToolTransport(executeNativeTool);
  const taskExtensions = [
    ...(normalizedConnectorSnapshot === null
      ? []
      : [createConnectorExtension(normalizedConnectorSnapshot, executeNativeTool)]),
    ...normalizedExtensionPackages.flatMap((extensionPackage) =>
      extensionPackage.schemaVersion === 2
        ? [createPiRegisterToolExtensionDescriptor(
            extensionPackage,
            productTools,
            piRegisterTransport,
            {
              onActivity: (activity) => recordPiRegisterToolActivity(state, activity),
            },
          )]
        : createDeclarativeExtensionPackageDescriptors(
            [extensionPackage],
            productTools,
            normalizedConnectorSnapshot,
            executeNativeTool,
          )
    ),
  ];
  const extensionHost = createBuiltInMobileExtensionHost(taskExtensions);
  const legacyTools = enableFixtureTool ? [fixtureTool] : productTools;
  const tools = extensionHost.composeTools(legacyTools);
  const packageToolNames = normalizedExtensionPackages.flatMap((extensionPackage) =>
    extensionPackage.tools.map((tool) => tool.name)
  );
  const defaultActiveToolNames = tools
    .map((candidate) => candidate.name)
    .filter((name) => name !== TASK_PLAN_UPDATE_TOOL_NAME && !GOAL_TOOL_NAMES.includes(name));
  const restoredGoalSnapshot = restoreGoalExtensionState(restoredEntries);
  const restoredGoal = restoredGoalSnapshot === null
    ? null
    : {
        ...restoredGoalSnapshot,
        preGoalActiveToolNames: reconcilePackageToolSnapshot(
          restoredGoalSnapshot.preGoalActiveToolNames,
          tools,
          packageToolNames,
        ),
      };
  const planMode = taskId !== null && (initialPlanMode || restoredPlan.planMode);
  if (planMode && restoredGoal?.state === "active") {
    throw new Error("PI_MOBILE_GOAL_PLAN_MODE_CONFLICT");
  }
  const prePlanActiveToolNames = planMode
    ? restoredPlan.prePlanActiveToolNames === null
      ? defaultActiveToolNames
      : reconcilePackageToolSnapshot(
          restoredPlan.prePlanActiveToolNames,
          tools,
          packageToolNames,
        )
    : null;
  const restoredActiveToolNames = restoredPlan.activeToolNames === null
    ? null
    : reconcilePackageToolSnapshot(
        restoredPlan.activeToolNames,
        tools,
        packageToolNames,
      );
  const requestedActiveToolNames = planMode
    ? [...PLAN_ALLOWED_TOOL_NAMES, ...connectorExtensionToolNames(normalizedConnectorSnapshot)]
      .filter((name) => tools.some((tool) => tool.name === name))
    : restoredActiveToolNames?.filter((name) =>
      name !== TASK_PLAN_UPDATE_TOOL_NAME &&
      (restoredGoal?.state === "active" || !GOAL_TOOL_NAMES.includes(name)) &&
      tools.some((tool) => tool.name === name)
    ) ?? (
      restoredGoal?.state === "active"
        ? [...defaultActiveToolNames, ...GOAL_TOOL_NAMES]
        : defaultActiveToolNames
    );
  const initialActiveToolNames = extensionHost.createActiveToolSnapshot(
    tools,
    requestedActiveToolNames,
  );
  let harness: AgentHarness;
  try {
    harness = new AgentHarness({
      env,
      session,
      models,
      model,
      tools,
      activeToolNames: initialActiveToolNames,
      resources: { skills: toPiSkills(normalizedSkillResources) },
      systemPrompt: kind === "prompt"
        ? ({ resources }) => {
            const base = state.planMode
              ? `${MOMODING_TASK_SYSTEM_PROMPT} ${PLAN_MODE_SYSTEM_PROMPT}`
              : state.goal?.state === "active"
                ? `${MOMODING_TASK_SYSTEM_PROMPT} ${GOAL_MODE_SYSTEM_PROMPT} Active goal: ${state.goal.instruction}`
                : MOMODING_TASK_SYSTEM_PROMPT;
            const skillIndex = formatSkillsForSystemPrompt(resources.skills ?? []);
            return skillIndex.length === 0
              ? base
              : `${base}\n\n${skillIndex}\nThese locations are virtual on-device paths, not host filesystem paths. Pass the listed absolute /mobile-skills/<skill>/SKILL.md location to skill_resource, or pass a path relative to that Skill. Use paged list/read calls, do not invent paths, and never claim that reading a script executed it.`;
          }
        : "Phone-local native OpenRouter Provider bridge gate",
    });
    extensionHost.attach(harness);
  } catch (error) {
    try {
      extensionHost.dispose();
    } catch {
      // Preserve the Harness construction failure after best-effort cleanup.
    }
    throw error;
  }
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
    providerKind,
    modelId,
    providerBindingRecorded: restoredProviderBinding !== null,
    connectorBindingRecorded: restoredConnector !== null,
    connectorSnapshot: normalizedConnectorSnapshot,
    harness,
    extensionHost,
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
    codexProviderOutbox: [],
    codexProviderCancellationOutbox: [],
    pendingCodexProviders: new Map(),
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
    extensionSetDigest: extensionPackageSetDigest(normalizedExtensionPackages),
    extensionSetTrusted: true,
    childAgents,
    childEventOutbox,
    childEventAckHighWater: new Map<string, number>(),
    executeNativeTool,
  };
  const releaseEventSubscription = harness.subscribe((event) => recordEvent(state, event));
  state.unsubscribe = () => {
    runCleanupActions("PI_MOBILE_SCENARIO_UNSUBSCRIBE_FAILED", [
      ["events", releaseEventSubscription],
      ["native_result", releaseNativeResultHook],
      ["live_image_context", releaseLiveImageContextHook],
      ["extensions", () => extensionHost.dispose()],
    ]);
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

function reconcilePackageToolSnapshot(
  previous: readonly string[],
  tools: readonly AgentTool[],
  currentPackageToolNames: readonly string[],
): string[] {
  const available = new Set(tools.map((tool) => tool.name));
  const reconciled = previous.filter((name) => available.has(name));
  const seen = new Set(reconciled);
  for (const name of currentPackageToolNames) {
    if (available.has(name) && !seen.has(name)) {
      reconciled.push(name);
      seen.add(name);
    }
  }
  return reconciled;
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

function providerBindingFromEntries(
  entries: SessionTreeEntry[],
): { kind: NativeProviderKind; modelId: string } | null {
  let binding: { kind: NativeProviderKind; modelId: string } | null = null;
  for (const entry of entries) {
    if (entry.type !== "custom" || entry.customType !== PROVIDER_BINDING_ENTRY_TYPE) continue;
    if (entry.data === null || typeof entry.data !== "object" || Array.isArray(entry.data)) {
      throw new Error("PI_MOBILE_SESSION_PROVIDER_BINDING_INVALID");
    }
    const data = entry.data as Record<string, unknown>;
    if (
      Object.keys(data).sort().join(",") !== "kind,modelId" ||
      (data.kind !== "openrouter" && data.kind !== "codex") ||
      typeof data.modelId !== "string"
    ) {
      throw new Error("PI_MOBILE_SESSION_PROVIDER_BINDING_INVALID");
    }
    requireModelId(data.modelId, data.kind);
    const candidate: { kind: NativeProviderKind; modelId: string } = {
      kind: data.kind as NativeProviderKind,
      modelId: data.modelId,
    };
    if (
      binding !== null &&
      (binding.kind !== candidate.kind || binding.modelId !== candidate.modelId)
    ) {
      throw new Error("PI_MOBILE_SESSION_PROVIDER_BINDING_CONFLICT");
    }
    binding = candidate;
  }
  return binding;
}

async function ensureProviderBinding(state: NativeScenarioState): Promise<void> {
  if (state.taskId === null || state.providerBindingRecorded) return;
  await state.session.appendCustomEntry(PROVIDER_BINDING_ENTRY_TYPE, {
    kind: state.providerKind,
    modelId: state.modelId,
  });
  state.providerBindingRecorded = true;
}

async function ensureConnectorBinding(state: NativeScenarioState): Promise<void> {
  if (state.connectorSnapshot === null || state.connectorBindingRecorded) return;
  await state.session.appendCustomEntry(
    CONNECTOR_SNAPSHOT_ENTRY_TYPE,
    connectorBinding(state.connectorSnapshot),
  );
  state.connectorBindingRecorded = true;
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
    pendingProviderCount(state) > 0 ||
    state.pendingTools.size > 0 ||
    queuedProviderRequestCount(state) > 0 ||
    queuedProviderCancellationCount(state) > 0 ||
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
    await ensureProviderBinding(state);
    await ensureConnectorBinding(state);
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
    await ensureProviderBinding(state);
    await ensureConnectorBinding(state);
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
    await syncSkillResourceTool(state, resources.length > 0);
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

async function syncSkillResourceTool(
  state: NativeScenarioState,
  enabled: boolean,
): Promise<void> {
  const tools = state.harness.getTools();
  const hasTool = tools.some((tool) => tool.name === SKILL_RESOURCE_TOOL_NAME);
  if (hasTool === enabled) return;
  const activeNames = activeToolNames(state).filter((name) => name !== SKILL_RESOURCE_TOOL_NAME);
  let prePlanNames: string[] | null = null;
  if (state.planMode) {
    if (state.prePlanActiveToolNames === null) {
      throw new Error("PI_MOBILE_PLAN_TOOL_SNAPSHOT_MISSING");
    }
    prePlanNames = state.prePlanActiveToolNames.filter(
      (name) => name !== SKILL_RESOURCE_TOOL_NAME,
    );
  }
  if (!enabled) {
    await state.harness.setTools(
      tools.filter((tool) => tool.name !== SKILL_RESOURCE_TOOL_NAME),
      activeNames,
    );
    if (prePlanNames !== null) await replacePlanModeToolSnapshot(state, prePlanNames);
    return;
  }
  const skillTool = createAndroidSkillResourceTool(state.executeNativeTool);
  const attachmentIndex = tools.findIndex((tool) => tool.name === "attachment_read");
  const nextTools = [...tools];
  nextTools.splice(attachmentIndex < 0 ? nextTools.length : attachmentIndex + 1, 0, skillTool);
  await state.harness.setTools(
    nextTools,
    state.planMode ? activeNames : [...activeNames, SKILL_RESOURCE_TOOL_NAME],
  );
  if (prePlanNames !== null) {
    await replacePlanModeToolSnapshot(state, [...prePlanNames, SKILL_RESOURCE_TOOL_NAME]);
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
    pendingProviderCount(state) > 0 ||
    state.pendingTools.size > 0 ||
    state.pendingAttachedTaskMessages > 0 ||
    queuedProviderRequestCount(state) > 0 ||
    queuedProviderCancellationCount(state) > 0 ||
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
  const state = requireNativeScenario();
  return state.providerKind === "codex"
    ? drainCodexRequests(state)
    : drainOpenRouterRequests(state);
}

export function drainNativeProviderCancellations(): NativeProviderCancellation[] {
  const state = requireNativeScenario();
  return state.providerKind === "codex"
    ? drainCodexCancellations(state)
    : drainOpenRouterCancellations(state);
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
  if (state.providerKind === "codex") pushCodexEvent(state, requestId, chunk);
  else pushOpenRouterChunk(state, requestId, chunk, () => updateTerminal(state));
  return nativeOpenRouterScenarioStatus();
}

export function completeNativeProviderRequest(
  requestId: string,
  generationId?: string,
): Record<string, unknown> {
  const state = requireNativeScenario();
  if (state.providerKind === "codex") completeCodexRequest(state, requestId);
  else {
    completeOpenRouterRequest(
      state,
      requestId,
      generationId,
      () => updateTerminal(state),
    );
  }
  return nativeOpenRouterScenarioStatus();
}

export function failNativeProviderRequest(
  requestId: string,
  safeMessage: string,
): Record<string, unknown> {
  const state = requireNativeScenario();
  const message = requireSafeProviderError(safeMessage);
  if (state.providerKind === "codex") failCodexRequest(state, requestId, message);
  else failOpenRouterRequest(state, requestId, message, () => updateTerminal(state));
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
    pendingProviderCount: pendingProviderCount(state),
    queuedProviderRequestCount: queuedProviderRequestCount(state),
    queuedProviderCancellationCount: queuedProviderCancellationCount(state),
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
    connector: state.connectorSnapshot === null ? null : connectorBinding(state.connectorSnapshot),
    resourceSetDigest: state.resourceSetDigest,
    resourceSetTrusted: state.resourceSetTrusted,
    resourceTransitionPending: state.resourceTransitionPending,
    resourceUpdateCount: state.resourceUpdateCount,
    extensionSetDigest: state.extensionSetDigest,
    extensionSetTrusted: state.extensionSetTrusted,
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
  try {
    runCleanupActions("PI_MOBILE_SCENARIO_CLOSE_FAILED", [
      ["child_agents", () => state.childAgents?.close("runtime_rebuilt")],
      ["subscriptions", state.unsubscribe],
      ["openrouter_bridge", () => closeOpenRouterNativeBridge(state)],
      ["codex_bridge", () => closeCodexNativeBridge(state)],
    ]);
  } finally {
    for (const pending of state.pendingTools.values()) {
      clearToolAbort(pending);
      pending.reject(new Error("PI_MOBILE_RUNTIME_CLOSED"));
    }
    state.pendingTools.clear();
    state.toolOutbox.length = 0;
    state.childEventOutbox.length = 0;
    nativeScenarioState = null;
  }
}

function createNativeProviderStream(
  state: NativeScenarioState,
  model: Model<Api>,
  context: Context,
  options?: SimpleStreamOptions,
  childBinding?: PiChildBinding,
) {
  if (state.providerKind === "codex") {
    return createCodexNativeStream(
      state,
      model as Model<"openai-codex-responses">,
      context,
      options,
      {
        consumeLiveContext: (messages) => consumeLiveToolContext(messages, state),
        updateTerminal: () => updateTerminal(state),
      },
      childBinding,
    );
  }
  return createOpenRouterNativeStream(
    state,
    model as Model<"openai-completions">,
    context,
    options,
    {
      consumeLiveContext: (messages) =>
        consumeLiveToolContext(messages, state),
      updateTerminal: () => updateTerminal(state),
      recordWebActivityEvent: (event) => {
        state.events.push(event);
        state.eventTypes.push(event.type);
      },
    },
    childBinding,
  );
}

function recordPiRegisterToolActivity(
  state: NativeScenarioState,
  activity: PiRegisterToolActivity,
): void {
  const eventState = activity.phase === "started"
    ? "running"
    : activity.phase === "completed"
      ? "completed"
      : state.stopRequested || activity.code === "EXTENSION_PACKAGE_STOPPED"
        ? "cancelled"
        : "failed";
  const event = activity.kind === "host_tool"
    ? {
        type: "extension_tool_activity" as const,
        state: eventState,
        toolCallId: activity.toolCallId,
        seq: activity.seq,
        kind: activity.kind,
        packageId: activity.packageId,
        name: activity.name,
        targetTool: activity.targetTool,
        ...(activity.code === undefined ? {} : { code: activity.code }),
      }
    : {
        type: "extension_tool_activity" as const,
        state: eventState,
        toolCallId: activity.toolCallId,
        seq: activity.seq,
        kind: activity.kind,
        packageId: activity.packageId,
        ...(activity.method === undefined ? {} : { method: activity.method }),
        ...(activity.origin === undefined ? {} : { origin: activity.origin }),
        ...(activity.status === undefined ? {} : { status: activity.status }),
        ...(activity.responseBytes === undefined ? {} : { responseBytes: activity.responseBytes }),
        ...(activity.durationMillis === undefined ? {} : { durationMillis: activity.durationMillis }),
        ...(activity.redirects === undefined ? {} : { redirects: activity.redirects }),
        ...(activity.code === undefined ? {} : { code: activity.code }),
      };
  state.events.push(event);
  state.eventTypes.push(event.type);
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
  provider: Provider,
  binding: PiChildBinding,
): Models {
  const childProvider: Provider = {
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
    pendingProviderCount(state) === 0 &&
    state.pendingTools.size === 0 &&
    state.pendingAttachedTaskMessages === 0;
}

function pendingProviderCount(state: NativeScenarioState): number {
  return state.pendingProviders.size + state.pendingCodexProviders.size;
}

function queuedProviderRequestCount(state: NativeScenarioState): number {
  return state.providerOutbox.length + state.codexProviderOutbox.length;
}

function queuedProviderCancellationCount(state: NativeScenarioState): number {
  return state.providerCancellationOutbox.length +
    state.codexProviderCancellationOutbox.length;
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

function requireModelId(value: string, providerKind: NativeProviderKind): void {
  const valid = providerKind === "codex"
    ? /^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$/.test(value)
    : /^[A-Za-z0-9][A-Za-z0-9._-]{0,63}\/[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$/.test(value);
  if (!valid) throw new Error("PI_MOBILE_PROVIDER_MODEL_ID_INVALID");
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

function runCleanupActions(
  errorCode: string,
  actions: readonly (readonly [label: string, action: () => unknown])[],
): void {
  const errors: string[] = [];
  for (const [label, action] of actions) {
    try {
      action();
    } catch (error) {
      errors.push(`${label}:${safeErrorMessage(error)}`);
    }
  }
  if (errors.length > 0) throw new Error(`${errorCode} ${errors.join("|")}`);
}

function safeErrorMessage(error: unknown): string {
  return error instanceof Error ? error.message : "Phone-local Provider operation failed";
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}
