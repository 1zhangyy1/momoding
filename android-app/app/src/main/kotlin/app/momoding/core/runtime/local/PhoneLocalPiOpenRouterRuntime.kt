package app.momoding.core.runtime.local

import android.content.Context
import app.momoding.core.provider.OpenRouterChatRequest
import app.momoding.core.provider.OpenRouterNativeClient
import app.momoding.core.provider.OpenRouterRequestException
import app.momoding.core.provider.ProviderCredential
import app.momoding.core.provider.ProviderCredentialVault
import app.momoding.core.skills.PhoneLocalSkillParser
import app.momoding.core.skills.EnabledSkillResourceSet
import app.momoding.core.skills.SkillAvailability
import app.momoding.core.skills.SkillDocumentParseException
import app.momoding.core.skills.SkillDocumentParseResult
import app.momoding.core.skills.requireValidSkillResource
import app.momoding.core.skills.skillResourceSetDigest
import java.security.MessageDigest
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Concrete phone-local Pi runtime for OpenRouter prompts and one active phone-local task.
 *
 * Pi owns the Agent loop inside QuickJS. Android owns credentials and HTTP, then returns raw
 * OpenRouter chunks through Pi's native Provider mailbox. No credential or Authorization header
 * crosses into JavaScript.
 */
class PhoneLocalPiOpenRouterRuntime internal constructor(
    context: Context,
    private val credentialVault: ProviderCredentialVault,
    private val client: OpenRouterNativeClient,
    private val ownerDispatcher: ExecutorCoroutineDispatcher,
    private val networkScope: CoroutineScope,
    private val attentionBridge: PhoneLocalAttentionBridge? = null,
    private val childUpdateSink: (
        List<PiChildAgentEventEnvelope>,
        List<PiChildAgentSnapshot>,
    ) -> Unit = ::missingChildPersistenceSink,
    private val skillParsePollMillis: Long = SKILL_PARSE_POLL_MILLIS,
) : AutoCloseable, PhoneLocalSkillParser {
    constructor(
        context: Context,
        attentionBridge: PhoneLocalAttentionBridge? = null,
        childUpdateSink: (
            List<PiChildAgentEventEnvelope>,
            List<PiChildAgentSnapshot>,
        ) -> Unit = ::missingChildPersistenceSink,
    ) : this(
        context = context,
        credentialVault = ProviderCredentialVault.create(context),
        client = OpenRouterNativeClient(),
        ownerDispatcher = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "phone-local-pi-openrouter")
        }.asCoroutineDispatcher(),
        networkScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        attentionBridge = attentionBridge,
        childUpdateSink = childUpdateSink,
    )

    private val appContext = context.applicationContext
    private var engine: PhoneLocalPiEngine? = null
    private var requestPump: OpenRouterRequestPump? = null
    private var activeTaskCredential: ProviderCredential? = null
    private val taskCommands = ConcurrentLinkedQueue<PendingTaskCommand>()
    private val commandLock = Any()
    @Volatile
    private var activeTaskId: String? = null
    @Volatile
    private var running = false
    @Volatile
    private var lastStatus: PiNativeOpenRouterScenarioStatus? = null
    @Volatile
    private var stopQueued = false
    @Volatile
    private var acceptingCommands = false
    @Volatile
    private var skillParsing = false
    @Volatile
    private var shuttingDown = false
    @Volatile
    private var multimodalAgentProfileId: String? = null
    private var multimodalAgentModelId: String? = null
    private var toolAgentProfileId: String? = null
    private var toolAgentModelId: String? = null
    private var closed = false

    suspend fun requireImageInputCapability(): String = withContext(ownerDispatcher) {
        checkOpen()
        val credential = if (activeTaskId != null) {
            requireNotNull(activeTaskCredential) { "PI_MOBILE_TASK_SESSION_CREDENTIAL_MISSING" }
        } else {
            requireNotNull(credentialVault.load()) { "PI_MOBILE_PROVIDER_CREDENTIAL_MISSING" }
        }
        requireImageInputCapability(credential)
    }

    suspend fun requireToolCapability(): String = withContext(ownerDispatcher) {
        checkOpen()
        val credential = if (activeTaskId != null) {
            requireNotNull(activeTaskCredential) { "PI_MOBILE_TASK_SESSION_CREDENTIAL_MISSING" }
        } else {
            requireNotNull(credentialVault.load()) { "PI_MOBILE_PROVIDER_CREDENTIAL_MISSING" }
        }
        requireToolCapability(credential)
    }

    private suspend fun requireToolCapability(credential: ProviderCredential): String {
        val modelId = credential.profile.modelId
        if (toolAgentProfileId == credential.profile.id && toolAgentModelId == modelId) return modelId
        val model = client.listModels(credential.apiKey).singleOrNull { it.id == modelId }
            ?: error("PI_MOBILE_TOOL_MODEL_CAPABILITY_UNKNOWN")
        check(model.supportedParameters.any { it.equals("tools", ignoreCase = true) }) {
            "PI_MOBILE_TOOL_MODEL_UNSUPPORTED"
        }
        toolAgentProfileId = credential.profile.id
        toolAgentModelId = modelId
        return modelId
    }

    private suspend fun requireImageInputCapability(credential: ProviderCredential): String {
        val modelId = credential.profile.modelId
        if (
            multimodalAgentProfileId == credential.profile.id &&
            multimodalAgentModelId == modelId
        ) return modelId
        val model = client.listModels(credential.apiKey).singleOrNull { it.id == modelId }
            ?: error("PI_MOBILE_IMAGE_MODEL_CAPABILITY_UNKNOWN")
        check(model.inputModalities.any { it.equals("image", ignoreCase = true) }) {
            "PI_MOBILE_IMAGE_MODEL_UNSUPPORTED"
        }
        check(model.supportedParameters.any { it.equals("tools", ignoreCase = true) }) {
            "PI_MOBILE_IMAGE_MODEL_TOOLS_UNSUPPORTED"
        }
        multimodalAgentProfileId = credential.profile.id
        multimodalAgentModelId = modelId
        return modelId
    }

    override suspend fun parseSkillDocument(content: String): SkillDocumentParseResult =
        withContext(ownerDispatcher) {
            checkOpen()
            check(!running) { "PI_MOBILE_SKILL_PARSE_BUSY" }
            check(!skillParsing) { "PI_MOBILE_SKILL_PARSE_BUSY" }
            skillParsing = true
            val existingEngine = engine
            val parserEngine = existingEngine ?: PhoneLocalPiEngine(appContext.assets, ownerDispatcher)
            var parseId: Int? = null
            try {
                if (existingEngine == null) parserEngine.bootstrap()
                var status = parserEngine.beginSkillDocumentParse(content)
                parseId = status.parseId
                var polls = 0
                while (status.phase == "parsing") {
                    check(polls++ < MAX_SKILL_PARSE_POLLS) { "PI_MOBILE_SKILL_PARSE_TIMEOUT" }
                    delay(skillParsePollMillis)
                    status = parserEngine.skillDocumentParseStatus(status.parseId)
                }
                status.toSkillDocumentParseResult()
            } finally {
                try {
                    withContext(NonCancellable) {
                        try {
                            parseId?.let { parserEngine.clearSkillDocumentParse(it) }
                        } finally {
                            if (existingEngine == null) parserEngine.shutdown()
                        }
                    }
                } finally {
                    skillParsing = false
                }
            }
        }

    suspend fun runPrompt(
        prompt: String,
        onStatus: (PiNativeOpenRouterScenarioStatus) -> Unit = {},
    ): PiNativeOpenRouterScenarioStatus = withContext(ownerDispatcher) {
        checkOpen()
        check(!skillParsing) { "PI_MOBILE_SKILL_PARSE_IN_PROGRESS" }
        check(!running) { "PI_MOBILE_OPENROUTER_PROMPT_ALREADY_RUNNING" }
        check(engine == null && activeTaskId == null) {
            "PI_MOBILE_TASK_SESSION_ALREADY_OPEN"
        }
        val credential = requireNotNull(credentialVault.load()) {
            "PI_MOBILE_PROVIDER_CREDENTIAL_MISSING"
        }
        val activeEngine = PhoneLocalPiEngine(appContext.assets, ownerDispatcher)
        val activePump = OpenRouterRequestPump(
            engine = activeEngine,
            credential = credential,
            client = client,
            networkScope = networkScope,
            attentionBridge = null,
            taskId = null,
            childUpdateSink = childUpdateSink,
        )
        engine = activeEngine
        requestPump = activePump
        running = true
        lastStatus = null
        openCommandMailbox()
        try {
            activeEngine.bootstrap()
            var status = activeEngine.startNativeOpenRouterPrompt(
                prompt = prompt,
                modelId = credential.profile.modelId,
            )
            status = pumpUntilTerminal(status, activePump, onStatus)
            status
        } finally {
            activePump.close()
            activeEngine.shutdown()
            requestPump = null
            engine = null
            running = false
            closeCommandMailbox("PI_MOBILE_OPENROUTER_PROMPT_CLOSED")
        }
    }

    suspend fun startTaskSession(
        taskId: String,
        prompt: String,
        sessionId: String = taskId,
        planMode: Boolean = false,
        skillResources: EnabledSkillResourceSet = emptySkillResourceSet(),
        images: List<PiRuntimeImageInput> = emptyList(),
        textAttachments: List<PiRuntimeTextAttachmentInput> = emptyList(),
        onStatus: (PiNativeOpenRouterScenarioStatus) -> Unit = {},
    ): PiNativeOpenRouterScenarioStatus = withContext(ownerDispatcher) {
        checkOpen()
        check(!skillParsing) { "PI_MOBILE_SKILL_PARSE_IN_PROGRESS" }
        check(!running) { "PI_MOBILE_OPENROUTER_PROMPT_ALREADY_RUNNING" }
        check(engine == null && activeTaskId == null) {
            "PI_MOBILE_TASK_SESSION_ALREADY_OPEN"
        }
        requireValidSkillResourceSet(skillResources)
        val credential = requireNotNull(credentialVault.load()) {
            "PI_MOBILE_PROVIDER_CREDENTIAL_MISSING"
        }
        if (images.isNotEmpty()) requireImageInputCapability(credential)
        if (textAttachments.isNotEmpty()) requireToolCapability(credential)
        val activeEngine = PhoneLocalPiEngine(appContext.assets, ownerDispatcher)
        val activePump = OpenRouterRequestPump(
            engine = activeEngine,
            credential = credential,
            client = client,
            networkScope = networkScope,
            attentionBridge = attentionBridge,
            taskId = taskId,
            childUpdateSink = childUpdateSink,
        )
        engine = activeEngine
        requestPump = activePump
        activeTaskCredential = credential
        activeTaskId = taskId
        running = true
        lastStatus = null
        openCommandMailbox()
        try {
            activeEngine.bootstrap()
            val initial = activeEngine.startNativeOpenRouterTaskSession(
                taskId = taskId,
                sessionId = sessionId,
                prompt = prompt,
                modelId = credential.profile.modelId,
                planMode = planMode,
                skillResources = skillResources.resources,
                images = images,
                textAttachments = textAttachments,
            )
            pumpUntilTerminal(initial, activePump, onStatus).also { terminal ->
                requireTrustedSkillResourceSet(terminal, skillResources)
            }
        } catch (error: Throwable) {
            closeActiveEngine()
            throw error
        } finally {
            running = false
        }
    }

    suspend fun startTaskSkillSession(
        taskId: String,
        skillName: String,
        additionalInstructions: String?,
        sessionId: String = taskId,
        skillResources: EnabledSkillResourceSet,
        onStatus: (PiNativeOpenRouterScenarioStatus) -> Unit = {},
    ): PiNativeOpenRouterScenarioStatus = withContext(ownerDispatcher) {
        checkOpen()
        check(!skillParsing) { "PI_MOBILE_SKILL_PARSE_IN_PROGRESS" }
        check(!running) { "PI_MOBILE_OPENROUTER_PROMPT_ALREADY_RUNNING" }
        check(engine == null && activeTaskId == null) {
            "PI_MOBILE_TASK_SESSION_ALREADY_OPEN"
        }
        requireValidSkillResourceSet(skillResources)
        check(skillResources.resources.any { it.name == skillName }) {
            "PI_MOBILE_SKILL_NOT_ENABLED"
        }
        val credential = requireNotNull(credentialVault.load()) {
            "PI_MOBILE_PROVIDER_CREDENTIAL_MISSING"
        }
        val activeEngine = PhoneLocalPiEngine(appContext.assets, ownerDispatcher)
        val activePump = OpenRouterRequestPump(
            engine = activeEngine,
            credential = credential,
            client = client,
            networkScope = networkScope,
            attentionBridge = attentionBridge,
            taskId = taskId,
            childUpdateSink = childUpdateSink,
        )
        engine = activeEngine
        requestPump = activePump
        activeTaskCredential = credential
        activeTaskId = taskId
        running = true
        lastStatus = null
        openCommandMailbox()
        try {
            activeEngine.bootstrap()
            val initial = activeEngine.startNativeOpenRouterTaskSkillSession(
                taskId = taskId,
                sessionId = sessionId,
                skillName = skillName,
                additionalInstructions = additionalInstructions,
                modelId = credential.profile.modelId,
                skillResources = skillResources.resources,
            )
            pumpUntilTerminal(initial, activePump, onStatus).also { terminal ->
                requireTrustedSkillResourceSet(terminal, skillResources)
            }
        } catch (error: Throwable) {
            closeActiveEngine()
            throw error
        } finally {
            running = false
        }
    }

    suspend fun setTaskPlanMode(
        taskId: String,
        enabled: Boolean,
        onStatus: (PiNativeOpenRouterScenarioStatus) -> Unit = {},
    ): PiNativeOpenRouterScenarioStatus = withContext(ownerDispatcher) {
        checkOpen()
        check(!skillParsing) { "PI_MOBILE_SKILL_PARSE_IN_PROGRESS" }
        check(!running) { "PI_MOBILE_OPENROUTER_PROMPT_ALREADY_RUNNING" }
        check(activeTaskId == taskId) { "PI_MOBILE_TASK_SESSION_NOT_OPEN" }
        val activeEngine = requireNotNull(engine) { "PI_MOBILE_TASK_SESSION_ENGINE_MISSING" }
        val activePump = requireNotNull(requestPump) { "PI_MOBILE_TASK_SESSION_PUMP_MISSING" }
        running = true
        lastStatus = null
        openCommandMailbox()
        try {
            val initial = activeEngine.setNativeOpenRouterTaskPlanMode(enabled)
            val terminal = pumpUntilTerminal(initial, activePump, onStatus)
            check(terminal.commandError == null && terminal.planMode == enabled) {
                terminal.commandError ?: "PI_MOBILE_PLAN_MODE_TRANSITION_FAILED"
            }
            terminal
        } catch (error: Throwable) {
            closeActiveEngine()
            throw error
        } finally {
            running = false
        }
    }

    suspend fun implementTaskPlan(
        taskId: String,
        planDigest: String,
        onStatus: (PiNativeOpenRouterScenarioStatus) -> Unit = {},
    ): PiNativeOpenRouterScenarioStatus = withContext(ownerDispatcher) {
        checkOpen()
        check(!skillParsing) { "PI_MOBILE_SKILL_PARSE_IN_PROGRESS" }
        check(!running) { "PI_MOBILE_OPENROUTER_PROMPT_ALREADY_RUNNING" }
        check(activeTaskId == taskId) { "PI_MOBILE_TASK_SESSION_NOT_OPEN" }
        val activeEngine = requireNotNull(engine) { "PI_MOBILE_TASK_SESSION_ENGINE_MISSING" }
        val activePump = requireNotNull(requestPump) { "PI_MOBILE_TASK_SESSION_PUMP_MISSING" }
        running = true
        lastStatus = null
        openCommandMailbox()
        try {
            val initial = activeEngine.implementNativeOpenRouterTaskPlan(planDigest)
            pumpUntilTerminal(initial, activePump, onStatus)
        } catch (error: Throwable) {
            if (
                error.message?.contains("PI_MOBILE_PLAN_DIGEST_STALE") == true ||
                error.message?.contains("PI_MOBILE_PLAN_NOT_READY") == true
            ) {
                closeCommandMailbox("PI_MOBILE_PLAN_IMPLEMENT_REJECTED")
            } else {
                closeActiveEngine()
            }
            throw error
        } finally {
            running = false
        }
    }

    suspend fun startTaskGoal(
        taskId: String,
        goalId: String,
        instruction: String,
        generation: Int,
        startedAtMillis: Long,
        onStatus: (PiNativeOpenRouterScenarioStatus) -> Unit = {},
    ): PiNativeOpenRouterScenarioStatus = runGoalCommand(taskId, onStatus) { engine ->
        engine.startNativeOpenRouterTaskGoal(
            goalId = goalId,
            instruction = instruction,
            generation = generation,
            startedAtMillis = startedAtMillis,
        )
    }

    suspend fun continueTaskGoal(
        taskId: String,
        goalId: String,
        generation: Int,
        turnIndex: Int,
        resume: Boolean,
        onStatus: (PiNativeOpenRouterScenarioStatus) -> Unit = {},
    ): PiNativeOpenRouterScenarioStatus = runGoalCommand(taskId, onStatus) { engine ->
        engine.continueNativeOpenRouterTaskGoal(
            goalId = goalId,
            generation = generation,
            turnIndex = turnIndex,
            resume = resume,
        )
    }

    suspend fun setTaskGoalState(
        taskId: String,
        goalId: String,
        generation: Int,
        targetState: String,
        onStatus: (PiNativeOpenRouterScenarioStatus) -> Unit = {},
    ): PiNativeOpenRouterScenarioStatus = withContext(ownerDispatcher) {
        checkOpen()
        check(!skillParsing) { "PI_MOBILE_SKILL_PARSE_IN_PROGRESS" }
        check(!running) { "PI_MOBILE_OPENROUTER_PROMPT_ALREADY_RUNNING" }
        check(activeTaskId == taskId) { "PI_MOBILE_TASK_SESSION_NOT_OPEN" }
        val activeEngine = requireNotNull(engine) { "PI_MOBILE_TASK_SESSION_ENGINE_MISSING" }
        val activePump = requireNotNull(requestPump) { "PI_MOBILE_TASK_SESSION_PUMP_MISSING" }
        running = true
        lastStatus = null
        openCommandMailbox()
        try {
            val initial = activeEngine.setNativeOpenRouterTaskGoalState(
                goalId = goalId,
                generation = generation,
                targetState = targetState,
            )
            pumpUntilTerminal(initial, activePump, onStatus)
        } catch (error: Throwable) {
            if (isGoalControlRejection(error)) {
                closeCommandMailbox("PI_MOBILE_GOAL_STATE_REJECTED")
            } else {
                closeActiveEngine()
            }
            throw error
        } finally {
            running = false
        }
    }

    private suspend fun runGoalCommand(
        taskId: String,
        onStatus: (PiNativeOpenRouterScenarioStatus) -> Unit,
        start: suspend (PhoneLocalPiEngine) -> PiNativeOpenRouterScenarioStatus,
    ): PiNativeOpenRouterScenarioStatus = withContext(ownerDispatcher) {
        checkOpen()
        check(!skillParsing) { "PI_MOBILE_SKILL_PARSE_IN_PROGRESS" }
        check(!running) { "PI_MOBILE_OPENROUTER_PROMPT_ALREADY_RUNNING" }
        check(activeTaskId == taskId) { "PI_MOBILE_TASK_SESSION_NOT_OPEN" }
        val activeEngine = requireNotNull(engine) { "PI_MOBILE_TASK_SESSION_ENGINE_MISSING" }
        val activePump = requireNotNull(requestPump) { "PI_MOBILE_TASK_SESSION_PUMP_MISSING" }
        running = true
        lastStatus = null
        openCommandMailbox()
        try {
            pumpUntilTerminal(start(activeEngine), activePump, onStatus)
        } catch (error: Throwable) {
            if (isGoalControlRejection(error)) {
                closeCommandMailbox("PI_MOBILE_GOAL_COMMAND_REJECTED")
            } else {
                closeActiveEngine()
            }
            throw error
        } finally {
            running = false
        }
    }

    suspend fun continueTaskPrompt(
        taskId: String,
        prompt: String,
        images: List<PiRuntimeImageInput> = emptyList(),
        textAttachments: List<PiRuntimeTextAttachmentInput> = emptyList(),
        onStatus: (PiNativeOpenRouterScenarioStatus) -> Unit = {},
    ): PiNativeOpenRouterScenarioStatus = withContext(ownerDispatcher) {
        checkOpen()
        check(!skillParsing) { "PI_MOBILE_SKILL_PARSE_IN_PROGRESS" }
        check(!running) { "PI_MOBILE_OPENROUTER_PROMPT_ALREADY_RUNNING" }
        check(activeTaskId == taskId) { "PI_MOBILE_TASK_SESSION_NOT_OPEN" }
        val activeEngine = requireNotNull(engine) { "PI_MOBILE_TASK_SESSION_ENGINE_MISSING" }
        val activePump = requireNotNull(requestPump) { "PI_MOBILE_TASK_SESSION_PUMP_MISSING" }
        if (textAttachments.isNotEmpty()) {
            requireToolCapability(requireNotNull(activeTaskCredential))
        }
        running = true
        lastStatus = null
        openCommandMailbox()
        try {
            val initial = activeEngine.continueNativeOpenRouterTaskPrompt(prompt, images, textAttachments)
            pumpUntilTerminal(initial, activePump, onStatus)
        } catch (error: Throwable) {
            closeActiveEngine()
            throw error
        } finally {
            running = false
        }
    }

    suspend fun syncTaskSkillResources(
        taskId: String,
        skillResources: EnabledSkillResourceSet,
    ): PiNativeOpenRouterScenarioStatus = withContext(ownerDispatcher) {
        checkOpen()
        check(!skillParsing) { "PI_MOBILE_SKILL_PARSE_IN_PROGRESS" }
        check(!running) { "PI_MOBILE_OPENROUTER_PROMPT_ALREADY_RUNNING" }
        check(activeTaskId == taskId) { "PI_MOBILE_TASK_SESSION_NOT_OPEN" }
        requireValidSkillResourceSet(skillResources)
        val activeEngine = requireNotNull(engine) { "PI_MOBILE_TASK_SESSION_ENGINE_MISSING" }
        val activePump = requireNotNull(requestPump) { "PI_MOBILE_TASK_SESSION_PUMP_MISSING" }
        running = true
        lastStatus = null
        try {
            val before = activeEngine.nativeOpenRouterScenarioStatus()
            check(before.terminal && before.resourceSetTrusted) {
                "PI_MOBILE_SKILL_RESOURCES_UNTRUSTED"
            }
            val changed = before.resourceSetDigest != skillResources.digest
            val initial = activeEngine.setNativeOpenRouterTaskResources(skillResources.resources)
            val terminal = pumpUntilTerminal(initial, activePump) {}
            requireTrustedSkillResourceSet(terminal, skillResources)
            check(terminal.providerRequestsIssued == before.providerRequestsIssued) {
                "PI_MOBILE_SKILL_RESOURCE_SYNC_PROVIDER_WORK"
            }
            check(terminal.toolRequestsIssued == before.toolRequestsIssued) {
                "PI_MOBILE_SKILL_RESOURCE_SYNC_TOOL_WORK"
            }
            check(
                terminal.resourceUpdateCount == before.resourceUpdateCount + if (changed) 1 else 0
            ) { "PI_MOBILE_SKILL_RESOURCE_SYNC_EVENT_COUNT" }
            terminal
        } catch (error: Throwable) {
            closeActiveEngine()
            throw error
        } finally {
            running = false
        }
    }

    suspend fun continueTaskSkill(
        taskId: String,
        skillName: String,
        additionalInstructions: String?,
        onStatus: (PiNativeOpenRouterScenarioStatus) -> Unit = {},
    ): PiNativeOpenRouterScenarioStatus = withContext(ownerDispatcher) {
        checkOpen()
        check(!skillParsing) { "PI_MOBILE_SKILL_PARSE_IN_PROGRESS" }
        check(!running) { "PI_MOBILE_OPENROUTER_PROMPT_ALREADY_RUNNING" }
        check(activeTaskId == taskId) { "PI_MOBILE_TASK_SESSION_NOT_OPEN" }
        val activeEngine = requireNotNull(engine) { "PI_MOBILE_TASK_SESSION_ENGINE_MISSING" }
        val activePump = requireNotNull(requestPump) { "PI_MOBILE_TASK_SESSION_PUMP_MISSING" }
        running = true
        lastStatus = null
        openCommandMailbox()
        try {
            val initial = activeEngine.invokeNativeOpenRouterTaskSkill(
                skillName = skillName,
                additionalInstructions = additionalInstructions,
            )
            pumpUntilTerminal(initial, activePump, onStatus)
        } catch (error: Throwable) {
            closeActiveEngine()
            throw error
        } finally {
            running = false
        }
    }

    suspend fun restoreTaskSession(
        taskId: String,
        sessionId: String,
        snapshot: PiNativeTaskSessionSnapshot,
        skillResources: EnabledSkillResourceSet = emptySkillResourceSet(),
        images: List<PiRuntimeImageInput> = emptyList(),
        requiresTools: Boolean = false,
    ): PiNativeOpenRouterScenarioStatus = withContext(ownerDispatcher) {
        checkOpen()
        check(!skillParsing) { "PI_MOBILE_SKILL_PARSE_IN_PROGRESS" }
        check(!running) { "PI_MOBILE_OPENROUTER_PROMPT_ALREADY_RUNNING" }
        check(engine == null && activeTaskId == null) {
            "PI_MOBILE_TASK_SESSION_ALREADY_OPEN"
        }
        check(snapshot.taskId == taskId) { "PI_MOBILE_SESSION_SNAPSHOT_TASK_MISMATCH" }
        requireValidSkillResourceSet(skillResources)
        val credential = requireNotNull(credentialVault.load()) {
            "PI_MOBILE_PROVIDER_CREDENTIAL_MISSING"
        }
        if (images.isNotEmpty()) requireImageInputCapability(credential)
        if (requiresTools) requireToolCapability(credential)
        val activeEngine = PhoneLocalPiEngine(appContext.assets, ownerDispatcher)
        val activePump = OpenRouterRequestPump(
            engine = activeEngine,
            credential = credential,
            client = client,
            networkScope = networkScope,
            attentionBridge = attentionBridge,
            taskId = taskId,
            childUpdateSink = childUpdateSink,
        )
        engine = activeEngine
        requestPump = activePump
        activeTaskCredential = credential
        activeTaskId = taskId
        lastStatus = null
        closeCommandMailbox("PI_MOBILE_TASK_SESSION_RESTORING")
        try {
            activeEngine.bootstrap()
            val restored = activeEngine.restoreNativeOpenRouterTaskSession(
                taskId = taskId,
                sessionId = sessionId,
                turnCount = snapshot.turnCount,
                entries = snapshot.entries,
                modelId = credential.profile.modelId,
                skillResources = skillResources.resources,
                images = images,
            )
            check(
                restored.terminal &&
                    restored.providerRequestsIssued == 0 &&
                    restored.pendingProviderCount == 0 &&
                    restored.queuedProviderRequestCount == 0 &&
                    restored.toolRequestsIssued == 0 &&
                    restored.pendingToolCount == 0 &&
                    restored.queuedToolRequestCount == 0
            ) {
                "PI_MOBILE_TASK_SESSION_RESTORE_REPLAYED_WORK"
            }
            requireTrustedSkillResourceSet(restored, skillResources)
            lastStatus = restored
            restored
        } catch (error: Throwable) {
            closeActiveEngine()
            throw error
        }
    }

    suspend fun steerTask(
        taskId: String,
        text: String,
        images: List<PiRuntimeImageInput> = emptyList(),
        textAttachments: List<PiRuntimeTextAttachmentInput> = emptyList(),
    ): PiNativeOpenRouterScenarioStatus {
        if (textAttachments.isNotEmpty()) requireToolCapability()
        val command = PendingTaskCommand.Steer(taskId, text, images, textAttachments)
        enqueueTaskCommand(command)
        return command.completion.await()
    }

    suspend fun followUpTask(
        taskId: String,
        text: String,
        images: List<PiRuntimeImageInput> = emptyList(),
        textAttachments: List<PiRuntimeTextAttachmentInput> = emptyList(),
    ): PiNativeOpenRouterScenarioStatus {
        if (textAttachments.isNotEmpty()) requireToolCapability()
        val command = PendingTaskCommand.FollowUp(taskId, text, images, textAttachments)
        enqueueTaskCommand(command)
        return command.completion.await()
    }

    suspend fun cancelChildAgent(taskId: String, childId: String): Boolean {
        val command = PendingTaskCommand.CancelChild(taskId, childId)
        enqueueTaskCommand(command)
        return command.completion.await()
    }

    suspend fun taskSessionSnapshot(taskId: String): PiNativeTaskSessionSnapshot =
        withContext(ownerDispatcher) {
            checkOpen()
            check(!skillParsing) { "PI_MOBILE_SKILL_PARSE_IN_PROGRESS" }
            check(!running && activeTaskId == taskId) { "PI_MOBILE_TASK_SESSION_NOT_SETTLED" }
            requireNotNull(engine).nativeOpenRouterTaskSessionSnapshot()
        }

    suspend fun closeTaskSession(taskId: String): Boolean = withContext(ownerDispatcher) {
        checkOpen()
        check(!skillParsing) { "PI_MOBILE_SKILL_PARSE_IN_PROGRESS" }
        check(!running) { "PI_MOBILE_TASK_SESSION_RUNNING_USE_STOP" }
        if (activeTaskId != taskId) return@withContext false
        closeActiveEngine()
        true
    }

    fun stop(): PiNativeOpenRouterScenarioStatus? {
        synchronized(commandLock) {
            if (!running || !acceptingCommands) return null
            if (!stopQueued) {
                stopQueued = true
                taskCommands += PendingTaskCommand.Stop
            }
            return lastStatus
        }
    }

    suspend fun shutdown() = withContext(NonCancellable) {
        val shouldWaitForPrompt = withContext(ownerDispatcher) {
            if (closed) return@withContext false
            check(!shuttingDown) { "PI_MOBILE_OPENROUTER_RUNTIME_SHUTTING_DOWN" }
            shuttingDown = true
            if (running) {
                requestPump?.cancelAndroidTools("runtime_shutdown")
                engine?.abortNativeOpenRouterScenario()
                true
            } else {
                false
            }
        }
        if (shouldWaitForPrompt) {
            while (withContext(ownerDispatcher) { running }) {
                delay(PUMP_INTERVAL_MILLIS)
            }
        }
        while (withContext(ownerDispatcher) { skillParsing }) {
            delay(skillParsePollMillis)
        }
        val shouldClose = withContext(ownerDispatcher) {
            requestPump?.close()
            engine?.shutdown()
            requestPump = null
            engine = null
            activeTaskCredential = null
            activeTaskId = null
            running = false
            lastStatus = null
            closeCommandMailbox("PI_MOBILE_OPENROUTER_RUNTIME_CLOSED")
            closed = true
            shuttingDown = false
            networkScope.coroutineContext[Job]?.cancelAndJoin()
            true
        }
        if (shouldClose) ownerDispatcher.close()
    }

    override fun close() {
        check(!running) {
            "PI_MOBILE_OPENROUTER_RUNTIME_RUNNING_USE_SHUTDOWN"
        }
        check(!skillParsing) {
            "PI_MOBILE_SKILL_PARSE_IN_PROGRESS"
        }
        check(!shuttingDown) {
            "PI_MOBILE_OPENROUTER_RUNTIME_SHUTTING_DOWN"
        }
        check(engine == null) {
            "PI_MOBILE_TASK_SESSION_OPEN_USE_SHUTDOWN"
        }
        if (closed) return
        closed = true
        networkScope.coroutineContext[Job]?.cancel()
        ownerDispatcher.close()
    }

    private suspend fun pumpUntilTerminal(
        initial: PiNativeOpenRouterScenarioStatus,
        activePump: OpenRouterRequestPump,
        onStatus: (PiNativeOpenRouterScenarioStatus) -> Unit,
    ): PiNativeOpenRouterScenarioStatus {
        var status = initial
        lastStatus = status
        publishStatus(status, onStatus)
        while (!status.terminal || status.queuedChildEventCount > 0) {
            drainTaskCommands()
            val nextStatus = activePump.pump()
            if (nextStatus != status) publishStatus(nextStatus, onStatus)
            status = nextStatus
            lastStatus = status
            if (!status.terminal || status.queuedChildEventCount > 0) delay(PUMP_INTERVAL_MILLIS)
        }
        releasePersistedTerminalChildren(status)
        closeCommandMailbox("PI_MOBILE_TASK_RUN_SETTLED")
        activePump.cancelAndroidTools("tool_abort")
        return status
    }

    private fun publishStatus(
        status: PiNativeOpenRouterScenarioStatus,
        onStatus: (PiNativeOpenRouterScenarioStatus) -> Unit,
    ) {
        onStatus(status)
    }

    private suspend fun releasePersistedTerminalChildren(status: PiNativeOpenRouterScenarioStatus) {
        val terminalIds = status.childAgents
            .filter { child -> child.state != "running" }
            .map(PiChildAgentSnapshot::childId)
            .distinct()
        if (terminalIds.isNotEmpty()) {
            requireNotNull(engine).acknowledgeNativeOpenRouterChildAgents(terminalIds)
        }
    }

    private fun enqueueTaskCommand(command: PendingTaskCommand) {
        synchronized(commandLock) {
            check(running && acceptingCommands && activeTaskId == command.taskId) {
                "PI_MOBILE_TASK_SESSION_NOT_RUNNING"
            }
            taskCommands += command
        }
    }

    private fun checkOpen() {
        check(!closed && !shuttingDown) { "PI_MOBILE_OPENROUTER_RUNTIME_CLOSED" }
    }

    private suspend fun drainTaskCommands() {
        while (true) {
            val command = taskCommands.poll() ?: return
            try {
                when (command) {
                    is PendingTaskCommand.Steer -> {
                        check(command.taskId == activeTaskId) {
                            "PI_MOBILE_TASK_SESSION_CHANGED"
                        }
                        val applied = requireNotNull(engine)
                            .steerNativeOpenRouterTask(
                                command.text,
                                command.images,
                                command.textAttachments,
                            )
                        lastStatus = applied
                        command.completion.complete(applied)
                    }
                    is PendingTaskCommand.FollowUp -> {
                        check(command.taskId == activeTaskId) {
                            "PI_MOBILE_TASK_SESSION_CHANGED"
                        }
                        val applied = requireNotNull(engine)
                            .followUpNativeOpenRouterTask(
                                command.text,
                                command.images,
                                command.textAttachments,
                            )
                        lastStatus = applied
                        command.completion.complete(applied)
                    }
                    is PendingTaskCommand.CancelChild -> {
                        check(command.taskId == activeTaskId) {
                            "PI_MOBILE_TASK_SESSION_CHANGED"
                        }
                        val result = requireNotNull(engine)
                            .cancelNativeOpenRouterChildAgent(command.childId)
                        lastStatus = result.status
                        command.completion.complete(result.accepted)
                    }
                    PendingTaskCommand.Stop -> {
                        requireNotNull(requestPump).cancelAndroidTools("session_stop")
                        requireNotNull(engine).abortNativeOpenRouterScenario()
                    }
                }
            } catch (error: Throwable) {
                command.reject(error)
                throw error
            }
        }
    }

    private fun openCommandMailbox() {
        synchronized(commandLock) {
            check(taskCommands.isEmpty()) { "PI_MOBILE_TASK_COMMAND_MAILBOX_NOT_EMPTY" }
            stopQueued = false
            acceptingCommands = true
        }
    }

    private fun closeCommandMailbox(reason: String) {
        val rejected = mutableListOf<PendingTaskCommand>()
        synchronized(commandLock) {
            acceptingCommands = false
            stopQueued = false
            while (true) {
                rejected += taskCommands.poll() ?: break
            }
        }
        val error = IllegalStateException(reason)
        rejected.forEach { it.reject(error) }
    }

    private suspend fun closeActiveEngine() {
        try {
            requestPump?.close()
            engine?.shutdown()
        } finally {
            requestPump = null
            engine = null
            activeTaskCredential = null
            activeTaskId = null
            lastStatus = null
            closeCommandMailbox("PI_MOBILE_TASK_SESSION_CLOSED")
        }
    }

    private fun isGoalControlRejection(error: Throwable): Boolean =
        error.message?.let { message ->
            message.contains("PI_MOBILE_GOAL_NOT_") ||
                message.contains("PI_MOBILE_GOAL_GENERATION_STALE") ||
                message.contains("PI_MOBILE_GOAL_ALREADY_ACTIVE") ||
                message.contains("PI_MOBILE_GOAL_PLAN_MODE_CONFLICT") ||
                message.contains("PI_MOBILE_GOAL_STATE_INVALID")
        } == true

    private fun requireValidSkillResourceSet(resourceSet: EnabledSkillResourceSet) {
        check(skillResourceSetDigest(resourceSet.resources) == resourceSet.digest) {
            "PI_MOBILE_SKILL_RESOURCE_SET_DIGEST_MISMATCH"
        }
    }

    private fun requireTrustedSkillResourceSet(
        status: PiNativeOpenRouterScenarioStatus,
        resourceSet: EnabledSkillResourceSet,
    ) {
        check(status.resourceSetTrusted) { "PI_MOBILE_SKILL_RESOURCES_UNTRUSTED" }
        check(status.resourceSetDigest == resourceSet.digest) {
            "PI_MOBILE_SKILL_RESOURCE_SET_DIGEST_MISMATCH"
        }
        check(status.skillNames == resourceSet.resources.map { it.name }) {
            "PI_MOBILE_SKILL_RESOURCE_SET_NAMES_MISMATCH"
        }
    }

    private sealed interface PendingTaskCommand {
        val taskId: String?

        data class Steer(
            override val taskId: String,
            val text: String,
            val images: List<PiRuntimeImageInput> = emptyList(),
            val textAttachments: List<PiRuntimeTextAttachmentInput> = emptyList(),
            val completion: CompletableDeferred<PiNativeOpenRouterScenarioStatus> =
                CompletableDeferred(),
        ) : PendingTaskCommand

        data class FollowUp(
            override val taskId: String,
            val text: String,
            val images: List<PiRuntimeImageInput> = emptyList(),
            val textAttachments: List<PiRuntimeTextAttachmentInput> = emptyList(),
            val completion: CompletableDeferred<PiNativeOpenRouterScenarioStatus> =
                CompletableDeferred(),
        ) : PendingTaskCommand

        data class CancelChild(
            override val taskId: String,
            val childId: String,
            val completion: CompletableDeferred<Boolean> = CompletableDeferred(),
        ) : PendingTaskCommand

        data object Stop : PendingTaskCommand {
            override val taskId: String? = null
        }

        fun reject(error: Throwable) {
            when (this) {
                is Steer -> completion.completeExceptionally(error)
                is FollowUp -> completion.completeExceptionally(error)
                is CancelChild -> completion.completeExceptionally(error)
                Stop -> Unit
            }
        }
    }

    private companion object {
        const val PUMP_INTERVAL_MILLIS = 2L
        const val SKILL_PARSE_POLL_MILLIS = 1L
        const val MAX_SKILL_PARSE_POLLS = 100
    }
}

