package app.momoding.feature.tasks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import app.momoding.core.data.TaskAttentionKind
import app.momoding.core.data.AttentionResponseState
import app.momoding.core.data.TaskListRow
import app.momoding.core.data.TaskFailureRecovery
import app.momoding.core.data.TaskRepository
import app.momoding.core.data.taskFailureForRunState
import app.momoding.core.transport.AndroidSecureTransportRuntime
import app.momoding.core.transport.SecureTransportUiPhase
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface TaskHomeOneShot {
    data class OpenTask(val taskId: String, val cached: Boolean) : TaskHomeOneShot
    data class OpenAttention(val taskId: String, val callId: String) : TaskHomeOneShot
    data class OpenProvider(val taskId: String) : TaskHomeOneShot
}

class TaskHomeViewModel internal constructor(
    private val repository: TaskRepository,
    private val transportStatus: StateFlow<app.momoding.core.transport.SecureTransportUiStatus>,
    private val synchronizeTaskListAction: suspend () -> Unit,
    private val retryConnectionAction: suspend () -> Unit,
    private val openTaskAction: suspend (String) -> Unit,
    private val managementEnabled: Boolean = false,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) : ViewModel() {
    private val query = MutableStateFlow("")
    private val searchVisible = MutableStateFlow(false)
    private val loadState = MutableStateFlow(TaskHomeLoadState.LOADING)
    private val archivedVisible = MutableStateFlow(false)
    private val managementDialog = MutableStateFlow<TaskManagementDialogUiModel?>(null)
    private val managementNotice = MutableStateFlow<String?>(null)
    private val oneShotChannel = Channel<TaskHomeOneShot>(Channel.BUFFERED)
    val oneShots: Flow<TaskHomeOneShot> = oneShotChannel.receiveAsFlow()

    constructor(
        repository: TaskRepository,
        runtime: AndroidSecureTransportRuntime,
        nowMillis: () -> Long = System::currentTimeMillis,
    ) : this(
        repository = repository,
        transportStatus = runtime.uiStatus,
        synchronizeTaskListAction = {
            runtime.synchronizeTaskList()
            Unit
        },
        retryConnectionAction = runtime::retryConnection,
        openTaskAction = { taskId ->
            runtime.openTask(taskId)
            Unit
        },
        managementEnabled = false,
        nowMillis = nowMillis,
    )

    constructor(
        repository: TaskRepository,
        phoneLocalModelId: String,
        nowMillis: () -> Long = System::currentTimeMillis,
    ) : this(
        repository = repository,
        transportStatus = MutableStateFlow(
            app.momoding.core.transport.SecureTransportUiStatus(
                phase = SecureTransportUiPhase.READY,
                hostAlias = "On-device · $phoneLocalModelId",
            ),
        ),
        synchronizeTaskListAction = {},
        retryConnectionAction = {},
        openTaskAction = {},
        managementEnabled = true,
        nowMillis = nowMillis,
    )

    init {
        viewModelScope.launch {
            transportStatus
                .map { it.phase }
                .distinctUntilChanged()
                .collectLatest { phase ->
                    when (phase) {
                        SecureTransportUiPhase.READY -> {
                            loadState.value = TaskHomeLoadState.LOADING
                            try {
                                synchronizeTaskListAction()
                                loadState.value = TaskHomeLoadState.READY
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                loadState.value = TaskHomeLoadState.FAILED
                            }
                        }
                        SecureTransportUiPhase.RECONNECTING,
                        SecureTransportUiPhase.OFFLINE,
                        SecureTransportUiPhase.UNPAIRED,
                        SecureTransportUiPhase.HOST_REVOKED,
                        SecureTransportUiPhase.CREDENTIAL_LOST,
                        -> loadState.value = TaskHomeLoadState.READY
                        else -> loadState.value = TaskHomeLoadState.LOADING
                    }
                }
        }
    }

    private val controls = combine(
        query,
        searchVisible,
        archivedVisible,
        managementDialog,
        managementNotice,
    ) { currentQuery, showSearch, showArchived, dialog, notice ->
        TaskHomeControls(currentQuery, showSearch, showArchived, dialog, notice)
    }

    val state = combine(
        repository.observeTaskRows().catch {
            loadState.value = TaskHomeLoadState.FAILED
            emit(emptyList())
        },
        transportStatus,
        loadState,
        controls,
    ) { rows, transport, currentLoadState, currentControls ->
        projectTaskHomeUiState(
            rows = rows,
            transport = transport,
            controls = currentControls,
            loadState = currentLoadState,
            managementEnabled = managementEnabled,
            nowMillis = nowMillis(),
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        TaskHomeUiState(),
    )

    fun dispatch(action: TaskHomeAction) {
        when (action) {
            TaskHomeAction.Search -> searchVisible.value = !searchVisible.value
            is TaskHomeAction.EditSearch -> query.value = action.query
            TaskHomeAction.ClearSearch -> query.value = ""
            TaskHomeAction.RetryConnection -> viewModelScope.launch { retryConnectionAction() }
            is TaskHomeAction.OpenTask -> openTask(action.taskId, cached = false)
            is TaskHomeAction.OpenCachedTask -> openTask(action.taskId, cached = true)
            is TaskHomeAction.OpenAttention -> openAttention(action.taskId, action.callId)
            is TaskHomeAction.FixProvider -> if (managementEnabled && action.taskId.isNotBlank()) {
                viewModelScope.launch { oneShotChannel.send(TaskHomeOneShot.OpenProvider(action.taskId)) }
            }
            TaskHomeAction.ToggleArchived -> if (managementEnabled) {
                archivedVisible.value = !archivedVisible.value
                managementDialog.value = null
            }
            is TaskHomeAction.RequestRename -> if (managementEnabled) {
                managementDialog.value = TaskManagementDialogUiModel(
                    kind = TaskManagementDialogKind.RENAME,
                    taskId = action.taskId,
                    taskTitle = action.title,
                )
            }
            is TaskHomeAction.EditRename -> managementDialog.value
                ?.takeIf { it.kind == TaskManagementDialogKind.RENAME && !it.busy }
                ?.let { managementDialog.value = it.copy(draftTitle = action.title, error = null) }
            TaskHomeAction.CancelManagementDialog -> if (managementDialog.value?.busy != true) {
                managementDialog.value = null
            }
            TaskHomeAction.ConfirmRename -> confirmRename()
            is TaskHomeAction.SetPinned -> manage("Couldn’t update the pinned task.") {
                repository.setPinned(action.taskId, action.pinned)
            }
            is TaskHomeAction.Archive -> manage("Couldn’t archive the task.") {
                repository.archive(action.taskId)
            }
            is TaskHomeAction.Restore -> manage("Couldn’t restore the task.") {
                repository.restore(action.taskId)
            }
            is TaskHomeAction.RequestDelete -> if (managementEnabled) {
                managementDialog.value = TaskManagementDialogUiModel(
                    kind = TaskManagementDialogKind.DELETE,
                    taskId = action.taskId,
                    taskTitle = action.title,
                )
            }
            TaskHomeAction.ConfirmDelete -> confirmDelete()
            TaskHomeAction.NewTask,
            TaskHomeAction.PairHost,
            TaskHomeAction.OpenSettings,
            -> Unit
        }
    }

    private fun confirmRename() {
        val dialog = managementDialog.value
            ?.takeIf { it.kind == TaskManagementDialogKind.RENAME && !it.busy }
            ?: return
        managementDialog.value = dialog.copy(busy = true, error = null)
        viewModelScope.launch {
            runCatching { repository.rename(dialog.taskId, dialog.draftTitle) }
                .onSuccess {
                    managementDialog.value = null
                    managementNotice.value = "Task renamed."
                }
                .onFailure { error ->
                    managementDialog.value = dialog.copy(
                        busy = false,
                        error = error.message ?: "Couldn’t rename the task.",
                    )
                }
        }
    }

    private fun confirmDelete() {
        val dialog = managementDialog.value
            ?.takeIf { it.kind == TaskManagementDialogKind.DELETE && !it.busy }
            ?: return
        managementDialog.value = dialog.copy(busy = true, error = null)
        viewModelScope.launch {
            runCatching { repository.deleteArchived(dialog.taskId) }
                .onSuccess {
                    managementDialog.value = null
                    managementNotice.value = "Task permanently deleted."
                }
                .onFailure { error ->
                    managementDialog.value = dialog.copy(
                        busy = false,
                        error = error.message ?: "Couldn’t delete the task.",
                    )
                }
        }
    }

    private fun manage(failureNotice: String, action: suspend () -> Unit) {
        if (!managementEnabled) return
        viewModelScope.launch {
            managementNotice.value = null
            runCatching { action() }
                .onFailure { managementNotice.value = it.message ?: failureNotice }
        }
    }

    private fun openTask(taskId: String, cached: Boolean) {
        viewModelScope.launch {
            if (!repository.markRead(taskId)) return@launch
            oneShotChannel.send(TaskHomeOneShot.OpenTask(taskId, cached))
            runCatching { openTaskAction(taskId) }
        }
    }

    private fun openAttention(taskId: String, callId: String?) {
        viewModelScope.launch {
            if (!repository.markRead(taskId)) return@launch
            val exactCallId = callId?.takeIf(String::isNotBlank)
            if (exactCallId == null) {
                oneShotChannel.send(TaskHomeOneShot.OpenTask(taskId, cached = false))
            } else {
                oneShotChannel.send(TaskHomeOneShot.OpenAttention(taskId, exactCallId))
            }
            runCatching { openTaskAction(taskId) }
        }
    }

    class Factory(
        private val repository: TaskRepository,
        private val runtime: AndroidSecureTransportRuntime,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(TaskHomeViewModel::class.java))
            return TaskHomeViewModel(repository, runtime) as T
        }
    }

    class PhoneLocalFactory(
        private val repository: TaskRepository,
        private val modelId: String,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(TaskHomeViewModel::class.java))
            return TaskHomeViewModel(repository, modelId) as T
        }
    }
}

