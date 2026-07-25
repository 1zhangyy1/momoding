package app.momoding.feature.filechanges

import app.momoding.core.data.FileChangeRecord
import app.momoding.core.files.FileChangeSetState

enum class FileChangeLoadState {
    LOADING,
    READY,
    MISSING,
    CORRUPT,
}

data class FileChangeUiState(
    val taskId: String,
    val loadState: FileChangeLoadState = FileChangeLoadState.LOADING,
    val record: FileChangeRecord? = null,
    val connected: Boolean = false,
    val submitting: Boolean = false,
    val notice: String? = null,
    val nowMillis: Long = System.currentTimeMillis(),
) {
    val canApprove: Boolean
        get() = loadState == FileChangeLoadState.READY &&
            connected &&
            !submitting &&
            nowMillis < requireNotNull(record).expiresAtMillis &&
            record.state == FileChangeSetState.AWAITING_APPROVAL

    val canReject: Boolean
        get() = canApprove
}

sealed interface FileChangeAction {
    data object Approve : FileChangeAction
    data object Reject : FileChangeAction
    data object ClearNotice : FileChangeAction
}