private fun emptySkillResourceSet(): EnabledSkillResourceSet = EnabledSkillResourceSet(
    resources = emptyList(),
    digest = skillResourceSetDigest(emptyList()),
)

private fun missingChildPersistenceSink(
    events: List<PiChildAgentEventEnvelope>,
    snapshots: List<PiChildAgentSnapshot>,
): Nothing = error(
    "PI_MOBILE_CHILD_PERSISTENCE_SINK_MISSING events=${events.size} snapshots=${snapshots.size}",
)

internal class OpenRouterRequestPump(
    private val engine: PhoneLocalPiEngine,
    private val credential: ProviderCredential,
    private val client: OpenRouterNativeClient,
    private val networkScope: CoroutineScope,
    private val attentionBridge: PhoneLocalAttentionBridge?,
    private val taskId: String?,
    private val childUpdateSink: (
        List<PiChildAgentEventEnvelope>,
        List<PiChildAgentSnapshot>,
    ) -> Unit,
) {
    private val networkEvents = ConcurrentLinkedQueue<NetworkEvent>()
    private val jobs = mutableMapOf<String, Job>()
    private val cancelledRequestIds = mutableSetOf<String>()
    private val androidToolEvents = ConcurrentLinkedQueue<AndroidToolEvent>()
    private val androidToolJobs = mutableMapOf<String, Job>()
    @Volatile
    private var screenImageCapabilityReady: Boolean? = null

    suspend fun pump(): PiNativeOpenRouterScenarioStatus {
        drainCancellations()
        drainNetworkEvents()
        drainAndroidToolEvents()
        startProviderRequests()
        startAndroidToolRequests()
        attentionBridge?.deliverReady(engine)
        drainNetworkEvents()
        drainAndroidToolEvents()
        val childEvents = peekChildAgentEvents()
        val status = engine.nativeOpenRouterScenarioStatus()
        if (taskId != null && (childEvents.isNotEmpty() || status.childAgents.isNotEmpty())) {
            persistChildUpdate(childEvents, status.childAgents)
            if (childEvents.isNotEmpty()) {
                val acknowledgements = childEvents
                    .groupBy(PiChildAgentEventEnvelope::parentToolCallId)
                    .map { (_, events) -> events.toAcknowledgement() }
                check(engine.acknowledgeNativeOpenRouterChildEvents(acknowledgements) == childEvents.size) {
                    "PI_MOBILE_CHILD_EVENT_ACK_COUNT_MISMATCH"
                }
            }
        }
        return status
    }

    private suspend fun peekChildAgentEvents(): List<PiChildAgentEventEnvelope> =
        if (taskId == null) emptyList() else engine.peekNativeOpenRouterChildEvents()

    private fun persistChildUpdate(
        events: List<PiChildAgentEventEnvelope>,
        snapshots: List<PiChildAgentSnapshot>,
    ) {
        var firstFailure: Throwable? = null
        repeat(CHILD_PERSISTENCE_ATTEMPTS) { attempt ->
            try {
                childUpdateSink(events, snapshots)
                return
            } catch (error: Throwable) {
                if (firstFailure == null) {
                    firstFailure = error
                } else {
                    firstFailure.addSuppressed(error)
                }
                if (attempt + 1 == CHILD_PERSISTENCE_ATTEMPTS) throw firstFailure
            }
        }
    }

    private fun List<PiChildAgentEventEnvelope>.toAcknowledgement(): PiChildAgentEventAck {
        require(isNotEmpty()) { "PI_MOBILE_CHILD_EVENT_ACK_EMPTY" }
        val first = first()
        require(all { event ->
            event.parentTaskId == first.parentTaskId &&
                event.parentToolCallId == first.parentToolCallId &&
                event.childId == first.childId &&
                event.childName == first.childName
        }) { "PI_MOBILE_CHILD_EVENT_ACK_BINDING_MISMATCH" }
        val last = maxBy(PiChildAgentEventEnvelope::eventOrdinal)
        return PiChildAgentEventAck(
            parentTaskId = last.parentTaskId,
            parentToolCallId = last.parentToolCallId,
            childId = last.childId,
            childName = last.childName,
            throughEventOrdinal = last.eventOrdinal,
            throughDigest = last.event.toString().sha256(),
        )
    }

    suspend fun close() {
        cancelAndroidTools("session_close")
        jobs.values.toList().forEach { it.cancelAndJoin() }
        jobs.clear()
        networkEvents.clear()
    }

    suspend fun cancelAndroidTools(reason: String) {
        // Fence every pending Android tool job first. This closes the interval before a project
        // executor has registered its task/run and guarantees a late starter observes cancellation
        // before it can enter Linux execution.
        val activeToolJobs = androidToolJobs.values.toList()
        activeToolJobs.forEach { it.cancel() }
        taskId?.let { attentionBridge?.cancelTask(it, reason) }
        activeToolJobs.forEach { it.cancelAndJoin() }
        androidToolJobs.clear()
        androidToolEvents.clear()
        // A project job may have crossed command completion just before cancellation. Re-run the
        // Android cleanup after every job has joined so no unbound PREPARED plan survives Stop.
        taskId?.let { attentionBridge?.cancelTask(it, reason) }
    }

    private suspend fun drainCancellations() {
        engine.drainNativeProviderCancellations().forEach { cancellation ->
            require(cancellation.kind == "cancel_openrouter_stream") {
                "PI_MOBILE_PROVIDER_CANCELLATION_KIND_INVALID"
            }
            cancelledRequestIds += cancellation.id
            jobs.remove(cancellation.id)?.cancel()
            networkEvents.removeIf { it.requestId == cancellation.id }
        }
    }

    private suspend fun drainNetworkEvents() {
        while (true) {
            val event = networkEvents.poll() ?: return
            if (event.requestId in cancelledRequestIds) continue
            when (event) {
                is NetworkEvent.Chunk -> {
                    engine.pushNativeProviderChunk(event.requestId, event.value)
                }
                is NetworkEvent.Completed -> {
                    jobs.remove(event.requestId)
                    engine.completeNativeProviderRequest(
                        event.requestId,
                        event.generationId,
                    )
                }
                is NetworkEvent.Failed -> {
                    jobs.remove(event.requestId)
                    engine.failNativeProviderRequest(event.requestId, event.safeMessage)
                }
            }
        }
    }

    private suspend fun startProviderRequests() {
        engine.drainNativeProviderRequests().forEach { request ->
            require(request.kind == "openrouter_chat_stream") {
                "PI_MOBILE_PROVIDER_REQUEST_KIND_INVALID"
            }
            require(request.id !in jobs) { "PI_MOBILE_PROVIDER_REQUEST_DUPLICATED" }
            val job = networkScope.launch {
                try {
                    val result = client.stream(
                        credential = credential,
                        request = OpenRouterChatRequest(
                            modelId = request.modelId,
                            messages = request.messages,
                            tools = request.tools,
                            maxTokens = request.maxTokens,
                        ),
                        onChunk = { chunk ->
                            networkEvents += NetworkEvent.Chunk(request.id, chunk)
                        },
                    )
                    networkEvents += NetworkEvent.Completed(
                        requestId = request.id,
                        generationId = result.generationId,
                    )
                } catch (_: CancellationException) {
                    // Pi Stop is the source of truth. The owner thread already removed this request.
                } catch (error: Throwable) {
                    networkEvents += NetworkEvent.Failed(
                        requestId = request.id,
                        safeMessage = safeProviderMessage(error),
                    )
                }
            }
            jobs[request.id] = job
        }
    }

    private suspend fun startAndroidToolRequests() {
        val requests = engine.drainNativeProviderToolRequests()
        if (requests.isEmpty()) return
        val bridge = requireNotNull(attentionBridge) {
            "PI_MOBILE_ANDROID_TOOL_BRIDGE_MISSING"
        }
        val boundTaskId = requireNotNull(taskId) {
            "PI_MOBILE_ANDROID_TOOL_TASK_MISSING"
        }
        requests.forEach { request ->
            require(request.id !in androidToolJobs) {
                "PI_MOBILE_ANDROID_TOOL_REQUEST_DUPLICATED"
            }
            androidToolJobs[request.id] = networkScope.launch {
                try {
                    val result = if (
                        request.toolName == PhoneLocalScreenCaptureToolExecutor.TOOL_NAME &&
                        !supportsScreenToolImages()
                    ) {
                        val payload = buildJsonObject {
                            put("ok", false)
                            put("errorCode", "MODEL_IMAGE_INPUT_UNAVAILABLE")
                            put(
                                "errorMessage",
                                "The active model cannot receive a live screen image.",
                            )
                        }
                        PiNativeAndroidToolResult(
                            contentPayload = payload,
                            details = payload,
                            isError = true,
                        )
                    } else {
                        bridge.handleNativeRequest(boundTaskId, request)
                    }
                    androidToolEvents += if (result == null) {
                        AndroidToolEvent.Accepted(request.id)
                    } else {
                        AndroidToolEvent.Completed(request.id, result)
                    }
                } catch (_: CancellationException) {
                    // Runtime Stop/close first asks the Android executor to stop its process.
                } catch (_: Throwable) {
                    androidToolEvents += AndroidToolEvent.Failed(
                        request.id,
                        "Android rejected the project tool request",
                    )
                }
            }
        }
    }

    private suspend fun drainAndroidToolEvents() {
        while (true) {
            when (val event = androidToolEvents.poll() ?: return) {
                is AndroidToolEvent.Accepted -> androidToolJobs.remove(event.requestId)
                is AndroidToolEvent.Completed -> {
                    androidToolJobs.remove(event.requestId)
                    resolveAndroidToolIfPending(event.requestId, event.result)
                }
                is AndroidToolEvent.Failed -> {
                    androidToolJobs.remove(event.requestId)
                    resolveAndroidToolIfPending(
                        event.requestId,
                        PiNativeAndroidToolResult(
                            contentPayload = buildJsonObject {
                            put("ok", false)
                            put("errorCode", "ANDROID_TOOL_FAILED")
                            put("errorMessage", event.safeMessage)
                            },
                            isError = true,
                        ),
                    )
                }
            }
        }
    }

    private suspend fun resolveAndroidToolIfPending(
        requestId: String,
        result: PiNativeAndroidToolResult,
    ) {
        try {
            engine.resolveNativeProviderToolRequest(
                requestId = requestId,
                contentPayload = result.contentPayload,
                details = result.details,
                content = result.content,
                isError = result.isError,
            )
        } catch (error: Throwable) {
            if (error.message?.contains("PI_MOBILE_NATIVE_PROVIDER_TOOL_NOT_FOUND") != true) {
                throw error
            }
            // Pi removed the pending tool when Stop aborted the Agent turn.
        }
    }

    private fun safeProviderMessage(error: Throwable): String =
        if (error is OpenRouterRequestException) {
            error.message ?: "OpenRouter request failed"
        } else {
            "OpenRouter request failed"
        }

    private suspend fun supportsScreenToolImages(): Boolean {
        screenImageCapabilityReady?.let { return it }
        val model = runCatching { client.listModels(credential.apiKey) }
            .getOrNull()
            ?.singleOrNull { it.id == credential.profile.modelId }
        return (
            model != null &&
                model.inputModalities.any { it.equals("image", ignoreCase = true) } &&
                model.supportedParameters.any { it.equals("tools", ignoreCase = true) }
            ).also { screenImageCapabilityReady = it }
    }

    private companion object {
        const val CHILD_PERSISTENCE_ATTEMPTS = 2
    }

    private sealed interface NetworkEvent {
        val requestId: String

        data class Chunk(
            override val requestId: String,
            val value: JsonObject,
        ) : NetworkEvent

        data class Completed(
            override val requestId: String,
            val generationId: String?,
        ) : NetworkEvent

        data class Failed(
            override val requestId: String,
            val safeMessage: String,
        ) : NetworkEvent
    }

    private sealed interface AndroidToolEvent {
        val requestId: String

        data class Accepted(override val requestId: String) : AndroidToolEvent

        data class Completed(
            override val requestId: String,
            val result: PiNativeAndroidToolResult,
        ) : AndroidToolEvent

        data class Failed(
            override val requestId: String,
            val safeMessage: String,
        ) : AndroidToolEvent
    }
}

