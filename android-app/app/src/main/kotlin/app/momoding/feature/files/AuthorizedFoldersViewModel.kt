package app.momoding.feature.files

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import app.momoding.core.files.AuthorizedFoldersRepository
import java.io.FileNotFoundException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class AuthorizedFoldersViewModel(
    private val repository: AuthorizedFoldersRepository,
) : ViewModel() {
    private val mutableState = MutableStateFlow(AuthorizedFoldersUiState())
    val state: StateFlow<AuthorizedFoldersUiState> = mutableState.asStateFlow()

    private val mutableOneShots = MutableSharedFlow<AuthorizedFoldersOneShot>(extraBufferCapacity = 1)
    val oneShots: SharedFlow<AuthorizedFoldersOneShot> = mutableOneShots.asSharedFlow()

    init {
        refresh()
    }

    fun dispatch(action: AuthorizedFoldersAction) {
        when (action) {
            AuthorizedFoldersAction.Back -> mutableOneShots.tryEmit(AuthorizedFoldersOneShot.Back)
            AuthorizedFoldersAction.AddFolder -> mutableOneShots.tryEmit(
                AuthorizedFoldersOneShot.LaunchSystemTreePicker,
            )
            is AuthorizedFoldersAction.PickerFinished -> handlePickerResult(action)
            AuthorizedFoldersAction.Refresh -> refresh()
            is AuthorizedFoldersAction.OpenFolder -> openFolder(action.grantId)
            is AuthorizedFoldersAction.RequestRevoke -> mutableState.value =
                mutableState.value.copy(pendingRevokeGrantId = action.grantId)
            AuthorizedFoldersAction.CancelRevoke -> mutableState.value =
                mutableState.value.copy(pendingRevokeGrantId = null)
            AuthorizedFoldersAction.ConfirmRevoke -> confirmRevoke()
            AuthorizedFoldersAction.ClearNotice -> mutableState.value =
                mutableState.value.copy(notice = null)
        }
    }

    private fun handlePickerResult(action: AuthorizedFoldersAction.PickerFinished) {
        val treeUri = action.treeUri
        if (treeUri == null) {
            mutableState.value = mutableState.value.copy(notice = "Folder selection cancelled.")
            return
        }
        if (mutableState.value.busyAction != null) return
        viewModelScope.launch {
            mutableState.value = mutableState.value.copy(busyAction = "authorize", notice = null)
            try {
                val folder = repository.authorize(treeUri, action.resultFlags)
                val folders = repository.folders()
                mutableState.value = mutableState.value.copy(
                    loading = false,
                    busyAction = null,
                    folders = folders,
                    selectedGrantId = folder.grantId,
                    documents = emptyList(),
                    notice = "Folder authorized. After a task grant, only metadata and opaque aliases can be exposed to the Agent.",
                )
                openFolder(folder.grantId)
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                mutableState.value = mutableState.value.copy(
                    loading = false,
                    busyAction = null,
                    notice = publicFailure(error, "Could not authorize this folder."),
                )
            }
        }
    }

    private fun refresh() {
        if (mutableState.value.busyAction != null) return
        viewModelScope.launch {
            mutableState.value = mutableState.value.copy(loading = true, busyAction = "refresh")
            try {
                val folders = repository.folders()
                val selected = mutableState.value.selectedGrantId?.takeIf { grantId ->
                    folders.any { it.grantId == grantId }
                }
                mutableState.value = mutableState.value.copy(
                    loading = false,
                    busyAction = null,
                    folders = folders,
                    selectedGrantId = selected,
                    documents = if (selected == null) emptyList() else mutableState.value.documents,
                    notice = null,
                )
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                mutableState.value = mutableState.value.copy(
                    loading = false,
                    busyAction = null,
                    notice = publicFailure(error, "Could not refresh authorized folders."),
                )
            }
        }
    }

    private fun openFolder(grantId: String) {
        if (mutableState.value.busyAction != null) return
        viewModelScope.launch {
            mutableState.value = mutableState.value.copy(
                selectedGrantId = grantId,
                documents = emptyList(),
                busyAction = "metadata",
                notice = null,
            )
            try {
                val listing = repository.metadata(grantId)
                mutableState.value = mutableState.value.copy(
                    busyAction = null,
                    documents = listing.documents,
                    listingTruncated = listing.truncated,
                    truncationReasons = listing.truncationReasons,
                )
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                mutableState.value = mutableState.value.copy(
                    busyAction = null,
                    documents = emptyList(),
                    notice = publicFailure(error, "Could not read folder metadata."),
                )
                refresh()
            }
        }
    }

    private fun confirmRevoke() {
        val grantId = mutableState.value.pendingRevokeGrantId ?: return
        if (mutableState.value.busyAction != null) return
        viewModelScope.launch {
            mutableState.value = mutableState.value.copy(
                pendingRevokeGrantId = null,
                busyAction = "revoke",
                notice = null,
            )
            try {
                repository.revoke(grantId)
                val folders = repository.folders()
                mutableState.value = mutableState.value.copy(
                    busyAction = null,
                    folders = folders,
                    selectedGrantId = mutableState.value.selectedGrantId?.takeIf { it != grantId },
                    documents = if (mutableState.value.selectedGrantId == grantId) {
                        emptyList()
                    } else {
                        mutableState.value.documents
                    },
                    notice = "Folder access removed from this phone.",
                )
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                mutableState.value = mutableState.value.copy(
                    busyAction = null,
                    notice = publicFailure(error, "Could not remove folder access."),
                )
            }
        }
    }

    private fun publicFailure(error: Throwable, fallback: String): String = when (error) {
        is SecurityException -> "$fallback Authorization is missing or expired."
        is FileNotFoundException -> "$fallback The Documents Provider is unavailable."
        is IllegalArgumentException -> "$fallback Android returned an invalid folder."
        else -> fallback
    }

    class Factory(
        private val repository: AuthorizedFoldersRepository,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(AuthorizedFoldersViewModel::class.java))
            return AuthorizedFoldersViewModel(repository) as T
        }
    }
}
