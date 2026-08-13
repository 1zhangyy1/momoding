package app.momoding.core.runtime.local

import android.content.res.AssetManager
import com.dokar.quickjs.QuickJs
import app.momoding.core.connector.ConnectorToolSnapshot
import app.momoding.core.extensions.ExtensionPackageSnapshot
import app.momoding.core.extensions.extensionPackageSnapshotsWireJson
import app.momoding.core.skills.PhoneLocalSkillResource
import java.security.MessageDigest
import java.security.SecureRandom
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

private const val RUNTIME_ASSET_DIRECTORY = "pi-runtime"
private const val MANIFEST_ASSET = "$RUNTIME_ASSET_DIRECTORY/manifest.json"
private const val EXPECTED_MANIFEST_SCHEMA = 1
private const val EXPECTED_PI_VERSION = "0.80.6"
private const val QUICK_JS_MEMORY_LIMIT_BYTES = 128L * 1024L * 1024L
private const val QUICK_JS_STACK_LIMIT_BYTES = 6L * 1024L * 1024L
private const val SECURE_RANDOM_POOL_BYTES = 4096
private val EXPECTED_COMPATIBILITY_TRANSFORMS = listOf(
    "pi-agent-core-compat-import:narrow-browser-shim",
    "pi-tool-validation:fail-closed-json-schema-subset",
)

@Serializable
data class PiRuntimeAssetManifest(
    val schemaVersion: Int,
    val runtime: String,
    val piVersion: String,
    val bundleFile: String,
    val bundleSha256: String,
    val sourceSha256: String,
    val buildRevision: String,
    val buildTarget: String,
    val compatibilityTransforms: List<String>,
)

@Serializable
data class PiRuntimeBootstrap(
    val ok: Boolean,
    val schemaVersion: String,
    val piVersion: String,
    val buildRevision: String,
    val runtime: String,
    val modelId: String,
    val thinkingLevel: String,
    val activeToolCount: Int,
    val capabilities: Map<String, Boolean>,
)

@Serializable
private data class PiRuntimeCloseResult(
    val ok: Boolean,
    val closed: Boolean,
)

@Serializable
internal data class PiMobileSkillParseStatus(
    val ok: Boolean,
    val parseId: Int,
    val phase: String,
    val resource: PhoneLocalSkillResource? = null,
    val availability: String? = null,
    val diagnosticCode: String? = null,
    val diagnosticMessage: String? = null,
    val errorCode: String? = null,
    val errorMessage: String? = null,
)

@Serializable
private data class PiMobileSkillParseClearResult(
    val ok: Boolean,
    val cleared: Boolean,
)

data class PiRuntimeBootstrapProof(
    val manifest: PiRuntimeAssetManifest,
    val bootstrap: PiRuntimeBootstrap,
    val bundleSizeBytes: Int,
    val quickJsVersion: String,
)

enum class PiFakeScenarioKind(val wireValue: String) {
    TOOL_SUCCESS("tool_success"),
    TOOL_ERROR("tool_error"),
    PROVIDER_ERROR("provider_error"),
    STOP_BEFORE_TOOL("stop_before_tool"),
}

enum class PiNativeOpenRouterScenarioKind(val wireValue: String) {
    TEXT("text"),
    TOOL("tool"),
    PROVIDER_ERROR("provider_error"),
    STOP("stop"),
}

@Serializable
data class PiNativeToolRequest(
    val id: String,
    val kind: String,
    val toolCallId: String,
    val toolName: String,
    val arguments: JsonObject,
)

@Serializable
data class PiNativeProviderRequest(
    val id: String,
    val kind: String,
    val modelId: String,
    val messages: JsonArray? = null,
    val tools: JsonArray? = null,
    val maxTokens: Int? = null,
    val sessionId: String? = null,
    val body: JsonObject? = null,
    val parentTaskId: String? = null,
    val parentToolCallId: String? = null,
    val childId: String? = null,
    val childName: String? = null,
)

@Serializable
data class PiNativeProviderCancellation(
    val id: String,
    val kind: String,
)

@Serializable
data class PiRuntimeImageInput(
    val attachmentId: String,
    val mimeType: String,
    val data: String,
)

@Serializable
data class PiRuntimeTextAttachmentInput(
    val attachmentId: String,
    val displayName: String,
    val mimeType: String,
    val byteSize: Long,
)

@Serializable
data class PiChildAgentEventEnvelope(
    val parentTaskId: String,
    val parentToolCallId: String,
    val childId: String,
    val childName: String,
    val eventOrdinal: Int,
    val event: JsonObject,
)

@Serializable
data class PiChildAgentEventAck(
    val parentTaskId: String,
    val parentToolCallId: String,
    val childId: String,
    val childName: String,
    val throughEventOrdinal: Int,
    val throughDigest: String,
)

@Serializable
data class PiChildAgentSnapshot(
    val parentTaskId: String,
    val parentToolCallId: String,
    val childId: String,
    val childName: String,
    val instruction: String,
    val state: String,
    val resultSummary: String? = null,
    val resultText: String? = null,
    val resultTruncated: Boolean = false,
    val terminalReason: String? = null,
    val stopReason: String? = null,
    val model: String? = null,
    val turnCount: Int = 0,
    val inputTokens: Int = 0,
    val outputTokens: Int = 0,
    val cacheReadTokens: Int = 0,
    val cacheWriteTokens: Int = 0,
    val contextTokens: Int = 0,
    val costUsd: Double = 0.0,
    val eventTypes: List<String>,
    val eventCount: Int,
)

