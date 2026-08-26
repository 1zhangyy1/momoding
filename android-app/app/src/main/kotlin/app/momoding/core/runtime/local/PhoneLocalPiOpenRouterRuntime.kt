package app.momoding.core.runtime.local

import android.content.Context
import app.momoding.core.connector.ConnectorToolSnapshot
import app.momoding.core.connector.ConnectorToolSnapshotPolicy
import app.momoding.core.connector.PhoneLocalConnectorToolExecutor
import app.momoding.core.connector.PhoneLocalConnectorToolHandler
import app.momoding.core.provider.ActiveChatProviderSelection
import app.momoding.core.provider.ActiveChatProviderStore
import app.momoding.core.provider.ActiveChatProviderPolicy
import app.momoding.core.provider.ChatProviderKind
import app.momoding.core.provider.CodexNativeClient
import app.momoding.core.provider.CodexResponsesRequest
import app.momoding.core.provider.CodexTransportException
import app.momoding.core.provider.OpenRouterChatRequest
import app.momoding.core.provider.OpenRouterCapabilityProbe
import app.momoding.core.provider.OpenRouterNativeClient
import app.momoding.core.provider.OpenRouterRequestException
import app.momoding.core.provider.OpenRouterWebFetchConfig
import app.momoding.core.provider.OpenRouterWebSearchConfig
import app.momoding.core.provider.ProviderCapabilityAvailability
import app.momoding.core.provider.ProviderCredential
import app.momoding.core.provider.ProviderCredentialVault
import app.momoding.core.provider.ProviderRuntimeConfiguration
import app.momoding.core.provider.ProviderSelection
import app.momoding.core.provider.ProviderSelectionStore
import app.momoding.core.skills.PhoneLocalSkillParser
import app.momoding.core.skills.EnabledSkillResourceSet
import app.momoding.core.extensions.EnabledExtensionPackageSet
import app.momoding.core.extensions.extensionPackageSetDigest
import app.momoding.core.skills.SkillAvailability
import app.momoding.core.skills.SkillDocumentParseException
import app.momoding.core.skills.SkillDocumentParseResult
import app.momoding.core.skills.requireValidSkillResource
import app.momoding.core.skills.skillResourceSetDigest
import java.security.MessageDigest
import java.time.ZonedDateTime
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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
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
    private val connectorTools: PhoneLocalConnectorToolHandler? = null,
    private val childUpdateSink: (
        List<PiChildAgentEventEnvelope>,
        List<PiChildAgentSnapshot>,
    ) -> Unit = ::missingChildPersistenceSink,
    private val skillParsePollMillis: Long = SKILL_PARSE_POLL_MILLIS,
    private val selectionStore: ProviderSelectionStore? = null,
    private val capabilityProbe: OpenRouterCapabilityProbe = OpenRouterCapabilityProbe(client),
    private val activeChatProviderStore: ActiveChatProviderStore? = null,
    private val codexClient: CodexNativeClient? = null,
    private val workspaceModeForTask: suspend (String) -> PhoneLocalWorkspaceMode = {
        PhoneLocalWorkspaceMode.PRIVATE_SCRATCH
    },
    private val nowProvider: () -> ZonedDateTime = ZonedDateTime::now,
) : AutoCloseable, PhoneLocalSkillParser {
    constructor(
        context: Context,
        attentionBridge: PhoneLocalAttentionBridge? = null,
        credentialVault: ProviderCredentialVault = ProviderCredentialVault.create(context),
        client: OpenRouterNativeClient = OpenRouterNativeClient(),
        selectionStore: ProviderSelectionStore = ProviderSelectionStore.create(context),
        activeChatProviderStore: ActiveChatProviderStore = ActiveChatProviderStore.create(context),
        codexClient: CodexNativeClient? = null,
        workspaceModeForTask: suspend (String) -> PhoneLocalWorkspaceMode = {
            PhoneLocalWorkspaceMode.PRIVATE_SCRATCH
        },
        childUpdateSink: (
            List<PiChildAgentEventEnvelope>,
            List<PiChildAgentSnapshot>,
        ) -> Unit = ::missingChildPersistenceSink,
    ) : this(
        context = context,
        credentialVault = credentialVault,
        client = client,
        ownerDispatcher = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "phone-local-pi-openrouter")
        }.asCoroutineDispatcher(),
        networkScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        attentionBridge = attentionBridge,
        childUpdateSink = childUpdateSink,
        selectionStore = selectionStore,
        activeChatProviderStore = activeChatProviderStore,
        codexClient = codexClient,
        workspaceModeForTask = workspaceModeForTask,
    )

    private val appContext = context.applicationContext
    private var engine: PhoneLocalPiEngine? = null
    private var requestPump: NativeProviderRequestPump? = null
    private var activeTaskProvider: RuntimeChatProvider? = null
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
    private var closed = false

    suspend fun currentProviderConfiguration(): ProviderRuntimeConfiguration =
        withContext(ownerDispatcher) {
            checkOpen()
            val credential = (activeTaskProvider as? RuntimeChatProvider.OpenRouter)?.credential
                ?: loadCredential()
            ProviderRuntimeConfiguration(
                selection = selectionStore?.reconcile(credential.profile)
                    ?: ProviderSelection.defaults(credential.profile),
                capabilities = capabilityProbe.resolve(credential),
            )
        }

    suspend fun requireImageInputCapability(): String = withContext(ownerDispatcher) {
        checkOpen()
        val provider = if (activeTaskId != null) {
            requireNotNull(activeTaskProvider) { "PI_MOBILE_TASK_SESSION_PROVIDER_MISSING" }
        } else {
            loadActiveProvider()
        }
        requireImageInputCapability(provider)
    }

    suspend fun requireToolCapability(): String = withContext(ownerDispatcher) {
        checkOpen()
        val provider = if (activeTaskId != null) {
            requireNotNull(activeTaskProvider) { "PI_MOBILE_TASK_SESSION_PROVIDER_MISSING" }
        } else {
            loadActiveProvider()
        }
        requireToolCapability(provider)
    }

    private suspend fun requireToolCapability(provider: RuntimeChatProvider): String =
        when (provider) {
            is RuntimeChatProvider.OpenRouter -> requireToolCapability(provider.credential)
            is RuntimeChatProvider.Codex -> provider.modelId
        }

    private suspend fun requireImageInputCapability(provider: RuntimeChatProvider): String =
        when (provider) {
            is RuntimeChatProvider.OpenRouter -> requireImageInputCapability(provider.credential)
            is RuntimeChatProvider.Codex -> error("PI_MOBILE_CODEX_IMAGE_INPUT_NOT_VERIFIED")
        }

    private suspend fun requireToolCapability(credential: ProviderCredential): String {
        val modelId = credential.profile.modelId
        when (capabilityProbe.resolve(credential).functionTools) {
            ProviderCapabilityAvailability.AVAILABLE -> Unit
            ProviderCapabilityAvailability.UNSUPPORTED -> {
                error("PI_MOBILE_TOOL_MODEL_UNSUPPORTED")
            }
            ProviderCapabilityAvailability.UNKNOWN,
            ProviderCapabilityAvailability.NOT_IMPLEMENTED,
            -> error("PI_MOBILE_TOOL_MODEL_CAPABILITY_UNKNOWN")
        }
        return modelId
    }

    private suspend fun requireImageInputCapability(credential: ProviderCredential): String {
        val modelId = credential.profile.modelId
        val capabilities = capabilityProbe.resolve(credential)
        when (capabilities.imageInput) {
            ProviderCapabilityAvailability.AVAILABLE -> Unit
            ProviderCapabilityAvailability.UNSUPPORTED -> {
                error("PI_MOBILE_IMAGE_MODEL_UNSUPPORTED")
            }
            ProviderCapabilityAvailability.UNKNOWN,
            ProviderCapabilityAvailability.NOT_IMPLEMENTED,
            -> error("PI_MOBILE_IMAGE_MODEL_CAPABILITY_UNKNOWN")
        }
        when (capabilities.functionTools) {
            ProviderCapabilityAvailability.AVAILABLE -> Unit
            ProviderCapabilityAvailability.UNSUPPORTED -> {
                error("PI_MOBILE_IMAGE_MODEL_TOOLS_UNSUPPORTED")
            }
            ProviderCapabilityAvailability.UNKNOWN,
            ProviderCapabilityAvailability.NOT_IMPLEMENTED,
            -> error("PI_MOBILE_IMAGE_MODEL_CAPABILITY_UNKNOWN")
        }
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
                status.toSkillDocumentParseResult().copy(sourceDocumentSha256 = content.sha256())
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
        val credential = loadCredential()
        val activeEngine = PhoneLocalPiEngine(appContext.assets, ownerDispatcher)
        val activePump = NativeProviderRequestPump(
            engine = activeEngine,
            provider = RuntimeChatProvider.OpenRouter(credential),
            openRouterClient = client,
            codexClient = null,
            networkScope = networkScope,
            attentionBridge = null,
            taskId = null,
            childUpdateSink = childUpdateSink,
            webSearch = { webSearchConfiguration(credential) },
            webFetch = { webFetchConfiguration(credential) },
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
        connectorToolSnapshot: ConnectorToolSnapshot? = null,
        extensionPackages: EnabledExtensionPackageSet = emptyExtensionPackageSet(),
        onStatus: (PiNativeOpenRouterScenarioStatus) -> Unit = {},
    ): PiNativeOpenRouterScenarioStatus = withContext(ownerDispatcher) {
        checkOpen()
        check(!skillParsing) { "PI_MOBILE_SKILL_PARSE_IN_PROGRESS" }
        check(!running) { "PI_MOBILE_OPENROUTER_PROMPT_ALREADY_RUNNING" }
        check(engine == null && activeTaskId == null) {
            "PI_MOBILE_TASK_SESSION_ALREADY_OPEN"
        }
        requireValidSkillResourceSet(skillResources)
        requireValidExtensionPackageSet(extensionPackages)
        val approvedConnectorSnapshot = connectorToolSnapshot?.let(
            ConnectorToolSnapshotPolicy::requireValid,
        )
        if (approvedConnectorSnapshot != null) {
            requireNotNull(connectorTools) { "PI_MOBILE_CONNECTOR_EXECUTOR_MISSING" }
        }
        val provider = loadActiveProvider()
        val imageGenerationEnabled = imageGenerationEnabled(provider)
        val taskEnvironment = taskEnvironmentSnapshot(taskId, provider, imageGenerationEnabled)
        if (images.isNotEmpty()) requireImageInputCapability(provider)
        if (textAttachments.isNotEmpty()) requireToolCapability(provider)
        val activeEngine = PhoneLocalPiEngine(appContext.assets, ownerDispatcher)
        val activePump = NativeProviderRequestPump(
            engine = activeEngine,
            provider = provider,
            openRouterClient = client,
            codexClient = codexClient,
            networkScope = networkScope,
            attentionBridge = attentionBridge,
            connectorTools = connectorTools,
            taskId = taskId,
            childUpdateSink = childUpdateSink,
            webSearch = {
                (provider as? RuntimeChatProvider.OpenRouter)
                    ?.credential
                    ?.let(::webSearchConfiguration)
            },
            webFetch = {
                (provider as? RuntimeChatProvider.OpenRouter)
                    ?.credential
                    ?.let(::webFetchConfiguration)
            },
        )
        engine = activeEngine
        requestPump = activePump
        activeTaskProvider = provider
        activeTaskId = taskId
        running = true
        lastStatus = null
        openCommandMailbox()
        try {
            activeEngine.bootstrap()
            val initial = when (provider) {
                is RuntimeChatProvider.OpenRouter -> activeEngine.startNativeOpenRouterTaskSession(
                    taskId = taskId,
                    sessionId = sessionId,
                    prompt = prompt,
                    modelId = provider.modelId,
                    planMode = planMode,
                    skillResources = skillResources.resources,
                    images = images,
                    textAttachments = textAttachments,
                    imageGenerationEnabled = imageGenerationEnabled,
                    connectorToolSnapshot = approvedConnectorSnapshot,
                    extensionPackages = extensionPackages.packages,
                    taskEnvironment = taskEnvironment,
                )
                is RuntimeChatProvider.Codex -> activeEngine.startNativeCodexTaskSession(
                    taskId = taskId,
                    sessionId = sessionId,
                    prompt = prompt,
                    modelId = provider.modelId,
                    planMode = planMode,
                    skillResources = skillResources.resources,
                    textAttachments = textAttachments,
                    connectorToolSnapshot = approvedConnectorSnapshot,
                    extensionPackages = extensionPackages.packages,
                    taskEnvironment = taskEnvironment,
                )
            }
            pumpUntilTerminal(initial, activePump, onStatus).also { terminal ->
                requireTrustedSkillResourceSet(terminal, skillResources)
                requireTrustedExtensionPackageSet(terminal, extensionPackages)
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
        extensionPackages: EnabledExtensionPackageSet = emptyExtensionPackageSet(),
        onStatus: (PiNativeOpenRouterScenarioStatus) -> Unit = {},
    ): PiNativeOpenRouterScenarioStatus = withContext(ownerDispatcher) {
        checkOpen()
        check(!skillParsing) { "PI_MOBILE_SKILL_PARSE_IN_PROGRESS" }
        check(!running) { "PI_MOBILE_OPENROUTER_PROMPT_ALREADY_RUNNING" }
        check(engine == null && activeTaskId == null) {
            "PI_MOBILE_TASK_SESSION_ALREADY_OPEN"
        }
        requireValidSkillResourceSet(skillResources)
        requireValidExtensionPackageSet(extensionPackages)
        check(skillResources.resources.any { it.name == skillName }) {
            "PI_MOBILE_SKILL_NOT_ENABLED"
        }
        val provider = loadActiveProvider()
        val imageGenerationEnabled = imageGenerationEnabled(provider)
        val taskEnvironment = taskEnvironmentSnapshot(taskId, provider, imageGenerationEnabled)
        val activeEngine = PhoneLocalPiEngine(appContext.assets, ownerDispatcher)
        val activePump = NativeProviderRequestPump(
            engine = activeEngine,
            provider = provider,
            openRouterClient = client,
            codexClient = codexClient,
            networkScope = networkScope,
            attentionBridge = attentionBridge,
            taskId = taskId,
            childUpdateSink = childUpdateSink,
            webSearch = {
                (provider as? RuntimeChatProvider.OpenRouter)
                    ?.credential
                    ?.let(::webSearchConfiguration)
            },
            webFetch = {
                (provider as? RuntimeChatProvider.OpenRouter)
                    ?.credential
                    ?.let(::webFetchConfiguration)
            },
        )
        engine = activeEngine
        requestPump = activePump
        activeTaskProvider = provider
        activeTaskId = taskId
        running = true
        lastStatus = null
        openCommandMailbox()
        try {
            activeEngine.bootstrap()
            val initial = when (provider) {
                is RuntimeChatProvider.OpenRouter -> activeEngine.startNativeOpenRouterTaskSkillSession(
                    taskId = taskId,
                    sessionId = sessionId,
                    skillName = skillName,
                    additionalInstructions = additionalInstructions,
                    modelId = provider.modelId,
                    skillResources = skillResources.resources,
                    imageGenerationEnabled = imageGenerationEnabled,
                    extensionPackages = extensionPackages.packages,
                    taskEnvironment = taskEnvironment,
                )
                is RuntimeChatProvider.Codex -> activeEngine.startNativeCodexTaskSkillSession(
                    taskId = taskId,
                    sessionId = sessionId,
                    skillName = skillName,
                    additionalInstructions = additionalInstructions,
                    modelId = provider.modelId,
                    skillResources = skillResources.resources,
                    extensionPackages = extensionPackages.packages,
                    taskEnvironment = taskEnvironment,
                )
            }
            pumpUntilTerminal(initial, activePump, onStatus).also { terminal ->
                requireTrustedSkillResourceSet(terminal, skillResources)
                requireTrustedExtensionPackageSet(terminal, extensionPackages)
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
            refreshTaskEnvironment(taskId, activeEngine)
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
            refreshTaskEnvironment(taskId, activeEngine)
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
            requireToolCapability(requireNotNull(activeTaskProvider))
        }
        running = true
        lastStatus = null
        openCommandMailbox()
        try {
            refreshTaskEnvironment(taskId, activeEngine)
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

    suspend fun taskExtensionPackageSetMatches(
        taskId: String,
        extensionPackages: EnabledExtensionPackageSet,
    ): Boolean = withContext(ownerDispatcher) {
        checkOpen()
        requireValidExtensionPackageSet(extensionPackages)
        if (activeTaskId != taskId || running) return@withContext false
        val activeEngine = engine ?: return@withContext false
        val status = activeEngine.nativeOpenRouterScenarioStatus()
        status.terminal && status.extensionSetTrusted &&
            status.extensionSetDigest == extensionPackages.digest
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
            refreshTaskEnvironment(taskId, activeEngine)
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
        connectorToolSnapshot: ConnectorToolSnapshot? = null,
        extensionPackages: EnabledExtensionPackageSet = emptyExtensionPackageSet(),
    ): PiNativeOpenRouterScenarioStatus = withContext(ownerDispatcher) {
        checkOpen()
        check(!skillParsing) { "PI_MOBILE_SKILL_PARSE_IN_PROGRESS" }
        check(!running) { "PI_MOBILE_OPENROUTER_PROMPT_ALREADY_RUNNING" }
        check(engine == null && activeTaskId == null) {
            "PI_MOBILE_TASK_SESSION_ALREADY_OPEN"
        }
        check(snapshot.taskId == taskId) { "PI_MOBILE_SESSION_SNAPSHOT_TASK_MISMATCH" }
        requireValidSkillResourceSet(skillResources)
        requireValidExtensionPackageSet(extensionPackages)
        val approvedConnectorSnapshot = connectorToolSnapshot?.let(
            ConnectorToolSnapshotPolicy::requireValid,
        )
        if (approvedConnectorSnapshot != null) {
            requireNotNull(connectorTools) { "PI_MOBILE_CONNECTOR_EXECUTOR_MISSING" }
        }
        val provider = loadProviderForSnapshot(snapshot.entries)
        val imageGenerationEnabled = imageGenerationEnabled(provider)
        val taskEnvironment = taskEnvironmentSnapshot(taskId, provider, imageGenerationEnabled)
        if (images.isNotEmpty()) requireImageInputCapability(provider)
        if (requiresTools) requireToolCapability(provider)
        val activeEngine = PhoneLocalPiEngine(appContext.assets, ownerDispatcher)
        val activePump = NativeProviderRequestPump(
            engine = activeEngine,
            provider = provider,
            openRouterClient = client,
            codexClient = codexClient,
            networkScope = networkScope,
            attentionBridge = attentionBridge,
            connectorTools = connectorTools,
            taskId = taskId,
            childUpdateSink = childUpdateSink,
            webSearch = {
                (provider as? RuntimeChatProvider.OpenRouter)
                    ?.credential
                    ?.let(::webSearchConfiguration)
            },
            webFetch = {
                (provider as? RuntimeChatProvider.OpenRouter)
                    ?.credential
                    ?.let(::webFetchConfiguration)
            },
        )
        engine = activeEngine
        requestPump = activePump
        activeTaskProvider = provider
        activeTaskId = taskId
        lastStatus = null
        closeCommandMailbox("PI_MOBILE_TASK_SESSION_RESTORING")
        try {
            activeEngine.bootstrap()
            val restored = when (provider) {
                is RuntimeChatProvider.OpenRouter ->
                    activeEngine.restoreNativeOpenRouterTaskSession(
                        taskId = taskId,
                        sessionId = sessionId,
                        turnCount = snapshot.turnCount,
                        entries = snapshot.entries,
                        modelId = provider.modelId,
                        skillResources = skillResources.resources,
                        images = images,
                        imageGenerationEnabled = imageGenerationEnabled,
                        connectorToolSnapshot = approvedConnectorSnapshot,
                        extensionPackages = extensionPackages.packages,
                        taskEnvironment = taskEnvironment,
                    )
                is RuntimeChatProvider.Codex -> activeEngine.restoreNativeCodexTaskSession(
                    taskId = taskId,
                    sessionId = sessionId,
                    turnCount = snapshot.turnCount,
                    entries = snapshot.entries,
                    modelId = provider.modelId,
                    skillResources = skillResources.resources,
                    connectorToolSnapshot = approvedConnectorSnapshot,
                    extensionPackages = extensionPackages.packages,
                    taskEnvironment = taskEnvironment,
                )
            }
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
            requireTrustedExtensionPackageSet(restored, extensionPackages)
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
                requestPump?.cancelAndroidTools("host_shutdown")
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
            activeTaskProvider = null
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
        activePump: NativeProviderRequestPump,
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
        activePump.cancelAndroidTools(ANDROID_TOOL_TERMINAL_TURN_CLEANUP_REASON)
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

    private fun loadCredential(): ProviderCredential {
        val credential = requireNotNull(credentialVault.load()) {
            "PI_MOBILE_PROVIDER_CREDENTIAL_MISSING"
        }
        selectionStore?.reconcile(credential.profile)
        return credential
    }

    private fun loadActiveProvider(): RuntimeChatProvider {
        val openRouterCredential = credentialVault.load()
        val active = activeChatProviderStore?.load(
            openRouterCredential?.profile?.modelId ?: DEFAULT_OPENROUTER_MODEL_ID,
        ) ?: ActiveChatProviderSelection(
            ChatProviderKind.OPENROUTER,
            requireNotNull(openRouterCredential) {
                "PI_MOBILE_PROVIDER_CREDENTIAL_MISSING"
            }.profile.modelId,
        )
        val resolved = if (active.kind == ChatProviderKind.OPENROUTER) {
            active.copy(
                modelId = requireNotNull(openRouterCredential) {
                    "PI_MOBILE_PROVIDER_CREDENTIAL_MISSING"
                }.profile.modelId,
            )
        } else {
            active
        }
        return providerFromSelection(resolved, openRouterCredential)
    }

    private fun loadProviderForSnapshot(entries: JsonArray): RuntimeChatProvider {
        val binding = persistedProviderBinding(entries) ?: return RuntimeChatProvider.OpenRouter(
            loadCredential(),
        )
        return providerFromSelection(binding, credentialVault.load())
    }

    private fun providerFromSelection(
        selection: ActiveChatProviderSelection,
        openRouterCredential: ProviderCredential?,
    ): RuntimeChatProvider = when (selection.kind) {
        ChatProviderKind.OPENROUTER -> {
            val credential = requireNotNull(openRouterCredential) {
                "PI_MOBILE_PROVIDER_CREDENTIAL_MISSING"
            }
            selectionStore?.reconcile(credential.profile)
            RuntimeChatProvider.OpenRouter(
                credential.copy(profile = credential.profile.copy(modelId = selection.modelId)),
            )
        }
        ChatProviderKind.CODEX -> {
            check(codexClient != null) { "PI_MOBILE_CODEX_PROVIDER_NOT_CONFIGURED" }
            RuntimeChatProvider.Codex(selection.modelId)
        }
    }

    private fun webSearchConfiguration(
        credential: ProviderCredential,
    ): OpenRouterWebSearchConfig? {
        val selection = selectionStore?.load(credential.profile)
            ?: ProviderSelection.defaults(credential.profile)
        return OpenRouterWebSearchConfig().takeIf { selection.webSearchEnabled }
    }

    private fun webFetchConfiguration(
        credential: ProviderCredential,
    ): OpenRouterWebFetchConfig? {
        val selection = selectionStore?.load(credential.profile)
            ?: ProviderSelection.defaults(credential.profile)
        return OpenRouterWebFetchConfig().takeIf { selection.webSearchEnabled }
    }

    private fun imageGenerationEnabled(provider: RuntimeChatProvider): Boolean = when (provider) {
        is RuntimeChatProvider.OpenRouter -> {
            val selection = selectionStore?.load(provider.credential.profile)
                ?: ProviderSelection.defaults(provider.credential.profile)
            selection.imageGenerationEnabled && selection.imageModelId != null
        }
        is RuntimeChatProvider.Codex -> false
    }

    private suspend fun taskEnvironmentSnapshot(
        taskId: String,
        provider: RuntimeChatProvider,
        imageGenerationEnabled: Boolean,
    ): PiAgentEnvironmentSnapshot {
        val webSearchEnabled = provider is RuntimeChatProvider.OpenRouter &&
            webSearchConfiguration(provider.credential) != null
        val webFetchEnabled = provider is RuntimeChatProvider.OpenRouter &&
            webFetchConfiguration(provider.credential) != null
        return PiAgentEnvironmentSnapshot.create(
            workspaceMode = workspaceModeForTask(taskId),
            webSearchEnabled = webSearchEnabled,
            webFetchEnabled = webFetchEnabled,
            imageGenerationEnabled = imageGenerationEnabled,
            now = nowProvider(),
        )
    }

    private suspend fun refreshTaskEnvironment(
        taskId: String,
        activeEngine: PhoneLocalPiEngine,
    ) {
        val provider = requireNotNull(activeTaskProvider) {
            "PI_MOBILE_TASK_PROVIDER_MISSING"
        }
        activeEngine.setNativeOpenRouterTaskEnvironment(
            taskEnvironmentSnapshot(
                taskId = taskId,
                provider = provider,
                imageGenerationEnabled = imageGenerationEnabled(provider),
            ),
        )
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
            activeTaskProvider = null
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

    private fun requireValidExtensionPackageSet(packageSet: EnabledExtensionPackageSet) {
        check(extensionPackageSetDigest(packageSet.packages) == packageSet.digest) {
            "PI_MOBILE_EXTENSION_PACKAGE_SET_DIGEST_MISMATCH"
        }
    }

    private fun requireTrustedExtensionPackageSet(
        status: PiNativeOpenRouterScenarioStatus,
        packageSet: EnabledExtensionPackageSet,
    ) {
        check(status.extensionSetTrusted) { "PI_MOBILE_EXTENSION_PACKAGES_UNTRUSTED" }
        check(status.extensionSetDigest == packageSet.digest) {
            "PI_MOBILE_EXTENSION_PACKAGE_SET_DIGEST_MISMATCH"
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
        const val DEFAULT_OPENROUTER_MODEL_ID = "deepseek/deepseek-v4-pro"
    }
}

private fun emptySkillResourceSet(): EnabledSkillResourceSet = EnabledSkillResourceSet(
    resources = emptyList(),
    digest = skillResourceSetDigest(emptyList()),
)

private fun emptyExtensionPackageSet(): EnabledExtensionPackageSet = EnabledExtensionPackageSet(
    packages = emptyList(),
    digest = extensionPackageSetDigest(emptyList()),
)

private fun missingChildPersistenceSink(
    events: List<PiChildAgentEventEnvelope>,
    snapshots: List<PiChildAgentSnapshot>,
): Nothing = error(
    "PI_MOBILE_CHILD_PERSISTENCE_SINK_MISSING events=${events.size} snapshots=${snapshots.size}",
)

internal sealed interface RuntimeChatProvider {
    val modelId: String

    data class OpenRouter(val credential: ProviderCredential) : RuntimeChatProvider {
        override val modelId: String = credential.profile.modelId
    }

    data class Codex(override val modelId: String) : RuntimeChatProvider
}

private fun persistedProviderBinding(entries: JsonArray): ActiveChatProviderSelection? {
    val bindings = entries.mapNotNull { element ->
        val entry = element as? JsonObject ?: return@mapNotNull null
        if ((entry["type"] as? JsonPrimitive)?.contentOrNull != "custom") {
            return@mapNotNull null
        }
        if ((entry["customType"] as? JsonPrimitive)?.contentOrNull != PROVIDER_BINDING_ENTRY_TYPE) {
            return@mapNotNull null
        }
        val data = entry["data"] as? JsonObject
            ?: error("PI_MOBILE_SESSION_PROVIDER_BINDING_INVALID")
        check(data.keys == setOf("kind", "modelId")) {
            "PI_MOBILE_SESSION_PROVIDER_BINDING_INVALID"
        }
        ActiveChatProviderSelection(
            kind = ChatProviderKind.fromWireValue(
                (data["kind"] as? JsonPrimitive)?.contentOrNull
                    ?: error("PI_MOBILE_SESSION_PROVIDER_BINDING_INVALID"),
            ),
            modelId = (data["modelId"] as? JsonPrimitive)?.contentOrNull
                ?: error("PI_MOBILE_SESSION_PROVIDER_BINDING_INVALID"),
        ).also(ActiveChatProviderPolicy::validate)
    }
    check(bindings.distinct().size <= 1) { "PI_MOBILE_SESSION_PROVIDER_BINDING_CONFLICT" }
    return bindings.lastOrNull()
}

private const val PROVIDER_BINDING_ENTRY_TYPE = "pi_mobile_provider_binding"

internal class NativeProviderRequestPump(
    private val engine: PhoneLocalPiEngine,
    private val provider: RuntimeChatProvider,
    private val openRouterClient: OpenRouterNativeClient,
    private val codexClient: CodexNativeClient?,
    private val networkScope: CoroutineScope,
    private val attentionBridge: PhoneLocalAttentionBridge?,
    private val connectorTools: PhoneLocalConnectorToolHandler? = null,
    private val taskId: String?,
    private val childUpdateSink: (
        List<PiChildAgentEventEnvelope>,
        List<PiChildAgentSnapshot>,
    ) -> Unit,
    private val webSearch: () -> OpenRouterWebSearchConfig?,
    private val webFetch: () -> OpenRouterWebFetchConfig?,
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
        cancelAndroidTools("host_shutdown")
        jobs.values.toList().forEach { it.cancelAndJoin() }
        jobs.clear()
        networkEvents.clear()
        taskId?.let { connectorTools?.closeTask(it) }
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
            val expectedKind = when (provider) {
                is RuntimeChatProvider.OpenRouter -> "cancel_openrouter_stream"
                is RuntimeChatProvider.Codex -> "cancel_codex_responses_stream"
            }
            require(cancellation.kind == expectedKind) {
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
                    val outcome = engine.pushNativeProviderChunkOutcome(
                        event.requestId,
                        event.value,
                    )
                    if (!outcome.requestActive) {
                        jobs.remove(event.requestId)?.cancelAndJoin()
                        networkEvents.removeIf { queued -> queued.requestId == event.requestId }
                    }
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
            require(request.id !in jobs) { "PI_MOBILE_PROVIDER_REQUEST_DUPLICATED" }
            val job = networkScope.launch {
                try {
                    val generationId = when (val activeProvider = provider) {
                        is RuntimeChatProvider.OpenRouter -> {
                            require(request.kind == "openrouter_chat_stream") {
                                "PI_MOBILE_PROVIDER_REQUEST_KIND_INVALID"
                            }
                            openRouterClient.stream(
                                credential = activeProvider.credential,
                                request = OpenRouterChatRequest(
                                    modelId = request.modelId,
                                    messages = requireNotNull(request.messages) {
                                        "PI_MOBILE_OPENROUTER_MESSAGES_MISSING"
                                    },
                                    functionTools = request.tools,
                                    webSearch = webSearch(),
                                    webFetch = webFetch(),
                                    maxTokens = request.maxTokens,
                                ),
                                onChunk = { chunk ->
                                    networkEvents += NetworkEvent.Chunk(request.id, chunk)
                                },
                            ).generationId
                        }
                        is RuntimeChatProvider.Codex -> {
                            require(request.kind == "codex_responses_stream") {
                                "PI_MOBILE_PROVIDER_REQUEST_KIND_INVALID"
                            }
                            requireNotNull(codexClient) {
                                "PI_MOBILE_CODEX_PROVIDER_NOT_CONFIGURED"
                            }.stream(
                                CodexResponsesRequest(
                                    modelId = request.modelId,
                                    body = requireNotNull(request.body) {
                                        "PI_MOBILE_CODEX_BODY_MISSING"
                                    },
                                    sessionId = request.sessionId,
                                ),
                            ) { event ->
                                networkEvents += NetworkEvent.Chunk(request.id, event)
                            }
                            null
                        }
                    }
                    networkEvents += NetworkEvent.Completed(
                        requestId = request.id,
                        generationId = generationId,
                    )
                } catch (_: CancellationException) {
                    // Pi Stop is the source of truth. The owner thread already removed this request.
                } catch (error: Throwable) {
                    networkEvents += NetworkEvent.Failed(
                        requestId = request.id,
                        safeMessage = safeProviderMessage(provider, error),
                    )
                }
            }
            jobs[request.id] = job
        }
    }

    private suspend fun startAndroidToolRequests() {
        val requests = engine.drainNativeProviderToolRequests()
        if (requests.isEmpty()) return
        val boundTaskId = requireNotNull(taskId) {
            "PI_MOBILE_ANDROID_TOOL_TASK_MISSING"
        }
        requests.forEach { request ->
            require(request.id !in androidToolJobs) {
                "PI_MOBILE_ANDROID_TOOL_REQUEST_DUPLICATED"
            }
            androidToolJobs[request.id] = networkScope.launch {
                try {
                    val result = if (request.kind == PhoneLocalConnectorToolExecutor.NATIVE_KIND) {
                        requireNotNull(connectorTools) {
                            "PI_MOBILE_CONNECTOR_EXECUTOR_MISSING"
                        }.execute(boundTaskId, request)
                    } else if (
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
                        val bridge = requireNotNull(attentionBridge) {
                            "PI_MOBILE_ANDROID_TOOL_BRIDGE_MISSING"
                        }
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
            val boundTaskId = taskId
            if (boundTaskId != null) {
                attentionBridge?.discardUndeliveredImageResult(boundTaskId, result)
            }
            if (error.message?.contains("PI_MOBILE_NATIVE_PROVIDER_TOOL_NOT_FOUND") != true) {
                throw error
            }
            // Pi removed the pending tool when Stop aborted the Agent turn. Any generated
            // attachment is removed above so a cancelled paid request cannot leave a ghost item.
        }
    }

    private fun safeProviderMessage(provider: RuntimeChatProvider, error: Throwable): String =
        when (provider) {
            is RuntimeChatProvider.OpenRouter -> if (error is OpenRouterRequestException) {
                error.safeTaskMessage()
            } else {
                "OpenRouter request failed"
            }
            is RuntimeChatProvider.Codex -> if (error is CodexTransportException) {
                error.message ?: "Codex request failed"
            } else {
                "Codex request failed"
            }
        }.take(160)

    private suspend fun supportsScreenToolImages(): Boolean {
        screenImageCapabilityReady?.let { return it }
        val credential = (provider as? RuntimeChatProvider.OpenRouter)?.credential
            ?: return false.also { screenImageCapabilityReady = it }
        val model = runCatching { openRouterClient.listModels(credential.apiKey) }
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

internal const val ANDROID_TOOL_TERMINAL_TURN_CLEANUP_REASON = "tool_abort"

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