private fun PiMobileSkillParseStatus.toSkillDocumentParseResult(): SkillDocumentParseResult {
    if (phase == "failed") {
        check(!ok && !errorCode.isNullOrBlank()) { "PI_MOBILE_SKILL_PARSE_FAILURE_INVALID" }
        throw SkillDocumentParseException(errorCode)
    }
    check(phase == "completed" && ok) { "PI_MOBILE_SKILL_PARSE_STATUS_INVALID" }
    val parsedResource = requireNotNull(resource) { "PI_MOBILE_SKILL_PARSE_RESOURCE_MISSING" }
    requireValidSkillResource(parsedResource)
    val parsedAvailability = when (availability) {
        "available" -> SkillAvailability.AVAILABLE
        "unavailable" -> SkillAvailability.UNAVAILABLE
        else -> error("PI_MOBILE_SKILL_PARSE_AVAILABILITY_INVALID")
    }
    if (parsedAvailability == SkillAvailability.AVAILABLE) {
        check(diagnosticCode == null && diagnosticMessage == null) {
            "PI_MOBILE_SKILL_PARSE_DIAGNOSTIC_INVALID"
        }
    } else {
        check(!diagnosticCode.isNullOrBlank() && !diagnosticMessage.isNullOrBlank()) {
            "PI_MOBILE_SKILL_PARSE_DIAGNOSTIC_MISSING"
        }
    }
    return SkillDocumentParseResult(
        resource = parsedResource,
        availability = parsedAvailability,
        diagnosticCode = diagnosticCode,
        diagnosticMessage = diagnosticMessage,
    )
}

private fun String.sha256(): String = MessageDigest.getInstance("SHA-256")
    .digest(toByteArray(Charsets.UTF_8))
    .joinToString("") { byte -> "%02x".format(byte) }
