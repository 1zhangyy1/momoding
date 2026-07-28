import {
  AgentHarness,
  InMemorySessionStorage,
  Session,
  type ExecutionEnv,
} from "@earendil-works/pi-agent-core";
import type {
  Api,
  ApiStreamOptions,
  AssistantMessage,
  AssistantMessageEventStream,
  Context,
  Model,
  Models,
  Provider,
  SimpleStreamOptions,
} from "@earendil-works/pi-ai";
import {
  installPiMobilePlatformGlobals,
  platformCapabilities,
} from "./platform-globals.js";
import {
  abortFakeScenario,
  closeFakeScenario,
  drainNativeRequests,
  rejectNativeRequest,
  resolveNativeRequest,
  scenarioStatus,
  startFakeScenario,
  type FakeScenarioKind,
} from "./fake-agent-scenario.js";
import {
  acknowledgeNativeOpenRouterChildAgents,
  acknowledgeNativeOpenRouterChildEvents,
  abortNativeOpenRouterScenario,
  cancelNativeOpenRouterChildAgent,
  beginSkillDocumentParse,
  clearSkillDocumentParse,
  closeNativeOpenRouterScenario,
  closeSkillDocumentParse,
  completeNativeProviderRequest,
  continueNativeOpenRouterTaskPrompt,
  currentSkillDocumentParse,
  drainNativeProviderCancellations,
  drainNativeOpenRouterChildEvents,
  drainNativeProviderRequests,
  drainNativeProviderToolRequests,
  failNativeProviderRequest,
  peekNativeOpenRouterChildEvents,
  followUpNativeOpenRouterTask,
  nativeOpenRouterScenarioStatus,
  nativeOpenRouterTaskSessionSnapshot,
  pushNativeProviderChunk,
  resolveNativeProviderToolRequest,
  restoreNativeOpenRouterTaskSession,
  invokeNativeOpenRouterTaskSkill,
  implementNativeOpenRouterTaskPlan,
  setNativeOpenRouterTaskPlanMode,
  setNativeOpenRouterTaskGoalState,
  setNativeOpenRouterTaskResources,
  startNativeOpenRouterPrompt,
  startNativeOpenRouterScenario,
  startNativeOpenRouterTaskGoal,
  startNativeOpenRouterTaskSession,
  startNativeOpenRouterTaskSkillSession,
  steerNativeOpenRouterTask,
  continueNativeOpenRouterTaskGoal,
  type NativeOpenRouterScenarioKind,
  type PiChildEventAck,
  requirePiMobileSkillResources,
  requireRuntimeImageInputs,
  requireRuntimeTextAttachmentInputs,
} from "./native-openrouter-scenario.js";

declare const __PI_VERSION__: string;
declare const __BUNDLE_SCHEMA_VERSION__: string;
declare const __BUILD_REVISION__: string;

interface RuntimeState {
  harness: AgentHarness;
  models: Models;
}

const BOOTSTRAP_SESSION_ID = "phone-local-l0-bootstrap";
const BOOTSTRAP_CREATED_AT = "1970-01-01T00:00:00.000Z";
let runtimeState: RuntimeState | null = null;

installPiMobilePlatformGlobals();

const bootstrapModel: Model<"phone-local-bootstrap"> = {
  id: "phone-local-bootstrap",
  name: "Phone-local bootstrap",
  api: "phone-local-bootstrap",
  provider: "phone-local",
  baseUrl: "about:blank",
  reasoning: false,
  input: ["text"],
  cost: {
    input: 0,
    output: 0,
    cacheRead: 0,
    cacheWrite: 0,
  },
  contextWindow: 1,
  maxTokens: 1,
};

function unsupportedStream(): never {
  throw new Error("Provider streaming belongs to L0-3; L0-2 only boots AgentHarness");
}

