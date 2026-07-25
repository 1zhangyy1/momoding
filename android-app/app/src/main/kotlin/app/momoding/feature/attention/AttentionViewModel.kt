package app.momoding.feature.attention

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import app.momoding.core.data.AttentionDataSource
import app.momoding.core.data.AttentionDraftWrite
import app.momoding.core.data.AttentionPrompt
import app.momoding.core.data.AttentionRecord
import app.momoding.core.data.AttentionRecordState
import app.momoding.core.data.AttentionRepository
import app.momoding.core.data.AttentionResponseState
import app.momoding.core.data.AttentionValidationCode
import app.momoding.core.transport.AndroidSecureTransportRuntime
import app.momoding.core.transport.AttentionUserDecision
import app.momoding.core.transport.SecureTransportUiPhase
import app.momoding.core.transport.SecureTransportUiStatus
import app.momoding.core.runtime.local.PhoneLocalAttentionBridge
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

class AttentionViewModel internal constructor(
    private val identity: AttentionIdentity,
    private val repository: AttentionDataSource,
    private val transportStatus: StateFlow<SecureTransportUiStatus>,
    private val submitDecision: suspend (AttentionUserDecision) -> Unit,
    private val retryConnection: suspend () -> Unit,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val reducer: AttentionReducer = AttentionReducer(),
) : ViewModel() {
    private val mutableState = MutableStateFlow<AttentionUiState>(AttentionUiState.Loading(identity))
    val state: StateFlow<AttentionUiState> = mutableState.asStateFlow()

    private val oneShotChannel = Channel<AttentionOneShot>(Channel.BUFFERED)
    val oneShots: Flow<AttentionOneShot> = oneShotChannel.receiveAsFlow()
    private val intentChannel = Channel<AttentionIntent>(Channel.UNLIMITED)
    private val terminalIntentLocked = AtomicBoolean(false)
    private val routeExitLocked = AtomicBoolean(false)
    private val routeExitEffectEmitted = AtomicBoolean(false)
    private val emittedEffectIds = mutableSetOf<String>()

    private var source: AttentionRecordState? = null
    private var connection = transportStatus.value.toAttentionConnection()
    private var notice: AttentionTransientNotice? = null
    private var draftBarrierFailure: AttentionTransientNotice? = null
    private var expiryJob: Job? = null
    private var sourceObserved = false

    constructor(
        taskId: String,
        callId: String,
        repository: AttentionRepository,
        runtime: AndroidSecureTransportRuntime,
    ) : this(
        identity = AttentionIdentity(taskId, callId),
        repository = repository,
        transportStatus = runtime.uiStatus,
        submitDecision = runtime::submitAttentionDecision,
        retryConnection = runtime::retryConnection,
    )

    init {
        viewModelScope.launch {
            repository.observe(identity.taskId, identity.callId).collect(::acceptSource)
        }
        viewModelScope.launch {
            transportStatus.collect { status ->
                connection = status.toAttentionConnection()
                rebuild()
            }
        }
        viewModelScope.launch {
            for (intent in intentChannel) processSafely(intent)
        }
    }

    fun dispatch(intent: AttentionIntent) {
        if (!isAllowed(intent)) return
        if (terminalIntentLocked.get() && intent.isMutable()) return
        if (routeExitLocked.get() && intent.isMutable()) return

        val lockedTerminal = intent.isTerminal() && terminalIntentLocked.compareAndSet(false, true)
        if (intent.isTerminal() && !lockedTerminal) return
        val lockedExit = intent in setOf(AttentionIntent.Dismiss, AttentionIntent.ReturnToTask) &&
            routeExitLocked.compareAndSet(false, true)
        if (intent in setOf(AttentionIntent.Dismiss, AttentionIntent.ReturnToTask) && !lockedExit) {
            if (lockedTerminal) terminalIntentLocked.set(false)
            return
        }
        if (intentChannel.trySend(intent).isFailure) {
            if (lockedTerminal) terminalIntentLocked.set(false)
            if (lockedExit) routeExitLocked.set(false)
        }
    }

    private suspend fun process(intent: AttentionIntent) {
        when (intent) {
            is AttentionIntent.EditCustom -> saveCustom(intent)
            is AttentionIntent.SelectOption -> saveOption(intent.index)
            AttentionIntent.SubmitAnswer -> submitQuestionAnswer()
            AttentionIntent.Skip -> submitTerminal(AttentionUserDecision.Skip(identity.callId))
            AttentionIntent.Confirm -> submitTerminal(AttentionUserDecision.Confirm(identity.callId))
            AttentionIntent.Decline -> submitTerminal(AttentionUserDecision.Decline(identity.callId))
            AttentionIntent.AllowContentRead ->
                submitTerminal(AttentionUserDecision.AllowContentRead(identity.callId))
            AttentionIntent.DenyContentRead ->
                submitTerminal(AttentionUserDecision.DenyContentRead(identity.callId))
            AttentionIntent.Dismiss -> dismiss()
            AttentionIntent.RetryConnection -> retry()
            AttentionIntent.ReturnToTask -> emitReturn(
                AttentionReturnReason.USER_RETURN,
                routeAlreadyClaimed = true,
            )
        }
    }

    private suspend fun processSafely(intent: AttentionIntent) {
        try {
            process(intent)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            when (intent) {
                is AttentionIntent.EditCustom,
                is AttentionIntent.SelectOption,
                -> publishDraftFailure(blockBarrier = true)
                AttentionIntent.SubmitAnswer,
                AttentionIntent.Skip,
                AttentionIntent.Confirm,
                AttentionIntent.Decline,
                AttentionIntent.AllowContentRead,
                AttentionIntent.DenyContentRead,
                -> {
                    val exact = repository.currentOrCorrupt(identity)
                    acceptSource(exact)
                    if (!exact.hasDurableTerminal()) {
                        notice = AttentionTransientNotice.DECISION_NOT_SENT
                        unlockTerminal()
                        rebuild()
                    }
                }
                AttentionIntent.Dismiss -> {
                    routeExitLocked.set(false)
                    notice = AttentionTransientNotice.DISMISS_NOT_SAVED
                    acceptSource(repository.currentOrCorrupt(identity))
                }
                AttentionIntent.RetryConnection -> {
                    notice = AttentionTransientNotice.CONNECTION_RETRY_FAILED
                    rebuild()
                }
                AttentionIntent.ReturnToTask -> routeExitLocked.set(false)
            }
        }
    }

    private suspend fun saveCustom(intent: AttentionIntent.EditCustom) {
        if (intent.text.length > MAX_DURABLE_ANSWER_UTF16) {
            notice = AttentionTransientNotice.EDITOR_LIMIT_REACHED
            draftBarrierFailure = notice
            rebuild()
            return
        }
        val current = exactQuestionOrNull() ?: return
        if (intent.selectionStart !in 0..intent.text.length ||
            intent.selectionEnd !in intent.selectionStart..intent.text.length
        ) {
            publishDraftFailure(blockBarrier = true)
            return
        }
        val validation = if (intent.text.length > MAX_SUBMIT_ANSWER_UTF16) {
            AttentionValidationCode.ANSWER_TOO_LONG
        } else {
            null
        }
        persistDraft(
            AttentionDraftWrite(
                selectedOptionIndex = null,
                customAnswer = intent.text,
                selectionStart = intent.selectionStart,
                selectionEnd = intent.selectionEnd,
                validationCode = validation,
            ),
            current,
        )
    }

    private suspend fun saveOption(index: Int) {
        val current = exactQuestionOrNull() ?: return
        val prompt = current.prompt as AttentionPrompt.Question
        if (index !in prompt.options.indices) {
            publishDraftFailure(blockBarrier = true)
            return
        }
        persistDraft(
            AttentionDraftWrite(
                selectedOptionIndex = index,
                customAnswer = "",
                selectionStart = 0,
                selectionEnd = 0,
                validationCode = null,
            ),
            current,
        )
    }

    private suspend fun persistDraft(write: AttentionDraftWrite, expected: AttentionRecord) {
        try {
            check(expected.taskId == identity.taskId && expected.callId == identity.callId)
            repository.saveDraft(identity.taskId, identity.callId, write)
            draftBarrierFailure = null
            notice = null
            acceptSource(repository.current(identity.taskId, identity.callId))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            publishDraftFailure(blockBarrier = true)
        }
    }

    private suspend fun submitQuestionAnswer() {
        if (!draftBarrierIsClear()) return unlockTerminal()
        val current = exactQuestionOrNull() ?: return unlockTerminal()
        val draft = current.draft
        if (draft.selectedOptionIndex == null && draft.customAnswer.isBlank()) {
            try {
                repository.saveDraft(
                    identity.taskId,
                    identity.callId,
                    AttentionDraftWrite(
                        selectedOptionIndex = null,
                        customAnswer = draft.customAnswer,
                        selectionStart = draft.selectionStart,
                        selectionEnd = draft.selectionEnd,
                        validationCode = AttentionValidationCode.ANSWER_REQUIRED,
                    ),
                )
                notice = null
                acceptSource(repository.current(identity.taskId, identity.callId))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                publishDraftFailure(blockBarrier = false)
            } finally {
                unlockTerminal()
            }
            return
        }
        if (draft.validationCode != null || draft.customAnswer.length > MAX_SUBMIT_ANSWER_UTF16) {
            unlockTerminal()
            return
        }
        val decision = draft.selectedOptionIndex?.let {
            AttentionUserDecision.Option(identity.callId, it)
        } ?: AttentionUserDecision.Custom(identity.callId, draft.customAnswer)
        submitTerminal(decision)
    }

    private suspend fun submitTerminal(decision: AttentionUserDecision) {
        if (!draftBarrierIsClear()) return unlockTerminal()
        val before = readExactActionable() ?: return unlockTerminal()
        if (before.callId != decision.callId) return unlockTerminal()
        try {
            submitDecision(decision)
            notice = null
            val after = repository.current(identity.taskId, identity.callId)
            acceptSource(after)
            if (!after.hasDurableTerminal()) unlockTerminal()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            val after = repository.currentOrCorrupt(identity)
            acceptSource(after)
            if (!after.hasDurableTerminal()) {
                notice = AttentionTransientNotice.DECISION_NOT_SENT
                unlockTerminal()
                rebuild()
            }
        }
    }

    private suspend fun dismiss() {
        try {
            val current = repository.current(identity.taskId, identity.callId)
            val projected = reducer.reduce(identity, current, connection, nowMillis(), notice)
            val visible = projected as? AttentionUiState.Visible
            if (visible?.actions?.canDismiss != true) {
                routeExitLocked.set(false)
                return
            }
            if (visible.state == AttentionVisibleState.Expired) {
                notice = null
                emitReturn(
                    AttentionReturnReason.DISMISSED,
                    routeAlreadyClaimed = true,
                )
                return
            }
            if (!draftBarrierIsClear()) {
                routeExitLocked.set(false)
                return
            }
            repository.dismiss(identity.taskId, identity.callId)
            notice = null
            emitReturn(
                AttentionReturnReason.DISMISSED,
                routeAlreadyClaimed = true,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            routeExitLocked.set(false)
            notice = AttentionTransientNotice.DISMISS_NOT_SAVED
            acceptSource(repository.currentOrCorrupt(identity))
        }
    }

    private suspend fun retry() {
        try {
            retryConnection()
            notice = null
            rebuild()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            notice = AttentionTransientNotice.CONNECTION_RETRY_FAILED
            rebuild()
        }
    }

    private suspend fun exactQuestionOrNull(): AttentionRecord? {
        val current = repository.current(identity.taskId, identity.callId)
        acceptSource(current)
        val record = (current as? AttentionRecordState.Available)?.record ?: return null
        val projected = reducer.reduce(identity, current, connection, nowMillis(), notice)
        val actions = (projected as? AttentionUiState.Visible)?.actions ?: return null
        if (record.prompt !is AttentionPrompt.Question ||
            (!actions.canEditCustom && !actions.canSelectOption)
        ) {
            return null
        }
        return record
    }

    private suspend fun readExactActionable(): AttentionRecord? {
        val current = repository.current(identity.taskId, identity.callId)
        acceptSource(current)
        val record = (current as? AttentionRecordState.Available)?.record ?: return null
        val visible = reducer.reduce(identity, current, connection, nowMillis(), notice)
            as? AttentionUiState.Visible ?: return null
        val allowed = when (record.prompt) {
            is AttentionPrompt.Question -> visible.actions.canSkip || visible.actions.canSubmitAnswer
            is AttentionPrompt.Confirmation -> visible.actions.canConfirm || visible.actions.canDecline
            is AttentionPrompt.ContentRead ->
                visible.actions.canAllowContentRead || visible.actions.canDenyContentRead
            is AttentionPrompt.FileChanges -> false
        }
        return record.takeIf { allowed }
    }

    private fun acceptSource(value: AttentionRecordState) {
        val previous = mutableState.value
        val first = !sourceObserved
        sourceObserved = true
        source = value
        rebuild()
        val current = mutableState.value
        emitTransition(previous, current, first)
        scheduleExpiry(value)
    }

    private fun rebuild() {
        mutableState.value = source?.let { value ->
            reducer.reduce(identity, value, connection, nowMillis(), notice)
        } ?: AttentionUiState.Loading(identity)
    }

    private fun scheduleExpiry(value: AttentionRecordState) {
        expiryJob?.cancel()
        val record = (value as? AttentionRecordState.Available)?.record ?: return
        val visible = mutableState.value as? AttentionUiState.Visible ?: return
        if (visible.state !in setOf(
                AttentionVisibleState.ConfirmPending,
                AttentionVisibleState.QuestionPending,
                AttentionVisibleState.OfflinePending,
            ) && visible.state !is AttentionVisibleState.ValidationError
        ) {
            return
        }
        val remaining = record.expiresAtMillis - nowMillis()
        if (remaining <= 0L) {
            rebuild()
            return
        }
        expiryJob = viewModelScope.launch {
            delay(remaining)
            val previous = mutableState.value
            rebuild()
            emitTransition(previous, mutableState.value, first = false)
        }
    }

    private fun emitTransition(
        previous: AttentionUiState,
        current: AttentionUiState,
        first: Boolean,
    ) {
        val currentVisible = current as? AttentionUiState.Visible
        val previousVisible = previous as? AttentionUiState.Visible
        if (currentVisible?.state == AttentionVisibleState.Responding &&
            (first || previousVisible?.state != AttentionVisibleState.Responding)
        ) {
            emitOnce(
                AttentionOneShot.AnnounceResponding(
                    effectId = "${identity.stableKey}:responding",
                ),
            )
        }
        if (!first && previousVisible?.state.isLiveState() && currentVisible != null) {
            when (currentVisible.state) {
                AttentionVisibleState.Resolved,
                AttentionVisibleState.Rejected,
                AttentionVisibleState.Skipped,
                -> emitReturn(AttentionReturnReason.COMPLETED)
                AttentionVisibleState.Cancelled ->
                    emitReturn(AttentionReturnReason.CANCELLED)
                else -> Unit
            }
        }
        if (current is AttentionUiState.Unavailable ||
            current is AttentionUiState.Corrupt ||
            current is AttentionUiState.FailedClosedHidden
        ) {
            emitReturn(AttentionReturnReason.UNAVAILABLE)
        }
    }

    private fun emitReturn(
        reason: AttentionReturnReason,
        routeAlreadyClaimed: Boolean = false,
    ) {
        if (!routeAlreadyClaimed) routeExitLocked.compareAndSet(false, true)
        if (routeAlreadyClaimed && !routeExitLocked.get()) return
        if (!routeExitEffectEmitted.compareAndSet(false, true)) return
        emitOnce(
            AttentionOneShot.ReturnToTask(
                effectId = "${identity.stableKey}:route-exit",
                reason = reason,
            ),
        )
    }

    private fun emitOnce(effect: AttentionOneShot) {
        if (emittedEffectIds.add(effect.effectId)) oneShotChannel.trySend(effect)
    }

    private fun publishDraftFailure(blockBarrier: Boolean) {
        notice = AttentionTransientNotice.DRAFT_NOT_SAVED
        if (blockBarrier) draftBarrierFailure = notice
        rebuild()
    }

    private fun draftBarrierIsClear(): Boolean {
        val failure = draftBarrierFailure ?: return true
        notice = failure
        rebuild()
        return false
    }

    private fun unlockTerminal() {
        terminalIntentLocked.set(false)
    }

    private fun isAllowed(intent: AttentionIntent): Boolean {
        val actions = (mutableState.value as? AttentionUiState.Visible)?.actions
        return when (intent) {
            is AttentionIntent.EditCustom -> actions?.canEditCustom == true
            is AttentionIntent.SelectOption -> actions?.canSelectOption == true
            AttentionIntent.SubmitAnswer -> actions?.canSubmitAnswer == true
            AttentionIntent.Skip -> actions?.canSkip == true
            AttentionIntent.Confirm -> actions?.canConfirm == true
            AttentionIntent.Decline -> actions?.canDecline == true
            AttentionIntent.AllowContentRead -> actions?.canAllowContentRead == true
            AttentionIntent.DenyContentRead -> actions?.canDenyContentRead == true
            AttentionIntent.Dismiss -> actions?.canDismiss == true
            AttentionIntent.RetryConnection -> actions?.canRetryConnection == true
            AttentionIntent.ReturnToTask -> actions?.canReturnToTask == true
        }
    }

    override fun onCleared() {
        expiryJob?.cancel()
        intentChannel.close()
        oneShotChannel.close()
    }

    class Factory(
        private val taskId: String,
        private val callId: String,
        private val repository: AttentionRepository,
        private val runtime: AndroidSecureTransportRuntime,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(AttentionViewModel::class.java))
            return AttentionViewModel(taskId, callId, repository, runtime) as T
        }
    }

    class PhoneLocalFactory(
        private val taskId: String,
        private val callId: String,
        private val repository: AttentionRepository,
        private val bridge: PhoneLocalAttentionBridge,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(AttentionViewModel::class.java))
            return AttentionViewModel(
                identity = AttentionIdentity(taskId, callId),
                repository = repository,
                transportStatus = PHONE_LOCAL_READY,
                submitDecision = bridge::submitDecision,
                retryConnection = {},
            ) as T
        }
    }

    private companion object {
        const val MAX_SUBMIT_ANSWER_UTF16 = 4_096
        const val MAX_DURABLE_ANSWER_UTF16 = 8_192
        val PHONE_LOCAL_READY = MutableStateFlow(
            SecureTransportUiStatus(SecureTransportUiPhase.READY),
        )
    }
}