@Serializable
data class PiChildAgentCancelResult(
    val accepted: Boolean,
    val status: PiNativeOpenRouterScenarioStatus,
)

@Serializable
private data class PiChildAgentAcknowledgeResult(
    val evictedChildIds: List<String>,
)

@Serializable
private data class PiChildAgentEventAcknowledgeResult(
    val acknowledgedEventCount: Int,
)

@Serializable
data class PiNativeOpenRouterScenarioStatus(
    val kind: String,
    val phase: String,
    val terminal: Boolean,
    val expectationMet: Boolean,
    val promptSettled: Boolean,
    val stopRequested: Boolean,
    val stopCompleted: Boolean,
    val promptError: String? = null,
    val providerError: String? = null,
    val stopError: String? = null,
    val finalText: String? = null,
    val events: List<JsonObject>,
    val eventTypes: List<String>,
    val pendingProviderCount: Int,
    val queuedProviderRequestCount: Int,
    val queuedProviderCancellationCount: Int,
    val providerRequestsIssued: Int,
    val providerRequestsCompleted: Int,
    val providerRequestsFailed: Int,
    val providerCancellationsIssued: Int,
    val childProviderRequestsIssued: Int = 0,
    val childProviderRequestsCompleted: Int = 0,
    val childProviderRequestsFailed: Int = 0,
    val childProviderCancellationsIssued: Int = 0,
    val lateProviderRequestsAfterStop: Int,
    val pendingToolCount: Int,
    val queuedToolRequestCount: Int,
    val toolRequestsIssued: Int,
    val toolRequestsResolved: Int,
    val toolExecutionsStarted: Int,
    val toolExecutionsEnded: Int,
    val lateToolStartsAfterStop: Int,
    val hasAgentStart: Boolean,
    val hasSettled: Boolean,
    val hasAbort: Boolean,
    val taskId: String? = null,
    val turnCount: Int = 0,
    val sessionEntryCount: Int = 0,
    val commandError: String? = null,
    val runEvents: List<JsonObject> = emptyList(),
    val runEventTypes: List<String> = emptyList(),
    val planMode: Boolean = false,
    val activeToolNames: List<String> = emptyList(),
    val prePlanActiveToolNames: List<String>? = null,
    val latestPlan: PiTaskPlanSnapshot? = null,
    val planTransitionPending: Boolean = false,
    val goal: PiTaskGoalSnapshot? = null,
    val goalTransitionPending: Boolean = false,
    val connector: PiConnectorBinding? = null,
    val childAgents: List<PiChildAgentSnapshot> = emptyList(),
    val queuedChildEventCount: Int = 0,
    val resourceSetDigest: String? = null,
    val resourceSetTrusted: Boolean = true,
    val resourceTransitionPending: Boolean = false,
    val resourceUpdateCount: Int = 0,
    val extensionSetDigest: String? = null,
    val extensionSetTrusted: Boolean = true,
    val skillNames: List<String> = emptyList(),
)

@Serializable
data class PiConnectorBinding(
    val connectorId: String,
    val connectionId: String,
    val sourceLabel: String,
    val mode: String,
    val schemaDigest: String,
    val exposedToolNames: List<String>,
)

@Serializable
data class PiNativeTaskSessionSnapshot(
    val taskId: String,
    val turnCount: Int,
    val entries: JsonArray,
    val planMode: Boolean = false,
    val activeToolNames: List<String> = emptyList(),
    val prePlanActiveToolNames: List<String>? = null,
    val latestPlan: PiTaskPlanSnapshot? = null,
    val goal: PiTaskGoalSnapshot? = null,
    val childAgents: List<PiChildAgentSnapshot> = emptyList(),
)

@Serializable
data class PiTaskPlanStep(
    val id: String,
    val text: String,
    val status: String,
)

@Serializable
data class PiTaskPlanSnapshot(
    val explanation: String,
    val steps: List<PiTaskPlanStep>,
    val planDigest: String,
)

@Serializable
data class PiTaskGoalSnapshot(
    val goalId: String,
    val instruction: String,
    val state: String,
    val progressSummary: String? = null,
    val progressMarker: String? = null,
    val terminalReason: String? = null,
    val generation: Int,
    val startedAtMillis: Long,
    val preGoalActiveToolNames: List<String>,
)

@Serializable
data class PiFakeScenarioStatus(
    val kind: String,
    val phase: String,
    val terminal: Boolean,
    val expectationMet: Boolean,
    val promptSettled: Boolean,
    val stopRequested: Boolean,
    val stopCompleted: Boolean,
    val promptError: String? = null,
    val stopError: String? = null,
    val finalText: String? = null,
    val events: List<JsonObject>,
    val eventTypes: List<String>,
    val pendingNativeRequestCount: Int,
    val queuedNativeRequestCount: Int,
    val toolRequestsIssued: Int,
    val toolRequestsResolved: Int,
    val toolRequestsRejected: Int,
    val toolExecutionsStarted: Int,
    val toolExecutionsEnded: Int,
    val toolErrors: Int,
    val lateToolStartsAfterStop: Int,
    val hasAgentStart: Boolean,
    val hasSettled: Boolean,
    val hasAbort: Boolean,
)