internal fun SecureTransportUiPhase.toTaskHomeConnection(): TaskHomeConnectionState = when (this) {
    SecureTransportUiPhase.READY -> TaskHomeConnectionState.CONNECTED
    SecureTransportUiPhase.STARTING,
    SecureTransportUiPhase.CONNECTING,
    SecureTransportUiPhase.AUTHENTICATING,
    SecureTransportUiPhase.SYNCHRONIZING,
    SecureTransportUiPhase.PAIRING,
    -> TaskHomeConnectionState.CONNECTING
    SecureTransportUiPhase.RECONNECTING -> TaskHomeConnectionState.RECONNECTING
    SecureTransportUiPhase.OFFLINE -> TaskHomeConnectionState.OFFLINE
    SecureTransportUiPhase.UNPAIRED,
    SecureTransportUiPhase.HOST_REVOKED,
    SecureTransportUiPhase.CREDENTIAL_LOST,
    -> TaskHomeConnectionState.UNPAIRED
    SecureTransportUiPhase.VERSION_MISMATCH,
    SecureTransportUiPhase.ERROR,
    -> TaskHomeConnectionState.ERROR
}

internal fun projectTaskHomeUiState(
    rows: List<TaskListRow>,
    transport: app.momoding.core.transport.SecureTransportUiStatus,
    controls: TaskHomeControls,
    loadState: TaskHomeLoadState,
    managementEnabled: Boolean,
    nowMillis: Long,
): TaskHomeUiState {
    val connection = transport.phase.toTaskHomeConnection()
    val visibleRows = rows.filter {
        if (controls.archivedVisible) it.archivedAtMillis != null else it.archivedAtMillis == null
    }
    val filtered = if (controls.query.isBlank()) {
        visibleRows
    } else {
        visibleRows.filter { it.title.contains(controls.query, ignoreCase = true) }
    }
    return TaskHomeUiState(
        hostAlias = transport.hostAlias ?: "Self-hosted Pi Host",
        connection = connection,
        loadState = loadState,
        sections = filtered.toSections(
            nowMillis = nowMillis,
            cached = connection in setOf(TaskHomeConnectionState.OFFLINE, TaskHomeConnectionState.RECONNECTING),
            archivedVisible = controls.archivedVisible,
        ),
        query = controls.query,
        searchVisible = controls.searchVisible,
        notice = controls.notice ?: if (loadState == TaskHomeLoadState.FAILED && filtered.isNotEmpty()) {
            "Couldn’t refresh from the Host. Showing local task history."
        } else {
            null
        },
        archivedVisible = controls.archivedVisible,
        managementEnabled = managementEnabled,
        managementDialog = controls.dialog,
    )
}