function createBootstrapModels(): Models {
  const provider: Provider = {
    id: "phone-local",
    name: "Phone-local bootstrap",
    auth: {
      apiKey: {
        name: "Phone-local bootstrap",
        resolve: async () => undefined,
      },
    },
    getModels: () => [bootstrapModel],
    stream: unsupportedStream,
    streamSimple: unsupportedStream,
  };

  return {
    getProviders: () => [provider],
    getProvider: (id: string) => id === provider.id ? provider : undefined,
    getModels: (providerId?: string) =>
      providerId === undefined || providerId === provider.id ? [bootstrapModel] : [],
    getModel: (providerId: string, id: string) =>
      providerId === provider.id && id === bootstrapModel.id ? bootstrapModel : undefined,
    refresh: async () => undefined,
    getAuth: async () => undefined,
    stream: <TApi extends Api>(
      _model: Model<TApi>,
      _context: Context,
      _options?: ApiStreamOptions<TApi>,
    ): AssistantMessageEventStream => unsupportedStream(),
    complete: async <TApi extends Api>(
      _model: Model<TApi>,
      _context: Context,
      _options?: ApiStreamOptions<TApi>,
    ): Promise<AssistantMessage> => unsupportedStream(),
    streamSimple: (
      _model: Model<Api>,
      _context: Context,
      _options?: SimpleStreamOptions,
    ): AssistantMessageEventStream => unsupportedStream(),
    completeSimple: async (
      _model: Model<Api>,
      _context: Context,
      _options?: SimpleStreamOptions,
    ): Promise<AssistantMessage> => unsupportedStream(),
  };
}

function createBootstrapEnv(): ExecutionEnv {
  const unavailable = async () => ({
    ok: false as const,
    error: new Error("Android capabilities are not connected during L0-2 bootstrap"),
  });

  return {
    cwd: "/",
    absolutePath: unavailable,
    joinPath: unavailable,
    readTextFile: unavailable,
    readTextLines: unavailable,
    readBinaryFile: unavailable,
    writeFile: unavailable,
    appendFile: unavailable,
    fileInfo: unavailable,
    listDir: unavailable,
    canonicalPath: unavailable,
    exists: unavailable,
    createDir: unavailable,
    remove: unavailable,
    createTempDir: unavailable,
    createTempFile: unavailable,
    exec: unavailable,
    cleanup: async () => undefined,
  } as unknown as ExecutionEnv;
}

function requireSecureRandom(): void {
  if (typeof globalThis.crypto?.getRandomValues !== "function") {
    throw new Error("PI_MOBILE_SECURE_RANDOM_MISSING");
  }
}

export function bootstrapJson(): string {
  if (runtimeState !== null) {
    throw new Error("PI_MOBILE_RUNTIME_ALREADY_BOOTED");
  }
  requireSecureRandom();

  const models = createBootstrapModels();
  const session = new Session(
    new InMemorySessionStorage({
      metadata: {
        id: BOOTSTRAP_SESSION_ID,
        createdAt: BOOTSTRAP_CREATED_AT,
      },
    }),
  );
  const harness = new AgentHarness({
    env: createBootstrapEnv(),
    session,
    models,
    model: bootstrapModel,
    systemPrompt: "Phone-local Pi L0 bootstrap",
  });
  runtimeState = { harness, models };

  return JSON.stringify({
    ok: true,
    schemaVersion: __BUNDLE_SCHEMA_VERSION__,
    piVersion: __PI_VERSION__,
    buildRevision: __BUILD_REVISION__,
    runtime: "AgentHarness",
    modelId: harness.getModel().id,
    thinkingLevel: harness.getThinkingLevel(),
    activeToolCount: harness.getActiveTools().length,
    capabilities: platformCapabilities(),
  });
}

export function statusJson(): string {
  return JSON.stringify({
    booted: runtimeState !== null,
    piVersion: __PI_VERSION__,
    runtime: runtimeState ? "AgentHarness" : null,
    modelId: runtimeState?.harness.getModel().id ?? null,
    capabilities: platformCapabilities(),
  });
}

