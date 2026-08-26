package app.momoding.feature.tasks

import android.util.Log
import app.momoding.core.attachments.AttachmentPayloadException
import app.momoding.core.attachments.AttachmentRepository
import app.momoding.core.attachments.RuntimeAttachmentBatch
import app.momoding.wire.TaskRunState
import app.momoding.wire.WireErrorCode
import app.momoding.core.data.RoomCommandDraftJournal
import app.momoding.core.data.PhoneLocalGoalRecord
import app.momoding.core.data.PhoneLocalGoalRepository
import app.momoding.core.data.PhoneLocalChildAgentRepository
import app.momoding.core.data.PhoneLocalGoalState
import app.momoding.core.data.GoalContinuationDecision
import app.momoding.core.runtime.local.PhoneLocalPiEventProjector
import app.momoding.core.runtime.local.PhoneLocalAttentionBridge
import app.momoding.core.runtime.local.PhoneLocalPiOpenRouterRuntime
import app.momoding.core.runtime.local.PiNativeOpenRouterScenarioStatus
import app.momoding.core.runtime.local.PiTaskGoalSnapshot
import app.momoding.core.runtime.local.PhoneLocalTaskPlanState
import app.momoding.core.runtime.local.taskPlanStateFromEntries
import app.momoding.core.skills.EnabledSkillResourceSet
import app.momoding.core.skills.SkillAvailability
import app.momoding.core.skills.SkillComposerInput
import app.momoding.core.skills.SkillRepository
import app.momoding.core.skills.parseSkillComposerInput
import app.momoding.core.skills.skillResourceSetDigest
import app.momoding.core.extensions.EnabledExtensionPackageSet
import app.momoding.core.extensions.ExtensionPackageRepository
import app.momoding.core.extensions.extensionPackageSetDigest
import app.momoding.feature.newtask.DraftTitlePolicy
import app.momoding.feature.newtask.TaskCreationProgress
import app.momoding.feature.newtask.TaskCreationStage
import app.momoding.feature.taskdetail.TaskCommandKind
import app.momoding.feature.taskdetail.TaskCommandProgress
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Concrete application owner for the one active phone-local Pi task.
 *
 * It coordinates the existing product screens, the concrete Pi runtime, and the existing Room
 * projection. It does not define or translate a second runtime protocol.
 */
