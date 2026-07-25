package app.momoding.feature.taskdetail

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import app.momoding.core.attachments.TaskAttachmentGateway
import app.momoding.wire.WireErrorCode
import app.momoding.core.data.TaskDetailRepository
import app.momoding.core.data.TaskDetailSnapshot
import app.momoding.core.transport.AndroidSecureTransportRuntime
import app.momoding.core.transport.ExactWireOutcome
import app.momoding.core.transport.SecureTransportUiPhase
import app.momoding.core.transport.SecureTransportUiStatus
import app.momoding.core.transport.TaskOpenSuccess
import app.momoding.core.transport.TaskReplayProgress
import app.momoding.feature.tasks.PhoneLocalTaskCoordinator
import app.momoding.core.runtime.local.PhoneLocalTaskPlanState
import app.momoding.core.policy.TaskApprovalMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class TaskDetailViewModel internal constructor(
    private val taskId: String,
    private val repository: TaskDetailRepository,
    private val transportStatus: StateFlow<SecureTransportUiStatus>,
    private val replayProgress: StateFlow<Map<String, TaskReplayProgress>>,
    private val openTaskAction: suspend (String) -> ExactWireOutcome<TaskOpenSuccess>,
    private val retryConnectionAction: suspend () -> Unit,
    private val coordinator: TaskCommandCoordinator? = null,
    private val phoneLocalCoordinator: PhoneLocalTaskCoordinator? = null,
    private val savedStateHandle: SavedStateHandle,
    private val reducer: PiUiReducer = PiUiReducer(),
    private val phoneLocal: Boolean = false,
    private val taskAttachments: TaskAttachmentGateway? = null,
    private val imageAttachmentInputEnabled: Boolean = false,
    private val textFileAttachmentInputEnabled: Boolean = false,
    private val approvalModePersistence: suspend (TaskApprovalMode) -> Unit = { mode ->
        repository.updateApprovalMode(taskId, mode)
    },
) : ViewModel() {
    private val mutableState = MutableStateFlow(
        TaskDetailUiState(
            taskId = taskId,
            hostAlias = if (phoneLocal) "this phone" else "Self-hosted Pi Host",
            runningMode = RunningComposerMode.FOLLOW_UP,
            composer = TaskDetailSavedStateCodec.restoreComposer(savedStateHandle),
            phoneLocal = phoneLocal,
            imageAttachmentInputEnabled = imageAttachmentInputEnabled,
            textFileAttachmentInputEnabled = textFileAttachmentInputEnabled,
        ),
    )
    val state: StateFlow<TaskDetailUiState> = mutableState.asStateFlow()
    private val mutableOneShots = MutableSharedFlow<TaskDetailOneShot>(extraBufferCapacity = 8)
    val oneShots: SharedFlow<TaskDetailOneShot> = mutableOneShots.asSharedFlow()

    private var snapshot: TaskDetailSnapshot? = null
    private var projection: PiUiProjection? = null
    private var transport = transportStatus.value
    private var replay = replayProgress.value[taskId]
    private var commandProgress: TaskCommandProgress = TaskCommandProgress.Idle
    private var repositoryFailed = false
    private var openResolution = TaskOpenResolution.PENDING
    private var acknowledgedCompletion: String? = null
    private var phoneLocalPlan = PhoneLocalTaskPlanState()
    private val consumedAttentionReturnEffects = mutableSetOf<String>()

    constructor(
        taskId: String,
        repository: TaskDetailRepository,
        runtime: AndroidSecureTransportRuntime,
        coordinator: TaskCommandCoordinator,
        savedStateHandle: SavedStateHandle,
    ) : this(
        taskId = taskId,
        repository = repository,
        transportStatus = runtime.uiStatus,
        replayProgress = runtime.taskReplayProgress,
        openTaskAction = runtime::openTask,
        retryConnectionAction = runtime::retryConnection,
        coordinator = coordinator,
        savedStateHandle = savedStateHandle,
    )

    constructor(
        taskId: String,
        repository: TaskDetailRepository,
        coordinator: PhoneLocalTaskCoordinator,
        modelId: String,
        savedStateHandle: SavedStateHandle,
        taskAttachments: TaskAttachmentGateway? = null,
        imageAttachmentInputEnabled: Boolean = false,
        textFileAttachmentInputEnabled: Boolean = false,
    ) : this(
        taskId = taskId,
        repository = repository,
        transportStatus = MutableStateFlow(
            SecureTransportUiStatus(
                phase = SecureTransportUiPhase.READY,
                hostAlias = "this phone · $modelId",
            ),
        ),
        replayProgress = MutableStateFlow(emptyMap()),
        openTaskAction = { ExactWireOutcome.Success(TaskOpenSuccess(1)) },
        retryConnectionAction = {},
        phoneLocalCoordinator = coordinator,
        savedStateHandle = savedStateHandle,
        phoneLocal = true,
        taskAttachments = taskAttachments,
        imageAttachmentInputEnabled = imageAttachmentInputEnabled,
        textFileAttachmentInputEnabled = textFileAttachmentInputEnabled,
    )

    init {
        check((coordinator == null) != (phoneLocalCoordinator == null)) {
            "Task Detail requires exactly one task coordinator"
        }
        viewModelScope.launch {
            repository.observe(taskId)
                .catch {
                    repositoryFailed = true
                    rebuild()
                }
                .collect { value ->
                    snapshot = value
                    projection = value?.let(reducer::reduce)
                    rebuild()
                }
        }
        phoneLocalCoordinator?.let { localCoordinator ->
            viewModelScope.launch {
                localCoordinator.observePlan(taskId).collect { plan ->
                    phoneLocalPlan = plan
                    rebuild()
                }
            }
        }
        taskAttachments?.let { gateway ->
            viewModelScope.launch {
                gateway.observeTaskStagedAttachments(taskId).collect { records ->
                    val ui = records.map { record ->
                        TaskDetailAttachmentUiModel(
                            attachmentId = record.attachmentId,
                            displayName = record.displayName,
                            byteSize = record.byteSize,
                            kind = record.kind,
                            thumbnailPng = gateway.thumbnailPng(record.attachmentId),
                        )
                    }
                    mutableState.value = mutableState.value.copy(attachments = ui)
                    rebuild()
                }
            }
        }
        viewModelScope.launch {
            transportStatus.collect { status ->
                transport = status
                rebuild()
            }
        }
        viewModelScope.launch {
            replayProgress.collect { progress ->
                replay = progress[taskId]
                rebuild()
            }
        }
        viewModelScope.launch {
            val progressFlow = coordinator?.observe(taskId)
                ?: requireNotNull(phoneLocalCoordinator).observeCommands(taskId)
            progressFlow.collect { progress ->
                commandProgress = progress
                when (progress) {
                    is TaskCommandProgress.Completed -> {
                        if (acknowledgedCompletion != progress.commandId) {
                            acknowledgedCompletion = progress.commandId
                            if (progress.kind != TaskCommandKind.STOP) {
                                saveComposer(TextFieldValue())
                                mutableState.value = mutableState.value.copy(composer = TextFieldValue())
                            }
                            acknowledgeCommand(progress.commandId)
                        }
                    }
                    is TaskCommandProgress.Failed -> if (
                        progress.text != null && mutableState.value.composer.text.isBlank()
                    ) {
                        val restored = TextFieldValue(
                            progress.text,
                            TextRange(progress.text.length),
                        )
                        saveComposer(restored)
                        mutableState.value = mutableState.value.copy(
                            composer = restored,
                        )
                    }
                    else -> Unit
                }
                rebuild()
            }
        }
        viewModelScope.launch {
            transportStatus
                .map { it.phase }
                .distinctUntilChanged()
                .filter { it == SecureTransportUiPhase.READY }
                .collect {
                    try {
                        openResolution = when (val outcome = openTaskAction(taskId)) {
                            is ExactWireOutcome.Success -> TaskOpenResolution.ACCEPTED
                            is ExactWireOutcome.Failure -> if (outcome.error.code == WireErrorCode.TASK_NOT_FOUND) {
                                TaskOpenResolution.MISSING
                            } else {
                                TaskOpenResolution.ERROR
                            }
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        openResolution = TaskOpenResolution.ERROR
                    }
                    rebuild()
                }
        }
    }

    fun dispatch(action: TaskDetailAction) {
        when (action) {
            TaskDetailAction.Back -> mutableOneShots.tryEmit(TaskDetailOneShot.Back)
            is TaskDetailAction.EditComposer -> if (mutableState.value.canEditComposer) {
                saveComposer(action.value)
                mutableState.value = mutableState.value.copy(composer = action.value)
            }
            is TaskDetailAction.SelectRunningMode -> if (
                mutableState.value.composerMode in setOf(TaskComposerMode.STEER, TaskComposerMode.FOLLOW_UP)
            ) {
                TaskDetailSavedStateCodec.saveRunningMode(savedStateHandle, action.mode)
                mutableState.value = mutableState.value.copy(runningMode = action.mode)
                rebuild()
            }
            TaskDetailAction.Submit -> submit()
            TaskDetailAction.Stop -> if (mutableState.value.canStop) stopTask()
            TaskDetailAction.RetryConnection -> viewModelScope.launch { retryConnectionAction() }
            TaskDetailAction.FixProvider -> if (mutableState.value.providerRecoveryAvailable) {
                mutableOneShots.tryEmit(TaskDetailOneShot.OpenProvider)
            }
            TaskDetailAction.RetryOriginal -> retryOriginal()
            is TaskDetailAction.ToggleTool -> {
                val current = projection ?: return
                projection = current.copy(timeline = reducer.toggleTool(action.stableKey))
                rebuild()
            }
            is TaskDetailAction.ToggleChildAgent -> {
                mutableState.value = mutableState.value.copy(
                    childAgents = mutableState.value.childAgents.map { child ->
                        if (child.parentToolCallId == action.parentToolCallId) {
                            child.copy(expanded = !child.expanded)
                        } else {
                            child
                        }
                    },
                )
            }
            is TaskDetailAction.CancelChildAgent -> {
                val child = mutableState.value.childAgents.singleOrNull {
                    it.parentToolCallId == action.parentToolCallId
                }
                if (child?.state == TaskChildAgentState.RUNNING) {
                    phoneLocalCoordinator?.cancelChildAgent(taskId, action.parentToolCallId)
                }
            }
            TaskDetailAction.TogglePlanMode -> if (mutableState.value.canTogglePlanMode) {
                phoneLocalCoordinator?.setPlanMode(taskId, !mutableState.value.planMode)
            }
            is TaskDetailAction.SelectApprovalMode -> selectApprovalMode(action.mode)
            is TaskDetailAction.ImplementPlan -> if (
                mutableState.value.canImplementPlan &&
                mutableState.value.latestPlanDigest == action.planDigest
            ) {
                phoneLocalCoordinator?.implementPlan(taskId, action.planDigest)
            }
            TaskDetailAction.OpenCreateGoal -> if (mutableState.value.canCreateGoal) {
                mutableState.value = mutableState.value.copy(
                    goalEditorOpen = true,
                    goalEditorMode = GoalEditorMode.CREATE,
                    goalDraft = TextFieldValue(),
                )
            }
            TaskDetailAction.OpenEditGoal -> if (mutableState.value.canEditGoal) {
                val instruction = requireNotNull(mutableState.value.goal).instruction
                mutableState.value = mutableState.value.copy(
                    goalEditorOpen = true,
                    goalEditorMode = GoalEditorMode.EDIT,
                    goalDraft = TextFieldValue(instruction, TextRange(instruction.length)),
                )
            }
            TaskDetailAction.CloseGoalEditor -> mutableState.value = mutableState.value.copy(
                goalEditorOpen = false,
                goalDraft = TextFieldValue(),
            )
            is TaskDetailAction.EditGoalDraft -> if (mutableState.value.goalEditorOpen) {
                mutableState.value = mutableState.value.copy(goalDraft = action.value)
            }
            TaskDetailAction.SubmitGoal -> submitGoal()
            TaskDetailAction.PauseGoal -> if (mutableState.value.canPauseGoal) {
                phoneLocalCoordinator?.pauseGoal(taskId)
            }
            TaskDetailAction.ResumeGoal -> if (mutableState.value.canResumeGoal) {
                phoneLocalCoordinator?.resumeGoal(taskId)
            }
            is TaskDetailAction.ConfirmGoal -> {
                val allowed = when (action.action) {
                    GoalConfirmation.EDIT -> mutableState.value.canEditGoal
                    GoalConfirmation.CLEAR -> mutableState.value.canClearGoal
                }
                if (allowed) {
                    mutableState.value = mutableState.value.copy(goalConfirmation = action.action)
                }
            }
            TaskDetailAction.DismissGoalConfirmation -> mutableState.value =
                mutableState.value.copy(goalConfirmation = null)
            TaskDetailAction.ApplyGoalConfirmation -> applyGoalConfirmation()
            TaskDetailAction.OpenOutputs -> mutableOneShots.tryEmit(TaskDetailOneShot.OpenOutputs)
            TaskDetailAction.OpenDiff -> mutableOneShots.tryEmit(TaskDetailOneShot.OpenDiff)
            is TaskDetailAction.OpenAttention -> taskDetailAttentionOneShot(
                mutableState.value,
                action.callId,
            )?.let(mutableOneShots::tryEmit)
            TaskDetailAction.OpenAttachmentMenu -> if (
                (mutableState.value.attachmentInputEnabled || mutableState.value.phoneLocal) &&
                mutableState.value.canEditComposer &&
                !mutableState.value.attachmentImporting
            ) {
                mutableState.value = mutableState.value.copy(
                    attachmentMenuOpen = true,
                    attachmentError = null,
                )
            }
            TaskDetailAction.DismissAttachmentMenu -> mutableState.value =
                mutableState.value.copy(attachmentMenuOpen = false)
            is TaskDetailAction.ImportPhotos -> importPhotos(action.uris)
            is TaskDetailAction.ImportTextFile -> importTextFile(action.uri)
            is TaskDetailAction.RemoveAttachment -> removeAttachment(action.attachmentId)
        }
    }

    fun acceptAttentionReturn(effectId: String, callId: String, unavailable: Boolean) {
        if (effectId.isBlank() || callId.isBlank() || !consumedAttentionReturnEffects.add(effectId)) return
        mutableState.value = mutableState.value.copy(
            attentionNavigationNotice = TaskAttentionNavigationNotice.UNAVAILABLE.takeIf { unavailable },
        )
    }

    private fun submit() {
        val current = mutableState.value
        if (!current.canSubmit) return
        val kind = when (current.composerMode) {
            TaskComposerMode.PROMPT -> TaskCommandKind.PROMPT
            TaskComposerMode.STEER -> TaskCommandKind.STEER
            TaskComposerMode.FOLLOW_UP -> TaskCommandKind.FOLLOW_UP
            TaskComposerMode.BLOCKED -> return
        }
        submitTaskCommand(kind, current.composer.text)
    }

    private fun selectApprovalMode(mode: TaskApprovalMode) {
        val current = mutableState.value
        if (!current.canSelectApprovalMode || current.approvalMode == mode) return
        mutableState.value = current.copy(
            approvalMode = mode,
            approvalModeSaving = true,
            approvalModeError = null,
        )
        viewModelScope.launch {
            try {
                approvalModePersistence(mode)
                snapshot = snapshot?.copy(approvalMode = mode)
                mutableState.value = mutableState.value.copy(approvalModeSaving = false)
                if (mode == TaskApprovalMode.FULL_ACCESS) {
                    mutableOneShots.emit(TaskDetailOneShot.OpenFullAccessSetup)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.value = mutableState.value.copy(
                    approvalMode = current.approvalMode,
                    approvalModeSaving = false,
                    approvalModeError = "The approval mode could not be saved.",
                )
            }
        }
    }

    private fun importPhotos(uris: List<android.net.Uri>) {
        val gateway = taskAttachments ?: return
        val current = mutableState.value
        if (!current.imageAttachmentInputEnabled || !current.canEditComposer ||
            current.attachmentImporting || uris.isEmpty()
        ) return
        mutableState.value = current.copy(
            attachmentMenuOpen = false,
            attachmentImporting = true,
            attachmentError = null,
        )
        viewModelScope.launch {
            try {
                val result = gateway.importTaskPhotoPickerSelection(taskId, uris)
                mutableState.value = mutableState.value.copy(
                    attachmentImporting = false,
                    attachmentError = result.failures.firstOrNull()?.safeMessage,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                mutableState.value = mutableState.value.copy(
                    attachmentImporting = false,
                    attachmentError = "The selected image could not be saved on this phone.",
                )
            }
        }
    }

    private fun importTextFile(uri: android.net.Uri) {
        val gateway = taskAttachments ?: return
        val current = mutableState.value
        if (!current.textFileAttachmentInputEnabled || !current.canEditComposer ||
            current.attachmentImporting
        ) return
        mutableState.value = current.copy(
            attachmentMenuOpen = false,
            attachmentImporting = true,
            attachmentError = null,
        )
        viewModelScope.launch {
            try {
                val result = gateway.importTaskOpenDocument(taskId, uri)
                mutableState.value = mutableState.value.copy(
                    attachmentImporting = false,
                    attachmentError = result.failures.firstOrNull()?.safeMessage,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                mutableState.value = mutableState.value.copy(
                    attachmentImporting = false,
                    attachmentError = "The selected text file could not be saved on this phone.",
                )
            }
        }
    }

    private fun removeAttachment(attachmentId: String) {
        val gateway = taskAttachments ?: return
        val current = mutableState.value
        if (!current.canEditComposer || current.attachmentImporting) return
        viewModelScope.launch {
            try {
                if (!gateway.removeTaskStagedAttachment(taskId, attachmentId)) {
                    mutableState.value = mutableState.value.copy(
                        attachmentError = "The attachment is no longer staged for this message.",
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                mutableState.value = mutableState.value.copy(
                    attachmentError = "The attachment could not be removed.",
                )
            }
        }
    }

    private fun retryOriginal() {
        val current = mutableState.value
        if (!current.canRetryOriginal) return
        val text = requireNotNull(current.retryOriginalText)
        if (phoneLocalCoordinator != null) {
            phoneLocalCoordinator.retryOriginal(taskId, text)
        } else {
            submitTaskCommand(TaskCommandKind.PROMPT, text)
        }
    }

    private fun submitGoal() {
        val current = mutableState.value
        val instruction = current.goalDraft.text.trim()
        if (!current.goalEditorOpen || instruction.isEmpty() || instruction.length > 4096) return
        when (current.goalEditorMode) {
            GoalEditorMode.CREATE -> if (current.canCreateGoal) {
                if (phoneLocalCoordinator?.createGoal(taskId, instruction) == true) {
                    mutableState.value = current.copy(
                        goalEditorOpen = false,
                        goalDraft = TextFieldValue(),
                    )
                }
            }
            GoalEditorMode.EDIT -> if (current.canEditGoal) {
                mutableState.value = current.copy(
                    goalEditorOpen = false,
                    goalConfirmation = GoalConfirmation.EDIT,
                )
            }
        }
    }

    private fun applyGoalConfirmation() {
        val current = mutableState.value
        when (current.goalConfirmation) {
            GoalConfirmation.EDIT -> {
                val instruction = current.goalDraft.text.trim()
                if (current.canEditGoal && instruction.isNotEmpty() && instruction.length <= 4096) {
                    phoneLocalCoordinator?.editGoal(taskId, instruction)
                }
            }
            GoalConfirmation.CLEAR -> if (current.canClearGoal) {
                phoneLocalCoordinator?.clearGoal(taskId)
            }
            null -> return
        }
        mutableState.value = current.copy(
            goalConfirmation = null,
            goalDraft = TextFieldValue(),
        )
    }

    private fun rebuild() {
        mutableState.value = projectTaskDetailUiState(
            current = mutableState.value,
            snapshot = snapshot,
            projection = projection,
            transport = transport,
            replay = replay,
            commandProgress = commandProgress,
            repositoryFailed = repositoryFailed,
            openResolution = openResolution,
            phoneLocalPlan = phoneLocalPlan,
        )
    }

    private fun saveComposer(value: TextFieldValue) {
        TaskDetailSavedStateCodec.saveComposer(savedStateHandle, value)
    }

    private fun submitTaskCommand(kind: TaskCommandKind, text: String) {
        coordinator?.submit(taskId, kind, text)
            ?: requireNotNull(phoneLocalCoordinator).submit(taskId, kind, text)
    }

    private fun stopTask() {
        coordinator?.stop(taskId) ?: requireNotNull(phoneLocalCoordinator).stop(taskId)
    }

    private fun acknowledgeCommand(commandId: String) {
        coordinator?.acknowledge(taskId, commandId)
            ?: requireNotNull(phoneLocalCoordinator).acknowledge(taskId, commandId)
    }

    class Factory(
        private val taskId: String,
        private val repository: TaskDetailRepository,
        private val runtime: AndroidSecureTransportRuntime,
        private val coordinator: TaskCommandCoordinator,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
            require(modelClass.isAssignableFrom(TaskDetailViewModel::class.java))
            return TaskDetailViewModel(
                taskId = taskId,
                repository = repository,
                runtime = runtime,
                coordinator = coordinator,
                savedStateHandle = extras.createSavedStateHandle(),
            ) as T
        }
    }

    class PhoneLocalFactory(
        private val taskId: String,
        private val repository: TaskDetailRepository,
        private val coordinator: PhoneLocalTaskCoordinator,
        private val modelId: String,
        private val taskAttachments: TaskAttachmentGateway? = null,
        private val imageAttachmentInputEnabled: Boolean = false,
        private val textFileAttachmentInputEnabled: Boolean = false,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
            require(modelClass.isAssignableFrom(TaskDetailViewModel::class.java))
            return TaskDetailViewModel(
                taskId = taskId,
                repository = repository,
                coordinator = coordinator,
                modelId = modelId,
                savedStateHandle = extras.createSavedStateHandle(),
                taskAttachments = taskAttachments,
                imageAttachmentInputEnabled = imageAttachmentInputEnabled,
                textFileAttachmentInputEnabled = textFileAttachmentInputEnabled,
            ) as T
        }
    }

}

internal fun taskDetailAttentionOneShot(
    state: TaskDetailUiState,
    callId: String,
): TaskDetailOneShot.OpenAttention? = callId.takeIf {
    it.isNotBlank() && state.attention?.callId == it
}?.let { exactCallId ->
    TaskDetailOneShot.OpenAttention(
        callId = exactCallId,
        fileChanges = state.attention?.fileChanges == true,
    )
}

internal object TaskDetailSavedStateCodec {
    private const val COMPOSER_TEXT = "task-detail-composer-text"
    private const val SELECTION_START = "task-detail-selection-start"
    private const val SELECTION_END = "task-detail-selection-end"
    private const val RUNNING_MODE = "task-detail-running-mode"

    fun saveComposer(handle: SavedStateHandle, value: TextFieldValue) {
        handle[COMPOSER_TEXT] = value.text
        handle[SELECTION_START] = value.selection.start
        handle[SELECTION_END] = value.selection.end
    }

    fun restoreComposer(handle: SavedStateHandle): TextFieldValue {
        val text = handle.get<String>(COMPOSER_TEXT).orEmpty()
        val start = handle.get<Int>(SELECTION_START)?.coerceIn(0, text.length) ?: text.length
        val end = handle.get<Int>(SELECTION_END)?.coerceIn(0, text.length) ?: start
        return TextFieldValue(text, TextRange(start, end))
    }

    fun saveRunningMode(handle: SavedStateHandle, mode: RunningComposerMode) {
        handle[RUNNING_MODE] = mode.name
    }

    fun restoreRunningMode(handle: SavedStateHandle): RunningComposerMode = handle.get<String>(RUNNING_MODE)
        ?.let { value -> runCatching { RunningComposerMode.valueOf(value) }.getOrNull() }
        ?: RunningComposerMode.STEER
}

internal enum class TaskOpenResolution { PENDING, ACCEPTED, MISSING, ERROR }

internal fun projectTaskDetailUiState(
    current: TaskDetailUiState,
    snapshot: TaskDetailSnapshot?,
    projection: PiUiProjection?,
    transport: SecureTransportUiStatus,
    replay: TaskReplayProgress?,
    commandProgress: TaskCommandProgress,
    repositoryFailed: Boolean = false,
    openResolution: TaskOpenResolution = TaskOpenResolution.PENDING,
    phoneLocalPlan: PhoneLocalTaskPlanState = PhoneLocalTaskPlanState(),
): TaskDetailUiState {
    val loadState = when {
        repositoryFailed -> TaskDetailLoadState.ERROR
        snapshot != null -> TaskDetailLoadState.READY
        openResolution == TaskOpenResolution.MISSING -> TaskDetailLoadState.MISSING
        openResolution == TaskOpenResolution.ERROR -> TaskDetailLoadState.ERROR
        else -> TaskDetailLoadState.LOADING
    }
    val replaying = replay != null
    val runState = if (replaying) TaskDetailRunState.RECOVERING else projection?.runState ?: TaskDetailRunState.UNKNOWN
    val mode = composerMode(
        runState = runState,
        stopFenced = snapshot?.activeStopFence == true,
        attentionPending = projection?.attention != null,
    )
    val connection = transport.phase.toTaskDetailConnection()
    val blockedReason = when {
        snapshot?.activeStopFence == true -> if (current.phoneLocal) {
            "Waiting for Momoding to stop."
        } else {
            "Waiting for the Host to confirm that the task stopped."
        }
        mode == TaskComposerMode.BLOCKED -> when (runState) {
            TaskDetailRunState.STOPPING -> "The task is stopping."
            TaskDetailRunState.RECOVERING -> if (current.phoneLocal) {
                "Momoding is recovering this task."
            } else {
                "The Host is recovering this task."
            }
            else -> "Wait until the current task state is safe."
        }
        connection != TaskDetailConnectionState.CONNECTED -> if (current.phoneLocal) {
            "Momoding is unavailable."
        } else {
            "Reconnect to the Host to send a message."
        }
        else -> null
    }
    return current.copy(
        title = snapshot?.title ?: current.title,
        hostAlias = transport.hostAlias ?: current.hostAlias,
        loadState = loadState,
        connection = connection,
        runState = runState,
        timeline = applyChildAgentOverrides(
            projection?.timeline ?: current.timeline,
            snapshot?.childAgents.orEmpty(),
        ),
        queue = projection?.queue ?: emptyList(),
        attention = projection?.attention,
        composerMode = mode,
        command = commandProgress.toUiState(),
        recovery = if (replaying) {
            TaskRecoveryUiModel(TaskRecoveryKind.WIRE_REPLAY, requireNotNull(replay).replayedEventCount)
        } else {
            projection?.recovery
        },
        activeStopFence = snapshot?.activeStopFence == true,
        composerBlockedReason = blockedReason,
        planMode = current.phoneLocal && phoneLocalPlan.enabled,
        latestPlanDigest = phoneLocalPlan.latestPlan?.planDigest,
        planActionPending = current.phoneLocal && phoneLocalPlan.actionPending,
        planError = phoneLocalPlan.error,
        approvalMode = if (current.approvalModeSaving) {
            current.approvalMode
        } else {
            snapshot?.approvalMode ?: current.approvalMode
        },
        goal = snapshot?.goal?.takeUnless { it.state == TaskGoalState.CLEARED.name }?.let { goal ->
            TaskGoalUiModel(
                goalId = goal.goalId,
                instruction = goal.instruction,
                state = TaskGoalState.valueOf(goal.state),
                progressSummary = goal.progressSummary,
                progressMarker = goal.progressMarker,
                terminalReason = goal.terminalReason,
                pauseReason = goal.pauseReason,
                automaticTurnCount = goal.automaticTurnCount,
                generation = goal.generation,
            )
        },
        childAgents = snapshot?.childAgents.orEmpty().map { child ->
            TaskChildAgentUiModel(
                parentToolCallId = child.parentToolCallId,
                childId = child.childId,
                name = child.childName,
                instruction = child.instruction,
                state = TaskChildAgentState.valueOf(child.state),
                summary = child.resultSummary,
                result = child.resultText,
                resultTruncated = child.resultTruncated,
                terminalReason = child.terminalReason,
                stopReason = child.stopReason,
                model = child.model,
                turnCount = child.turnCount,
                inputTokens = child.inputTokens,
                outputTokens = child.outputTokens,
                cacheReadTokens = child.cacheReadTokens,
                cacheWriteTokens = child.cacheWriteTokens,
                contextTokens = child.contextTokens,
                costUsd = child.costUsd,
                eventCount = child.eventCount,
                expanded = current.childAgents.firstOrNull {
                    it.parentToolCallId == child.parentToolCallId
                }?.expanded == true,
            )
        },
    )
}

private fun applyChildAgentOverrides(
    timeline: TimelineWindow,
    children: List<app.momoding.core.data.TaskDetailChildAgentRecord>,
): TimelineWindow {
    if (children.isEmpty()) return timeline
    val byToolCall = children.associateBy { it.parentToolCallId }
    fun override(item: TimelineItem): TimelineItem {
        if (item !is TimelineItem.ToolActivity) return item
        val child = byToolCall[item.toolCallId] ?: return item
        val state = when (child.state) {
            "RUNNING" -> ToolActivityState.RUNNING
            "COMPLETED" -> ToolActivityState.SUCCESS
            "FAILED" -> ToolActivityState.FAILURE
            "CANCELLED" -> ToolActivityState.CANCELLED
            else -> error("PI_MOBILE_CHILD_STATE_INVALID")
        }
        val detail = when (child.state) {
            "RUNNING" -> "Child agent is working"
            "COMPLETED" -> child.resultSummary ?: "Child analysis completed"
            "FAILED" -> child.terminalReason ?: "Child analysis failed"
            "CANCELLED" -> child.terminalReason ?: "Child analysis cancelled"
            else -> error("PI_MOBILE_CHILD_STATE_INVALID")
        }
        return item.copy(
            title = "Delegated to ${child.childName}",
            detail = detail,
            state = state,
        )
    }
    return timeline.copy(
        settledItems = timeline.settledItems.map(::override),
        activeItem = timeline.activeItem?.let(::override),
    )
}

internal fun SecureTransportUiPhase.toTaskDetailConnection(): TaskDetailConnectionState = when (this) {
    SecureTransportUiPhase.READY -> TaskDetailConnectionState.CONNECTED
    SecureTransportUiPhase.STARTING,
    SecureTransportUiPhase.PAIRING,
    SecureTransportUiPhase.CONNECTING,
    SecureTransportUiPhase.AUTHENTICATING,
    SecureTransportUiPhase.RECONNECTING,
    -> TaskDetailConnectionState.RECONNECTING
    SecureTransportUiPhase.SYNCHRONIZING -> TaskDetailConnectionState.CONNECTED
    SecureTransportUiPhase.OFFLINE -> TaskDetailConnectionState.OFFLINE
    SecureTransportUiPhase.UNPAIRED,
    SecureTransportUiPhase.HOST_REVOKED,
    SecureTransportUiPhase.CREDENTIAL_LOST,
    -> TaskDetailConnectionState.UNPAIRED
    SecureTransportUiPhase.VERSION_MISMATCH,
    SecureTransportUiPhase.ERROR,
    -> TaskDetailConnectionState.ERROR
}

private fun composerMode(
    runState: TaskDetailRunState,
    stopFenced: Boolean,
    attentionPending: Boolean,
): TaskComposerMode {
    if (stopFenced || attentionPending) return TaskComposerMode.BLOCKED
    return when (runState) {
        TaskDetailRunState.RUNNING,
        -> TaskComposerMode.FOLLOW_UP
        TaskDetailRunState.STOPPED,
        TaskDetailRunState.SETTLED,
        TaskDetailRunState.FAILED,
        TaskDetailRunState.INTERRUPTED,
        -> TaskComposerMode.PROMPT
        TaskDetailRunState.STARTING,
        TaskDetailRunState.WAITING,
        TaskDetailRunState.RETRYING,
        TaskDetailRunState.COMPACTING,
        TaskDetailRunState.STOPPING,
        TaskDetailRunState.RECOVERING,
        TaskDetailRunState.UNKNOWN,
        -> TaskComposerMode.BLOCKED
    }
}

private fun TaskCommandProgress.toUiState(): TaskCommandUiState = when (this) {
    TaskCommandProgress.Idle,
    is TaskCommandProgress.Completed,
    -> TaskCommandUiState.Idle
    is TaskCommandProgress.Persisting -> TaskCommandUiState.Persisting(kind)
    is TaskCommandProgress.Working -> if (recovering) {
        TaskCommandUiState.Recovering(kind)
    } else {
        TaskCommandUiState.Sending(kind)
    }
    is TaskCommandProgress.Failed -> TaskCommandUiState.Failed(
        kind = kind,
        code = code.name,
        retryable = retryable,
        text = text,
        safeMessage = safeMessage,
    )
}

internal fun WireErrorCode.taskDetailMessage(): String = when (this) {
    WireErrorCode.SESSION_BUSY -> "The task changed before this message reached Pi."
    WireErrorCode.TASK_NOT_FOUND -> "The Host could not find this task."
    WireErrorCode.UNAUTHORIZED -> "This phone is no longer authorized by the Host."
    WireErrorCode.BAD_REQUEST -> "The Host rejected this task command."
    WireErrorCode.ABORTED -> "The command was stopped before it completed."
    else -> "The Host could not safely complete this command."
}