export function startScenarioJson(kind: string): string {
  if (runtimeState === null) throw new Error("PI_MOBILE_RUNTIME_NOT_BOOTED");
  if (!isFakeScenarioKind(kind)) throw new Error(`PI_MOBILE_SCENARIO_UNKNOWN ${kind}`);
  return JSON.stringify(startFakeScenario(kind, createBootstrapEnv()));
}

export function scenarioStatusJson(): string {
  return JSON.stringify(scenarioStatus());
}

export function drainNativeRequestsJson(): string {
  return JSON.stringify(drainNativeRequests());
}

export function resolveNativeRequestJson(requestId: string, resultJson: string): string {
  return JSON.stringify(resolveNativeRequest(requestId, JSON.parse(resultJson) as unknown));
}

export function rejectNativeRequestJson(requestId: string, message: string): string {
  return JSON.stringify(rejectNativeRequest(requestId, message));
}

export function abortScenarioJson(): string {
  return JSON.stringify(abortFakeScenario());
}

export function startNativeOpenRouterScenarioJson(
  kind: string,
  modelId: string,
): string {
  if (runtimeState === null) throw new Error("PI_MOBILE_RUNTIME_NOT_BOOTED");
  if (!isNativeOpenRouterScenarioKind(kind)) {
    throw new Error(`PI_MOBILE_NATIVE_PROVIDER_SCENARIO_UNKNOWN ${kind}`);
  }
  return JSON.stringify(
    startNativeOpenRouterScenario(kind, modelId, createBootstrapEnv()),
  );
}

export function startNativeOpenRouterPromptJson(
  prompt: string,
  modelId: string,
): string {
  if (runtimeState === null) throw new Error("PI_MOBILE_RUNTIME_NOT_BOOTED");
  return JSON.stringify(
    startNativeOpenRouterPrompt(prompt, modelId, createBootstrapEnv()),
  );
}

export function startNativeOpenRouterTaskSessionJson(
  taskId: string,
  prompt: string,
  modelId: string,
  sessionId?: string,
  planMode = false,
  skillResourcesJson = "[]",
  imageInputsJson = "[]",
  textAttachmentInputsJson = "[]",
): string {
  if (runtimeState === null) throw new Error("PI_MOBILE_RUNTIME_NOT_BOOTED");
  return JSON.stringify(
    startNativeOpenRouterTaskSession(
      taskId,
      prompt,
      modelId,
      createBootstrapEnv(),
      sessionId,
      planMode,
      requirePiMobileSkillResources(JSON.parse(skillResourcesJson) as unknown),
      requireRuntimeImageInputs(JSON.parse(imageInputsJson) as unknown),
      requireRuntimeTextAttachmentInputs(JSON.parse(textAttachmentInputsJson) as unknown),
    ),
  );
}

export function startNativeOpenRouterTaskSkillSessionJson(
  taskId: string,
  skillName: string,
  additionalInstructions: string | undefined,
  modelId: string,
  sessionId?: string,
  skillResourcesJson = "[]",
): string {
  if (runtimeState === null) throw new Error("PI_MOBILE_RUNTIME_NOT_BOOTED");
  return JSON.stringify(
    startNativeOpenRouterTaskSkillSession(
      taskId,
      skillName,
      additionalInstructions,
      modelId,
      createBootstrapEnv(),
      sessionId,
      requirePiMobileSkillResources(JSON.parse(skillResourcesJson) as unknown),
    ),
  );
}

export function beginSkillDocumentParseJson(rawContent: string): string {
  if (runtimeState === null) throw new Error("PI_MOBILE_RUNTIME_NOT_BOOTED");
  return JSON.stringify(beginSkillDocumentParse(rawContent));
}

export function skillDocumentParseStatusJson(parseId: number): string {
  return JSON.stringify(currentSkillDocumentParse(parseId));
}

export function clearSkillDocumentParseJson(parseId: number): string {
  clearSkillDocumentParse(parseId);
  return JSON.stringify({ ok: true, cleared: true });
}

