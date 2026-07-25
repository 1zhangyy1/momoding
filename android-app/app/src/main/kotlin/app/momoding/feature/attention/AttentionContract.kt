package app.momoding.feature.attention

import app.momoding.core.data.AttentionDeliveryState
import app.momoding.core.data.AttentionDraft
import app.momoding.core.data.AttentionLedgerState
import app.momoding.core.data.AttentionPrompt
import app.momoding.core.data.AttentionRecord
import app.momoding.core.data.AttentionRecordState
import app.momoding.core.data.AttentionResponseState
import app.momoding.core.data.AttentionTerminalKind
import app.momoding.core.data.AttentionValidationCode

data class AttentionIdentity(
    val taskId: String,
    val callId: String,
) {
    val stableKey: String = "$taskId:$callId"
}

sealed interface AttentionConnectionState {
    data object Ready : AttentionConnectionState
    data class Offline(val canRetry: Boolean) : AttentionConnectionState
}

enum class AttentionTransientNotice {
    DECISION_NOT_SENT,
    DRAFT_NOT_SAVED,
    DISMISS_NOT_SAVED,
    EDITOR_LIMIT_REACHED,
    CONNECTION_RETRY_FAILED,
}

enum class AttentionBlockingReason {
    TASK_STOPPING,
}

data class AttentionActionPolicy(
    val canEditCustom: Boolean = false,
    val canSelectOption: Boolean = false,
    val canSubmitAnswer: Boolean = false,
    val canSkip: Boolean = false,
    val canConfirm: Boolean = false,
    val canDecline: Boolean = false,
    val canAllowContentRead: Boolean = false,
    val canDenyContentRead: Boolean = false,
    val canDismiss: Boolean = false,
    val canRetryConnection: Boolean = false,
    val canReturnToTask: Boolean = false,
)

sealed interface AttentionVisibleState {
    data object ConfirmPending : AttentionVisibleState
    data object ContentReadPending : AttentionVisibleState
    data object FileChangesPending : AttentionVisibleState
    data object QuestionPending : AttentionVisibleState
    data class ValidationError(val code: AttentionValidationCode) : AttentionVisibleState
    data object OfflinePending : AttentionVisibleState
    data object Responding : AttentionVisibleState
    data object Resolved : AttentionVisibleState
    data object Rejected : AttentionVisibleState
    data object Skipped : AttentionVisibleState
    data object Expired : AttentionVisibleState
    data object Cancelled : AttentionVisibleState
    data object AlreadyAnswered : AttentionVisibleState
}

sealed interface AttentionUiState {
    val identity: AttentionIdentity

    data class Loading(override val identity: AttentionIdentity) : AttentionUiState

    data class Unavailable(override val identity: AttentionIdentity) : AttentionUiState

    data class Corrupt(override val identity: AttentionIdentity) : AttentionUiState

    data class FailedClosedHidden(override val identity: AttentionIdentity) : AttentionUiState

    data class Visible(
        override val identity: AttentionIdentity,
        val state: AttentionVisibleState,
        val prompt: AttentionPrompt?,
        val draft: AttentionDraft?,
        val actions: AttentionActionPolicy,
        val blockingReason: AttentionBlockingReason? = null,
        val notice: AttentionTransientNotice? = null,
    ) : AttentionUiState
}

sealed interface AttentionIntent {
    data class EditCustom(
        val text: String,
        val selectionStart: Int,
        val selectionEnd: Int,
    ) : AttentionIntent

    data class SelectOption(val index: Int) : AttentionIntent
    data object SubmitAnswer : AttentionIntent
    data object Skip : AttentionIntent
    data object Confirm : AttentionIntent
    data object Decline : AttentionIntent
    data object AllowContentRead : AttentionIntent
    data object DenyContentRead : AttentionIntent
    data object Dismiss : AttentionIntent
    data object RetryConnection : AttentionIntent
    data object ReturnToTask : AttentionIntent
}

enum class AttentionReturnReason {
    DISMISSED,
    COMPLETED,
    CANCELLED,
    UNAVAILABLE,
    USER_RETURN,
}

sealed interface AttentionOneShot {
    val effectId: String