internal fun projectTaskHomeUiState(
    rows: List<TaskListRow>,
    transport: app.momoding.core.transport.SecureTransportUiStatus,
    query: String,
    searchVisible: Boolean,
    loadState: TaskHomeLoadState,
    nowMillis: Long,
): TaskHomeUiState = projectTaskHomeUiState(
    rows = rows,
    transport = transport,
    controls = TaskHomeControls(query, searchVisible, false, null, null),
    loadState = loadState,
    managementEnabled = false,
    nowMillis = nowMillis,
)

internal data class TaskHomeControls(
    val query: String,
    val searchVisible: Boolean,
    val archivedVisible: Boolean,
    val dialog: TaskManagementDialogUiModel?,
    val notice: String?,
)

private fun List<TaskListRow>.toSections(
    nowMillis: Long,
    cached: Boolean,
    archivedVisible: Boolean,
): List<TaskSectionUiModel> {
    val models = map { it.toUiModel(nowMillis, cached) }
    if (archivedVisible) {
        return listOf(
            TaskSectionUiModel(
                "Archived",
                models.sortedByDescending { row ->
                    firstOrNull { it.taskId == row.taskId }?.archivedAtMillis ?: 0L
                },
                showCount = false,
            ),
        ).filter { it.rows.isNotEmpty() }
    }
    val pinned = models.filter(TaskRowUiModel::pinned).sortedByDescending { row ->
        firstOrNull { it.taskId == row.taskId }?.pinnedAtMillis ?: 0L
    }
    val unpinned = models.filterNot(TaskRowUiModel::pinned)
    val attention = unpinned.filter { it.status == TaskRowStatus.ATTENTION }
    val running = unpinned.filter { it.status == TaskRowStatus.RUNNING }
    val failed = unpinned.filter { it.status == TaskRowStatus.FAILED }
    val recent = unpinned.filter { it.status == TaskRowStatus.COMPLETED }
    return buildList {
        if (pinned.isNotEmpty()) add(TaskSectionUiModel("Pinned", pinned, showCount = false))
        if (attention.isNotEmpty()) add(TaskSectionUiModel("Needs attention", attention))
        if (running.isNotEmpty()) add(TaskSectionUiModel("Running", running))
        if (failed.isNotEmpty()) add(TaskSectionUiModel("Failed", failed))
        if (recent.isNotEmpty()) add(TaskSectionUiModel("Recent", recent, showCount = false))
    }
}