export function setNativeOpenRouterTaskPlanModeJson(enabled: boolean): string {
  return JSON.stringify(setNativeOpenRouterTaskPlanMode(enabled));
}

export function implementNativeOpenRouterTaskPlanJson(planDigest: string): string {
  return JSON.stringify(implementNativeOpenRouterTaskPlan(planDigest));
}

export function startNativeOpenRouterTaskGoalJson(
  goalId: string,
  instruction: string,
  generation: number,
  startedAtMillis: number,
): string {
  return JSON.stringify(
    startNativeOpenRouterTaskGoal(goalId, instruction, generation, startedAtMillis),
  );
}

export function continueNativeOpenRouterTaskGoalJson(
  goalId: string,
  generation: number,
  turnIndex: number,
  resume: boolean,
): string {
  return JSON.stringify(
    continueNativeOpenRouterTaskGoal(goalId, generation, turnIndex, resume),
  );
}

export function setNativeOpenRouterTaskGoalStateJson(
  goalId: string,
  generation: number,
  targetState: "paused" | "limited" | "failed" | "cleared",
): string {
  return JSON.stringify(
    setNativeOpenRouterTaskGoalState(goalId, generation, targetState),
  );
}

export function continueNativeOpenRouterTaskPromptJson(
  prompt: string,
  imageInputsJson = "[]",
  textAttachmentInputsJson = "[]",
): string {
  return JSON.stringify(
    continueNativeOpenRouterTaskPrompt(
      prompt,
      requireRuntimeImageInputs(JSON.parse(imageInputsJson) as unknown),
      requireRuntimeTextAttachmentInputs(JSON.parse(textAttachmentInputsJson) as unknown),
    ),
  );
}

export function setNativeOpenRouterTaskResourcesJson(skillResourcesJson: string): string {
  return JSON.stringify(
    setNativeOpenRouterTaskResources(
      requirePiMobileSkillResources(JSON.parse(skillResourcesJson) as unknown),
    ),
  );
}

export function invokeNativeOpenRouterTaskSkillJson(
  skillName: string,
  additionalInstructions?: string,
): string {
  return JSON.stringify(
    invokeNativeOpenRouterTaskSkill(skillName, additionalInstructions),
  );
}

export function restoreNativeOpenRouterTaskSessionJson(
  taskId: string,
  sessionId: string,
  turnCount: number,
  entriesJson: string,
  modelId: string,
  skillResourcesJson = "[]",
  imageInputsJson = "[]",
): string {
  if (runtimeState === null) throw new Error("PI_MOBILE_RUNTIME_NOT_BOOTED");
  return JSON.stringify(
    restoreNativeOpenRouterTaskSession(
      taskId,
      sessionId,
      turnCount,
      JSON.parse(entriesJson) as unknown,
      modelId,
      createBootstrapEnv(),
      requirePiMobileSkillResources(JSON.parse(skillResourcesJson) as unknown),
      requireRuntimeImageInputs(JSON.parse(imageInputsJson) as unknown),
    ),
  );
}

export function steerNativeOpenRouterTaskJson(
  text: string,
  imageInputsJson = "[]",
  textAttachmentInputsJson = "[]",
): string {
  return JSON.stringify(
    steerNativeOpenRouterTask(
      text,
      requireRuntimeImageInputs(JSON.parse(imageInputsJson) as unknown),
      requireRuntimeTextAttachmentInputs(JSON.parse(textAttachmentInputsJson) as unknown),
    ),
  );
}

export function followUpNativeOpenRouterTaskJson(
  text: string,
  imageInputsJson = "[]",
  textAttachmentInputsJson = "[]",
): string {
  return JSON.stringify(
    followUpNativeOpenRouterTask(
      text,
      requireRuntimeImageInputs(JSON.parse(imageInputsJson) as unknown),
      requireRuntimeTextAttachmentInputs(JSON.parse(textAttachmentInputsJson) as unknown),
    ),
  );
}