    data class AnnounceResponding(
        override val effectId: String,
    ) : AttentionOneShot

    data class ReturnToTask(
        override val effectId: String,
        val reason: AttentionReturnReason,
    ) : AttentionOneShot
}

class AttentionReducer {
    fun reduce(
        identity: AttentionIdentity,
        source: AttentionRecordState,
        connection: AttentionConnectionState,
        nowMillis: Long,
        notice: AttentionTransientNotice? = null,
    ): AttentionUiState = when (source) {
        AttentionRecordState.Missing -> AttentionUiState.Unavailable(identity)
        AttentionRecordState.Corrupt -> AttentionUiState.Corrupt(identity)
        AttentionRecordState.FailedClosedHidden -> AttentionUiState.FailedClosedHidden(identity)
        is AttentionRecordState.Available -> reduceAvailable(
            identity,
            source.record,
            connection,
            nowMillis,
            notice,
        )
    }

    private fun reduceAvailable(
        identity: AttentionIdentity,
        record: AttentionRecord,
        connection: AttentionConnectionState,
        nowMillis: Long,
        notice: AttentionTransientNotice?,
    ): AttentionUiState {
        if (record.taskId != identity.taskId || record.callId != identity.callId) {
            return AttentionUiState.Unavailable(identity)
        }
        if (!isConsistent(record)) return AttentionUiState.Corrupt(identity)
        if (record.ledgerState == AttentionLedgerState.FAILED_CLOSED) {
            return AttentionUiState.FailedClosedHidden(identity)
        }

        val visible = when {
            record.ledgerState == AttentionLedgerState.CANCELLED ->
                AttentionVisibleState.Cancelled
            nowMillis >= record.expiresAtMillis -> AttentionVisibleState.Expired
            record.responseState == AttentionResponseState.RESPONDING ->
                AttentionVisibleState.Responding
            record.responseState == AttentionResponseState.RESOLVED ->
                AttentionVisibleState.Resolved
            record.responseState == AttentionResponseState.REJECTED ->
                AttentionVisibleState.Rejected
            record.responseState == AttentionResponseState.SKIPPED ->
                AttentionVisibleState.Skipped
            record.responseState == AttentionResponseState.EXPIRED ->
                AttentionVisibleState.Expired
            record.responseState == AttentionResponseState.CANCELLED ->
                AttentionVisibleState.Cancelled
            record.responseState == AttentionResponseState.ALREADY_ANSWERED ->
                AttentionVisibleState.AlreadyAnswered
            connection is AttentionConnectionState.Offline ->
                AttentionVisibleState.OfflinePending
            record.responseState == AttentionResponseState.VALIDATION_ERROR ->
                AttentionVisibleState.ValidationError(
                    requireNotNull(record.draft.validationCode),
                )
            record.prompt is AttentionPrompt.Confirmation ->
                AttentionVisibleState.ConfirmPending
            record.prompt is AttentionPrompt.ContentRead ->
                AttentionVisibleState.ContentReadPending
            record.prompt is AttentionPrompt.FileChanges ->
                AttentionVisibleState.FileChangesPending
            else -> AttentionVisibleState.QuestionPending
        }
        val showRequest = visible in setOf(
            AttentionVisibleState.ConfirmPending,
            AttentionVisibleState.ContentReadPending,
            AttentionVisibleState.FileChangesPending,
            AttentionVisibleState.QuestionPending,
            AttentionVisibleState.OfflinePending,
        ) || visible is AttentionVisibleState.ValidationError
        return AttentionUiState.Visible(
            identity = identity,
            state = visible,
            prompt = record.prompt.takeIf { showRequest },
            draft = record.draft.takeIf { showRequest },
            actions = actionsFor(visible, record, connection, notice),
            blockingReason = AttentionBlockingReason.TASK_STOPPING.takeIf {
                record.activeStopFence && visible.isPendingState()
            },
            notice = notice,
        )
    }

