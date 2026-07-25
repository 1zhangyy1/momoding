package app.momoding.feature.filechanges

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import app.momoding.core.data.FileChangeRecordState
import app.momoding.core.data.FileChangeRepository
import app.momoding.core.runtime.local.PhoneLocalAttentionBridge
import app.momoding.core.transport.AndroidSecureTransportRuntime
import app.momoding.core.transport.AttentionUserDecision
import app.momoding.core.transport.SecureTransportUiPhase
import app.momoding.core.transport.SecureTransportUiStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class FileChangeViewModel internal constructor(
    private val taskId: String,
    private val commitCallId: String?,
    repository: FileChangeRepository,
    transportStatus: StateFlow<SecureTransportUiStatus>,
    private val submitDecision: suspend (AttentionUserDecision) -> Unit,
) : ViewModel() {
    private val mutableState = MutableStateFlow(FileChangeUiState(taskId = taskId))
    val state: StateFlow<FileChangeUiState> = mutableState.asStateFlow()

    init {
        viewModelScope.launch {
            repository.observe(taskId, commitCallId).collect { source ->
                mutableState.value = when (source) {
                    FileChangeRecordState.Missing -> mutableState.value.copy(
                        loadState = FileChangeLoadState.MISSING,
                        record = null,
                    )
                    FileChangeRecordState.Corrupt -> mutableState.value.copy(
                        loadState = FileChangeLoadState.CORRUPT,
                        record = null,
                    )
                    is FileChangeRecordState.Available -> mutableState.value.copy(
                        loadState = FileChangeLoadState.READY,
                        record = source.record,
                        nowMillis = System.currentTimeMillis(),
                    )
                }
            }
        }
        viewModelScope.launch {
            transportStatus.collect { status ->
                mutableState.value = mutableState.value.copy(
                    connected = status.phase == SecureTransportUiPhase.READY,
                    nowMillis = System.currentTimeMillis(),
                )
            }
        }
    }

    fun dispatch(action: FileChangeAction) {
        when (action) {
            FileChangeAction.Approve -> submit(approve = true)
            FileChangeAction.Reject -> submit(approve = false)
            FileChangeAction.ClearNotice -> mutableState.value =
                mutableState.value.copy(notice = null)
        }
    }

    private fun submit(approve: Boolean) {
        val current = mutableState.value
        if ((approve && !current.canApprove) || (!approve && !current.canReject)) return
        val callId = current.record?.commitCallId ?: return
        mutableState.value = current.copy(submitting = true, notice = null)
        viewModelScope.launch {
            try {
                submitDecision(
                    if (approve) {
                        AttentionUserDecision.ApproveFileChanges(callId)
                    } else {
                        AttentionUserDecision.RejectFileChanges(callId)
                    },
                )
                mutableState.value = mutableState.value.copy(
                    submitting = false,
                    nowMillis = System.currentTimeMillis(),
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                mutableState.value = mutableState.value.copy(
                    submitting = false,
                    notice = "The decision could not be completed. Check the connection and try again.",
                    nowMillis = System.currentTimeMillis(),
                )
            }
        }
    }

    class Factory(
        private val taskId: String,
        private val commitCallId: String?,
        private val repository: FileChangeRepository,
        private val runtime: AndroidSecureTransportRuntime,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(FileChangeViewModel::class.java))
            return FileChangeViewModel(
                taskId = taskId,
                commitCallId = commitCallId,
                repository = repository,
                transportStatus = runtime.uiStatus,
                submitDecision = runtime::submitAttentionDecision,
            ) as T
        }
    }

    class PhoneLocalFactory(
        private val taskId: String,
        private val commitCallId: String?,
        private val repository: FileChangeRepository,
        private val bridge: PhoneLocalAttentionBridge,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(FileChangeViewModel::class.java))
            return FileChangeViewModel(
                taskId = taskId,
                commitCallId = commitCallId,
                repository = repository,
                transportStatus = PHONE_LOCAL_READY,
                submitDecision = bridge::submitDecision,
            ) as T
        }
    }

    private companion object {
        val PHONE_LOCAL_READY = MutableStateFlow(
            SecureTransportUiStatus(SecureTransportUiPhase.READY),
        )
    }
}