export function cancelNativeOpenRouterChildAgentJson(childId: string): string {
  return JSON.stringify(cancelNativeOpenRouterChildAgent(childId));
}

export function acknowledgeNativeOpenRouterChildAgentsJson(childIdsJson: string): string {
  const childIds = JSON.parse(childIdsJson) as unknown;
  if (!Array.isArray(childIds) || !childIds.every((childId) => typeof childId === "string")) {
    throw new Error("PI_MOBILE_CHILD_ACK_INVALID");
  }
  return JSON.stringify(acknowledgeNativeOpenRouterChildAgents(childIds));
}

export function nativeOpenRouterTaskSessionSnapshotJson(): string {
  return JSON.stringify(nativeOpenRouterTaskSessionSnapshot());
}

export function nativeOpenRouterScenarioStatusJson(): string {
  return JSON.stringify(nativeOpenRouterScenarioStatus());
}

export function drainNativeProviderRequestsJson(): string {
  return JSON.stringify(drainNativeProviderRequests());
}

export function drainNativeProviderCancellationsJson(): string {
  return JSON.stringify(drainNativeProviderCancellations());
}

export function drainNativeOpenRouterChildEventsJson(): string {
  return JSON.stringify(drainNativeOpenRouterChildEvents());
}

export function peekNativeOpenRouterChildEventsJson(): string {
  return JSON.stringify(peekNativeOpenRouterChildEvents());
}

export function acknowledgeNativeOpenRouterChildEventsJson(
  acknowledgementsJson: string,
): string {
  const acknowledgements = JSON.parse(acknowledgementsJson) as unknown;
  if (!Array.isArray(acknowledgements)) {
    throw new Error("PI_MOBILE_CHILD_EVENT_ACK_INVALID");
  }
  return JSON.stringify(
    acknowledgeNativeOpenRouterChildEvents(acknowledgements as PiChildEventAck[]),
  );
}

export function pushNativeProviderChunkJson(
  requestId: string,
  chunkJson: string,
): string {
  return JSON.stringify(
    pushNativeProviderChunk(requestId, JSON.parse(chunkJson) as unknown),
  );
}

export function completeNativeProviderRequestJson(
  requestId: string,
  generationId?: string,
): string {
  return JSON.stringify(
    completeNativeProviderRequest(requestId, generationId),
  );
}

export function failNativeProviderRequestJson(
  requestId: string,
  safeMessage: string,
): string {
  return JSON.stringify(failNativeProviderRequest(requestId, safeMessage));
}

export function drainNativeProviderToolRequestsJson(): string {
  return JSON.stringify(drainNativeProviderToolRequests());
}

export function resolveNativeProviderToolRequestJson(
  requestId: string,
  contentPayloadJson: string,
  detailsJson?: string,
  isError = false,
  contentJson?: string,
): string {
  return JSON.stringify(
    resolveNativeProviderToolRequest(
      requestId,
      JSON.parse(contentPayloadJson) as unknown,
      detailsJson === undefined
        ? undefined
        : JSON.parse(detailsJson) as unknown,
      isError,
      contentJson === undefined
        ? undefined
        : JSON.parse(contentJson) as unknown,
    ),
  );
}

export function abortNativeOpenRouterScenarioJson(): string {
  return JSON.stringify(abortNativeOpenRouterScenario());
}

export function closeJson(): string {
  const wasBooted = runtimeState !== null;
  closeFakeScenario();
  closeNativeOpenRouterScenario();
  closeSkillDocumentParse();
  runtimeState = null;
  return JSON.stringify({
    ok: true,
    closed: wasBooted,
  });
}

function isFakeScenarioKind(value: string): value is FakeScenarioKind {
  return value === "tool_success" ||
    value === "tool_error" ||
    value === "provider_error" ||
    value === "stop_before_tool";
}

function isNativeOpenRouterScenarioKind(
  value: string,
): value is NativeOpenRouterScenarioKind {
  return value === "text" ||
    value === "tool" ||
    value === "provider_error" ||
    value === "stop";
}