    private fun actionsFor(
        visible: AttentionVisibleState,
        record: AttentionRecord,
        connection: AttentionConnectionState,
        notice: AttentionTransientNotice?,
    ): AttentionActionPolicy {
        val base = when (visible) {
        AttentionVisibleState.ConfirmPending -> AttentionActionPolicy(
            canConfirm = true,
            canDecline = true,
            canDismiss = true,
        )
        AttentionVisibleState.ContentReadPending -> AttentionActionPolicy(
            canAllowContentRead =
                (record.prompt as AttentionPrompt.ContentRead).filesVerified,
            canDenyContentRead = true,
            canDismiss = true,
        )
        AttentionVisibleState.FileChangesPending -> AttentionActionPolicy(
            canDismiss = true,
        )
        AttentionVisibleState.QuestionPending -> AttentionActionPolicy(
            canEditCustom = true,
            canSelectOption = (record.prompt as AttentionPrompt.Question).options.isNotEmpty(),
            canSubmitAnswer = true,
            canSkip = true,
            canDismiss = true,
        )
        is AttentionVisibleState.ValidationError -> AttentionActionPolicy(
            canEditCustom = true,
            canSelectOption = (record.prompt as AttentionPrompt.Question).options.isNotEmpty(),
            canSkip = true,
            canDismiss = true,
        )
        AttentionVisibleState.OfflinePending -> AttentionActionPolicy(
            canDismiss = true,
            canRetryConnection = (connection as AttentionConnectionState.Offline).canRetry,
        )
        AttentionVisibleState.Responding -> AttentionActionPolicy()
        AttentionVisibleState.Resolved,
        AttentionVisibleState.Rejected,
        AttentionVisibleState.Skipped,
        AttentionVisibleState.Cancelled,
        AttentionVisibleState.AlreadyAnswered,
        -> AttentionActionPolicy(canReturnToTask = true)
        AttentionVisibleState.Expired -> AttentionActionPolicy(canDismiss = true)
        }
        val stopFenced = if (record.activeStopFence && visible.isPendingState()) {
            AttentionActionPolicy(
                canDismiss = base.canDismiss,
                canRetryConnection = base.canRetryConnection,
            )
        } else {
            base
        }
        if (!visible.isPendingState()) return stopFenced
        return when (notice) {
            AttentionTransientNotice.DRAFT_NOT_SAVED,
            AttentionTransientNotice.EDITOR_LIMIT_REACHED,
            -> AttentionActionPolicy(
                canEditCustom = stopFenced.canEditCustom,
                canSelectOption = stopFenced.canSelectOption,
                canReturnToTask = true,
            )
            AttentionTransientNotice.DISMISS_NOT_SAVED ->
                stopFenced.copy(canReturnToTask = true)
            AttentionTransientNotice.DECISION_NOT_SENT,
            AttentionTransientNotice.CONNECTION_RETRY_FAILED,
            null,
            -> stopFenced
        }
    }

    private fun isConsistent(record: AttentionRecord): Boolean {
        if (record.expiresAtMillis < 0L || record.receivedAtMillis < 0L) return false
        if (!hasConsistentDraft(record)) return false
        if ((record.responseState == AttentionResponseState.VALIDATION_ERROR) !=
            (record.draft.validationCode != null)
        ) return false
        return when (record.ledgerState) {
            AttentionLedgerState.RECEIVED,
            AttentionLedgerState.AWAITING_USER,
            -> record.terminalKind == null && when (record.responseState) {
                AttentionResponseState.PENDING -> record.draft.validationCode == null &&
                    record.deliveryState == AttentionDeliveryState.NOT_READY
                AttentionResponseState.VALIDATION_ERROR -> record.draft.validationCode != null &&
                    record.deliveryState == AttentionDeliveryState.NOT_READY
                AttentionResponseState.ALREADY_ANSWERED ->
                    record.deliveryState == AttentionDeliveryState.HOST_TERMINAL_DURABLE
                else -> false
            }
            AttentionLedgerState.TERMINAL -> when (record.deliveryState) {
                AttentionDeliveryState.READY_TO_SEND,
                AttentionDeliveryState.SENT_UNCONFIRMED,
                AttentionDeliveryState.HOST_TERMINAL_DURABLE,
                -> record.responseState == AttentionResponseState.RESPONDING &&
                    record.terminalKind in setOf(
                        AttentionTerminalKind.SUCCEEDED,
                        AttentionTerminalKind.REJECTED,
                    )
                AttentionDeliveryState.PI_DELIVERED -> when (record.responseState) {
                    AttentionResponseState.RESOLVED,
                    AttentionResponseState.SKIPPED,
                    -> record.terminalKind == AttentionTerminalKind.SUCCEEDED
                    AttentionResponseState.REJECTED ->
                        record.terminalKind == AttentionTerminalKind.REJECTED
                    else -> false
                }
                AttentionDeliveryState.ABANDONED ->
                    record.responseState == AttentionResponseState.CANCELLED &&
                        record.terminalKind in setOf(
                            AttentionTerminalKind.SUCCEEDED,
                            AttentionTerminalKind.REJECTED,
                        )
                AttentionDeliveryState.NOT_READY -> false
            }
            AttentionLedgerState.CANCELLED ->
                record.deliveryState == AttentionDeliveryState.NOT_READY &&
                    record.responseState == AttentionResponseState.CANCELLED &&
                    record.terminalKind == AttentionTerminalKind.CANCELLED
            AttentionLedgerState.TIMED_OUT ->
                record.deliveryState != AttentionDeliveryState.NOT_READY &&
                    record.responseState == AttentionResponseState.EXPIRED &&
                    record.terminalKind == AttentionTerminalKind.TIMED_OUT
            AttentionLedgerState.FAILED_CLOSED ->
                record.responseState == AttentionResponseState.CANCELLED &&
                    record.terminalKind == AttentionTerminalKind.FAILED
        }
    }