/**
 * Low-level L0 engine boundary. One instance is owned and called by exactly one dedicated thread.
 *
 * This is a concrete Pi runtime engine, not a selectable runtime adapter. The L0-3 methods expose
 * Pi's native fake-provider events and one narrow Android tool mailbox without translating either.
 */
class PhoneLocalPiEngine(
    private val assets: AssetManager,
    dispatcher: CoroutineDispatcher,
    private val secureRandom: SecureRandom = SecureRandom(),
) : AutoCloseable {
    private val ownerThread = Thread.currentThread()
    private val json = Json {
        ignoreUnknownKeys = false
        explicitNulls = false
    }
    private val quickJs = QuickJs.create(dispatcher).apply {
        memoryLimit = QUICK_JS_MEMORY_LIMIT_BYTES
        maxStackSize = QUICK_JS_STACK_LIMIT_BYTES
    }
    private var booted = false
    private var closed = false

    suspend fun bootstrap(): PiRuntimeBootstrapProof {
        checkOwnerThread()
        check(!closed) { "PI_MOBILE_ENGINE_CLOSED" }
        check(!booted) { "PI_MOBILE_ENGINE_ALREADY_BOOTED" }

        val manifest = assets.open(MANIFEST_ASSET).bufferedReader().use { reader ->
            json.decodeFromString<PiRuntimeAssetManifest>(reader.readText())
        }
        validatePiRuntimeManifest(manifest)

        val bundleBytes = assets.open("$RUNTIME_ASSET_DIRECTORY/${manifest.bundleFile}").use {
            it.readBytes()
        }
        val actualHash = bundleBytes.sha256()
        check(actualHash == manifest.bundleSha256) {
            "PI_MOBILE_BUNDLE_HASH_MISMATCH expected=${manifest.bundleSha256} actual=$actualHash"
        }

        quickJs.evaluate<Any?>(secureRandomPrelude(), "android-secure-random.js")
        quickJs.evaluate<Any?>(
            bundleBytes.toString(Charsets.UTF_8),
            manifest.bundleFile,
        )
        val resultJson = quickJs.evaluate<String>(
            "PiMobileRuntimeBundle.bootstrapJson()",
            "pi-mobile-bootstrap.js",
        )
        val result = json.decodeFromString<PiRuntimeBootstrap>(resultJson)

        check(result.ok) { "PI_MOBILE_BOOTSTRAP_FAILED" }
        check(result.piVersion == manifest.piVersion) {
            "PI_MOBILE_BOOTSTRAP_PI_VERSION_MISMATCH"
        }
        check(result.buildRevision == manifest.buildRevision) {
            "PI_MOBILE_BOOTSTRAP_REVISION_MISMATCH"
        }
        check(result.runtime == "AgentHarness") {
            "PI_MOBILE_BOOTSTRAP_RUNTIME_MISMATCH"
        }
        check(result.capabilities["secureRandom"] == true) {
            "PI_MOBILE_SECURE_RANDOM_MISSING"
        }

        booted = true
        return PiRuntimeBootstrapProof(
            manifest = manifest,
            bootstrap = result,
            bundleSizeBytes = bundleBytes.size,
            quickJsVersion = quickJs.version,
        )
    }

    suspend fun startScenario(kind: PiFakeScenarioKind): PiFakeScenarioStatus {
        checkReady()
        return decodeScenarioStatus(
            evaluateString(
                "PiMobileRuntimeBundle.startScenarioJson(${jsString(kind.wireValue)})",
                "pi-mobile-scenario-start.js",
            ),
        )
    }

    suspend fun scenarioStatus(): PiFakeScenarioStatus {
        checkReady()
        return decodeScenarioStatus(
            evaluateString(
                "PiMobileRuntimeBundle.scenarioStatusJson()",
                "pi-mobile-scenario-status.js",
            ),
        )
    }

    suspend fun drainNativeRequests(): List<PiNativeToolRequest> {
        checkReady()
        return json.decodeFromString(
            evaluateString(
                "PiMobileRuntimeBundle.drainNativeRequestsJson()",
                "pi-mobile-native-drain.js",
            ),
        )
    }

    suspend fun resolveNativeRequest(requestId: String, result: JsonObject): PiFakeScenarioStatus {
        checkReady()
        return decodeScenarioStatus(
            evaluateString(
                "PiMobileRuntimeBundle.resolveNativeRequestJson(" +
                    "${jsString(requestId)},${jsString(result.toString())})",
                "pi-mobile-native-resolve.js",
            ),
        )
    }

    suspend fun rejectNativeRequest(requestId: String, message: String): PiFakeScenarioStatus {
        checkReady()
        return decodeScenarioStatus(
            evaluateString(
                "PiMobileRuntimeBundle.rejectNativeRequestJson(" +
                    "${jsString(requestId)},${jsString(message)})",
                "pi-mobile-native-reject.js",
            ),
        )
    }

    suspend fun abortScenario(): PiFakeScenarioStatus {
        checkReady()
        return decodeScenarioStatus(
            evaluateString(
                "PiMobileRuntimeBundle.abortScenarioJson()",
                "pi-mobile-scenario-abort.js",
            ),
        )
    }

    suspend fun startNativeOpenRouterScenario(
        kind: PiNativeOpenRouterScenarioKind,
        modelId: String,
    ): PiNativeOpenRouterScenarioStatus {
        checkReady()
        return decodeNativeOpenRouterStatus(
            evaluateString(
                "PiMobileRuntimeBundle.startNativeOpenRouterScenarioJson(" +
                    "${jsString(kind.wireValue)},${jsString(modelId)})",
                "pi-mobile-native-provider-start.js",
            ),
        )
    }

    suspend fun startNativeOpenRouterPrompt(
        prompt: String,
        modelId: String,
    ): PiNativeOpenRouterScenarioStatus {
        checkReady()
        return decodeNativeOpenRouterStatus(
            evaluateString(
                "PiMobileRuntimeBundle.startNativeOpenRouterPromptJson(" +
                    "${jsString(prompt)},${jsString(modelId)})",
                "pi-mobile-native-provider-prompt.js",
            ),
        )
    }

    internal suspend fun beginSkillDocumentParse(content: String): PiMobileSkillParseStatus {
        checkReady()
        return decodeSkillParseStatus(
            evaluateString(
                "PiMobileRuntimeBundle.beginSkillDocumentParseJson(${jsString(content)})",
                "pi-mobile-skill-parse-start.js",
            ),
        )
    }

    internal suspend fun skillDocumentParseStatus(parseId: Int): PiMobileSkillParseStatus {
        checkReady()
        return decodeSkillParseStatus(
            evaluateString(
                "PiMobileRuntimeBundle.skillDocumentParseStatusJson($parseId)",
                "pi-mobile-skill-parse-status.js",
            ),
        )
    }

    suspend fun clearSkillDocumentParse(parseId: Int) {
        checkReady()
        val result = json.decodeFromString<PiMobileSkillParseClearResult>(
            evaluateString(
                "PiMobileRuntimeBundle.clearSkillDocumentParseJson($parseId)",
                "pi-mobile-skill-parse-clear.js",
            ),
        )
        check(result.ok && result.cleared) { "PI_MOBILE_SKILL_PARSE_CLEAR_FAILED" }
    }

    suspend fun startNativeOpenRouterTaskSession(
        taskId: String,
        sessionId: String,
        prompt: String,
        modelId: String,
        planMode: Boolean = false,
        skillResources: List<PhoneLocalSkillResource> = emptyList(),
        images: List<PiRuntimeImageInput> = emptyList(),
        textAttachments: List<PiRuntimeTextAttachmentInput> = emptyList(),
        imageGenerationEnabled: Boolean = false,
        connectorToolSnapshot: ConnectorToolSnapshot? = null,
        extensionPackages: List<ExtensionPackageSnapshot> = emptyList(),
    ): PiNativeOpenRouterScenarioStatus {
        checkReady()
        return decodeNativeOpenRouterStatus(
            evaluateString(
                "PiMobileRuntimeBundle.startNativeOpenRouterTaskSessionJson(" +
                    "${jsString(taskId)},${jsString(prompt)},${jsString(modelId)}," +
                    "${jsString(sessionId)},$planMode," +
                    "${jsString(json.encodeToString(skillResources))}," +
                    "${jsString(json.encodeToString(images))}," +
                    "${jsString(json.encodeToString(textAttachments))}," +
                    "$imageGenerationEnabled," +
                    "${jsString(json.encodeToString(connectorToolSnapshot))}," +
                    "${jsString(extensionPackageSnapshotsWireJson(extensionPackages))})",
                "pi-mobile-native-task-start.js",
            ),
        )
    }

    suspend fun startNativeCodexTaskSession(
        taskId: String,
        sessionId: String,
        prompt: String,
        modelId: String,
        planMode: Boolean = false,
        skillResources: List<PhoneLocalSkillResource> = emptyList(),
        textAttachments: List<PiRuntimeTextAttachmentInput> = emptyList(),
        connectorToolSnapshot: ConnectorToolSnapshot? = null,
        extensionPackages: List<ExtensionPackageSnapshot> = emptyList(),
    ): PiNativeOpenRouterScenarioStatus {
        checkReady()
        return decodeNativeOpenRouterStatus(
            evaluateString(
                "PiMobileRuntimeBundle.startNativeCodexTaskSessionJson(" +
                    "${jsString(taskId)},${jsString(prompt)},${jsString(modelId)}," +
                    "${jsString(sessionId)},$planMode," +
                    "${jsString(json.encodeToString(skillResources))}," +
                    "${jsString(json.encodeToString(textAttachments))}," +
                    "${jsString(json.encodeToString(connectorToolSnapshot))}," +
                    "${jsString(extensionPackageSnapshotsWireJson(extensionPackages))})",
                "pi-mobile-native-codex-task-start.js",
            ),
        )
    }

    suspend fun startNativeCodexTaskSkillSession(
        taskId: String,
        sessionId: String,
        skillName: String,
        additionalInstructions: String?,
        modelId: String,
        skillResources: List<PhoneLocalSkillResource>,
        extensionPackages: List<ExtensionPackageSnapshot> = emptyList(),
    ): PiNativeOpenRouterScenarioStatus {
        checkReady()
        val instructions = additionalInstructions?.let(::jsString) ?: "undefined"
        return decodeNativeOpenRouterStatus(
            evaluateString(
                "PiMobileRuntimeBundle.startNativeCodexTaskSkillSessionJson(" +
                    "${jsString(taskId)},${jsString(skillName)},$instructions," +
                    "${jsString(modelId)},${jsString(sessionId)}," +
                    "${jsString(json.encodeToString(skillResources))},null," +
                    "${jsString(extensionPackageSnapshotsWireJson(extensionPackages))})",
                "pi-mobile-native-codex-task-skill-start.js",
            ),
        )
    }

    suspend fun startNativeOpenRouterTaskSkillSession(
        taskId: String,
        sessionId: String,
        skillName: String,
        additionalInstructions: String?,
        modelId: String,
        skillResources: List<PhoneLocalSkillResource>,
        imageGenerationEnabled: Boolean = false,
        extensionPackages: List<ExtensionPackageSnapshot> = emptyList(),
    ): PiNativeOpenRouterScenarioStatus {
        checkReady()
        val instructions = additionalInstructions?.let(::jsString) ?: "undefined"
        return decodeNativeOpenRouterStatus(
            evaluateString(
                "PiMobileRuntimeBundle.startNativeOpenRouterTaskSkillSessionJson(" +
                    "${jsString(taskId)},${jsString(skillName)},$instructions," +
                    "${jsString(modelId)},${jsString(sessionId)}," +
                    "${jsString(json.encodeToString(skillResources))}," +
                    "$imageGenerationEnabled,null," +
                    "${jsString(extensionPackageSnapshotsWireJson(extensionPackages))})",
                "pi-mobile-native-task-skill-start.js",
            ),
        )
    }

    suspend fun setNativeOpenRouterTaskPlanMode(
        enabled: Boolean,
    ): PiNativeOpenRouterScenarioStatus {
        checkReady()
        return decodeNativeOpenRouterStatus(
            evaluateString(
                "PiMobileRuntimeBundle.setNativeOpenRouterTaskPlanModeJson($enabled)",
                "pi-mobile-native-task-plan-mode.js",
            ),
        )
    }

    suspend fun implementNativeOpenRouterTaskPlan(
        planDigest: String,
    ): PiNativeOpenRouterScenarioStatus {
        checkReady()
        return decodeNativeOpenRouterStatus(
            evaluateString(
                "PiMobileRuntimeBundle.implementNativeOpenRouterTaskPlanJson(" +
                    "${jsString(planDigest)})",
                "pi-mobile-native-task-plan-implement.js",
            ),
        )
    }

    suspend fun startNativeOpenRouterTaskGoal(
        goalId: String,
        instruction: String,
        generation: Int,
        startedAtMillis: Long,
    ): PiNativeOpenRouterScenarioStatus {
        checkReady()
        return decodeNativeOpenRouterStatus(
            evaluateString(
                "PiMobileRuntimeBundle.startNativeOpenRouterTaskGoalJson(" +
                    "${jsString(goalId)},${jsString(instruction)},$generation,$startedAtMillis)",
                "pi-mobile-native-task-goal-start.js",
            ),
        )
    }

    suspend fun continueNativeOpenRouterTaskGoal(
        goalId: String,
        generation: Int,
        turnIndex: Int,
        resume: Boolean,
    ): PiNativeOpenRouterScenarioStatus {
        checkReady()
        return decodeNativeOpenRouterStatus(
            evaluateString(
                "PiMobileRuntimeBundle.continueNativeOpenRouterTaskGoalJson(" +
                    "${jsString(goalId)},$generation,$turnIndex,$resume)",
                "pi-mobile-native-task-goal-continue.js",
            ),
        )
    }

    suspend fun setNativeOpenRouterTaskGoalState(
        goalId: String,
        generation: Int,
        targetState: String,
    ): PiNativeOpenRouterScenarioStatus {
        checkReady()
        return decodeNativeOpenRouterStatus(
            evaluateString(
                "PiMobileRuntimeBundle.setNativeOpenRouterTaskGoalStateJson(" +
                    "${jsString(goalId)},$generation,${jsString(targetState)})",
                "pi-mobile-native-task-goal-state.js",
            ),
        )
    }

    suspend fun continueNativeOpenRouterTaskPrompt(
        prompt: String,
        images: List<PiRuntimeImageInput> = emptyList(),
        textAttachments: List<PiRuntimeTextAttachmentInput> = emptyList(),
    ): PiNativeOpenRouterScenarioStatus {
        checkReady()
        return decodeNativeOpenRouterStatus(
            evaluateString(
                "PiMobileRuntimeBundle.continueNativeOpenRouterTaskPromptJson(" +
                    "${jsString(prompt)},${jsString(json.encodeToString(images))}," +
                    "${jsString(json.encodeToString(textAttachments))})",
                "pi-mobile-native-task-continue.js",
            ),
        )
    }

    suspend fun setNativeOpenRouterTaskResources(
        skillResources: List<PhoneLocalSkillResource>,
    ): PiNativeOpenRouterScenarioStatus {
        checkReady()
        return decodeNativeOpenRouterStatus(
            evaluateString(
                "PiMobileRuntimeBundle.setNativeOpenRouterTaskResourcesJson(" +
                    "${jsString(json.encodeToString(skillResources))})",
                "pi-mobile-native-task-resources.js",
            ),
        )
    }

    suspend fun invokeNativeOpenRouterTaskSkill(
        skillName: String,
        additionalInstructions: String?,
    ): PiNativeOpenRouterScenarioStatus {
        checkReady()
        val instructions = additionalInstructions?.let(::jsString) ?: "undefined"
        return decodeNativeOpenRouterStatus(
            evaluateString(
                "PiMobileRuntimeBundle.invokeNativeOpenRouterTaskSkillJson(" +
                    "${jsString(skillName)},$instructions)",
                "pi-mobile-native-task-skill.js",
            ),
        )
    }

    suspend fun restoreNativeOpenRouterTaskSession(
        taskId: String,
        sessionId: String,
        turnCount: Int,
        entries: JsonArray,
        modelId: String,
        skillResources: List<PhoneLocalSkillResource> = emptyList(),
        images: List<PiRuntimeImageInput> = emptyList(),
        imageGenerationEnabled: Boolean = false,
        connectorToolSnapshot: ConnectorToolSnapshot? = null,
        extensionPackages: List<ExtensionPackageSnapshot> = emptyList(),
    ): PiNativeOpenRouterScenarioStatus {
        checkReady()
        return decodeNativeOpenRouterStatus(
            evaluateString(
                "PiMobileRuntimeBundle.restoreNativeOpenRouterTaskSessionJson(" +
                    "${jsString(taskId)},${jsString(sessionId)},$turnCount," +
                    "${jsString(entries.toString())},${jsString(modelId)}," +
                    "${jsString(json.encodeToString(skillResources))}," +
                    "${jsString(json.encodeToString(images))}," +
                    "$imageGenerationEnabled," +
                    "${jsString(json.encodeToString(connectorToolSnapshot))}," +
                    "${jsString(extensionPackageSnapshotsWireJson(extensionPackages))})",
                "pi-mobile-native-task-restore.js",
            ),
        )
    }

    suspend fun restoreNativeCodexTaskSession(
        taskId: String,
        sessionId: String,
        turnCount: Int,
        entries: JsonArray,
        modelId: String,
        skillResources: List<PhoneLocalSkillResource> = emptyList(),
        connectorToolSnapshot: ConnectorToolSnapshot? = null,
        extensionPackages: List<ExtensionPackageSnapshot> = emptyList(),
    ): PiNativeOpenRouterScenarioStatus {
        checkReady()
        return decodeNativeOpenRouterStatus(
            evaluateString(
                "PiMobileRuntimeBundle.restoreNativeCodexTaskSessionJson(" +
                    "${jsString(taskId)},${jsString(sessionId)},$turnCount," +
                    "${jsString(entries.toString())},${jsString(modelId)}," +
                    "${jsString(json.encodeToString(skillResources))}," +
                    "${jsString(json.encodeToString(connectorToolSnapshot))}," +
                    "${jsString(extensionPackageSnapshotsWireJson(extensionPackages))})",
                "pi-mobile-native-codex-task-restore.js",
            ),
        )
    }

    suspend fun steerNativeOpenRouterTask(
        text: String,
        images: List<PiRuntimeImageInput> = emptyList(),
        textAttachments: List<PiRuntimeTextAttachmentInput> = emptyList(),
    ): PiNativeOpenRouterScenarioStatus {
        checkReady()
        return decodeNativeOpenRouterStatus(
            evaluateString(
                "PiMobileRuntimeBundle.steerNativeOpenRouterTaskJson(" +
                    "${jsString(text)},${jsString(json.encodeToString(images))}," +
                    "${jsString(json.encodeToString(textAttachments))})",
                "pi-mobile-native-task-steer.js",
            ),
        )
    }

    suspend fun followUpNativeOpenRouterTask(
        text: String,
        images: List<PiRuntimeImageInput> = emptyList(),
        textAttachments: List<PiRuntimeTextAttachmentInput> = emptyList(),
    ): PiNativeOpenRouterScenarioStatus {
        checkReady()
        return decodeNativeOpenRouterStatus(
            evaluateString(
                "PiMobileRuntimeBundle.followUpNativeOpenRouterTaskJson(" +
                    "${jsString(text)},${jsString(json.encodeToString(images))}," +
                    "${jsString(json.encodeToString(textAttachments))})",
                "pi-mobile-native-task-follow-up.js",
            ),
        )
    }

    suspend fun cancelNativeOpenRouterChildAgent(
        childId: String,
    ): PiChildAgentCancelResult {
        checkReady()
        return json.decodeFromString(
            evaluateString(
                "PiMobileRuntimeBundle.cancelNativeOpenRouterChildAgentJson(${jsString(childId)})",
                "pi-mobile-native-child-cancel.js",
            ),
        )
    }

    suspend fun acknowledgeNativeOpenRouterChildAgents(
        childIds: List<String>,
    ): List<String> {
        checkReady()
        require(childIds.isNotEmpty()) { "PI_MOBILE_CHILD_ACK_EMPTY" }
        val result = json.decodeFromString<PiChildAgentAcknowledgeResult>(
            evaluateString(
                "PiMobileRuntimeBundle.acknowledgeNativeOpenRouterChildAgentsJson(" +
                    "${jsString(json.encodeToString(childIds))})",
                "pi-mobile-native-child-ack.js",
            ),
        )
        return result.evictedChildIds
    }

    suspend fun nativeOpenRouterTaskSessionSnapshot(): PiNativeTaskSessionSnapshot {
        checkReady()
        return json.decodeFromString(
            evaluateString(
                "PiMobileRuntimeBundle.nativeOpenRouterTaskSessionSnapshotJson()",
                "pi-mobile-native-task-snapshot.js",
            ),
        )
    }

    suspend fun nativeOpenRouterScenarioStatus(): PiNativeOpenRouterScenarioStatus {
        checkReady()
        return decodeNativeOpenRouterStatus(
            evaluateString(
                "PiMobileRuntimeBundle.nativeOpenRouterScenarioStatusJson()",
                "pi-mobile-native-provider-status.js",
            ),
        )
    }

    suspend fun drainNativeProviderRequests(): List<PiNativeProviderRequest> {
        checkReady()
        return json.decodeFromString(
            evaluateString(
                "PiMobileRuntimeBundle.drainNativeProviderRequestsJson()",
                "pi-mobile-native-provider-drain.js",
            ),
        )
    }

    suspend fun drainNativeProviderCancellations(): List<PiNativeProviderCancellation> {
        checkReady()
        return json.decodeFromString(
            evaluateString(
                "PiMobileRuntimeBundle.drainNativeProviderCancellationsJson()",
                "pi-mobile-native-provider-cancel-drain.js",
            ),
        )
    }

    suspend fun drainNativeOpenRouterChildEvents(): List<PiChildAgentEventEnvelope> {
        checkReady()
        return json.decodeFromString(
            evaluateString(
                "PiMobileRuntimeBundle.drainNativeOpenRouterChildEventsJson()",
                "pi-mobile-native-child-events-drain.js",
            ),
        )
    }

    suspend fun peekNativeOpenRouterChildEvents(): List<PiChildAgentEventEnvelope> {
        checkReady()
        return json.decodeFromString(
            evaluateString(
                "PiMobileRuntimeBundle.peekNativeOpenRouterChildEventsJson()",
                "pi-mobile-native-child-events-peek.js",
            ),
        )
    }

    suspend fun acknowledgeNativeOpenRouterChildEvents(
        acknowledgements: List<PiChildAgentEventAck>,
    ): Int {
        checkReady()
        require(acknowledgements.isNotEmpty()) { "PI_MOBILE_CHILD_EVENT_ACK_EMPTY" }
        val result = json.decodeFromString<PiChildAgentEventAcknowledgeResult>(
            evaluateString(
                "PiMobileRuntimeBundle.acknowledgeNativeOpenRouterChildEventsJson(" +
                    "${jsString(json.encodeToString(acknowledgements))})",
                "pi-mobile-native-child-events-ack.js",
            ),
        )
        return result.acknowledgedEventCount
    }

    suspend fun pushNativeProviderChunk(
        requestId: String,
        chunk: JsonObject,
    ): PiNativeOpenRouterScenarioStatus {
        checkReady()
        return decodeNativeOpenRouterStatus(
            evaluateString(
                "PiMobileRuntimeBundle.pushNativeProviderChunkJson(" +
                    "${jsString(requestId)},${jsString(chunk.toString())})",
                "pi-mobile-native-provider-chunk.js",
            ),
        )
    }

    suspend fun completeNativeProviderRequest(
        requestId: String,
        generationId: String?,
    ): PiNativeOpenRouterScenarioStatus {
        checkReady()
        val generationArgument = generationId?.let(::jsString) ?: "undefined"
        return decodeNativeOpenRouterStatus(
            evaluateString(
                "PiMobileRuntimeBundle.completeNativeProviderRequestJson(" +
                    "${jsString(requestId)},$generationArgument)",
                "pi-mobile-native-provider-complete.js",
            ),
        )
    }

    suspend fun failNativeProviderRequest(
        requestId: String,
        safeMessage: String,
    ): PiNativeOpenRouterScenarioStatus {
        checkReady()
        return decodeNativeOpenRouterStatus(
            evaluateString(
                "PiMobileRuntimeBundle.failNativeProviderRequestJson(" +
                    "${jsString(requestId)},${jsString(safeMessage)})",
                "pi-mobile-native-provider-fail.js",
            ),
        )
    }

    suspend fun drainNativeProviderToolRequests(): List<PiNativeToolRequest> {
        checkReady()
        return json.decodeFromString(
            evaluateString(
                "PiMobileRuntimeBundle.drainNativeProviderToolRequestsJson()",
                "pi-mobile-native-provider-tool-drain.js",
            ),
        )
    }

    suspend fun resolveNativeProviderToolRequest(
        requestId: String,
        contentPayload: JsonObject,
        details: JsonObject = contentPayload,
        content: JsonArray? = null,
        isError: Boolean = false,
    ): PiNativeOpenRouterScenarioStatus {
        checkReady()
        return decodeNativeOpenRouterStatus(
            evaluateString(
                "PiMobileRuntimeBundle.resolveNativeProviderToolRequestJson(" +
                    "${jsString(requestId)},${jsString(contentPayload.toString())}," +
                    "${jsString(details.toString())},$isError," +
                    "${content?.let { jsString(it.toString()) } ?: "undefined"})",
                "pi-mobile-native-provider-tool-resolve.js",
            ),
        )
    }

    suspend fun abortNativeOpenRouterScenario(): PiNativeOpenRouterScenarioStatus {
        checkReady()
        return decodeNativeOpenRouterStatus(
            evaluateString(
                "PiMobileRuntimeBundle.abortNativeOpenRouterScenarioJson()",
                "pi-mobile-native-provider-abort.js",
            ),
        )
    }

    suspend fun shutdown() {
        checkOwnerThread()
        if (closed) return
        try {
            if (booted) {
                val resultJson = quickJs.evaluate<String>(
                    "PiMobileRuntimeBundle.closeJson()",
                    "pi-mobile-close.js",
                )
                val result = json.decodeFromString<PiRuntimeCloseResult>(resultJson)
                check(result.ok && result.closed) { "PI_MOBILE_CLOSE_FAILED" }
            }
        } finally {
            closed = true
            booted = false
            quickJs.close()
        }
    }

    override fun close() {
        checkOwnerThread()
        if (closed) return
        closed = true
        booted = false
        quickJs.close()
    }

    private fun secureRandomPrelude(): String {
        val entropy = ByteArray(SECURE_RANDOM_POOL_BYTES)
        secureRandom.nextBytes(entropy)
        val hex = entropy.joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
        entropy.fill(0)
        return """
            (function installAndroidSecureRandom(global) {
              var hex = "$hex";
              var cursor = 0;
              global.crypto = Object.freeze({
                getRandomValues: function getRandomValues(target) {
                  if (!target || typeof target.length !== "number") {
                    throw new TypeError("Expected an integer typed array");
                  }
                  if (target.byteLength > 65536) {
                    throw new Error("QuotaExceededError");
                  }
                  if (cursor + target.length * 2 > hex.length) {
                    throw new Error("PI_MOBILE_SECURE_RANDOM_POOL_EXHAUSTED");
                  }
                  for (var index = 0; index < target.length; index += 1) {
                    target[index] = parseInt(hex.slice(cursor, cursor + 2), 16);
                    cursor += 2;
                  }
                  return target;
                }
              });
            })(globalThis);
        """.trimIndent()
    }

    private fun checkOwnerThread() {
        check(Thread.currentThread() === ownerThread) {
            "PI_MOBILE_ENGINE_WRONG_THREAD"
        }
    }

    private fun checkReady() {
        checkOwnerThread()
        check(!closed) { "PI_MOBILE_ENGINE_CLOSED" }
        check(booted) { "PI_MOBILE_ENGINE_NOT_BOOTED" }
    }

    private suspend fun evaluateString(script: String, fileName: String): String =
        quickJs.evaluate(script, fileName)

    private fun decodeScenarioStatus(value: String): PiFakeScenarioStatus =
        json.decodeFromString(value)

    private fun decodeNativeOpenRouterStatus(
        value: String,
    ): PiNativeOpenRouterScenarioStatus = json.decodeFromString(value)

    private fun decodeSkillParseStatus(value: String): PiMobileSkillParseStatus =
        json.decodeFromString(value)

    private fun jsString(value: String): String = JsonPrimitive(value).toString()
}

