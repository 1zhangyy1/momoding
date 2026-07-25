package app.momoding.feature.attention

import app.momoding.core.data.AttentionDataSource
import app.momoding.core.data.AttentionDeliveryState
import app.momoding.core.data.AttentionDraft
import app.momoding.core.data.AttentionDraftWrite
import app.momoding.core.data.AttentionLedgerState
import app.momoding.core.data.AttentionOption
import app.momoding.core.data.AttentionPrompt
import app.momoding.core.data.AttentionPromptKind
import app.momoding.core.data.AttentionRecord
import app.momoding.core.data.AttentionRecordState
import app.momoding.core.data.AttentionResponseState
import app.momoding.core.data.AttentionTerminalKind
import app.momoding.core.data.AttentionValidationCode
import app.momoding.core.data.TaskAttentionSummary
import app.momoding.core.transport.AttentionUserDecision
import app.momoding.core.transport.SecureTransportUiPhase
import app.momoding.core.transport.SecureTransportUiStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AttentionViewModelTest {
    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `queued draft flushes before one terminal and Room alone proves responding and completion`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val source = FakeAttentionDataSource(record())
            val decisions = mutableListOf<AttentionUserDecision>()
            val viewModel = viewModel(source) { decision ->
                decisions += decision
                source.publish(
                    source.record().copy(
                        ledgerState = AttentionLedgerState.TERMINAL,
                        deliveryState = AttentionDeliveryState.READY_TO_SEND,
                        responseState = AttentionResponseState.RESPONDING,
                        terminalKind = AttentionTerminalKind.SUCCEEDED,
                    ),
                )
            }
            runCurrent()

            viewModel.dispatch(AttentionIntent.EditCustom("latest answer", 2, 7))
            viewModel.dispatch(AttentionIntent.SubmitAnswer)
            viewModel.dispatch(AttentionIntent.SubmitAnswer)
            runCurrent()

            assertEquals(listOf("save:latest answer"), source.operations)
            assertEquals(
                listOf(AttentionUserDecision.Custom(CALL_ID, "latest answer")),
                decisions,
            )
            assertEquals(
                AttentionVisibleState.Responding,
                (viewModel.state.value as AttentionUiState.Visible).state,
            )
            assertTrue(viewModel.oneShots.first() is AttentionOneShot.AnnounceResponding)

            source.publish(
                source.record().copy(
                    deliveryState = AttentionDeliveryState.PI_DELIVERED,
                    responseState = AttentionResponseState.RESOLVED,
                ),
            )
            runCurrent()
            assertEquals(
                AttentionOneShot.ReturnToTask(
                    effectId = "$TASK_ID:$CALL_ID:route-exit",
                    reason = AttentionReturnReason.COMPLETED,
                ),
                viewModel.oneShots.first(),
            )
        }

    @Test
    fun `blank over limit durable and hard editor limits keep fixed validation semantics`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val source = FakeAttentionDataSource(record())
        val decisions = mutableListOf<AttentionUserDecision>()
        val viewModel = viewModel(source) { decisions += it }
        runCurrent()

        viewModel.dispatch(AttentionIntent.SubmitAnswer)
        runCurrent()
        var visible = viewModel.state.value as AttentionUiState.Visible
        assertEquals(
            AttentionVisibleState.ValidationError(AttentionValidationCode.ANSWER_REQUIRED),
            visible.state,
        )
        assertFalse(visible.actions.canSubmitAnswer)
        assertEquals(emptyList<AttentionUserDecision>(), decisions)

        val tooLong = "a".repeat(4_097)
        viewModel.dispatch(AttentionIntent.EditCustom(tooLong, 4_097, 4_097))
        runCurrent()
        visible = viewModel.state.value as AttentionUiState.Visible
        assertEquals(
            AttentionVisibleState.ValidationError(AttentionValidationCode.ANSWER_TOO_LONG),
            visible.state,
        )
        assertEquals(tooLong, source.record().draft.customAnswer)

        viewModel.dispatch(AttentionIntent.EditCustom("b".repeat(8_193), 8_193, 8_193))
        runCurrent()
        visible = viewModel.state.value as AttentionUiState.Visible
        assertEquals(AttentionTransientNotice.EDITOR_LIMIT_REACHED, visible.notice)
        assertEquals(tooLong, source.record().draft.customAnswer)

        val submitLimit = "c".repeat(4_096)
        viewModel.dispatch(AttentionIntent.EditCustom(submitLimit, 4_096, 4_096))
        runCurrent()
        visible = viewModel.state.value as AttentionUiState.Visible
        assertEquals(AttentionVisibleState.QuestionPending, visible.state)
        assertEquals(null, visible.notice)
        assertEquals(submitLimit, source.record().draft.customAnswer)
    }

    @Test
    fun `a recreated viewmodel restores exact Room draft selection validation and call identity`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val retained = "r".repeat(4_097)
        val durableRecord = record(
            draft = AttentionDraft(
                selectedOptionIndex = null,
                customAnswer = retained,
                selectionStart = 17,
                selectionEnd = 4_097,
                validationCode = AttentionValidationCode.ANSWER_TOO_LONG,
            ),
        ).copy(responseState = AttentionResponseState.VALIDATION_ERROR)
        val source = FakeAttentionDataSource(durableRecord)

        val beforeRecreation = viewModel(source) { error("No decision expected") }
        runCurrent()
        val restored = viewModel(source) { error("No decision expected") }
        runCurrent()

        val before = beforeRecreation.state.value as AttentionUiState.Visible
        val after = restored.state.value as AttentionUiState.Visible
        assertEquals(before.identity, after.identity)
        assertEquals(AttentionIdentity(TASK_ID, CALL_ID), after.identity)
        assertEquals(before.state, after.state)
        assertEquals(before.draft, after.draft)
        assertEquals(retained, after.draft?.customAnswer)
        assertEquals(17, after.draft?.selectionStart)
        assertEquals(4_097, after.draft?.selectionEnd)
        assertEquals(AttentionValidationCode.ANSWER_TOO_LONG, after.draft?.validationCode)
    }

    @Test
    fun `runtime failure rereads exact Room state before unlocking retry`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val source = FakeAttentionDataSource(
            record(draft = draft(custom = "answer", start = 6, end = 6)),
        )
        var attempts = 0
        val viewModel = viewModel(source) {
            attempts += 1
            if (attempts == 1) throw IllegalStateException("transport detail must stay hidden")
            source.publish(
                source.record().copy(
                    ledgerState = AttentionLedgerState.TERMINAL,
                    deliveryState = AttentionDeliveryState.SENT_UNCONFIRMED,
                    responseState = AttentionResponseState.RESPONDING,
                    terminalKind = AttentionTerminalKind.SUCCEEDED,
                ),
            )
        }
        runCurrent()

        viewModel.dispatch(AttentionIntent.SubmitAnswer)
        runCurrent()
        var visible = viewModel.state.value as AttentionUiState.Visible
        assertEquals(AttentionVisibleState.QuestionPending, visible.state)
        assertEquals(AttentionTransientNotice.DECISION_NOT_SENT, visible.notice)

        viewModel.dispatch(AttentionIntent.SubmitAnswer)
        runCurrent()
        visible = viewModel.state.value as AttentionUiState.Visible
        assertEquals(2, attempts)
        assertEquals(AttentionVisibleState.Responding, visible.state)
        assertEquals(null, visible.notice)
    }

    @Test
    fun `runtime exception after durable terminal stays responding and keeps terminal lock`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val source = FakeAttentionDataSource(
            record(draft = draft(custom = "answer", start = 6, end = 6)),
        )
        var attempts = 0
        val viewModel = viewModel(source) {
            attempts += 1
            source.publish(
                source.record().copy(
                    ledgerState = AttentionLedgerState.TERMINAL,
                    deliveryState = AttentionDeliveryState.READY_TO_SEND,
                    responseState = AttentionResponseState.RESPONDING,
                    terminalKind = AttentionTerminalKind.SUCCEEDED,
                ),
            )
            throw IllegalStateException("caller must reread durable state")
        }
        runCurrent()

        viewModel.dispatch(AttentionIntent.SubmitAnswer)
        runCurrent()
        val visible = viewModel.state.value as AttentionUiState.Visible
        assertEquals(AttentionVisibleState.Responding, visible.state)
        assertEquals(null, visible.notice)

        viewModel.dispatch(AttentionIntent.SubmitAnswer)
        runCurrent()
        assertEquals(1, attempts)
    }

    @Test
    fun `dismiss is a serialized flush barrier and creates no terminal decision`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val source = FakeAttentionDataSource(record())
        val decisions = mutableListOf<AttentionUserDecision>()
        val viewModel = viewModel(source) { decisions += it }
        runCurrent()

        viewModel.dispatch(AttentionIntent.EditCustom("save before close", 4, 9))
        viewModel.dispatch(AttentionIntent.Dismiss)
        viewModel.dispatch(AttentionIntent.SubmitAnswer)
        runCurrent()

        assertEquals(listOf("save:save before close", "dismiss"), source.operations)
        assertTrue(source.record().dismissed)
        assertEquals(emptyList<AttentionUserDecision>(), decisions)
        assertEquals(
                AttentionOneShot.ReturnToTask(
                    effectId = "$TASK_ID:$CALL_ID:route-exit",
                    reason = AttentionReturnReason.DISMISSED,
                ),
            viewModel.oneShots.first(),
        )
    }

    @Test
    fun `expired dismiss exits without mutating the terminal ledger`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val source = FakeAttentionDataSource(
            record().copy(expiresAtMillis = NOW),
        )
        val viewModel = viewModel(source) { error("No decision expected") }
        runCurrent()

        val visible = viewModel.state.value as AttentionUiState.Visible
        assertEquals(AttentionVisibleState.Expired, visible.state)
        assertEquals(AttentionActionPolicy(canDismiss = true), visible.actions)

        viewModel.dispatch(AttentionIntent.Dismiss)
        runCurrent()

        assertEquals(emptyList<String>(), source.operations)
        assertEquals(
            AttentionOneShot.ReturnToTask(
                effectId = "$TASK_ID:$CALL_ID:route-exit",
                reason = AttentionReturnReason.DISMISSED,
            ),
            viewModel.oneShots.first(),
        )
    }

    @Test
    fun `active task stop fence blocks edits and terminal intents before runtime`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val source = FakeAttentionDataSource(record().copy(activeStopFence = true))
        var attempts = 0
        val viewModel = viewModel(source) { attempts += 1 }
        runCurrent()

        val visible = viewModel.state.value as AttentionUiState.Visible
        assertEquals(AttentionBlockingReason.TASK_STOPPING, visible.blockingReason)
        viewModel.dispatch(AttentionIntent.EditCustom("blocked", 0, 7))
        viewModel.dispatch(AttentionIntent.SubmitAnswer)
        viewModel.dispatch(AttentionIntent.Skip)
        runCurrent()

        assertEquals(emptyList<String>(), source.operations)
        assertEquals(0, attempts)
        assertEquals("", source.record().draft.customAnswer)
    }

    @Test
    fun `missing exact route exits once without substituting another call`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val source = FakeAttentionDataSource(AttentionRecordState.Missing)
        val viewModel = viewModel(source) { error("No decision expected") }
        runCurrent()

        assertTrue(viewModel.state.value is AttentionUiState.Unavailable)
        assertEquals(
                AttentionOneShot.ReturnToTask(
                    effectId = "$TASK_ID:$CALL_ID:route-exit",
                    reason = AttentionReturnReason.UNAVAILABLE,
                ),
            viewModel.oneShots.first(),
        )
    }

    @Test
    fun `failed custom draft blocks queued submit until a later durable edit succeeds`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val source = FakeAttentionDataSource(
            record(draft = draft(custom = "old answer", start = 10, end = 10)),
        )
        val decisions = mutableListOf<AttentionUserDecision>()
        val viewModel = viewModel(source) { decisions += it }
        runCurrent()

        source.failNextDraftSave()
        viewModel.dispatch(AttentionIntent.EditCustom("new answer", 10, 10))
        viewModel.dispatch(AttentionIntent.SubmitAnswer)
        runCurrent()

        assertEquals(emptyList<AttentionUserDecision>(), decisions)
        assertEquals("old answer", source.record().draft.customAnswer)
        assertEquals(
            AttentionTransientNotice.DRAFT_NOT_SAVED,
            (viewModel.state.value as AttentionUiState.Visible).notice,
        )

        viewModel.dispatch(AttentionIntent.EditCustom("recovered", 9, 9))
        runCurrent()
        assertTrue((viewModel.state.value as AttentionUiState.Visible).actions.canSubmitAnswer)

        viewModel.dispatch(AttentionIntent.SubmitAnswer)
        runCurrent()

        assertEquals(
            listOf(AttentionUserDecision.Custom(CALL_ID, "recovered")),
            decisions,
        )
        assertEquals("recovered", source.record().draft.customAnswer)
    }

    @Test
    fun `failed draft exposes a zero ledger return without pretending to dismiss`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val source = FakeAttentionDataSource(
            record(draft = draft(custom = "durable", start = 7, end = 7)),
        )
        val viewModel = viewModel(source) { error("No decision expected") }
        runCurrent()

        source.failNextDraftSave()
        viewModel.dispatch(AttentionIntent.EditCustom("unsaved", 7, 7))
        runCurrent()

        val failed = viewModel.state.value as AttentionUiState.Visible
        assertEquals(AttentionTransientNotice.DRAFT_NOT_SAVED, failed.notice)
        assertTrue(failed.actions.canReturnToTask)
        assertFalse(failed.actions.canDismiss)

        viewModel.dispatch(AttentionIntent.ReturnToTask)
        runCurrent()

        assertEquals(listOf("save:unsaved"), source.operations)
        assertFalse(source.record().dismissed)
        assertEquals("durable", source.record().draft.customAnswer)
        assertEquals(
            AttentionOneShot.ReturnToTask(
                effectId = "$TASK_ID:$CALL_ID:route-exit",
                reason = AttentionReturnReason.USER_RETURN,
            ),
            viewModel.oneShots.first(),
        )
    }

    @Test
    fun `failed option draft blocks queued submit`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val source = FakeAttentionDataSource(record())
        val decisions = mutableListOf<AttentionUserDecision>()
        val viewModel = viewModel(source) { decisions += it }
        runCurrent()

        source.failNextDraftSave()
        viewModel.dispatch(AttentionIntent.SelectOption(0))
        viewModel.dispatch(AttentionIntent.SubmitAnswer)
        runCurrent()

        assertEquals(emptyList<AttentionUserDecision>(), decisions)
        assertEquals(null, source.record().draft.selectedOptionIndex)
        assertEquals(
            AttentionTransientNotice.DRAFT_NOT_SAVED,
            (viewModel.state.value as AttentionUiState.Visible).notice,
        )
    }

    @Test
    fun `failed draft blocks queued dismiss without losing the edit`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val source = FakeAttentionDataSource(
            record(draft = draft(custom = "old answer", start = 10, end = 10)),
        )
        val viewModel = viewModel(source) { error("No decision expected") }
        runCurrent()

        source.failNextDraftSave()
        viewModel.dispatch(AttentionIntent.EditCustom("new answer", 10, 10))
        viewModel.dispatch(AttentionIntent.Dismiss)
        runCurrent()

        assertFalse(source.record().dismissed)
        assertEquals("old answer", source.record().draft.customAnswer)
        assertEquals(null, withTimeoutOrNull(1) { viewModel.oneShots.first() })
    }

    @Test
    fun `hard editor limit blocks queued submit of the previous durable answer`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val source = FakeAttentionDataSource(
            record(draft = draft(custom = "old answer", start = 10, end = 10)),
        )
        val decisions = mutableListOf<AttentionUserDecision>()
        val viewModel = viewModel(source) { decisions += it }
        runCurrent()

        viewModel.dispatch(AttentionIntent.EditCustom("x".repeat(8_193), 8_193, 8_193))
        viewModel.dispatch(AttentionIntent.SubmitAnswer)
        runCurrent()

        assertEquals(emptyList<AttentionUserDecision>(), decisions)
        assertEquals("old answer", source.record().draft.customAnswer)
        assertEquals(
            AttentionTransientNotice.EDITOR_LIMIT_REACHED,
            (viewModel.state.value as AttentionUiState.Visible).notice,
        )
    }

    @Test
    fun `live completion owns the only route exit even if user returns immediately`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val source = FakeAttentionDataSource(record())
        val viewModel = viewModel(source) { error("No decision expected") }
        runCurrent()

        source.publish(resolvedRecord())
        runCurrent()
        assertEquals(
            AttentionOneShot.ReturnToTask(
                effectId = "$TASK_ID:$CALL_ID:route-exit",
                reason = AttentionReturnReason.COMPLETED,
            ),
            viewModel.oneShots.first(),
        )

        viewModel.dispatch(AttentionIntent.ReturnToTask)
        runCurrent()
        assertEquals(null, withTimeoutOrNull(1) { viewModel.oneShots.first() })
    }

    @Test
    fun `live cancellation owns the only route exit even if user returns immediately`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val source = FakeAttentionDataSource(record())
        val viewModel = viewModel(source) { error("No decision expected") }
        runCurrent()

        source.publish(cancelledRecord())
        runCurrent()
        assertEquals(
            AttentionOneShot.ReturnToTask(
                effectId = "$TASK_ID:$CALL_ID:route-exit",
                reason = AttentionReturnReason.CANCELLED,
            ),
            viewModel.oneShots.first(),
        )

        viewModel.dispatch(AttentionIntent.ReturnToTask)
        runCurrent()
        assertEquals(null, withTimeoutOrNull(1) { viewModel.oneShots.first() })
    }

    @Test
    fun `completion followed by clear cannot emit a second route exit`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val source = FakeAttentionDataSource(record())
        val viewModel = viewModel(source) { error("No decision expected") }
        runCurrent()

        source.publish(resolvedRecord())
        runCurrent()
        assertTrue(viewModel.oneShots.first() is AttentionOneShot.ReturnToTask)

        source.publish(AttentionRecordState.Missing)
        runCurrent()
        assertEquals(null, withTimeoutOrNull(1) { viewModel.oneShots.first() })
    }

    @Test
    fun `restored completion waits for one explicit user return`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val source = FakeAttentionDataSource(resolvedRecord())
        val viewModel = viewModel(source) { error("No decision expected") }
        runCurrent()

        assertEquals(AttentionVisibleState.Resolved, (viewModel.state.value as AttentionUiState.Visible).state)
        assertEquals(null, withTimeoutOrNull(1) { viewModel.oneShots.first() })

        viewModel.dispatch(AttentionIntent.ReturnToTask)
        runCurrent()
        assertEquals(
            AttentionOneShot.ReturnToTask(
                effectId = "$TASK_ID:$CALL_ID:route-exit",
                reason = AttentionReturnReason.USER_RETURN,
            ),
            viewModel.oneShots.first(),
        )
        viewModel.dispatch(AttentionIntent.ReturnToTask)
        runCurrent()
        assertEquals(null, withTimeoutOrNull(1) { viewModel.oneShots.first() })
    }

    @Test
    fun `mandatory exit is not lost when Room publishes before stale pending dismiss`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))

        routeExitTargets().forEach { (target, expectedReason) ->
            val source = FakeAttentionDataSource(record())
            val viewModel = viewModel(source) { error("No decision expected") }
            runCurrent()

            source.publish(target)
            viewModel.dispatch(AttentionIntent.Dismiss)
            runCurrent()

            assertEquals(
                AttentionOneShot.ReturnToTask(
                    effectId = "$TASK_ID:$CALL_ID:route-exit",
                    reason = expectedReason,
                ),
                viewModel.oneShots.first(),
            )
            assertEquals(null, withTimeoutOrNull(1) { viewModel.oneShots.first() })
        }
    }

    @Test
    fun `mandatory exit is not lost when stale pending dismiss is queued before Room publish`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))

            routeExitTargets().forEach { (target, expectedReason) ->
                val source = FakeAttentionDataSource(record())
                val viewModel = viewModel(source) { error("No decision expected") }
                runCurrent()

                viewModel.dispatch(AttentionIntent.Dismiss)
                source.publish(target)
                runCurrent()

                assertEquals(
                    AttentionOneShot.ReturnToTask(
                        effectId = "$TASK_ID:$CALL_ID:route-exit",
                        reason = expectedReason,
                    ),
                    viewModel.oneShots.first(),
                )
                assertEquals(null, withTimeoutOrNull(1) { viewModel.oneShots.first() })
            }
        }

    private fun viewModel(
        source: FakeAttentionDataSource,
        submit: suspend (AttentionUserDecision) -> Unit,
    ) = AttentionViewModel(
        identity = AttentionIdentity(TASK_ID, CALL_ID),
        repository = source,
        transportStatus = MutableStateFlow(SecureTransportUiStatus(SecureTransportUiPhase.READY)),
        submitDecision = submit,
        retryConnection = {},
        nowMillis = { NOW },
    )

    private class FakeAttentionDataSource(initial: Any) : AttentionDataSource {
        private val mutable = MutableStateFlow(
            when (initial) {
                is AttentionRecord -> AttentionRecordState.Available(initial)
                is AttentionRecordState -> initial
                else -> error("Unsupported initial state")
            },
        )
        val operations = mutableListOf<String>()
        private var failNextDraftSave = false

        override fun observe(taskId: String, callId: String): Flow<AttentionRecordState> = mutable

        override fun observeTaskAttention(taskId: String): Flow<List<TaskAttentionSummary>> =
            MutableStateFlow(emptyList())

        override suspend fun current(taskId: String, callId: String): AttentionRecordState =
            if (taskId == TASK_ID && callId == CALL_ID) mutable.value else AttentionRecordState.Missing

        override suspend fun saveDraft(
            taskId: String,
            callId: String,
            draft: AttentionDraftWrite,
        ) {
            check(taskId == TASK_ID && callId == CALL_ID)
            val current = record()
            operations += "save:${draft.customAnswer}"
            if (failNextDraftSave) {
                failNextDraftSave = false
                throw IllegalStateException("draft write failed")
            }
            publish(
                current.copy(
                    ledgerState = AttentionLedgerState.AWAITING_USER,
                    responseState = if (draft.validationCode == null) {
                        AttentionResponseState.PENDING
                    } else {
                        AttentionResponseState.VALIDATION_ERROR
                    },
                    draft = AttentionDraft(
                        selectedOptionIndex = draft.selectedOptionIndex,
                        customAnswer = draft.customAnswer,
                        selectionStart = draft.selectionStart,
                        selectionEnd = draft.selectionEnd,
                        validationCode = draft.validationCode,
                    ),
                    dismissed = false,
                ),
            )
        }

        override suspend fun dismiss(taskId: String, callId: String) {
            check(taskId == TASK_ID && callId == CALL_ID)
            operations += "dismiss"
            publish(record().copy(dismissed = true))
        }

        fun publish(record: AttentionRecord) {
            mutable.value = AttentionRecordState.Available(record)
        }

        fun publish(state: AttentionRecordState) {
            mutable.value = state
        }

        fun failNextDraftSave() {
            failNextDraftSave = true
        }

        fun record(): AttentionRecord = (mutable.value as AttentionRecordState.Available).record
    }

    private fun record(
        draft: AttentionDraft = draft(),
    ) = AttentionRecord(
        taskId = TASK_ID,
        callId = CALL_ID,
        prompt = AttentionPrompt.Question(
            question = "Which path?",
            options = listOf(AttentionOption("Safe", "Keeps checks", true)),
        ),
        ledgerState = AttentionLedgerState.AWAITING_USER,
        deliveryState = AttentionDeliveryState.NOT_READY,
        responseState = AttentionResponseState.PENDING,
        terminalKind = null,
        draft = draft,
        expiresAtMillis = FUTURE,
        receivedAtMillis = 1L,
        dismissed = false,
    )

    private fun draft(
        custom: String = "",
        start: Int = 0,
        end: Int = 0,
    ) = AttentionDraft(
        selectedOptionIndex = null,
        customAnswer = custom,
        selectionStart = start,
        selectionEnd = end,
        validationCode = null,
    )

    private fun resolvedRecord() = record().copy(
        ledgerState = AttentionLedgerState.TERMINAL,
        deliveryState = AttentionDeliveryState.PI_DELIVERED,
        responseState = AttentionResponseState.RESOLVED,
        terminalKind = AttentionTerminalKind.SUCCEEDED,
    )

    private fun cancelledRecord() = record().copy(
        ledgerState = AttentionLedgerState.CANCELLED,
        deliveryState = AttentionDeliveryState.NOT_READY,
        responseState = AttentionResponseState.CANCELLED,
        terminalKind = AttentionTerminalKind.CANCELLED,
    )

    private fun routeExitTargets() = listOf(
        AttentionRecordState.Available(resolvedRecord()) to AttentionReturnReason.COMPLETED,
        AttentionRecordState.Available(cancelledRecord()) to AttentionReturnReason.CANCELLED,
        AttentionRecordState.Missing to AttentionReturnReason.UNAVAILABLE,
    )

    private companion object {
        const val TASK_ID = "11111111-1111-4111-8111-111111111111"
        const val CALL_ID = "22222222-2222-4222-8222-222222222222"
        const val NOW = 1_000L
        const val FUTURE = 1_000_000L
    }
}
