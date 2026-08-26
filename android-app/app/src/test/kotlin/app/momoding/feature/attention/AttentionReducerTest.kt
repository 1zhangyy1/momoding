package app.momoding.feature.attention

import app.momoding.core.data.AttentionDeliveryState
import app.momoding.core.data.AttentionConfirmationPresentation
import app.momoding.core.data.AttentionDraft
import app.momoding.core.data.AttentionLedgerState
import app.momoding.core.data.AttentionPrompt
import app.momoding.core.data.AttentionRecord
import app.momoding.core.data.AttentionRecordState
import app.momoding.core.data.AttentionResponseState
import app.momoding.core.data.AttentionTerminalKind
import app.momoding.core.data.AttentionValidationCode
import app.momoding.core.data.ContentReadDocument
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AttentionReducerTest {
    private val reducer = AttentionReducer()
    private val identity = AttentionIdentity(TASK_ID, CALL_ID)

    @Test
    fun `reducer projects all twelve visible attention states`() {
        assertState(
            AttentionVisibleState.ConfirmPending,
            record(prompt = confirmation()),
        )
        assertState(AttentionVisibleState.QuestionPending, record())
        assertState(
            AttentionVisibleState.ContentReadPending,
            record(prompt = contentRead()),
        )
        assertState(
            AttentionVisibleState.ValidationError(AttentionValidationCode.ANSWER_REQUIRED),
            record(
                response = AttentionResponseState.VALIDATION_ERROR,
                draft = draft(validation = AttentionValidationCode.ANSWER_REQUIRED),
            ),
        )
        assertState(
            AttentionVisibleState.OfflinePending,
            record(),
            AttentionConnectionState.Offline(canRetry = true),
        )
        assertState(
            AttentionVisibleState.Responding,
            record(
                ledger = AttentionLedgerState.TERMINAL,
                delivery = AttentionDeliveryState.HOST_TERMINAL_DURABLE,
                response = AttentionResponseState.RESPONDING,
                terminal = AttentionTerminalKind.SUCCEEDED,
            ),
        )
        assertState(
            AttentionVisibleState.Resolved,
            finalRecord(AttentionResponseState.RESOLVED, AttentionTerminalKind.SUCCEEDED),
        )
        assertState(
            AttentionVisibleState.Rejected,
            finalRecord(AttentionResponseState.REJECTED, AttentionTerminalKind.REJECTED),
        )
        assertState(
            AttentionVisibleState.Skipped,
            finalRecord(AttentionResponseState.SKIPPED, AttentionTerminalKind.SUCCEEDED),
        )
        assertState(
            AttentionVisibleState.Expired,
            record(
                ledger = AttentionLedgerState.TIMED_OUT,
                delivery = AttentionDeliveryState.READY_TO_SEND,
                response = AttentionResponseState.EXPIRED,
                terminal = AttentionTerminalKind.TIMED_OUT,
            ),
        )
        assertState(
            AttentionVisibleState.Cancelled,
            cancelledRecord(),
        )
        assertState(
            AttentionVisibleState.AlreadyAnswered,
            record(
                ledger = AttentionLedgerState.RECEIVED,
                delivery = AttentionDeliveryState.HOST_TERMINAL_DURABLE,
                response = AttentionResponseState.ALREADY_ANSWERED,
            ),
        )
    }

    @Test
    fun `fail closed cancelled expiry terminal and offline priority is deterministic`() {
        assertState(
            AttentionVisibleState.Cancelled,
            cancelledRecord(expiresAt = 1L),
            AttentionConnectionState.Offline(canRetry = true),
        )
        assertState(
            AttentionVisibleState.Expired,
            record(expiresAt = NOW),
            AttentionConnectionState.Offline(canRetry = true),
        )
        assertState(
            AttentionVisibleState.Responding,
            record(
                ledger = AttentionLedgerState.TERMINAL,
                delivery = AttentionDeliveryState.SENT_UNCONFIRMED,
                response = AttentionResponseState.RESPONDING,
                terminal = AttentionTerminalKind.SUCCEEDED,
            ),
            AttentionConnectionState.Offline(canRetry = true),
        )

        val invalidTerminal = record(
            ledger = AttentionLedgerState.TERMINAL,
            delivery = AttentionDeliveryState.NOT_READY,
            response = AttentionResponseState.RESPONDING,
            terminal = AttentionTerminalKind.SUCCEEDED,
        )
        assertTrue(project(invalidTerminal) is AttentionUiState.Corrupt)
        assertTrue(
            reducer.reduce(
                identity,
                AttentionRecordState.Available(invalidTerminal.copy(taskId = OTHER_TASK_ID)),
                AttentionConnectionState.Ready,
                NOW,
            ) is AttentionUiState.Unavailable,
        )
        assertTrue(
            reducer.reduce(
                identity,
                AttentionRecordState.FailedClosedHidden,
                AttentionConnectionState.Ready,
                NOW,
            ) is AttentionUiState.FailedClosedHidden,
        )
    }

    @Test
    fun `action policy blocks false success and keeps terminal states read only`() {
        val question = project(record()) as AttentionUiState.Visible
        assertTrue(question.actions.canEditCustom)
        assertTrue(question.actions.canSelectOption)
        assertTrue(question.actions.canSubmitAnswer)
        assertTrue(question.actions.canSkip)
        assertTrue(question.actions.canDismiss)

        val contentRead = project(record(prompt = contentRead())) as AttentionUiState.Visible
        assertTrue(contentRead.actions.canAllowContentRead)
        assertTrue(contentRead.actions.canDenyContentRead)
        assertFalse(contentRead.actions.canConfirm)

        val unverifiedContentRead = project(
            record(prompt = contentRead(filesVerified = false)),
        ) as AttentionUiState.Visible
        assertFalse(unverifiedContentRead.actions.canAllowContentRead)
        assertTrue(unverifiedContentRead.actions.canDenyContentRead)

        val offline = project(
            record(),
            AttentionConnectionState.Offline(canRetry = true),
        ) as AttentionUiState.Visible
        assertFalse(offline.actions.canEditCustom)
        assertFalse(offline.actions.canSelectOption)
        assertFalse(offline.actions.canSubmitAnswer)
        assertFalse(offline.actions.canSkip)
        assertTrue(offline.actions.canDismiss)
        assertTrue(offline.actions.canRetryConnection)

        val responding = project(
            record(
                ledger = AttentionLedgerState.TERMINAL,
                delivery = AttentionDeliveryState.READY_TO_SEND,
                response = AttentionResponseState.RESPONDING,
                terminal = AttentionTerminalKind.SUCCEEDED,
            ),
        ) as AttentionUiState.Visible
        assertEquals(AttentionActionPolicy(), responding.actions)
        assertNull(responding.prompt)
        assertNull(responding.draft)
        assertEquals(AttentionInteractionLanguage.ENGLISH, responding.promptLanguageHint)

        val resolved = project(
            finalRecord(AttentionResponseState.RESOLVED, AttentionTerminalKind.SUCCEEDED),
        ) as AttentionUiState.Visible
        assertTrue(resolved.actions.canReturnToTask)
        assertFalse(resolved.actions.canDismiss)

        val stopFenced = project(record(activeStopFence = true)) as AttentionUiState.Visible
        assertEquals(AttentionBlockingReason.TASK_STOPPING, stopFenced.blockingReason)
        assertFalse(stopFenced.actions.canEditCustom)
        assertFalse(stopFenced.actions.canSelectOption)
        assertFalse(stopFenced.actions.canSubmitAnswer)
        assertFalse(stopFenced.actions.canSkip)
        assertTrue(stopFenced.actions.canDismiss)
    }

    @Test
    fun `prompt language hint survives responding projection and cold reconstruction`() {
        val responding = record(
            prompt = AttentionPrompt.Question("请选择一个方案", emptyList()),
            ledger = AttentionLedgerState.TERMINAL,
            delivery = AttentionDeliveryState.READY_TO_SEND,
            response = AttentionResponseState.RESPONDING,
            terminal = AttentionTerminalKind.SUCCEEDED,
        )

        val firstProjection = project(responding) as AttentionUiState.Visible
        val reconstructedProjection = AttentionReducer().reduce(
            identity,
            AttentionRecordState.Available(responding),
            AttentionConnectionState.Ready,
            NOW,
        ) as AttentionUiState.Visible

        assertNull(firstProjection.prompt)
        assertEquals(AttentionInteractionLanguage.ZH_CN, firstProjection.promptLanguageHint)
        assertEquals(firstProjection.promptLanguageHint, reconstructedProjection.promptLanguageHint)
    }

    @Test
    fun `trusted confirmation presentation survives responding and cold reconstruction`() {
        val responding = record(
            prompt = confirmation(),
            ledger = AttentionLedgerState.TERMINAL,
            delivery = AttentionDeliveryState.READY_TO_SEND,
            response = AttentionResponseState.RESPONDING,
            terminal = AttentionTerminalKind.SUCCEEDED,
            confirmationPresentation =
                AttentionConfirmationPresentation.ANDROID_CALENDAR_LIST_EVENTS,
        )

        val firstProjection = project(responding) as AttentionUiState.Visible
        val reconstructedProjection = AttentionReducer().reduce(
            identity,
            AttentionRecordState.Available(responding),
            AttentionConnectionState.Ready,
            NOW,
        ) as AttentionUiState.Visible

        assertNull(firstProjection.prompt)
        assertEquals(
            AttentionConfirmationPresentation.ANDROID_CALENDAR_LIST_EVENTS,
            firstProjection.confirmationPresentation,
        )
        assertEquals(
            firstProjection.confirmationPresentation,
            reconstructedProjection.confirmationPresentation,
        )
    }

    @Test
    fun `expired exposes only dismiss while five notices produce one effective policy`() {
        val expired = project(record(expiresAt = NOW)) as AttentionUiState.Visible
        assertEquals(AttentionVisibleState.Expired, expired.state)
        assertEquals(AttentionActionPolicy(canDismiss = true), expired.actions)

        val decisionFailure = project(
            record(),
            notice = AttentionTransientNotice.DECISION_NOT_SENT,
        ) as AttentionUiState.Visible
        assertEquals(
            AttentionActionPolicy(
                canEditCustom = true,
                canSelectOption = true,
                canSubmitAnswer = true,
                canSkip = true,
                canDismiss = true,
            ),
            decisionFailure.actions,
        )

        listOf(
            AttentionTransientNotice.DRAFT_NOT_SAVED,
            AttentionTransientNotice.EDITOR_LIMIT_REACHED,
        ).forEach { notice ->
            val blockedDraft = project(record(), notice = notice) as AttentionUiState.Visible
            assertEquals(
                AttentionActionPolicy(
                    canEditCustom = true,
                    canSelectOption = true,
                    canReturnToTask = true,
                ),
                blockedDraft.actions,
            )
        }

        val dismissFailure = project(
            record(),
            notice = AttentionTransientNotice.DISMISS_NOT_SAVED,
        ) as AttentionUiState.Visible
        assertEquals(
            AttentionActionPolicy(
                canEditCustom = true,
                canSelectOption = true,
                canSubmitAnswer = true,
                canSkip = true,
                canDismiss = true,
                canReturnToTask = true,
            ),
            dismissFailure.actions,
        )

        val retryFailure = project(
            record(),
            connection = AttentionConnectionState.Offline(canRetry = true),
            notice = AttentionTransientNotice.CONNECTION_RETRY_FAILED,
        ) as AttentionUiState.Visible
        assertEquals(
            AttentionActionPolicy(
                canDismiss = true,
                canRetryConnection = true,
            ),
            retryFailure.actions,
        )
    }

    @Test
    fun `draft barrier notice remains fail closed when task stop fence arrives`() {
        val visible = project(
            record(activeStopFence = true),
            notice = AttentionTransientNotice.DRAFT_NOT_SAVED,
        ) as AttentionUiState.Visible

        assertEquals(AttentionBlockingReason.TASK_STOPPING, visible.blockingReason)
        assertEquals(AttentionActionPolicy(canReturnToTask = true), visible.actions)
    }

    @Test
    fun `typed question drafts fail closed on option exclusivity and UTF16 selection bounds`() {
        assertCorrupt(record(draft = draft(selected = -1)))
        assertCorrupt(record(draft = draft(selected = 1)))
        assertCorrupt(record(draft = draft(selected = 0, custom = "custom")))
        assertCorrupt(record(draft = draft(custom = "abc", start = -1, end = 0)))
        assertCorrupt(record(draft = draft(custom = "abc", start = 0, end = 4)))

        assertState(
            AttentionVisibleState.QuestionPending,
            record(draft = draft(selected = 0)),
        )
        assertState(
            AttentionVisibleState.QuestionPending,
            record(draft = draft(custom = "abc", start = 1, end = 3)),
        )
    }

    @Test
    fun `typed question draft length and validation boundaries match durable semantics`() {
        assertState(AttentionVisibleState.QuestionPending, record(draft = draft()))
        assertState(
            AttentionVisibleState.QuestionPending,
            record(draft = draft(custom = "a".repeat(4_096), start = 4_096, end = 4_096)),
        )
        assertCorrupt(
            record(draft = draft(custom = "a".repeat(4_097), start = 4_097, end = 4_097)),
        )
        assertState(
            AttentionVisibleState.ValidationError(AttentionValidationCode.ANSWER_TOO_LONG),
            record(
                response = AttentionResponseState.VALIDATION_ERROR,
                draft = draft(
                    custom = "a".repeat(4_097),
                    start = 4_097,
                    end = 4_097,
                    validation = AttentionValidationCode.ANSWER_TOO_LONG,
                ),
            ),
        )
        assertState(
            AttentionVisibleState.ValidationError(AttentionValidationCode.ANSWER_TOO_LONG),
            record(
                response = AttentionResponseState.VALIDATION_ERROR,
                draft = draft(
                    custom = "a".repeat(8_192),
                    start = 8_192,
                    end = 8_192,
                    validation = AttentionValidationCode.ANSWER_TOO_LONG,
                ),
            ),
        )
        assertCorrupt(
            record(
                response = AttentionResponseState.VALIDATION_ERROR,
                draft = draft(
                    custom = "a".repeat(8_193),
                    start = 8_193,
                    end = 8_193,
                    validation = AttentionValidationCode.ANSWER_TOO_LONG,
                ),
            ),
        )
    }

    @Test
    fun `typed validation codes must exactly describe the draft and response state`() {
        assertState(
            AttentionVisibleState.ValidationError(AttentionValidationCode.ANSWER_REQUIRED),
            record(
                response = AttentionResponseState.VALIDATION_ERROR,
                draft = draft(validation = AttentionValidationCode.ANSWER_REQUIRED),
            ),
        )
        assertCorrupt(
            record(
                response = AttentionResponseState.VALIDATION_ERROR,
                draft = draft(custom = "answer", validation = AttentionValidationCode.ANSWER_REQUIRED),
            ),
        )
        assertCorrupt(
            record(
                response = AttentionResponseState.VALIDATION_ERROR,
                draft = draft(selected = 0, validation = AttentionValidationCode.ANSWER_REQUIRED),
            ),
        )
        assertCorrupt(
            record(
                response = AttentionResponseState.VALIDATION_ERROR,
                draft = draft(
                    custom = "a".repeat(4_096),
                    validation = AttentionValidationCode.ANSWER_TOO_LONG,
                ),
            ),
        )
        assertCorrupt(
            record(
                response = AttentionResponseState.VALIDATION_ERROR,
                draft = draft(validation = AttentionValidationCode.ANSWER_TOO_LONG),
            ),
        )
        assertCorrupt(
            record(draft = draft(validation = AttentionValidationCode.ANSWER_REQUIRED)),
        )
        assertCorrupt(
            finalRecord(
                AttentionResponseState.RESOLVED,
                AttentionTerminalKind.SUCCEEDED,
            ).copy(draft = draft(validation = AttentionValidationCode.ANSWER_REQUIRED)),
        )
    }

    private fun assertState(
        expected: AttentionVisibleState,
        record: AttentionRecord,
        connection: AttentionConnectionState = AttentionConnectionState.Ready,
    ) {
        assertEquals(expected, (project(record, connection) as AttentionUiState.Visible).state)
    }

    private fun project(
        record: AttentionRecord,
        connection: AttentionConnectionState = AttentionConnectionState.Ready,
        notice: AttentionTransientNotice? = null,
    ) = reducer.reduce(
        identity,
        AttentionRecordState.Available(record),
        connection,
        NOW,
        notice,
    )

    private fun assertCorrupt(record: AttentionRecord) {
        assertTrue(project(record) is AttentionUiState.Corrupt)
    }

    private fun finalRecord(
        response: AttentionResponseState,
        terminal: AttentionTerminalKind,
    ) = record(
        ledger = AttentionLedgerState.TERMINAL,
        delivery = AttentionDeliveryState.PI_DELIVERED,
        response = response,
        terminal = terminal,
    )

    private fun cancelledRecord(expiresAt: Long = FUTURE) = record(
        ledger = AttentionLedgerState.CANCELLED,
        delivery = AttentionDeliveryState.NOT_READY,
        response = AttentionResponseState.CANCELLED,
        terminal = AttentionTerminalKind.CANCELLED,
        expiresAt = expiresAt,
    )

    private fun record(
        prompt: AttentionPrompt = question(),
        ledger: AttentionLedgerState = AttentionLedgerState.AWAITING_USER,
        delivery: AttentionDeliveryState = AttentionDeliveryState.NOT_READY,
        response: AttentionResponseState = AttentionResponseState.PENDING,
        terminal: AttentionTerminalKind? = null,
        draft: AttentionDraft = draft(),
        expiresAt: Long = FUTURE,
        activeStopFence: Boolean = false,
        confirmationPresentation: AttentionConfirmationPresentation? = null,
    ) = AttentionRecord(
        taskId = TASK_ID,
        callId = CALL_ID,
        prompt = prompt,
        ledgerState = ledger,
        deliveryState = delivery,
        responseState = response,
        terminalKind = terminal,
        draft = if (prompt is AttentionPrompt.Question) draft else draft(),
        expiresAtMillis = expiresAt,
        receivedAtMillis = 1L,
        dismissed = false,
        activeStopFence = activeStopFence,
        confirmationPresentation = confirmationPresentation,
    )

    private fun draft(
        selected: Int? = null,
        custom: String = "",
        start: Int = 0,
        end: Int = start,
        validation: AttentionValidationCode? = null,
    ) = AttentionDraft(
        selectedOptionIndex = selected,
        customAnswer = custom,
        selectionStart = start,
        selectionEnd = end,
        validationCode = validation,
    )

    private fun question() = AttentionPrompt.Question(
        question = "Which path?",
        options = listOf(
            app.momoding.core.data.AttentionOption("Safe", null, true),
        ),
    )

    private fun confirmation() = AttentionPrompt.Confirmation("Allow once?", "One-time approval")

    private fun contentRead(
        filesVerified: Boolean = true,
    ) = AttentionPrompt.ContentRead(
        grantId = "44444444-4444-4444-8444-444444444444",
        purpose = "Read project instructions",
        documents = listOf(
            ContentReadDocument(
                alias = "doc-0123456789abcdef01234567",
                displayName = "README.md",
                expectedMimeType = "text/markdown",
                maxBytes = 32_768,
            ),
        ),
        totalMaxBytes = 32_768,
        filesVerified = filesVerified,
    )

    private companion object {
        const val TASK_ID = "11111111-1111-4111-8111-111111111111"
        const val OTHER_TASK_ID = "22222222-2222-4222-8222-222222222222"
        const val CALL_ID = "33333333-3333-4333-8333-333333333333"
        const val NOW = 1_000L
        const val FUTURE = 2_000L
    }
}
