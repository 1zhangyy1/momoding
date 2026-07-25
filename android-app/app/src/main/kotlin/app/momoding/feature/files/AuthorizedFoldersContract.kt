package app.momoding.feature.files

import app.momoding.core.files.AuthorizedDocumentMetadata
import app.momoding.core.files.AuthorizedFolderSummary

data class AuthorizedFoldersUiState(
    val loading: Boolean = true,
    val busyAction: String? = null,
    val folders: List<AuthorizedFolderSummary> = emptyList(),
    val selectedGrantId: String? = null,
    val documents: List<AuthorizedDocumentMetadata> = emptyList(),
    val listingTruncated: Boolean = false,
    val truncationReasons: Set<String> = emptySet(),
    val pendingRevokeGrantId: String? = null,
    val notice: String? = null,
)

sealed interface AuthorizedFoldersAction {
    data object Back : AuthorizedFoldersAction
    data object AddFolder : AuthorizedFoldersAction
    data class PickerFinished(
        val treeUri: String?,
        val resultFlags: Int,
    ) : AuthorizedFoldersAction
    data object Refresh : AuthorizedFoldersAction
    data class OpenFolder(val grantId: String) : AuthorizedFoldersAction
    data class RequestRevoke(val grantId: String) : AuthorizedFoldersAction
    data object CancelRevoke : AuthorizedFoldersAction
    data object ConfirmRevoke : AuthorizedFoldersAction
    data object ClearNotice : AuthorizedFoldersAction
}

sealed interface AuthorizedFoldersOneShot {
    data object Back : AuthorizedFoldersOneShot
    data object LaunchSystemTreePicker : AuthorizedFoldersOneShot
}