internal fun validatePiRuntimeManifest(manifest: PiRuntimeAssetManifest) {
    check(manifest.schemaVersion == EXPECTED_MANIFEST_SCHEMA) {
        "PI_MOBILE_MANIFEST_SCHEMA_MISMATCH"
    }
    check(manifest.piVersion == EXPECTED_PI_VERSION) {
        "PI_MOBILE_MANIFEST_PI_VERSION_MISMATCH"
    }
    check(manifest.runtime == "earendil-works/pi AgentHarness") {
        "PI_MOBILE_MANIFEST_RUNTIME_MISMATCH"
    }
    check(manifest.bundleFile == "pi-mobile.js") {
        "PI_MOBILE_MANIFEST_BUNDLE_MISMATCH"
    }
    check(manifest.buildTarget == "es2020") {
        "PI_MOBILE_MANIFEST_BUILD_TARGET_MISMATCH"
    }
    check(manifest.bundleSha256.matches(Regex("^[0-9a-f]{64}$"))) {
        "PI_MOBILE_MANIFEST_HASH_INVALID"
    }
    check(manifest.sourceSha256.matches(Regex("^[0-9a-f]{64}$"))) {
        "PI_MOBILE_MANIFEST_SOURCE_HASH_INVALID"
    }
    check(manifest.buildRevision.matches(Regex("^[0-9a-f]{40}$"))) {
        "PI_MOBILE_MANIFEST_REVISION_INVALID"
    }
    check(manifest.compatibilityTransforms == EXPECTED_COMPATIBILITY_TRANSFORMS) {
        "PI_MOBILE_MANIFEST_COMPATIBILITY_TRANSFORM_MISMATCH"
    }
}

private fun ByteArray.sha256(): String {
    return MessageDigest.getInstance("SHA-256")
        .digest(this)
        .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
}