class PhoneLocalTaskCoordinator(
    private val runtime: PhoneLocalPiOpenRouterRuntime,
    private val projector: PhoneLocalPiEventProjector,
    private val journal: RoomCommandDraftJournal,
    private val applicationScope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val idFactory: () -> String = { UUID.randomUUID().toString() },
    private val attentionBridge: PhoneLocalAttentionBridge? = null,
    private val goalRepository: PhoneLocalGoalRepository? = null,
    private val childAgentRepository: PhoneLocalChildAgentRepository? = null,
    private val skillRepository: SkillRepository? = null,
    private val extensionPackageRepository: ExtensionPackageRepository? = null,
    private val attachmentRepository: AttachmentRepository? = null,
    private val beforeGoalContinuationStartFence: suspend (String) -> Unit = {},
    private val beforeGoalStartPiEntry: suspend (String) -> Unit = {},
) {
    private val creationProgress = ConcurrentHashMap<String, MutableStateFlow<TaskCreationProgress>>()
    private val commandProgress = ConcurrentHashMap<String, MutableStateFlow<TaskCommandProgress>>()
    private val creationJobs = ConcurrentHashMap<String, Job>()
    private val promptJobs = ConcurrentHashMap<String, Job>()
    private val commandJobs = ConcurrentHashMap<String, Job>()
    private val runJobs = ConcurrentHashMap<String, Job>()
    private val planJobs = ConcurrentHashMap<String, Job>()
    private val goalActionJobs = ConcurrentHashMap<String, Job>()
    private val childCancelJobs = ConcurrentHashMap<String, Job>()
    private val goalSchedulerJobs = ConcurrentHashMap<String, Job>()
    private val goalLocks = ConcurrentHashMap<String, Mutex>()
    private val goalDrivenRuns = ConcurrentHashMap.newKeySet<String>()
    private val planStates = ConcurrentHashMap<String, MutableStateFlow<PhoneLocalTaskPlanState>>()
    private val projectedEventCounts = ConcurrentHashMap<String, Int>()
    private val taskStreams = ConcurrentHashMap<String, String>()
    private val taskSessions = ConcurrentHashMap<String, String>()
    private val pendingStops = ConcurrentHashMap.newKeySet<String>()
    private val stoppingTasks = ConcurrentHashMap.newKeySet<String>()
    private val sessionTransition = Mutex()
    private val startupReady = CompletableDeferred<Unit>()
    private val stateLock = Any()
    private var sessionTaskId: String? = null
    private var runningTaskId: String? = null

    init {
        applicationScope.launch(ioDispatcher) {
            try {
                attentionBridge?.recoverDestroyedRuntime()
                projector.interruptStaleLocalRuns()
                recoverPendingTaskAttachments()
                goalRepository?.recoverInterruptedGoals()
                childAgentRepository?.recoverDestroyedRuntime()
                startupReady.complete(Unit)
            } catch (error: Throwable) {
                startupReady.completeExceptionally(error)
            }
        }
    }

    fun observeCreation(draftId: String): StateFlow<TaskCreationProgress> =
        creationProgress.getOrPut(draftId) { MutableStateFlow(TaskCreationProgress.Idle) }

    fun observeCommands(taskId: String): StateFlow<TaskCommandProgress> =
        commandProgress.getOrPut(taskId) { MutableStateFlow(TaskCommandProgress.Idle) }

    fun observePlan(taskId: String): StateFlow<PhoneLocalTaskPlanState> {
        val existing = planStates[taskId]
        if (existing != null) return existing
        val created = MutableStateFlow(PhoneLocalTaskPlanState())
        val selected = planStates.putIfAbsent(taskId, created) ?: created
        if (selected === created) {
            applicationScope.launch(ioDispatcher) {
                runCatching { projector.persistedSession(taskId) }
                    .getOrNull()
                    ?.let { persisted ->
                        selected.value = taskPlanStateFromEntries(persisted.snapshot.entries)
                    }
            }
        }
        return selected
    }

    fun cancelChildAgent(taskId: String, parentToolCallId: String): Boolean {
        val repository = childAgentRepository ?: return false
        val key = "$taskId:$parentToolCallId"
        if (childCancelJobs[key]?.isActive == true) return false
        val child = repository.runningChild(taskId, parentToolCallId) ?: return false
        val job = applicationScope.launch(ioDispatcher, start = CoroutineStart.LAZY) {
            startupReady.await()
            check(synchronized(stateLock) { runningTaskId == taskId }) {
                "PI_MOBILE_CHILD_PARENT_NOT_RUNNING"
            }
            check(runtime.cancelChildAgent(taskId, child.childId)) {
                "PI_MOBILE_CHILD_NOT_RUNNING"
            }
        }
        childCancelJobs[key] = job
        job.invokeOnCompletion { error ->
            childCancelJobs.remove(key, job)
            if (error != null && error !is CancellationException) {
                Log.w(LOG_TAG, "Child Agent cancel failed: ${error.message}")
            }
        }
        job.start()
        return true
    }

    fun setPlanMode(taskId: String, enabled: Boolean): Boolean {
        if (planJobs[taskId]?.isActive == true) return false
        val flow = planStateFor(taskId)
        flow.value = flow.value.copy(actionPending = true, error = null)
        planJobs[taskId] = applicationScope.launch(ioDispatcher) {
            try {
                startupReady.await()
                claimExistingSettledSession(taskId)
                val terminal = runtime.setTaskPlanMode(taskId, enabled) { status ->
                    updatePlanState(taskId, status, actionPending = true)
                }
                val snapshot = settledSessionSnapshot(taskId, "plan_mode_transition")
                projector.replaceWithSessionSnapshot(
                    taskId = taskId,
                    piSessionId = taskSession(taskId),
                    streamId = taskStream(taskId),
                    snapshot = snapshot,
                    runState = TaskRunState.COMPLETED,
                )
                updatePlanState(taskId, terminal, actionPending = false)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                abandonSessionClaim(taskId)
                flow.value = flow.value.copy(
                    actionPending = false,
                    error = "Plan mode could not be changed safely.",
                )
            } finally {
                synchronized(stateLock) {
                    if (runningTaskId == taskId) runningTaskId = null
                }
                planJobs.remove(taskId)
            }
        }
        return true
    }

    fun implementPlan(taskId: String, planDigest: String): Boolean {
        val flow = planStateFor(taskId)
        if (!flow.value.canImplement || flow.value.latestPlan?.planDigest != planDigest) return false
        if (planJobs[taskId]?.isActive == true) return false
        flow.value = flow.value.copy(actionPending = true, error = null)
        planJobs[taskId] = applicationScope.launch(ioDispatcher) {
            try {
                startupReady.await()
                claimExistingSettledSession(taskId)
                projector.markRunState(taskId, TaskRunState.STARTING, isStreaming = true)
                launchTaskRun(taskId) { onStatus ->
                    runtime.implementTaskPlan(taskId, planDigest) { status ->
                        updatePlanState(taskId, status, actionPending = !status.terminal)
                        onStatus(status)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                abandonSessionClaim(taskId)
                flow.value = flow.value.copy(
                    actionPending = false,
                    error = "The approved plan could not be started.",
                )
            } finally {
                planJobs.remove(taskId)
            }
        }
        return true
    }

    fun createGoal(taskId: String, instruction: String): Boolean =
        startOrEditGoal(taskId, instruction, edit = false)

    fun editGoal(taskId: String, instruction: String): Boolean =
        startOrEditGoal(taskId, instruction, edit = true)

    private fun startOrEditGoal(taskId: String, instruction: String, edit: Boolean): Boolean {
        if (
            instruction.isBlank() ||
            goalActionJobs[taskId]?.isActive == true ||
            planStateFor(taskId).value.enabled
        ) return false
        val repository = goalRepository ?: return false
        goalActionJobs[taskId] = applicationScope.launch(ioDispatcher) {
            var goal: PhoneLocalGoalRecord? = null
            try {
                goalLocks.getOrPut(taskId) { Mutex() }.withLock {
                    startupReady.await()
                    requireGoalPlanCompatible(taskId)
                    goal = if (edit) repository.edit(taskId, instruction) else {
                        repository.create(taskId, idFactory(), instruction)
                    }
                    val claimed = repository.beginClaimedTurn(
                        taskId = taskId,
                        goalId = requireNotNull(goal).goalId,
                        generation = requireNotNull(goal).generation,
                        turnIndex = 0,
                        automatic = false,
                    )
                    if (claimed == null) {
                        scheduleGoalStatePersistence(taskId, "paused")
                        return@withLock
                    }
                    beforeGoalStartPiEntry(taskId)
                    claimExistingSettledSession(taskId)
                    projector.markRunState(taskId, TaskRunState.STARTING, isStreaming = true)
                    goalDrivenRuns += taskId
                    val active = claimed
                    launchTaskRun(taskId) { onStatus ->
                        runtime.startTaskGoal(
                            taskId = taskId,
                            goalId = active.goalId,
                            instruction = active.instruction,
                            generation = active.generation,
                            startedAtMillis = active.startedAtMillis,
                        ) { status ->
                            syncGoalStatus(taskId, status)
                            onStatus(status)
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                goal?.let { repository.settleTurn(taskId, stopped = false, failed = true) }
                runCatching {
                    projector.markRunState(taskId, TaskRunState.FAILED, isStreaming = false)
                }
                abandonSessionClaim(taskId)
            } finally {
                goalActionJobs.remove(taskId)
            }
        }
        return true
    }

    fun pauseGoal(taskId: String): Boolean {
        if (goalActionJobs[taskId]?.isActive == true) return false
        val repository = goalRepository ?: return false
        return runCatching { repository.requestPause(taskId) }.fold(
            onSuccess = { paused ->
                if (paused.state == PhoneLocalGoalState.PAUSED) {
                    scheduleGoalStatePersistence(taskId, "paused")
                }
                true
            },
            onFailure = { false },
        )
    }

    fun resumeGoal(taskId: String): Boolean {
        if (goalActionJobs[taskId]?.isActive == true || planStateFor(taskId).value.enabled) return false
        val repository = goalRepository ?: return false
        goalActionJobs[taskId] = applicationScope.launch(ioDispatcher) {
            var goal: PhoneLocalGoalRecord? = null
            try {
                goalLocks.getOrPut(taskId) { Mutex() }.withLock {
                    startupReady.await()
                    requireGoalPlanCompatible(taskId)
                    goal = repository.claimResume(taskId)
                    val persistedRuntimeGoal = projector.persistedSession(taskId)?.snapshot?.goal
                    val restartingMissingInitialTurn = shouldRestartMissingCurrentGoal(
                        goal = requireNotNull(goal),
                        runtimeGoal = persistedRuntimeGoal,
                    )
                    val resumeTurnIndex = if (restartingMissingInitialTurn) {
                        maxOf(requireNotNull(goal).lastTurnIndex, 0)
                    } else {
                        requireNotNull(goal).lastTurnIndex + 1
                    }
                    val claimed = repository.beginClaimedTurn(
                        taskId = taskId,
                        goalId = requireNotNull(goal).goalId,
                        generation = requireNotNull(goal).generation,
                        turnIndex = resumeTurnIndex,
                        automatic = false,
                        restartExistingTurn = restartingMissingInitialTurn,
                    )
                    if (claimed == null) {
                        scheduleGoalStatePersistence(taskId, "paused")
                        return@withLock
                    }
                    claimExistingSettledSession(taskId)
                    projector.markRunState(taskId, TaskRunState.STARTING, isStreaming = true)
                    goalDrivenRuns += taskId
                    val active = claimed
                    launchTaskRun(taskId) { onStatus ->
                        resumeInterruptedGoal(
                            taskId = taskId,
                            goal = active,
                            restartMissingCurrentGoal = restartingMissingInitialTurn,
                            onStatus = onStatus,
                        )
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                goal?.let { repository.settleTurn(taskId, stopped = false, failed = true) }
                runCatching {
                    projector.markRunState(taskId, TaskRunState.FAILED, isStreaming = false)
                }
                abandonSessionClaim(taskId)
            } finally {
                goalActionJobs.remove(taskId)
            }
        }
        return true
    }

    fun clearGoal(taskId: String): Boolean {
        if (goalActionJobs[taskId]?.isActive == true || planStateFor(taskId).value.enabled) return false
        val repository = goalRepository ?: return false
        goalActionJobs[taskId] = applicationScope.launch(ioDispatcher) {
            try {
                goalLocks.getOrPut(taskId) { Mutex() }.withLock {
                    startupReady.await()
                    requireGoalPlanCompatible(taskId)
                    val cleared = repository.clear(taskId)
                    persistGoalState(taskId, cleared, "cleared")
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                abandonSessionClaim(taskId)
            } finally {
                goalActionJobs.remove(taskId)
            }
        }
        return true
    }

    fun start(draftId: String) {
        require(draftId.isNotBlank()) { "draftId is blank" }
        if (creationJobs[draftId]?.isActive == true) return
        creationJobs[draftId] = applicationScope.launch(ioDispatcher) {
            try {
                startDraft(draftId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                Log.e(LOG_TAG, "Phone-local task creation failed: ${error.message}", error)
                creationProgressFor(draftId).value = phoneLocalTaskCreationFailure(
                    text = journal.draft(draftId)?.text,
                    error = error,
                )
            } finally {
                creationJobs.remove(draftId)
            }
        }
    }

    fun retry(draftId: String) = start(draftId)

    fun submit(
        taskId: String,
        kind: TaskCommandKind,
        text: String,
    ): Boolean = submit(
        taskId = taskId,
        kind = kind,
        text = text,
        refreshProvider = false,
    )

    fun retryOriginal(taskId: String, text: String): Boolean = submit(
        taskId = taskId,
        kind = TaskCommandKind.PROMPT,
        text = text,
        refreshProvider = true,
    )

    private fun submit(
        taskId: String,
        kind: TaskCommandKind,
        text: String,
        refreshProvider: Boolean,
    ): Boolean {
        require(kind != TaskCommandKind.STOP) { "Use stop() for a stop command" }
        require(!refreshProvider || kind == TaskCommandKind.PROMPT) {
            "Only a settled prompt can refresh the Provider"
        }
        val composerInput = parseSkillComposerInput(text)
        if (kind != TaskCommandKind.PROMPT && composerInput !is SkillComposerInput.Prompt) {
            val commandId = idFactory()
            commandProgressFor(taskId).value = TaskCommandProgress.Failed(
                commandId = commandId,
                kind = kind,
                code = WireErrorCode.BAD_REQUEST,
                retryable = false,
                text = text,
                safeMessage = when (composerInput) {
                    is SkillComposerInput.Invalid -> composerInput.reason.safeMessage()
                    is SkillComposerInput.Skill ->
                        "Skill commands are available only when the current task is settled."
                    is SkillComposerInput.Prompt -> error("ordinary prompt already excluded")
                },
            )
            return true
        }
        val jobOwner = if (kind == TaskCommandKind.PROMPT) promptJobs else commandJobs
        if (jobOwner[taskId]?.isActive == true) return false
        val commandId = idFactory()
        commandProgressFor(taskId).value = TaskCommandProgress.Persisting(
            commandId = commandId,
            kind = kind,
            text = text,
        )
        jobOwner[taskId] = applicationScope.launch(ioDispatcher) {
            try {
                when (kind) {
                    TaskCommandKind.PROMPT -> continuePrompt(
                        taskId = taskId,
                        commandId = commandId,
                        text = text,
                        refreshProvider = refreshProvider,
                    )
                    TaskCommandKind.STEER -> queueRunningMessage(taskId, commandId, kind, text)
                    TaskCommandKind.FOLLOW_UP -> queueRunningMessage(taskId, commandId, kind, text)
                    TaskCommandKind.STOP -> error("Use stop() for a stop command")
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                runCatching { attachmentRepository?.releasePendingTaskImages(taskId, commandId) }
                commandProgressFor(taskId).value = TaskCommandProgress.Failed(
                    commandId = commandId,
                    kind = kind,
                    code = when (error.message) {
                        "PI_MOBILE_SESSION_SNAPSHOT_CORRUPT",
                        "PI_MOBILE_SESSION_SNAPSHOT_SCHEMA_UNSUPPORTED",
                        "PI_MOBILE_SESSION_SNAPSHOT_SESSION_MISMATCH",
                        "PI_MOBILE_SESSION_SNAPSHOT_STREAM_MISSING",
                        -> WireErrorCode.INVALID_SESSION_STATE
                        else -> WireErrorCode.RECOVERY_REQUIRED
                    },
                    retryable = false,
                    text = text,
                    safeMessage = skillCommandFailureMessage(text, error),
                )
            } finally {
                jobOwner.remove(taskId)
            }
        }
        return true
    }

    fun stop(taskId: String): Boolean {
        val running = synchronized(stateLock) { runningTaskId == taskId }
        if (!running || commandJobs[taskId]?.isActive == true) return false
        val commandId = idFactory()
        commandProgressFor(taskId).value = TaskCommandProgress.Persisting(
            commandId = commandId,
            kind = TaskCommandKind.STOP,
            text = null,
        )
        commandJobs[taskId] = applicationScope.launch(ioDispatcher) {
            try {
                stoppingTasks += taskId
                projector.markRunState(taskId, TaskRunState.STOPPING, isStreaming = true)
                commandProgressFor(taskId).value = TaskCommandProgress.Working(
                    commandId = commandId,
                    kind = TaskCommandKind.STOP,
                    text = null,
                    recovering = false,
                )
                pendingStops += taskId
                runtime.stop()?.let {
                    pendingStops.remove(taskId)
                }
                commandProgressFor(taskId).value = TaskCommandProgress.Completed(
                    commandId = commandId,
                    kind = TaskCommandKind.STOP,
                    runState = TaskRunState.STOPPING.name,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                stoppingTasks.remove(taskId)
                commandProgressFor(taskId).value = TaskCommandProgress.Failed(
                    commandId = commandId,
                    kind = TaskCommandKind.STOP,
                    code = WireErrorCode.ABORTED,
                    retryable = false,
                    text = null,
                )
            } finally {
                commandJobs.remove(taskId)
            }
        }
        return true
    }

    fun acknowledge(taskId: String, commandId: String) {
        val current = commandProgressFor(taskId).value
        val currentId = when (current) {
            is TaskCommandProgress.Completed -> current.commandId
            is TaskCommandProgress.Failed -> current.commandId
            else -> null
        }
        if (currentId == commandId) {
            commandProgressFor(taskId).value = TaskCommandProgress.Idle
        }
    }

    private suspend fun startDraft(draftId: String) {
        startupReady.await()
        val draft = requireNotNull(journal.draft(draftId)) { "Draft is missing" }
        val draftAttachmentIds = attachmentRepository?.draftAttachmentIds(draftId).orEmpty()
        require(draft.text.isNotBlank() || draftAttachmentIds.isNotEmpty()) { "Draft is blank" }
        val preparedAttachments = attachmentRepository?.prepareDraftAttachments(draftId)
            ?: EMPTY_RUNTIME_ATTACHMENTS
        if (preparedAttachments.images.isNotEmpty()) runtime.requireImageInputCapability()
        if (preparedAttachments.textFiles.isNotEmpty()) runtime.requireToolCapability()
        draft.taskId?.let { existingTaskId ->
            creationProgressFor(draftId).value = TaskCreationProgress.Started(existingTaskId)
            return
        }

        val composerInput = if (draft.text.isBlank()) {
            SkillComposerInput.Prompt(draft.text)
        } else {
            parseSkillComposerInput(draft.text)
        }
        if (draftAttachmentIds.isNotEmpty() && composerInput !is SkillComposerInput.Prompt) {
            throw SkillCommandRejected("Send attachments with an ordinary task prompt.")
        }
        val skillContext = when (composerInput) {
            is SkillComposerInput.Invalid -> throw SkillCommandRejected(
                composerInput.reason.safeMessage(),
            )
            is SkillComposerInput.Skill -> {
                if (draft.selectedMode == "PLAN") {
                    throw SkillCommandRejected(
                        "Turn off Plan mode before starting a task with a Skill.",
                    )
                }
                resolveSkillInvocation(composerInput)
            }
            is SkillComposerInput.Prompt -> null
        }
        val desiredSkills = skillContext?.enabledResourceSet ?: desiredSkillResources()
        val desiredExtensions = desiredExtensionPackages()

        creationProgressFor(draftId).value = TaskCreationProgress.Working(
            TaskCreationStage.CREATE,
            recovering = false,
        )
        val taskId = idFactory()
        val streamId = idFactory()
        val piSessionId = idFactory()
        claimNewSession(taskId)
        try {
            taskStreams[taskId] = streamId
            taskSessions[taskId] = piSessionId
            projectedEventCounts[taskId] = 0
            projector.createTask(
                taskId = taskId,
                title = draft.text.takeIf(String::isNotBlank)?.let(DraftTitlePolicy::title)
                    ?: "Attachment task",
                piSessionId = piSessionId,
                streamId = streamId,
                initialPrompt = draft.text,
                attachmentIds = preparedAttachments.images.map { it.attachmentId },
                textAttachments = preparedAttachments.textFiles,
                approvalMode = draft.approvalMode,
            )
            val runtimeAttachments = attachmentRepository?.claimPreparedDraftAttachments(
                draftId = draftId,
                taskId = taskId,
                messageLocalId = draft.promptCommandId,
                prepared = preparedAttachments,
            ) ?: EMPTY_RUNTIME_ATTACHMENTS
            journal.bindDraftTask(draftId, taskId)
            creationProgressFor(draftId).value = TaskCreationProgress.Working(
                TaskCreationStage.PROMPT,
                recovering = false,
            )
            creationProgressFor(draftId).value = TaskCreationProgress.Started(taskId)
            launchTaskRun(taskId) { onStatus ->
                when (composerInput) {
                    is SkillComposerInput.Skill -> runtime.startTaskSkillSession(
                        taskId = taskId,
                        skillName = composerInput.name,
                        additionalInstructions = composerInput.additionalInstructions,
                        sessionId = piSessionId,
                        skillResources = desiredSkills,
                        extensionPackages = desiredExtensions,
                        onStatus = onStatus,
                    )
                    is SkillComposerInput.Prompt -> runtime.startTaskSession(
                        taskId = taskId,
                        prompt = draft.text,
                        sessionId = piSessionId,
                        planMode = draft.selectedMode == "PLAN",
                        skillResources = desiredSkills,
                        extensionPackages = desiredExtensions,
                        images = runtimeAttachments.images,
                        textAttachments = runtimeAttachments.textFiles,
                        onStatus = onStatus,
                    )
                    is SkillComposerInput.Invalid -> error("invalid Skill command already rejected")
                }
            }
        } catch (error: Throwable) {
            abandonSessionClaim(taskId)
            throw error
        }
    }

    private suspend fun continuePrompt(
        taskId: String,
        commandId: String,
        text: String,
        refreshProvider: Boolean = false,
    ) {
        startupReady.await()
        val composerInput = if (text.isBlank()) {
            SkillComposerInput.Prompt(text)
        } else {
            parseSkillComposerInput(text)
        }
        val runtimeAttachments = attachmentRepository?.claimTaskStagedAttachments(taskId, commandId)
            ?: EMPTY_RUNTIME_ATTACHMENTS
        val claimedAttachmentIds = runtimeAttachments.attachmentIds
        require(text.isNotBlank() || claimedAttachmentIds.isNotEmpty()) { "Task input is empty" }
        if (runtimeAttachments.images.isNotEmpty()) runtime.requireImageInputCapability()
        if (runtimeAttachments.textFiles.isNotEmpty()) runtime.requireToolCapability()
        if (claimedAttachmentIds.isNotEmpty() && composerInput !is SkillComposerInput.Prompt) {
            throw SkillCommandRejected("Send attachments with an ordinary task prompt.")
        }
        val skillContext = when (composerInput) {
            is SkillComposerInput.Invalid -> throw SkillCommandRejected(
                composerInput.reason.safeMessage(),
            )
            is SkillComposerInput.Skill -> {
                requireSkillTaskMode(taskId)
                resolveSkillInvocation(composerInput)
            }
            is SkillComposerInput.Prompt -> null
        }
        val desiredSkills = skillContext?.enabledResourceSet ?: desiredSkillResources()
        val desiredExtensions = desiredExtensionPackages()
        claimExistingSettledSession(taskId, refreshProvider, desiredSkills, desiredExtensions)
        try {
            runtime.syncTaskSkillResources(taskId, desiredSkills)
            val piSessionId = taskSession(taskId)
            val streamId = taskStream(taskId)
            val prior = settledSessionSnapshot(taskId, "ordinary_prompt_prepare")
            projector.appendPendingPrompt(
                taskId = taskId,
                piSessionId = piSessionId,
                streamId = streamId,
                priorEntries = prior.entries,
                prompt = text,
                attachmentIds = runtimeAttachments.images.map { it.attachmentId },
                textAttachments = runtimeAttachments.textFiles,
            )
            commandProgressFor(taskId).value = TaskCommandProgress.Working(
                commandId = commandId,
                kind = TaskCommandKind.PROMPT,
                text = text,
                recovering = false,
            )
            commandProgressFor(taskId).value = TaskCommandProgress.Completed(
                commandId = commandId,
                kind = TaskCommandKind.PROMPT,
                runState = TaskRunState.STARTING.name,
            )
            if (goalRepository?.current(taskId)?.state == PhoneLocalGoalState.ACTIVE) {
                goalDrivenRuns += taskId
            }
            launchTaskRun(taskId) { onStatus ->
                when (composerInput) {
                    is SkillComposerInput.Skill -> runtime.continueTaskSkill(
                        taskId = taskId,
                        skillName = composerInput.name,
                        additionalInstructions = composerInput.additionalInstructions,
                        onStatus = onStatus,
                    )
                    is SkillComposerInput.Prompt -> runtime.continueTaskPrompt(
                        taskId = taskId,
                        prompt = text,
                        images = runtimeAttachments.images,
                        textAttachments = runtimeAttachments.textFiles,
                        onStatus = onStatus,
                    )
                    is SkillComposerInput.Invalid -> error("invalid Skill command already rejected")
                }
            }
        } catch (error: Throwable) {
            abandonSessionClaim(taskId)
            throw error
        }
    }

    private suspend fun queueRunningMessage(
        taskId: String,
        commandId: String,
        kind: TaskCommandKind,
        text: String,
    ) {
        check(synchronized(stateLock) { runningTaskId == taskId }) {
            "PI_MOBILE_TASK_SESSION_NOT_RUNNING"
        }
        val runtimeAttachments = attachmentRepository?.claimTaskStagedAttachments(taskId, commandId)
            ?: EMPTY_RUNTIME_ATTACHMENTS
        require(text.isNotBlank() || runtimeAttachments.attachmentIds.isNotEmpty()) {
            "Task input is empty"
        }
        if (runtimeAttachments.images.isNotEmpty()) runtime.requireImageInputCapability()
        if (runtimeAttachments.textFiles.isNotEmpty()) runtime.requireToolCapability()
        commandProgressFor(taskId).value = TaskCommandProgress.Working(
            commandId = commandId,
            kind = kind,
            text = text,
            recovering = false,
        )
        when (kind) {
            TaskCommandKind.STEER -> runtime.steerTask(
                taskId,
                text,
                runtimeAttachments.images,
                runtimeAttachments.textFiles,
            )
            TaskCommandKind.FOLLOW_UP -> runtime.followUpTask(
                taskId,
                text,
                runtimeAttachments.images,
                runtimeAttachments.textFiles,
            )
            else -> error("Only running queue messages are accepted")
        }
        commandProgressFor(taskId).value = TaskCommandProgress.Completed(
            commandId = commandId,
            kind = kind,
        )
    }

    private suspend fun runTask(
        taskId: String,
        execute: suspend (((PiNativeOpenRouterScenarioStatus) -> Unit)) -> PiNativeOpenRouterScenarioStatus,
    ) {
        val eventPersister = StreamingEventPersister(
            taskId = taskId,
            initialEventCount = projectedEventCounts[taskId] ?: 0,
        )
        try {
            var runningProjected = false
            val terminal = execute { status ->
                updatePlanState(taskId, status, actionPending = !status.terminal)
                syncGoalStatus(taskId, status)
                if (!runningProjected) {
                    if (taskId !in stoppingTasks) {
                        projector.markRunState(taskId, TaskRunState.RUNNING, isStreaming = true)
                    }
                    runningProjected = true
                }
                eventPersister.accept(status)
                if (pendingStops.remove(taskId)) {
                    runtime.stop()
                }
            }
            eventPersister.accept(terminal)
            eventPersister.flushAndCheck()
            val snapshot = settledSessionSnapshot(taskId, "run_terminal_persist")
            updatePlanState(taskId, terminal, actionPending = false)
            syncGoalStatus(taskId, terminal)
            val terminalState = when {
                terminal.stopRequested || terminal.hasAbort -> TaskRunState.STOPPED
                terminal.providerError != null || terminal.promptError != null ||
                    terminal.commandError != null -> TaskRunState.FAILED
                else -> TaskRunState.COMPLETED
            }
            projector.replaceWithSessionSnapshot(
                taskId = taskId,
                piSessionId = taskSession(taskId),
                streamId = taskStream(taskId),
                snapshot = snapshot,
                // Keep the task non-terminal until attachment reconciliation has completed.
                // Observers may treat a terminal run state as the durable postcondition gate.
                runState = TaskRunState.RUNNING,
                extensionActivities = terminal.events.filter { event ->
                    (event["type"] as? JsonPrimitive)?.contentOrNull == "extension_tool_activity"
                },
                providerWebActivities = terminal.events.filter { event ->
                    (event["type"] as? JsonPrimitive)?.contentOrNull in setOf(
                        "provider_web_search",
                        "provider_web_activity",
                    )
                },
            )
            attachmentRepository?.reconcileTaskImages(
                taskId,
                attachmentReferences(snapshot.entries),
            )
            projector.markRunState(taskId, terminalState, isStreaming = false)
            if (terminalState == TaskRunState.COMPLETED) {
                projector.stabilizeTaskTitle(taskId)
            }
            if (taskId in goalDrivenRuns) {
                val settledGoal = goalRepository?.settleTurn(
                    taskId = taskId,
                    stopped = terminalState == TaskRunState.STOPPED,
                    failed = terminalState == TaskRunState.FAILED,
                )
                scheduleAfterGoalTurn(taskId, settledGoal)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            Log.e(LOG_TAG, "Phone-local Pi run failed: ${error.message}", error)
            runCatching { reconcileTaskImagesFromPersistedSession(taskId) }
            runCatching {
                projector.markRunState(taskId, TaskRunState.FAILED, isStreaming = false)
            }
            // A failed projection or post-run persistence step may leave Pi's settled engine open.
            // Release both the coordinator claim and that exact runtime session before another
            // task starts; merely clearing sessionTaskId poisons the next start with
            // PI_MOBILE_TASK_SESSION_ALREADY_OPEN.
            abandonSessionClaim(taskId)
        } finally {
            eventPersister.cancel()
            pendingStops.remove(taskId)
            stoppingTasks.remove(taskId)
            synchronized(stateLock) {
                if (runningTaskId == taskId) runningTaskId = null
            }
            goalDrivenRuns.remove(taskId)
        }
    }

    private fun syncGoalStatus(taskId: String, status: PiNativeOpenRouterScenarioStatus) {
        val repository = goalRepository ?: return
        if (status.goal == null || repository.current(taskId) == null) return
        repository.syncRuntime(taskId, status.goal)
    }

    private fun scheduleAfterGoalTurn(taskId: String, settled: PhoneLocalGoalRecord?) {
        if (settled == null) return
        val repository = goalRepository ?: return
        launchGoalMaintenance(taskId) {
            try {
                while (runJobs[taskId]?.isActive == true) delay(GOAL_SCHEDULER_POLL_MILLIS)
                val current = repository.finalizePendingPause(taskId)
                when (current?.state) {
                    PhoneLocalGoalState.PAUSED -> {
                        persistGoalState(taskId, current, "paused")
                    }
                    PhoneLocalGoalState.FAILED -> {
                        if (current.terminalReason == "turn_failed") {
                            persistGoalState(taskId, current, "failed")
                        }
                    }
                    PhoneLocalGoalState.ACTIVE -> when (
                        val decision = repository.claimAutomaticContinuation(taskId)
                    ) {
                        GoalContinuationDecision.None -> Unit
                        is GoalContinuationDecision.Limit ->
                            persistGoalState(taskId, decision.goal, "limited")
                        is GoalContinuationDecision.Continue -> {
                            beforeGoalContinuationStartFence(taskId)
                            val claimed = repository.beginClaimedTurn(
                                taskId = taskId,
                                goalId = decision.goal.goalId,
                                generation = decision.goal.generation,
                                turnIndex = decision.turnIndex,
                                automatic = true,
                            )
                            if (claimed == null) {
                                repository.finalizePendingPause(taskId)
                                    ?.takeIf { it.state == PhoneLocalGoalState.PAUSED }
                                    ?.let { persistGoalState(taskId, it, "paused") }
                                return@launchGoalMaintenance
                            }
                            claimExistingSettledSession(taskId)
                            projector.markRunState(taskId, TaskRunState.STARTING, isStreaming = true)
                            goalDrivenRuns += taskId
                            launchTaskRun(taskId) { onStatus ->
                                runtime.continueTaskGoal(
                                    taskId = taskId,
                                    goalId = claimed.goalId,
                                    generation = claimed.generation,
                                    turnIndex = decision.turnIndex,
                                    resume = false,
                                ) { status ->
                                    syncGoalStatus(taskId, status)
                                    onStatus(status)
                                }
                            }
                        }
                    }
                    else -> Unit
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                repository.settleTurn(taskId, stopped = false, failed = true)
                runCatching {
                    projector.markRunState(taskId, TaskRunState.FAILED, isStreaming = false)
                }
                abandonSessionClaim(taskId)
            }
        }
    }

    private fun scheduleGoalStatePersistence(taskId: String, targetState: String) {
        val repository = goalRepository ?: return
        launchGoalMaintenance(taskId) {
            try {
                startupReady.await()
                while (runJobs[taskId]?.isActive == true) delay(GOAL_SCHEDULER_POLL_MILLIS)
                val goal = repository.finalizePendingPause(taskId) ?: return@launchGoalMaintenance
                if (targetState == "paused" && goal.state != PhoneLocalGoalState.PAUSED) {
                    return@launchGoalMaintenance
                }
                persistGoalState(taskId, goal, targetState)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                abandonSessionClaim(taskId)
            }
        }
    }

    private fun launchGoalMaintenance(
        taskId: String,
        block: suspend () -> Unit,
    ) {
        val job = applicationScope.launch(ioDispatcher, start = CoroutineStart.LAZY) {
            goalLocks.getOrPut(taskId) { Mutex() }.withLock { block() }
        }
        goalSchedulerJobs[taskId] = job
        job.invokeOnCompletion { goalSchedulerJobs.remove(taskId, job) }
        job.start()
    }

    private suspend fun persistGoalState(
        taskId: String,
        goal: PhoneLocalGoalRecord,
        targetState: String,
    ) {
        claimExistingSettledSession(taskId)
        try {
            val before = settledSessionSnapshot(taskId, "goal_state_before")
            val runtimeGoal = before.goal
            if (runtimeGoal != null) {
                check(runtimeGoal.goalId == goal.goalId) { "PI_MOBILE_GOAL_IDENTITY_MISMATCH" }
                check(runtimeGoal.generation == goal.generation) {
                    "PI_MOBILE_GOAL_GENERATION_MISMATCH"
                }
                if (runtimeGoal.state != targetState) {
                    val terminal = runtime.setTaskGoalState(
                        taskId = taskId,
                        goalId = goal.goalId,
                        generation = goal.generation,
                        targetState = targetState,
                    ) { status -> syncGoalStatus(taskId, status) }
                    syncGoalStatus(taskId, terminal)
                }
            }
            val snapshot = settledSessionSnapshot(taskId, "goal_state_after")
            projector.replaceWithSessionSnapshot(
                taskId = taskId,
                piSessionId = taskSession(taskId),
                streamId = taskStream(taskId),
                snapshot = snapshot,
                runState = if (goal.pauseReason == "turn_stopped") {
                    TaskRunState.STOPPED
                } else {
                    TaskRunState.COMPLETED
                },
            )
        } finally {
            synchronized(stateLock) {
                if (runningTaskId == taskId) runningTaskId = null
            }
        }
    }

    private suspend fun resumeInterruptedGoal(
        taskId: String,
        goal: PhoneLocalGoalRecord,
        restartMissingCurrentGoal: Boolean,
        onStatus: (PiNativeOpenRouterScenarioStatus) -> Unit,
    ): PiNativeOpenRouterScenarioStatus {
        val restored = settledSessionSnapshot(taskId, "goal_resume_prepare").goal
        val statusCallback: (PiNativeOpenRouterScenarioStatus) -> Unit = { status ->
            syncGoalStatus(taskId, status)
            onStatus(status)
        }
        if (restartMissingCurrentGoal) {
            check(restored == null || restored.isSafePriorGenerationFor(goal)) {
                "PI_MOBILE_GOAL_SESSION_STATE_MISMATCH"
            }
            return runtime.startTaskGoal(
                taskId = taskId,
                goalId = goal.goalId,
                instruction = goal.instruction,
                generation = goal.generation,
                startedAtMillis = goal.startedAtMillis,
                onStatus = statusCallback,
            )
        }
        val current = requireNotNull(restored) { "PI_MOBILE_GOAL_SESSION_STATE_MISSING" }
        check(current.goalId == goal.goalId) { "PI_MOBILE_GOAL_IDENTITY_MISMATCH" }
        check(current.generation == goal.generation) { "PI_MOBILE_GOAL_GENERATION_MISMATCH" }
        check(current.instruction == goal.instruction) { "PI_MOBILE_GOAL_INSTRUCTION_MISMATCH" }
        val resumeRuntimeState = when (current.state) {
            "active" -> false
            "paused", "blocked" -> true
            else -> error("PI_MOBILE_GOAL_NOT_RESUMABLE")
        }
        return runtime.continueTaskGoal(
            taskId = taskId,
            goalId = goal.goalId,
            generation = goal.generation,
            turnIndex = goal.lastTurnIndex,
            resume = resumeRuntimeState,
            onStatus = statusCallback,
        )
    }

    private fun shouldRestartMissingCurrentGoal(
        goal: PhoneLocalGoalRecord,
        runtimeGoal: PiTaskGoalSnapshot?,
    ): Boolean {
        if (runtimeGoal == null || runtimeGoal.isSafePriorGenerationFor(goal)) {
            check(goal.lastTurnIndex <= 0) { "PI_MOBILE_GOAL_SESSION_STATE_MISSING" }
            return true
        }
        check(runtimeGoal.goalId == goal.goalId) { "PI_MOBILE_GOAL_IDENTITY_MISMATCH" }
        check(runtimeGoal.generation == goal.generation) {
            "PI_MOBILE_GOAL_GENERATION_MISMATCH"
        }
        check(runtimeGoal.instruction == goal.instruction) {
            "PI_MOBILE_GOAL_INSTRUCTION_MISMATCH"
        }
        return false
    }

    private fun requireGoalPlanCompatible(taskId: String) {
        val persisted = requireNotNull(projector.persistedSession(taskId)) {
            "PI_MOBILE_SESSION_SNAPSHOT_MISSING"
        }
        check(!persisted.snapshot.planMode) { "PI_MOBILE_GOAL_PLAN_MODE_CONFLICT" }
    }

    private fun PiTaskGoalSnapshot.isSafePriorGenerationFor(goal: PhoneLocalGoalRecord): Boolean =
        generation < goal.generation && state != "active"

    private fun launchTaskRun(
        taskId: String,
        execute: suspend (((PiNativeOpenRouterScenarioStatus) -> Unit)) -> PiNativeOpenRouterScenarioStatus,
    ) {
        check(runJobs[taskId]?.isActive != true) { "PI_MOBILE_TASK_RUN_ALREADY_ACTIVE" }
        runJobs[taskId] = applicationScope.launch(ioDispatcher) {
            try {
                runTask(taskId, execute)
            } finally {
                runJobs.remove(taskId)
            }
        }
    }

    /**
     * Keeps Pi's exact event stream lossless while coalescing only high-frequency message updates.
     * Semantic boundaries are durable immediately; text deltas share one short Room transaction.
     */
    private inner class StreamingEventPersister(
        private val taskId: String,
        initialEventCount: Int,
    ) {
        private val lock = Any()
        private val pending = mutableListOf<JsonObject>()
        private var observedEventCount = initialEventCount
        private var flushJob: Job? = null
        private var failure: Throwable? = null

        fun accept(status: PiNativeOpenRouterScenarioStatus) {
            var shouldFlushNow = status.terminal
            var shouldSchedule = false
            synchronized(lock) {
                failure?.let { throw it }
                check(status.events.size >= observedEventCount) {
                    "PI_MOBILE_EVENT_HISTORY_MOVED_BACKWARDS"
                }
                val newEvents = status.events.drop(observedEventCount)
                observedEventCount = status.events.size
                if (newEvents.isNotEmpty()) {
                    pending += newEvents
                    shouldFlushNow = shouldFlushNow ||
                        newEvents.any { !it.isMessageUpdate() } ||
                        pending.size >= STREAM_EVENT_BATCH_MAX
                    shouldSchedule = !shouldFlushNow
                }
            }
            if (shouldFlushNow) {
                flushJob?.cancel()
                flush(throwOnFailure = true)
            } else if (shouldSchedule) {
                scheduleFlush()
            }
        }

        fun flushAndCheck() {
            flushJob?.cancel()
            flush(throwOnFailure = true)
            synchronized(lock) {
                failure?.let { throw it }
            }
        }

        fun cancel() {
            flushJob?.cancel()
        }

        private fun scheduleFlush() {
            synchronized(lock) {
                if (flushJob?.isActive == true) return
                flushJob = applicationScope.launch(ioDispatcher) {
                    delay(STREAM_EVENT_BATCH_WINDOW_MILLIS)
                    flush(throwOnFailure = false)
                }
            }
        }

        private fun flush(throwOnFailure: Boolean) {
            val error = synchronized(lock) {
                failure?.let { return@synchronized it }
                if (pending.isEmpty()) return
                val batch = pending.toList()
                try {
                    projector.append(
                        taskId = taskId,
                        piSessionId = taskSession(taskId),
                        streamId = taskStream(taskId),
                        events = batch,
                    )
                    pending.clear()
                    projectedEventCounts[taskId] = observedEventCount
                    null
                } catch (caught: Throwable) {
                    failure = caught
                    caught
                }
            }
            if (error != null) {
                runtime.stop()
                if (throwOnFailure) throw error
            }
        }
    }

    private fun JsonObject.isMessageUpdate(): Boolean =
        (this["type"] as? JsonPrimitive)?.contentOrNull == "message_update"

    private suspend fun recoverPendingTaskAttachments() {
        attachmentRepository?.taskIdsWithPendingImages()?.forEach { taskId ->
            reconcileTaskImagesFromPersistedSession(taskId)
        }
    }

    private suspend fun reconcileTaskImagesFromPersistedSession(taskId: String) {
        val references = projector.persistedSession(taskId)
            ?.snapshot
            ?.entries
            ?.let(::attachmentReferences)
            .orEmpty()
        attachmentRepository?.reconcileTaskImages(taskId, references)
    }

    private suspend fun claimNewSession(taskId: String) {
        sessionTransition.withLock {
            val currentRunning = synchronized(stateLock) { runningTaskId }
            check(currentRunning == null) { "PI_MOBILE_ANOTHER_TASK_IS_RUNNING" }
            val priorSession = synchronized(stateLock) { sessionTaskId }
            if (priorSession != null && priorSession != taskId) {
                runtime.closeTaskSession(priorSession)
            }
            synchronized(stateLock) {
                sessionTaskId = taskId
                runningTaskId = taskId
            }
        }
    }

    private suspend fun claimExistingSettledSession(
        taskId: String,
        refreshProvider: Boolean = false,
        skillResources: EnabledSkillResourceSet? = null,
        extensionPackages: EnabledExtensionPackageSet? = null,
    ) {
        val desiredSkills = skillResources ?: desiredSkillResources()
        val desiredExtensions = extensionPackages ?: desiredExtensionPackages()
        sessionTransition.withLock {
            val currentRunning = synchronized(stateLock) { runningTaskId }
            check(currentRunning == null) { "PI_MOBILE_TASK_SESSION_BUSY" }
            val priorSession = synchronized(stateLock) { sessionTaskId }
            if (priorSession != null && priorSession != taskId) {
                runtime.closeTaskSession(priorSession)
                synchronized(stateLock) {
                    if (sessionTaskId == priorSession) sessionTaskId = null
                }
            }
            var alreadyOpen = synchronized(stateLock) { sessionTaskId == taskId }
            if (alreadyOpen && refreshProvider) {
                check(runtime.closeTaskSession(taskId)) {
                    "PI_MOBILE_TASK_SESSION_PROVIDER_REFRESH_FAILED"
                }
                synchronized(stateLock) {
                    if (sessionTaskId == taskId) sessionTaskId = null
                }
                alreadyOpen = false
            }
            if (
                alreadyOpen &&
                !runtime.taskExtensionPackageSetMatches(taskId, desiredExtensions)
            ) {
                check(runtime.closeTaskSession(taskId)) {
                    "PI_MOBILE_TASK_SESSION_EXTENSION_REFRESH_FAILED"
                }
                synchronized(stateLock) {
                    if (sessionTaskId == taskId) sessionTaskId = null
                }
                alreadyOpen = false
            }
            if (!alreadyOpen) {
                val persisted = requireNotNull(projector.persistedSession(taskId)) {
                    "PI_MOBILE_SESSION_SNAPSHOT_MISSING"
                }
                val imageReferences = imageAttachmentReferences(persisted.snapshot.entries)
                val textReferences = textAttachmentReferences(persisted.snapshot.entries)
                val runtimeImages = attachmentRepository?.runtimeImagesForTask(
                    taskId,
                    imageReferences,
                ).orEmpty()
                runtime.restoreTaskSession(
                    taskId = taskId,
                    sessionId = persisted.piSessionId,
                    snapshot = persisted.snapshot,
                    skillResources = desiredSkills,
                    images = runtimeImages,
                    requiresTools = textReferences.isNotEmpty(),
                    extensionPackages = desiredExtensions,
                )
                taskStreams[taskId] = persisted.streamId
                taskSessions[taskId] = persisted.piSessionId
                projectedEventCounts[taskId] = 0
                synchronized(stateLock) {
                    sessionTaskId = taskId
                }
            }
            alignPausedGoalBeforeSessionUse(taskId)
            synchronized(stateLock) {
                check(runningTaskId == null) { "PI_MOBILE_TASK_SESSION_BUSY" }
                runningTaskId = taskId
            }
        }
    }

    /**
     * Process recovery intentionally pauses Android's durable Goal without replaying Provider work.
     * Pi's last settled Session can still contain the matching Goal as active, so reconcile that
     * control state before any ordinary prompt, Plan action, or Goal maintenance reuses the Session.
     */
    private suspend fun alignPausedGoalBeforeSessionUse(taskId: String) {
        val repository = goalRepository ?: return
        val localGoal = repository.current(taskId) ?: return
        if (localGoal.state != PhoneLocalGoalState.PAUSED) return
        val runtimeGoal = settledSessionSnapshot(taskId, "goal_recovery_align_before").goal ?: return
        if (
            runtimeGoal.goalId != localGoal.goalId ||
            runtimeGoal.generation != localGoal.generation ||
            runtimeGoal.state != "active"
        ) return
        val terminal = runtime.setTaskGoalState(
            taskId = taskId,
            goalId = localGoal.goalId,
            generation = localGoal.generation,
            targetState = "paused",
        ) { status -> syncGoalStatus(taskId, status) }
        syncGoalStatus(taskId, terminal)
        projector.saveSessionSnapshot(
            taskId = taskId,
            piSessionId = taskSession(taskId),
            snapshot = settledSessionSnapshot(taskId, "goal_recovery_align_after"),
        )
    }

    private suspend fun settledSessionSnapshot(
        taskId: String,
        operation: String,
    ) = try {
        runtime.taskSessionSnapshot(taskId)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Throwable) {
        throw IllegalStateException("$operation: ${error.message}", error)
    }

    private suspend fun abandonSessionClaim(taskId: String) {
        synchronized(stateLock) {
            if (runningTaskId == taskId) runningTaskId = null
            if (sessionTaskId == taskId) sessionTaskId = null
        }
        runCatching { runtime.closeTaskSession(taskId) }
    }

    private fun taskStream(taskId: String): String =
        requireNotNull(taskStreams[taskId]) { "PI_MOBILE_TASK_STREAM_MISSING" }

    private fun taskSession(taskId: String): String =
        requireNotNull(taskSessions[taskId]) { "PI_MOBILE_TASK_SESSION_MISSING" }

    private fun creationProgressFor(draftId: String): MutableStateFlow<TaskCreationProgress> =
        creationProgress.getOrPut(draftId) { MutableStateFlow(TaskCreationProgress.Idle) }

    private fun commandProgressFor(taskId: String): MutableStateFlow<TaskCommandProgress> =
        commandProgress.getOrPut(taskId) { MutableStateFlow(TaskCommandProgress.Idle) }

    private fun planStateFor(taskId: String): MutableStateFlow<PhoneLocalTaskPlanState> =
        planStates.getOrPut(taskId) { MutableStateFlow(PhoneLocalTaskPlanState()) }

    private fun updatePlanState(
        taskId: String,
        status: PiNativeOpenRouterScenarioStatus,
        actionPending: Boolean,
    ) {
        planStateFor(taskId).value = PhoneLocalTaskPlanState(
            enabled = status.planMode,
            activeToolNames = status.activeToolNames,
            prePlanActiveToolNames = status.prePlanActiveToolNames,
            latestPlan = status.latestPlan,
            actionPending = actionPending || status.planTransitionPending,
            error = status.commandError,
        )
    }

    private suspend fun desiredSkillResources(): EnabledSkillResourceSet =
        skillRepository?.enabledResourceSet() ?: EnabledSkillResourceSet(
            resources = emptyList(),
            digest = skillResourceSetDigest(emptyList()),
        )

    private suspend fun desiredExtensionPackages(): EnabledExtensionPackageSet =
        extensionPackageRepository?.enabledPackageSet() ?: EnabledExtensionPackageSet(
            packages = emptyList(),
            digest = extensionPackageSetDigest(emptyList()),
        )

    private suspend fun resolveSkillInvocation(
        command: SkillComposerInput.Skill,
    ): app.momoding.core.skills.SkillInvocationContext {
        val context = skillRepository?.invocationContext(command.name)
            ?: throw SkillCommandRejected("Skill '${command.name}' is not installed.")
        val record = context.requested
            ?: throw SkillCommandRejected("Skill '${command.name}' is not installed.")
        if (record.availability != SkillAvailability.AVAILABLE) {
            throw SkillCommandRejected("Skill '${command.name}' is unavailable on this phone.")
        }
        if (!record.enabled) {
            throw SkillCommandRejected("Enable Skill '${command.name}' in Extensions before using it.")
        }
        check(context.enabledResourceSet.resources.any { it.name == command.name }) {
            "PI_MOBILE_SKILL_ROOM_SNAPSHOT_MISMATCH"
        }
        return context
    }

    private fun requireSkillTaskMode(taskId: String) {
        val persisted = requireNotNull(projector.persistedSession(taskId)) {
            "PI_MOBILE_SESSION_SNAPSHOT_MISSING"
        }
        if (taskPlanStateFromEntries(persisted.snapshot.entries).enabled) {
            throw SkillCommandRejected("Turn off Plan mode before invoking a Skill.")
        }
        if (
            persisted.snapshot.goal?.state == "active" ||
            goalRepository?.current(taskId)?.state == PhoneLocalGoalState.ACTIVE
        ) {
            throw SkillCommandRejected("Pause or clear the active Goal before invoking a Skill.")
        }
    }

    private companion object {
        const val LOG_TAG = "PhoneLocalTask"
        const val GOAL_SCHEDULER_POLL_MILLIS = 2L
        const val STREAM_EVENT_BATCH_WINDOW_MILLIS = 32L
        const val STREAM_EVENT_BATCH_MAX = 32
    }
}

private class SkillCommandRejected(
    val safeMessage: String,
) : IllegalStateException("PI_MOBILE_SKILL_COMMAND_REJECTED")

private fun app.momoding.core.skills.SkillCommandInvalidReason.safeMessage(): String =
    when (this) {
        app.momoding.core.skills.SkillCommandInvalidReason.NAME_REQUIRED ->
            "Enter a Skill name after /skill:."
        app.momoding.core.skills.SkillCommandInvalidReason.NAME_INVALID ->
            "Skill names use lowercase letters, numbers, and single hyphens."
        app.momoding.core.skills.SkillCommandInvalidReason.INSTRUCTIONS_INVALID ->
            "The additional Skill instructions are too long or contain invalid text."
    }

private fun skillCommandFailureMessage(text: String?, error: Throwable): String? {
    (error as? SkillCommandRejected)?.let { return it.safeMessage }
    if (error is AttachmentPayloadException) {
        return "The selected images could not be prepared safely. Remove them or try again."
    }
    when {
        error.message?.contains("PI_MOBILE_IMAGE_MODEL_UNSUPPORTED") == true ->
            return "The selected model does not accept images. Choose an image-capable model in Settings."
        error.message?.contains("PI_MOBILE_IMAGE_MODEL_TOOLS_UNSUPPORTED") == true ->
            return "The selected model accepts images but cannot use the Agent tools required by Momoding. Choose another image-capable model in Settings."
        error.message?.contains("PI_MOBILE_IMAGE_MODEL_CAPABILITY_UNKNOWN") == true ->
            return "Momoding could not verify image support for the selected model. Your images were not sent."
        error.message?.contains("PI_MOBILE_IMAGE") == true ->
            return "The selected images could not be prepared safely. Remove them or try again."
    }
    val command = text?.let(::parseSkillComposerInput) as? SkillComposerInput.Skill ?: return null
    val code = error.message.orEmpty()
    return when {
        "BUSY" in code || "ALREADY_RUNNING" in code || "ANOTHER_TASK_IS_RUNNING" in code ->
            "Wait for the current on-device turn to settle before invoking Skill '${command.name}'."
        else -> "Skill '${command.name}' could not be prepared safely. The command was not sent."
    }
}

internal fun phoneLocalTaskCreationFailure(
    text: String?,
    error: Throwable,
): TaskCreationProgress.Failed {
    val errorCode = error.message.orEmpty()
    val sessionBusy = error is IllegalStateException && errorCode in setOf(
        "PI_MOBILE_ANOTHER_TASK_IS_RUNNING",
        "PI_MOBILE_TASK_SESSION_BUSY",
    )
    return TaskCreationProgress.Failed(
        stage = TaskCreationStage.CREATE,
        code = if (sessionBusy) WireErrorCode.SESSION_BUSY else WireErrorCode.RECOVERY_REQUIRED,
        retryable = error !is SkillCommandRejected,
        safeMessage = skillCommandFailureMessage(text, error) ?: if (sessionBusy) {
            "Another on-device task is still running or waiting for you. Finish or stop it, then retry."
        } else {
            null
        },
    )
}

private fun attachmentReferences(entries: JsonArray): Set<String> {
    return imageAttachmentReferences(entries) + textAttachmentReferences(entries)
}

private fun textAttachmentReferences(entries: JsonArray): Set<String> {
    val references = linkedSetOf<String>()
    val controls = mutableMapOf<String, Set<String>>()
    entries.forEach { entry ->
        val value = entry as? JsonObject ?: return@forEach
        when ((value["type"] as? JsonPrimitive)?.contentOrNull) {
            "custom" -> {
                if (
                    (value["customType"] as? JsonPrimitive)?.contentOrNull !=
                    "pi_mobile_text_attachments"
                ) return@forEach
                val controlId = (value["id"] as? JsonPrimitive)?.contentOrNull
                    ?.takeIf(String::isNotBlank)
                    ?: return@forEach
                val data = value["data"] as? JsonObject ?: return@forEach
                val attachments = data["attachments"] as? JsonArray ?: return@forEach
                val ids = attachments.mapNotNull { item ->
                    ((item as? JsonObject)?.get("attachmentId") as? JsonPrimitive)
                        ?.contentOrNull
                        ?.takeIf(ATTACHMENT_ID::matches)
                }.toSet()
                if (ids.isNotEmpty()) controls[controlId] = ids
            }
            "message" -> {
                val parentId = (value["parentId"] as? JsonPrimitive)?.contentOrNull
                    ?: return@forEach
                val message = value["message"] as? JsonObject ?: return@forEach
                if ((message["role"] as? JsonPrimitive)?.contentOrNull != "user") return@forEach
                val content = message["content"] as? JsonArray ?: return@forEach
                val text = content.mapNotNull { item ->
                    val block = item as? JsonObject ?: return@mapNotNull null
                    if ((block["type"] as? JsonPrimitive)?.contentOrNull == "text") {
                        (block["text"] as? JsonPrimitive)?.contentOrNull
                    } else null
                }.joinToString("")
                if (
                    text.lineSequence().firstOrNull() ==
                    "[momoding:text-attachments control=$parentId]"
                ) {
                    controls[parentId]?.let(references::addAll)
                }
            }
        }
    }
    return references
}

private fun imageAttachmentReferences(entries: JsonArray): Set<String> {
    val references = linkedSetOf<String>()
    fun visit(element: JsonElement) {
        when (element) {
            is JsonArray -> element.forEach(::visit)
            is JsonObject -> {
                if ((element["type"] as? JsonPrimitive)?.contentOrNull == "image") {
                    val data = (element["data"] as? JsonPrimitive)?.contentOrNull
                    val match = data?.let(ATTACHMENT_REFERENCE::matchEntire)
                    if (match != null) references += match.groupValues[1]
                }
                element.values.forEach(::visit)
            }
            else -> Unit
        }
    }
    visit(entries)
    return references
}

private val ATTACHMENT_REFERENCE = Regex(
    "^attachment:([0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12})$",
)
private val ATTACHMENT_ID = Regex(
    "^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$",
)

private val EMPTY_RUNTIME_ATTACHMENTS = RuntimeAttachmentBatch(
    images = emptyList(),
    textFiles = emptyList(),
    attachmentIds = emptyList(),
)
