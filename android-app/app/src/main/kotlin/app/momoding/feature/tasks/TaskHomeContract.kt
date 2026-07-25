package app.momoding.feature.tasks

import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import app.momoding.feature.settings.contractAction

enum class TaskHomeConnectionState {
    CONNECTED,
    CONNECTING,
    RECONNECTING,
    OFFLINE,
    UNPAIRED,
    ERROR,
}

enum class TaskHomeLoadState { LOADING, READY, FAILED }

enum class TaskRowStatus { ATTENTION, RUNNING, COMPLETED, FAILED }

enum class TaskRowOpenAction { OPEN_TASK, OPEN_CACHED_TASK, OPEN_ATTENTION }

data class TaskRowUiModel(
    val taskId: String,
    val title: String,
    val detail: String,
    val ageLabel: String,
    val status: TaskRowStatus,
    val unread: Boolean = false,
    val attentionKind: String? = null,
    val attentionCallId: String? = null,
    val openAction: TaskRowOpenAction = TaskRowOpenAction.OPEN_TASK,
    val pinned: Boolean = false,
    val archived: Boolean = false,
)

enum class TaskManagementDialogKind { RENAME, DELETE }

data class TaskManagementDialogUiModel(
    val kind: TaskManagementDialogKind,
    val taskId: String,
    val taskTitle: String,
    val draftTitle: String = taskTitle,
    val busy: Boolean = false,
    val error: String? = null,
)

data class TaskSectionUiModel(
    val title: String,
    val rows: List<TaskRowUiModel>,
    val showCount: Boolean = true,
)

data class TaskHomeUiState(
    val hostAlias: String = "Self-hosted Pi Host",
    val connection: TaskHomeConnectionState = TaskHomeConnectionState.CONNECTING,
    val loadState: TaskHomeLoadState = TaskHomeLoadState.LOADING,
    val sections: List<TaskSectionUiModel> = emptyList(),
    val query: String = "",
    val searchVisible: Boolean = false,
    val notice: String? = null,
    val archivedVisible: Boolean = false,
    val managementEnabled: Boolean = false,
    val managementDialog: TaskManagementDialogUiModel? = null,
) {
    val taskCount: Int get() = sections.sumOf { it.rows.size }
}

sealed interface TaskHomeAction {
    data object NewTask : TaskHomeAction
    data object Search : TaskHomeAction
    data class EditSearch(val query: String) : TaskHomeAction
    data object ClearSearch : TaskHomeAction
    data class OpenTask(val taskId: String) : TaskHomeAction
    data class OpenCachedTask(val taskId: String) : TaskHomeAction
    data class OpenAttention(val taskId: String, val callId: String?) : TaskHomeAction
    data class FixProvider(val taskId: String) : TaskHomeAction
    data object RetryConnection : TaskHomeAction
    data object PairHost : TaskHomeAction
    data object OpenSettings : TaskHomeAction
    data object ToggleArchived : TaskHomeAction
    data class RequestRename(val taskId: String, val title: String) : TaskHomeAction
    data class EditRename(val title: String) : TaskHomeAction
    data object CancelManagementDialog : TaskHomeAction
    data object ConfirmRename : TaskHomeAction
    data class SetPinned(val taskId: String, val pinned: Boolean) : TaskHomeAction
    data class Archive(val taskId: String) : TaskHomeAction
    data class Restore(val taskId: String) : TaskHomeAction
    data class RequestDelete(val taskId: String, val title: String) : TaskHomeAction
    data object ConfirmDelete : TaskHomeAction
}

enum class TaskHomeInteraction(val contractName: String) {
    OPEN_TASK("OpenTask"),
    OPEN_CACHED_TASK("OpenCachedTask"),
    OPEN_ATTENTION("OpenAttention"),
    FIX_PROVIDER("FixProvider"),
    NEW_TASK("NewTask"),
    SEARCH("Search"),
    EDIT_SEARCH("EditSearch"),
    CLEAR_SEARCH("ClearSearch"),
    RETRY_CONNECTION("RetryConnection"),
    PAIR_HOST("PairHost"),
    OPEN_SETTINGS("OpenSettings"),
    TOGGLE_ARCHIVED("ToggleArchived"),
    RENAME_TASK("RenameTask"),
    PIN_TASK("PinTask"),
    ARCHIVE_TASK("ArchiveTask"),
    RESTORE_TASK("RestoreTask"),
    DELETE_TASK("DeleteTask"),
}

fun Modifier.taskHomeContractAction(
    policy: TaskHomeInteractionPolicy,
    interaction: TaskHomeInteraction,
): Modifier = if (policy.allows(interaction)) {
    semantics { contractAction = interaction.contractName }
} else {
    this
}

data class TaskHomeInteractionPolicy(
    val allowed: Set<TaskHomeInteraction> = TaskHomeInteraction.entries.toSet(),
) {
    fun allows(interaction: TaskHomeInteraction): Boolean = interaction in allowed

    companion object {
        val All = TaskHomeInteractionPolicy()
    }
}