private fun TaskListRow.toUiModel(nowMillis: Long, cached: Boolean): TaskRowUiModel {
    val status = when {
        attentionKind != TaskAttentionKind.NONE -> TaskRowStatus.ATTENTION
        runState in setOf("STARTING", "RUNNING", "WAITING", "STOPPING") -> TaskRowStatus.RUNNING
        runState in setOf("FAILED", "INTERRUPTED") -> TaskRowStatus.FAILED
        else -> TaskRowStatus.COMPLETED
    }
    val typedFailure = failure ?: taskFailureForRunState(runState)
    val detail = when (status) {
        TaskRowStatus.ATTENTION -> if (
            primaryAttentionResponseState == AttentionResponseState.RESPONDING
        ) {
            "Response saved; waiting for Momoding"
        } else {
            when (attentionKind) {
                TaskAttentionKind.QUESTION -> "Momoding asked a question"
                TaskAttentionKind.CONFIRMATION -> "An action needs approval"
                TaskAttentionKind.FILE_CONTENT -> "File content access needs approval"
                TaskAttentionKind.MULTIPLE -> "$attentionCount items need your attention"
                TaskAttentionKind.UNSUPPORTED -> "Momoding is waiting for you"
                TaskAttentionKind.NONE -> "Momoding is waiting for you"
            }
        }
        TaskRowStatus.RUNNING -> when (runState) {
            "STARTING" -> "Starting Momoding"
            "WAITING" -> "Waiting for the next step"
            "STOPPING" -> "Stopping safely"
            else -> "Working on this task"
        }
        TaskRowStatus.FAILED -> requireNotNull(typedFailure).homeDetail
        TaskRowStatus.COMPLETED -> "Task completed"
    }
    return TaskRowUiModel(
        taskId = taskId,
        title = title.ifBlank { "Untitled task" },
        detail = detail,
        ageLabel = relativeAge(updatedAtMillis, nowMillis),
        status = status,
        unread = isUnread,
        attentionKind = attentionKind.takeUnless { it == TaskAttentionKind.NONE }?.name?.lowercase(Locale.ROOT),
        attentionCallId = primaryAttentionCallId,
        openAction = if (
            status == TaskRowStatus.ATTENTION &&
            !primaryAttentionCallId.isNullOrBlank()
        ) {
            TaskRowOpenAction.OPEN_ATTENTION
        } else if (cached) {
            TaskRowOpenAction.OPEN_CACHED_TASK
        } else {
            TaskRowOpenAction.OPEN_TASK
        },
        pinned = pinnedAtMillis != null,
        archived = archivedAtMillis != null,
        recoveryAction = typedFailure?.let {
            when (it.recovery) {
                TaskFailureRecovery.FIX_PROVIDER -> TaskRowRecoveryAction.FIX_PROVIDER
                TaskFailureRecovery.RETRY,
                TaskFailureRecovery.OPEN_TASK,
                -> TaskRowRecoveryAction.OPEN_TASK
            }
        },
    )
}

private fun relativeAge(updatedAtMillis: Long, nowMillis: Long): String {
    val elapsed = (nowMillis - updatedAtMillis).coerceAtLeast(0)
    return when {
        elapsed < 60_000 -> "now"
        elapsed < 3_600_000 -> "${elapsed / 60_000}m"
        elapsed < 86_400_000 -> "${elapsed / 3_600_000}h"
        else -> "${elapsed / 86_400_000}d"
    }
}