private fun AttentionIntent.isTerminal(): Boolean = this in setOf(
    AttentionIntent.SubmitAnswer,
    AttentionIntent.Skip,
    AttentionIntent.Confirm,
    AttentionIntent.Decline,
    AttentionIntent.AllowContentRead,
    AttentionIntent.DenyContentRead,
)

private fun AttentionIntent.isMutable(): Boolean = when (this) {
    is AttentionIntent.EditCustom,
    is AttentionIntent.SelectOption,
    AttentionIntent.SubmitAnswer,
    AttentionIntent.Skip,
    AttentionIntent.Confirm,
    AttentionIntent.Decline,
    AttentionIntent.AllowContentRead,
    AttentionIntent.DenyContentRead,
    AttentionIntent.Dismiss,
    -> true
    AttentionIntent.RetryConnection,
    AttentionIntent.ReturnToTask,
    -> false
}

private fun AttentionVisibleState?.isLiveState(): Boolean = this in setOf(
    AttentionVisibleState.ConfirmPending,
    AttentionVisibleState.ContentReadPending,
    AttentionVisibleState.QuestionPending,
    AttentionVisibleState.OfflinePending,
    AttentionVisibleState.Responding,
) || this is AttentionVisibleState.ValidationError

private fun AttentionRecordState.hasDurableTerminal(): Boolean =
    (this as? AttentionRecordState.Available)?.record?.responseState in setOf(
        AttentionResponseState.RESPONDING,
        AttentionResponseState.RESOLVED,
        AttentionResponseState.REJECTED,
        AttentionResponseState.SKIPPED,
        AttentionResponseState.EXPIRED,
        AttentionResponseState.CANCELLED,
    )

private suspend fun AttentionDataSource.currentOrCorrupt(
    identity: AttentionIdentity,
): AttentionRecordState = try {
    current(identity.taskId, identity.callId)
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: Exception) {
    AttentionRecordState.Corrupt
}

private fun SecureTransportUiStatus.toAttentionConnection(): AttentionConnectionState = when (phase) {
    SecureTransportUiPhase.READY -> AttentionConnectionState.Ready
    SecureTransportUiPhase.OFFLINE,
    SecureTransportUiPhase.RECONNECTING,
    -> AttentionConnectionState.Offline(canRetry = true)
    else -> AttentionConnectionState.Offline(canRetry = false)
}