    private fun hasConsistentDraft(record: AttentionRecord): Boolean {
        val draft = record.draft
        return when (val prompt = record.prompt) {
            is AttentionPrompt.Confirmation ->
                draft.selectedOptionIndex == null &&
                    draft.customAnswer.isEmpty() &&
                    draft.selectionStart == 0 &&
                    draft.selectionEnd == 0 &&
                    draft.validationCode == null
            is AttentionPrompt.ContentRead ->
                draft.selectedOptionIndex == null &&
                    draft.customAnswer.isEmpty() &&
                    draft.selectionStart == 0 &&
                    draft.selectionEnd == 0 &&
                    draft.validationCode == null
            is AttentionPrompt.FileChanges ->
                draft.selectedOptionIndex == null &&
                    draft.customAnswer.isEmpty() &&
                    draft.selectionStart == 0 &&
                    draft.selectionEnd == 0 &&
                    draft.validationCode == null
            is AttentionPrompt.Question -> {
                if (draft.customAnswer.length > MAX_DURABLE_ANSWER_UTF16) return false
                if (draft.selectionStart !in 0..draft.customAnswer.length ||
                    draft.selectionEnd !in draft.selectionStart..draft.customAnswer.length
                ) return false
                if (draft.customAnswer.isNotEmpty() && draft.selectedOptionIndex != null) {
                    return false
                }
                if (draft.selectedOptionIndex != null &&
                    draft.selectedOptionIndex !in prompt.options.indices
                ) return false
                when (draft.validationCode) {
                    AttentionValidationCode.ANSWER_REQUIRED ->
                        draft.selectedOptionIndex == null && draft.customAnswer.isBlank()
                    AttentionValidationCode.ANSWER_TOO_LONG ->
                        draft.selectedOptionIndex == null &&
                            draft.customAnswer.length in
                            (MAX_SUBMIT_ANSWER_UTF16 + 1)..MAX_DURABLE_ANSWER_UTF16
                    null -> record.responseState != AttentionResponseState.PENDING ||
                        draft.customAnswer.length <= MAX_SUBMIT_ANSWER_UTF16
                }
            }
        }
    }

    private companion object {
        const val MAX_SUBMIT_ANSWER_UTF16 = 4_096
        const val MAX_DURABLE_ANSWER_UTF16 = 8_192
    }
}

private fun AttentionVisibleState.isPendingState(): Boolean = this in setOf(
    AttentionVisibleState.ConfirmPending,
    AttentionVisibleState.ContentReadPending,
    AttentionVisibleState.FileChangesPending,
    AttentionVisibleState.QuestionPending,
    AttentionVisibleState.OfflinePending,
) || this is AttentionVisibleState.ValidationError
