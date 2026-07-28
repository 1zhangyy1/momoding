package app.momoding.core.data

import android.content.Context
import android.database.sqlite.SQLiteException
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.momoding.wire.DeviceClientWireError
import app.momoding.wire.DeviceToolResultClientFrame
import app.momoding.wire.DeviceToolTerminalKind
import app.momoding.wire.DurableTaskProjection
import app.momoding.wire.ProjectionWriteStage
import app.momoding.wire.RecoveryState
import app.momoding.wire.SnapshotDeviceCall
import app.momoding.wire.TaskRunState
import app.momoding.core.transport.AttentionApplicationCoordinator
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RoomAttentionLedgerTest {
    private lateinit var context: Context
    private lateinit var database: MomodingDatabase
    private lateinit var ledger: RoomAttentionLedger
    private val clock = AtomicLong(NOW)

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(DATABASE_NAME)
        openDatabase()
        database.momodingDao().upsertTask(task())
    }

    @After
    fun tearDown() {
        database.close()
        context.deleteDatabase(DATABASE_NAME)
    }

    @Test
    fun `request is atomic exact duplicate idempotent and conflicting binding fails closed`() {
        val first = accept(questionRequest())
        assertEquals(AttentionLedgerState.RECEIVED.name, first.operation.ledgerState)
        assertEquals(AttentionResponseState.PENDING.name, first.attention.responseState)
        assertEquals(1, database.momodingDao().deviceOperations(TASK_ID).size)
        assertEquals(1, database.momodingDao().localPendingAttention(TASK_ID).size)

        val duplicate = accept(questionRequest())
        assertEquals(first, duplicate)
        assertEquals(1, database.momodingDao().deviceOperations(TASK_ID).size)

        assertThrows(AttentionLedgerConflictException::class.java) {
            accept(
                questionRequest().copy(arguments = buildJsonObject { put("question", "Changed?") }),
            )
        }
        assertThrows(AttentionLedgerConflictException::class.java) {
            accept(questionRequest().copy(
                toolName = "device_files_read",
                arguments = buildJsonObject { put("alias", "not-authorized") },
            ))
        }
        assertEquals(first.operation.requestSha256, ledger.record(CALL_ID)!!.operation.requestSha256)
    }

    @Test
    fun `trusted invalid and expired first requests are atomically non actionable`() {
        val unsupportedCall = callId(13)
        val unsupported = accept(questionRequest(unsupportedCall).copy(
            toolName = "device_files_read",
            arguments = buildJsonObject { put("alias", "not-authorized") },
        ))
        assertFailedClosed(unsupported, "device_files_read", sideEffect = false, operationId = null)

        val duplicateLabelCall = callId(14)
        val duplicateLabels = accept(questionRequest(duplicateLabelCall).copy(
            arguments = buildJsonObject {
                put("question", "Choose")
                put("options", buildJsonArray {
                    add(buildJsonObject { put("label", "Same") })
                    add(buildJsonObject { put("label", "Same") })
                })
            },
        ))
        assertFailedClosed(
            duplicateLabels,
            "request_user_question",
            sideEffect = false,
            operationId = null,
        )

        val overLimitCall = callId(15)
        val overLimit = accept(questionRequest(overLimitCall).copy(
            arguments = buildJsonObject { put("question", "x".repeat(4_097)) },
        ))
        assertFailedClosed(
            overLimit,
            "request_user_question",
            sideEffect = false,
            operationId = null,
        )

        val sideEffectCall = callId(16)
        val sideEffect = accept(questionRequest(sideEffectCall).copy(
            sideEffect = true,
        ))
        assertFailedClosed(
            sideEffect,
            "request_user_question",
            sideEffect = true,
            operationId = null,
        )

        val operationOnlyCall = callId(25)
        val operationOnly = accept(questionRequest(operationOnlyCall).copy(
            operationId = OPERATION_ID,
        ))
        assertFailedClosed(
            operationOnly,
            "request_user_question",
            sideEffect = false,
            operationId = OPERATION_ID,
        )

        clock.set(EXPIRES_AT)
        val boundaryCall = callId(17)
        val boundary = accept(questionRequest(boundaryCall))
        assertEquals(AttentionLedgerState.TIMED_OUT.name, boundary.operation.ledgerState)
        assertEquals(AttentionResponseState.EXPIRED.name, boundary.attention.responseState)
        assertEquals(DeviceToolTerminalKind.TIMED_OUT.wireValue, boundary.operation.terminalKind)
        assertEquals(AttentionDeliveryState.READY_TO_SEND.name, boundary.operation.deliveryState)

        clock.set(EXPIRES_AT + 1)
        val pastCall = callId(18)
        val past = accept(questionRequest(pastCall))
        assertEquals(AttentionLedgerState.TIMED_OUT.name, past.operation.ledgerState)
        assertEquals(AttentionResponseState.EXPIRED.name, past.attention.responseState)
        assertEquals(DeviceToolTerminalKind.TIMED_OUT.wireValue, past.operation.terminalKind)

        assertEquals(7, ledger.terminalOperationsReadyForDelivery(DEVICE_ID).size)
        assertTrue(database.momodingDao().localPendingAttention(TASK_ID).none {
            it.responseState in setOf(
                AttentionResponseState.PENDING.name,
                AttentionResponseState.RESPONDING.name,
            )
        })
    }

    @Test
    fun `strict shape and wrong binding failures write zero rows`() {
        assertThrows(IllegalArgumentException::class.java) {
            accept(questionRequest().copy(callId = "not-a-uuid"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            accept(questionRequest(callId(19)).copy(deviceId = "other-device"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            accept(questionRequest(callId(20)).copy(
                taskId = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
            ))
        }
        assertThrows(IllegalArgumentException::class.java) {
            accept(questionRequest(callId(21)).copy(capabilityVersion = 2))
        }
        assertThrows(IllegalArgumentException::class.java) {
            accept(questionRequest(callId(22)).copy(toolName = ""))
        }
        assertTrue(database.momodingDao().deviceOperations(TASK_ID).isEmpty())
        assertTrue(database.momodingDao().localPendingAttention(TASK_ID).isEmpty())
    }

    @Test
    fun `file commit requires side effect identity and stores one exact terminal receipt`() {
        val valid = accept(fileCommitRequest(callId(30)))
        assertEquals(true, valid.operation.sideEffect)
        assertEquals(OPERATION_ID, valid.operation.operationId)
        assertEquals(AttentionLedgerState.RECEIVED.name, valid.operation.ledgerState)

        val unsafe = accept(
            fileCommitRequest(callId(31)).copy(
                sideEffect = false,
                operationId = null,
            ),
        )
        assertFailedClosed(
            unsafe,
            "device_files_commit_changes",
            sideEffect = false,
            operationId = null,
        )

        ledger.markAwaitingUser(valid.operation.callId)
        val terminal = ledger.recordTerminal(
            AttentionTerminalWrite(
                frame = DeviceToolResultClientFrame(
                    callId = valid.operation.callId,
                    taskId = TASK_ID,
                    deviceId = DEVICE_ID,
                    terminal = DeviceToolTerminalKind.SUCCEEDED,
                    result = buildJsonObject {
                        put("preparedId", PREPARED_ID)
                        put("planDigest", "a".repeat(64))
                        put("outcome", "completed")
                        put("appliedCount", 1)
                        put("approvalReceiptId", RECEIPT_ID)
                        put(
                            "results",
                            buildJsonArray {
                                add(
                                    buildJsonObject {
                                        put("operationId", ITEM_OPERATION_ID)
                                        put("kind", "create_file")
                                        put("state", "succeeded")
                                        put("resultAlias", "doc-${"b".repeat(24)}")
                                    },
                                )
                            },
                        )
                    },
                ),
                origin = AttentionTerminalOrigin.USER,
                nowMillis = clock.get(),
            ),
        )
        assertEquals(AttentionLedgerState.TERMINAL.name, terminal.operation.ledgerState)
        assertTrue(
            requireNotNull(terminal.operation.terminalFrameCanonicalJson)
                .contains(RECEIPT_ID),
        )
    }

    @Test
    fun `ui action is an exact side effect and auto policy is distinct from user approval`() {
        val callId = callId(32)
        val valid = accept(uiActionRequest(callId))
        assertTrue(valid.operation.sideEffect)
        assertEquals(OPERATION_ID, valid.operation.operationId)

        val terminal = ledger.recordTerminal(
            AttentionTerminalWrite(
                frame = DeviceToolResultClientFrame(
                    callId = callId,
                    taskId = TASK_ID,
                    deviceId = DEVICE_ID,
                    terminal = DeviceToolTerminalKind.SUCCEEDED,
                    result = buildJsonObject {
                        put("ok", true)
                        put("action", "click")
                        put("beforeSnapshotId", UI_SNAPSHOT_ID)
                        put("afterSnapshotId", UI_AFTER_SNAPSHOT_ID)
                        put("foregroundPackage", "dev.fixture")
                        put("targetChanged", false)
                        put("changed", true)
                        put("noChangeCount", 0)
                        put("sessionPaused", false)
                        put("actionCount", 1)
                    },
                ),
                origin = AttentionTerminalOrigin.AUTO_POLICY,
                nowMillis = clock.get(),
            ),
        )

        assertEquals(AttentionLedgerState.TERMINAL.name, terminal.operation.ledgerState)
        assertEquals(DeviceToolTerminalKind.SUCCEEDED.wireValue, terminal.operation.terminalKind)

        val unsafe = accept(
            uiActionRequest(callId(33)).copy(sideEffect = false, operationId = null),
        )
        assertFailedClosed(
            unsafe,
            "device_ui_action",
            sideEffect = false,
            operationId = null,
        )
    }

    @Test
    fun `failed closed terminal insert rolls back the authoritative request`() {
        database.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER fail_attention_insert BEFORE INSERT ON pending_attention " +
                "BEGIN SELECT RAISE(ABORT, 'injected attention insert failure'); END",
        )
        assertThrows(SQLiteException::class.java) {
            accept(questionRequest().copy(
                toolName = "device_files_read",
                arguments = buildJsonObject { put("alias", "not-authorized") },
            ))
        }
        database.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_attention_insert")
        assertNull(database.momodingDao().deviceOperation(CALL_ID))
        assertNull(database.momodingDao().pendingAttention(CALL_ID))
    }

    @Test
    fun `prior host observation is bound when request arrives and conflicts fail closed`() {
        writeProjection(
            RoomProjectionTransactionStore(database) { clock.getAndIncrement() },
            projection("running", includeLocalCall = true),
        )

        val accepted = accept(questionRequest())
        assertEquals("running", accepted.operation.hostObservationState)

        val conflictingCall = callId(7)
        writeProjection(
            RoomProjectionTransactionStore(database) { clock.getAndIncrement() },
            projection(
                hostState = "created",
                includeLocalCall = true,
                operationId = OPERATION_ID,
                callId = conflictingCall,
            ),
        )
        assertThrows(IllegalArgumentException::class.java) {
            accept(questionRequest(conflictingCall))
        }
        assertNull(database.momodingDao().deviceOperation(conflictingCall))
        assertNull(database.momodingDao().pendingAttention(conflictingCall))

        val argumentsConflictCall = callId(10)
        writeProjection(
            RoomProjectionTransactionStore(database) { clock.getAndIncrement() },
            projection(
                hostState = "created",
                includeLocalCall = true,
                callId = argumentsConflictCall,
                arguments = buildJsonObject { put("question", "Different binding") },
            ),
        )
        assertThrows(IllegalArgumentException::class.java) {
            accept(questionRequest(argumentsConflictCall))
        }
        assertNull(database.momodingDao().deviceOperation(argumentsConflictCall))
        assertNull(database.momodingDao().pendingAttention(argumentsConflictCall))

        listOf("terminal" to callId(23), "reconciled" to callId(24)).forEach { (state, callId) ->
            writeProjection(
                RoomProjectionTransactionStore(database) { clock.getAndIncrement() },
                projection(state, includeLocalCall = true, callId = callId),
            )
            val alreadyAnswered = accept(questionRequest(callId))
            assertEquals(AttentionResponseState.ALREADY_ANSWERED.name,
                alreadyAnswered.attention.responseState)
            assertEquals(AttentionDeliveryState.HOST_TERMINAL_DURABLE.name,
                alreadyAnswered.operation.deliveryState)
            assertNull(alreadyAnswered.operation.terminalSha256)
            assertThrows(IllegalArgumentException::class.java) {
                ledger.markAwaitingUser(callId)
            }
            assertThrows(IllegalArgumentException::class.java) {
                ledger.saveDraft(TASK_ID, callId, null, "answer", 0, 6)
            }
        }
    }

    @Test
    fun `draft option and UTF16 selection survive restart without creating a terminal`() {
        accept(questionRequest())
        ledger.markAwaitingUser(CALL_ID)
        val emoji = "A\uD83D\uDE00B"
        val saved = ledger.saveDraft(TASK_ID, CALL_ID, null, emoji, 1, 3)
        assertEquals(emoji, saved.attention.customAnswer)
        assertEquals(1, saved.attention.selectionStart)
        assertEquals(3, saved.attention.selectionEnd)
        assertNull(saved.operation.terminalSha256)
        assertThrows(IllegalArgumentException::class.java) {
            ledger.saveDraft(TASK_ID, CALL_ID, 0, emoji, 1, 3)
        }

        reopenDatabase()
        val restored = ledger.record(CALL_ID)!!
        assertEquals(emoji, restored.attention.customAnswer)
        assertEquals(3, restored.attention.selectionEnd)
        assertNull(restored.operation.terminalSha256)

        val option = ledger.saveDraft(TASK_ID, CALL_ID, 1, "", 0, 0)
        assertEquals(1, option.attention.selectedOptionIndex)
        assertThrows(IllegalArgumentException::class.java) {
            ledger.saveDraft(TASK_ID, CALL_ID, 2, "", 0, 0)
        }

        val required = ledger.saveDraft(TASK_ID, CALL_ID, null, "", 0, 0, "ANSWER_REQUIRED")
        assertEquals(AttentionResponseState.VALIDATION_ERROR.name, required.attention.responseState)
        assertThrows(IllegalArgumentException::class.java) {
            ledger.saveDraft(TASK_ID, CALL_ID, null, "answer", 0, 6, "ANSWER_REQUIRED")
        }
        assertThrows(IllegalArgumentException::class.java) {
            ledger.saveDraft(TASK_ID, CALL_ID, 0, "", 0, 0, "ANSWER_REQUIRED")
        }

        val atSubmitLimit = "x".repeat(4_096)
        ledger.saveDraft(TASK_ID, CALL_ID, null, atSubmitLimit, 4_096, 4_096)
        assertThrows(IllegalArgumentException::class.java) {
            ledger.saveDraft(TASK_ID, CALL_ID, null, atSubmitLimit, 0, 4_096, "ANSWER_TOO_LONG")
        }
        val overSubmitLimit = "x".repeat(4_097)
        assertThrows(IllegalArgumentException::class.java) {
            ledger.saveDraft(TASK_ID, CALL_ID, null, overSubmitLimit, 4_097, 4_097)
        }
        ledger.saveDraft(
            TASK_ID,
            CALL_ID,
            null,
            overSubmitLimit,
            4_097,
            4_097,
            "ANSWER_TOO_LONG",
        )
        val durableMaximum = "x".repeat(8_192)
        val maximum = ledger.saveDraft(
            TASK_ID,
            CALL_ID,
            null,
            durableMaximum,
            8_191,
            8_192,
            "ANSWER_TOO_LONG",
        )
        assertEquals(8_192, maximum.attention.customAnswer.length)
        assertEquals(8_192, maximum.attention.selectionEnd)
        assertThrows(IllegalArgumentException::class.java) {
            ledger.saveDraft(
                TASK_ID,
                CALL_ID,
                null,
                "x".repeat(8_193),
                8_193,
                8_193,
                "ANSWER_TOO_LONG",
            )
        }
        assertEquals(8_192, ledger.record(CALL_ID)!!.attention.customAnswer.length)
    }

    @Test
    fun `late host terminal atomically becomes observation-only handled state and survives restart`() {
        accept(questionRequest())
        val store = RoomProjectionTransactionStore(database) { clock.getAndIncrement() }
        writeProjection(store, projection("running", includeLocalCall = true))
        writeProjection(
            store,
            projection("terminal", includeLocalCall = true).copy(pendingAttention = emptyList()),
        )

        val handled = ledger.record(CALL_ID)!!
        assertEquals("terminal", handled.operation.hostObservationState)
        assertEquals(AttentionDeliveryState.HOST_TERMINAL_DURABLE.name, handled.operation.deliveryState)
        assertEquals(AttentionResponseState.ALREADY_ANSWERED.name, handled.attention.responseState)
        assertNull(handled.operation.terminalSha256)
        assertNotNull(runBlocking { database.momodingDao().observeLocalAttention(TASK_ID, CALL_ID).first() })
        assertEquals(0, runBlocking {
            TaskRepository(database, kotlinx.coroutines.Dispatchers.Unconfined)
                .observeTaskRows().first().single().attentionCount
        })
        assertTrue(runBlocking {
            TaskDetailRepository(database).observe(TASK_ID).first()!!.pendingAttention.isEmpty()
        })

        val coordinator = AttentionApplicationCoordinator(
            ledger = ledger,
            journal = RoomCommandDraftJournal(database) { clock.getAndIncrement() },
            nowMillis = { clock.get() },
            idFactory = { callId(90) },
        )
        coordinator.beginGeneration(1, DEVICE_ID)
        assertNull(coordinator.onProjectionCommitted(TASK_ID).protocolRecoveryReason)

        reopenDatabase()
        val restored = ledger.record(CALL_ID)!!
        assertEquals(AttentionResponseState.ALREADY_ANSWERED.name, restored.attention.responseState)
        assertEquals(AttentionDeliveryState.HOST_TERMINAL_DURABLE.name, restored.operation.deliveryState)
    }

    @Test
    fun `late host terminal pair update rolls back with the projection transaction`() {
        accept(questionRequest())
        val store = RoomProjectionTransactionStore(database) { clock.getAndIncrement() }
        writeProjection(store, projection("running", includeLocalCall = true))
        database.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER fail_late_host_attention BEFORE UPDATE ON pending_attention " +
                "WHEN OLD.callId = '$CALL_ID' " +
                "BEGIN SELECT RAISE(ABORT, 'injected late host attention failure'); END",
        )

        assertThrows(SQLiteException::class.java) {
            writeProjection(
                store,
                projection("terminal", includeLocalCall = true).copy(pendingAttention = emptyList()),
            )
        }
        database.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_late_host_attention")

        val restored = ledger.record(CALL_ID)!!
        assertEquals("running", restored.operation.hostObservationState)
        assertEquals(AttentionDeliveryState.NOT_READY.name, restored.operation.deliveryState)
        assertEquals(AttentionResponseState.PENDING.name, restored.attention.responseState)
        assertEquals("running", database.momodingDao().hostDeviceCallObservation(TASK_ID, CALL_ID)?.hostState)
    }

    @Test
    fun `terminal is single hash durable and delivery proof is monotonic`() {
        accept(questionRequest())
        val frame = optionResult(CALL_ID, 0, "Balanced approach")
        val first = ledger.recordTerminal(
            AttentionTerminalWrite(frame, AttentionTerminalOrigin.USER, NOW),
        )
        assertEquals(AttentionLedgerState.TERMINAL.name, first.operation.ledgerState)
        assertEquals(AttentionDeliveryState.READY_TO_SEND.name, first.operation.deliveryState)
        assertEquals(AttentionResponseState.RESPONDING.name, first.attention.responseState)
        assertNotNull(first.operation.terminalSha256)

        assertEquals(first, ledger.recordTerminal(
            AttentionTerminalWrite(frame, AttentionTerminalOrigin.USER, NOW),
        ))
        assertThrows(IllegalArgumentException::class.java) {
            ledger.recordTerminal(
                AttentionTerminalWrite(frame, AttentionTerminalOrigin.FAILED_CLOSED, NOW),
            )
        }
        assertThrows(AttentionLedgerConflictException::class.java) {
            ledger.recordTerminal(
                AttentionTerminalWrite(
                    optionResult(CALL_ID, 1, "Fast approach"),
                    AttentionTerminalOrigin.USER,
                    NOW,
                ),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            ledger.recordTerminal(
                AttentionTerminalWrite(
                    optionResult(CALL_ID, 0, "wrong"),
                    AttentionTerminalOrigin.USER,
                    NOW,
                ),
            )
        }

        reopenDatabase()
        assertEquals(
            AttentionDeliveryState.READY_TO_SEND.name,
            ledger.record(CALL_ID)!!.operation.deliveryState,
        )
        ledger.markSent(CALL_ID)
        reopenDatabase()
        assertEquals(
            AttentionDeliveryState.SENT_UNCONFIRMED.name,
            ledger.record(CALL_ID)!!.operation.deliveryState,
        )
        ledger.markHostTerminalDurable(CALL_ID)
        reopenDatabase()
        assertEquals(
            AttentionDeliveryState.HOST_TERMINAL_DURABLE.name,
            ledger.record(CALL_ID)!!.operation.deliveryState,
        )
        val delivered = markPiDelivered(CALL_ID)
        assertEquals(AttentionDeliveryState.PI_DELIVERED.name, delivered.operation.deliveryState)
        assertEquals(AttentionResponseState.RESOLVED.name, delivered.attention.responseState)
        assertThrows(IllegalArgumentException::class.java) { ledger.markSent(CALL_ID) }

        reopenDatabase()
        assertEquals(first.operation.terminalSha256, ledger.record(CALL_ID)!!.operation.terminalSha256)
        assertEquals(AttentionDeliveryState.PI_DELIVERED.name, ledger.record(CALL_ID)!!.operation.deliveryState)
    }

    @Test
    fun `failed attention terminal projection update rolls back the entire terminal`() {
        accept(questionRequest())
        database.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER fail_attention_terminal BEFORE UPDATE ON pending_attention " +
                "WHEN OLD.callId = '$CALL_ID' " +
                "BEGIN SELECT RAISE(ABORT, 'injected terminal projection failure'); END",
        )

        assertThrows(SQLiteException::class.java) {
            ledger.recordTerminal(
                AttentionTerminalWrite(
                    optionResult(CALL_ID, 0, "Balanced approach"),
                    AttentionTerminalOrigin.USER,
                    NOW,
                ),
            )
        }
        database.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_attention_terminal")

        val restored = ledger.record(CALL_ID)!!
        assertNull(restored.operation.terminalSha256)
        assertNull(restored.operation.terminalKind)
        assertEquals(AttentionLedgerState.RECEIVED.name, restored.operation.ledgerState)
        assertEquals(AttentionResponseState.PENDING.name, restored.attention.responseState)
        assertNull(restored.attention.terminalDisplayJson)
    }

    @Test
    fun `dismiss expiry and stop cancel races keep first durable terminal`() {
        accept(questionRequest())
        val dismissed = ledger.dismiss(TASK_ID, CALL_ID)
        assertNotNull(dismissed.attention.dismissedAtMillis)
        assertNull(dismissed.operation.terminalSha256)

        assertThrows(IllegalArgumentException::class.java) {
            ledger.recordTerminal(
                AttentionTerminalWrite(optionResult(CALL_ID, 0, "Balanced approach"),
                    AttentionTerminalOrigin.USER, EXPIRES_AT),
            )
        }
        val expired = ledger.recordTerminal(
            AttentionTerminalWrite(expiredResult(CALL_ID), AttentionTerminalOrigin.EXPIRY, EXPIRES_AT),
        )
        assertEquals(AttentionLedgerState.TIMED_OUT.name, expired.operation.ledgerState)
        assertEquals(AttentionResponseState.EXPIRED.name, expired.attention.responseState)
        assertEquals(expired.operation.terminalSha256, ledger.recordTerminal(
            AttentionTerminalWrite(optionResult(CALL_ID, 0, "Balanced approach"),
                AttentionTerminalOrigin.USER, NOW),
        ).operation.terminalSha256)

        val secondCall = callId(2)
        accept(questionRequest(secondCall))
        database.momodingDao().insertOutboundCommand(activeStopCommand())
        assertThrows(IllegalArgumentException::class.java) {
            ledger.recordTerminal(
                AttentionTerminalWrite(optionResult(secondCall, 0, "Balanced approach"),
                    AttentionTerminalOrigin.USER, NOW),
            )
        }
        val cancelled = ledger.recordTerminal(
            AttentionTerminalWrite(
                cancelledResult(secondCall),
                AttentionTerminalOrigin.HOST_CANCEL,
                NOW,
                cancelReason = "session_stop",
            ),
        )
        assertEquals(AttentionLedgerState.CANCELLED.name, cancelled.operation.ledgerState)
        assertEquals(AttentionDeliveryState.NOT_READY.name, cancelled.operation.deliveryState)
        assertEquals("session_stop", cancelled.operation.cancelObservationReason)
        assertFalse(ledger.terminalOperationsReadyForDelivery(DEVICE_ID).any { it.callId == secondCall })
        assertEquals(cancelled.operation.terminalSha256, ledger.recordTerminal(
            AttentionTerminalWrite(optionResult(secondCall, 0, "Balanced approach"),
                AttentionTerminalOrigin.USER, NOW),
        ).operation.terminalSha256)

        reopenDatabase()
        val duplicateHostCancel = ledger.recordTerminal(
            AttentionTerminalWrite(
                cancelledResult(secondCall),
                AttentionTerminalOrigin.HOST_CANCEL,
                NOW + 1,
                cancelReason = "session_stop",
            ),
        )
        assertEquals(cancelled.operation, duplicateHostCancel.operation)
        assertFalse(ledger.terminalOperationsReadyForDelivery(DEVICE_ID).any { it.callId == secondCall })
        assertThrows(AttentionLedgerConflictException::class.java) {
            ledger.recordTerminal(
                AttentionTerminalWrite(
                    cancelledResult(secondCall),
                    AttentionTerminalOrigin.HOST_CANCEL,
                    NOW + 2,
                    cancelReason = "tool_abort",
                ),
            )
        }

        assertEquals(1, database.momodingDao().releaseActiveStopFences(TASK_ID, NOW + 3))
        val thirdCall = callId(6)
        accept(questionRequest(thirdCall))
        val answered = ledger.recordTerminal(
            AttentionTerminalWrite(
                optionResult(thirdCall, 0, "Balanced approach"),
                AttentionTerminalOrigin.USER,
                NOW,
            ),
        )
        val lateCancel = ledger.recordTerminal(
            AttentionTerminalWrite(
                cancelledResult(thirdCall),
                AttentionTerminalOrigin.HOST_CANCEL,
                NOW + 1,
                cancelReason = "tool_abort",
            ),
        )
        assertEquals(answered.operation.terminalSha256, lateCancel.operation.terminalSha256)
        assertEquals(AttentionLedgerState.TERMINAL.name, lateCancel.operation.ledgerState)
        assertEquals("tool_abort", lateCancel.operation.cancelObservationReason)
        assertEquals(NOW + 1, lateCancel.operation.cancelObservedAtMillis)
        assertEquals(AttentionDeliveryState.READY_TO_SEND.name, lateCancel.operation.deliveryState)
        assertTrue(ledger.terminalOperationsReadyForDelivery(DEVICE_ID).any { it.callId == thirdCall })
        val duplicateCancel = ledger.recordTerminal(
            AttentionTerminalWrite(
                cancelledResult(thirdCall),
                AttentionTerminalOrigin.HOST_CANCEL,
                NOW + 2,
                cancelReason = "tool_abort",
            ),
        )
        assertEquals(lateCancel.operation, duplicateCancel.operation)
        assertThrows(AttentionLedgerConflictException::class.java) {
            ledger.recordTerminal(
                AttentionTerminalWrite(
                    cancelledResult(thirdCall),
                    AttentionTerminalOrigin.HOST_CANCEL,
                    NOW + 3,
                    cancelReason = "session_stop",
                ),
            )
        }
    }

    @Test
    fun `question skip confirmation and decline preserve exact terminal semantics`() {
        val skipCall = callId(3)
        accept(questionRequest(skipCall))
        val skipped = ledger.recordTerminal(
            AttentionTerminalWrite(skipResult(skipCall), AttentionTerminalOrigin.USER, NOW),
        )
        assertEquals(AttentionResponseState.SKIPPED.name,
            markPiDelivered(skipCall).attention.responseState)
        assertEquals(DeviceToolTerminalKind.SUCCEEDED.wireValue, skipped.operation.terminalKind)

        val confirmCall = callId(4)
        accept(confirmationRequest(confirmCall))
        val confirmed = ledger.recordTerminal(
            AttentionTerminalWrite(confirmResult(confirmCall), AttentionTerminalOrigin.USER, NOW),
        )
        assertEquals(DeviceToolTerminalKind.SUCCEEDED.wireValue, confirmed.operation.terminalKind)

        val declineCall = callId(5)
        accept(confirmationRequest(declineCall))
        val declined = ledger.recordTerminal(
            AttentionTerminalWrite(declineResult(declineCall), AttentionTerminalOrigin.USER, NOW),
        )
        assertEquals(DeviceToolTerminalKind.REJECTED.wireValue, declined.operation.terminalKind)
        assertEquals(AttentionResponseState.REJECTED.name,
            markPiDelivered(declineCall).attention.responseState)

        val customCall = callId(8)
        accept(questionRequest(customCall))
        val custom = ledger.recordTerminal(
            AttentionTerminalWrite(customResult(customCall, "Use the safer route"),
                AttentionTerminalOrigin.USER, NOW),
        )
        assertEquals(DeviceToolTerminalKind.SUCCEEDED.wireValue, custom.operation.terminalKind)

        val failedCall = callId(9)
        accept(questionRequest(failedCall))
        val failed = ledger.recordTerminal(
            AttentionTerminalWrite(failedResult(failedCall),
                AttentionTerminalOrigin.FAILED_CLOSED, NOW),
        )
        assertEquals(AttentionLedgerState.FAILED_CLOSED.name, failed.operation.ledgerState)
        assertEquals(DeviceToolTerminalKind.FAILED.wireValue, failed.operation.terminalKind)
    }

    @Test
    fun `snapshot replaces observations but never erases or downgrades local terminal`() {
        accept(questionRequest())
        val terminal = ledger.recordTerminal(
            AttentionTerminalWrite(
                optionResult(CALL_ID, 0, "Balanced approach"),
                AttentionTerminalOrigin.USER,
                NOW,
            ),
        )
        val store = RoomProjectionTransactionStore(database) { clock.getAndIncrement() }
        writeProjection(store, projection("terminal", includeLocalCall = true))
        val observed = ledger.record(CALL_ID)!!
        assertEquals("terminal", observed.operation.hostObservationState)
        assertEquals(terminal.operation.terminalSha256, observed.operation.terminalSha256)

        writeProjection(store, projection("running", includeLocalCall = true))
        assertEquals("terminal", ledger.record(CALL_ID)!!.operation.hostObservationState)
        assertEquals(terminal.operation.terminalSha256, ledger.record(CALL_ID)!!.operation.terminalSha256)

        writeProjection(store, projection("running", includeLocalCall = false))
        assertNotNull(ledger.record(CALL_ID))
        assertTrue(database.momodingDao().hostDeviceCallObservations(TASK_ID).isEmpty())

        assertThrows(ProjectionCorruptionException::class.java) {
            writeProjection(store, projection("terminal", includeLocalCall = true, operationId = OPERATION_ID))
        }
        assertEquals(terminal.operation.terminalSha256, ledger.record(CALL_ID)!!.operation.terminalSha256)

        assertThrows(ProjectionCorruptionException::class.java) {
            writeProjection(store, projection("unknown", includeLocalCall = true))
        }
        assertThrows(IllegalArgumentException::class.java) {
            writeProjection(
                store,
                projection("running", includeLocalCall = false).copy(
                    deviceCalls = listOf(
                        SnapshotDeviceCall(callId(11), OPERATION_ID, "created"),
                        SnapshotDeviceCall(callId(12), OPERATION_ID, "running"),
                    ),
                ),
            )
        }
    }

    private fun writeProjection(store: RoomProjectionTransactionStore, next: DurableTaskProjection) {
        store.transaction(TASK_ID) { transaction ->
            transaction.checkpoint(ProjectionWriteStage.RAW)
            transaction.checkpoint(ProjectionWriteStage.PROJECTION)
            transaction.checkpoint(ProjectionWriteStage.CURSOR)
            transaction.replace(next)
            transaction.checkpoint(ProjectionWriteStage.FINAL)
        }
    }

    private fun accept(
        request: AttentionRequestRecord,
        scope: AttentionAcceptanceScope = acceptanceScope(),
    ): AttentionLedgerRecord = ledger.acceptRequest(request, scope)

    private fun markPiDelivered(callId: String): AttentionLedgerRecord {
        val operation = requireNotNull(ledger.record(callId)).operation
        val expectation = requireNotNull(
            ledger.piDeliveryExpectationForValidatedPair(operation),
        )
        return ledger.markPiDeliveredAfterVerifiedProof(
            taskId = operation.taskId,
            callId = callId,
            terminalSemanticSha256 = expectation.terminalSemanticSha256,
        )
    }

    private fun acceptanceScope() = AttentionAcceptanceScope(
        taskId = TASK_ID,
        deviceId = DEVICE_ID,
        capabilityVersion = 1,
        originFocusKey = "composer",
    )

    private fun assertFailedClosed(
        record: AttentionLedgerRecord,
        toolName: String,
        sideEffect: Boolean,
        operationId: String?,
    ) {
        assertEquals(AttentionLedgerState.FAILED_CLOSED.name, record.operation.ledgerState)
        assertEquals(AttentionResponseState.CANCELLED.name, record.attention.responseState)
        assertEquals(DeviceToolTerminalKind.FAILED.wireValue, record.operation.terminalKind)
        assertEquals(AttentionDeliveryState.READY_TO_SEND.name, record.operation.deliveryState)
        assertEquals(toolName, record.operation.toolName)
        assertEquals(sideEffect, record.operation.sideEffect)
        assertEquals(operationId, record.operation.operationId)
        assertNotNull(record.operation.terminalSha256)
        assertTrue(requireNotNull(record.operation.terminalFrameCanonicalJson)
            .contains("UNSUPPORTED_DEVICE_CAPABILITY"))
    }

    private fun projection(
        hostState: String,
        includeLocalCall: Boolean,
        operationId: String? = null,
        callId: String = CALL_ID,
        arguments: kotlinx.serialization.json.JsonElement = questionArguments(),
    ) = DurableTaskProjection(
        taskId = TASK_ID,
        snapshotVersion = clock.getAndIncrement(),
        pendingAttention = if (includeLocalCall) listOf(buildJsonObject {
            put("callId", callId)
            put("toolName", "request_user_question")
            put("arguments", arguments)
            put("state", "waiting")
            put("expiresAt", EXPIRES_AT_TEXT)
            put("originFocusKey", "composer")
        }) else emptyList(),
        deviceCalls = if (includeLocalCall) {
            listOf(SnapshotDeviceCall(callId, operationId, hostState))
        } else {
            emptyList()
        },
        recoveryState = RecoveryState.NORMAL,
        runState = TaskRunState.WAITING,
        nextStageBatchOrdinal = 1,
    )

    private fun questionRequest(callId: String = CALL_ID) = AttentionRequestRecord(
        callId = callId,
        taskId = TASK_ID,
        piToolCallId = "pi-$callId",
        deviceId = DEVICE_ID,
        toolName = "request_user_question",
        arguments = questionArguments(),
        sideEffect = false,
        operationId = null,
        expiresAt = EXPIRES_AT_TEXT,
        capabilityVersion = 1,
    )

    private fun confirmationRequest(callId: String) = questionRequest(callId).copy(
        toolName = "request_user_confirmation",
        arguments = buildJsonObject {
            put("summary", "Allow this step?")
            put("details", "The agent will continue once.")
        },
    )

    private fun fileCommitRequest(callId: String) = questionRequest(callId).copy(
        toolName = "device_files_commit_changes",
        arguments = buildJsonObject {
            put("preparedId", PREPARED_ID)
            put("planDigest", "a".repeat(64))
        },
        sideEffect = true,
        operationId = OPERATION_ID,
    )

    private fun uiActionRequest(callId: String) = questionRequest(callId).copy(
        toolName = "device_ui_action",
        arguments = buildJsonObject {
            put("snapshotId", UI_SNAPSHOT_ID)
            put("nodeHandle", "$UI_SNAPSHOT_ID:n2")
            put("action", "click")
        },
        sideEffect = true,
        operationId = OPERATION_ID,
    )

    private fun questionArguments() = buildJsonObject {
        put("question", "Choose an approach")
        put("options", buildJsonArray {
            add(buildJsonObject { put("label", "Balanced approach"); put("recommended", true) })
            add(buildJsonObject { put("label", "Fast approach") })
        })
    }

    private fun optionResult(callId: String, index: Int, label: String) = DeviceToolResultClientFrame(
        callId = callId,
        taskId = TASK_ID,
        deviceId = DEVICE_ID,
        terminal = DeviceToolTerminalKind.SUCCEEDED,
        result = buildJsonObject {
            put("outcome", "answered")
            put("answer", buildJsonObject {
                put("kind", "option")
                put("index", index)
                put("label", label)
            })
        },
    )

    private fun customResult(callId: String, text: String) = DeviceToolResultClientFrame(
        callId = callId,
        taskId = TASK_ID,
        deviceId = DEVICE_ID,
        terminal = DeviceToolTerminalKind.SUCCEEDED,
        result = buildJsonObject {
            put("outcome", "answered")
            put("answer", buildJsonObject {
                put("kind", "custom")
                put("text", text)
            })
        },
    )

    private fun skipResult(callId: String) = DeviceToolResultClientFrame(
        callId, TASK_ID, DEVICE_ID, DeviceToolTerminalKind.SUCCEEDED,
        result = buildJsonObject { put("outcome", "skipped") },
    )

    private fun confirmResult(callId: String) = DeviceToolResultClientFrame(
        callId, TASK_ID, DEVICE_ID, DeviceToolTerminalKind.SUCCEEDED,
        result = buildJsonObject { put("outcome", "confirmed") },
    )

    private fun declineResult(callId: String) = DeviceToolResultClientFrame(
        callId, TASK_ID, DEVICE_ID, DeviceToolTerminalKind.REJECTED,
        error = DeviceClientWireError("USER_DECLINED", "User declined the confirmation"),
    )

    private fun expiredResult(callId: String) = DeviceToolResultClientFrame(
        callId, TASK_ID, DEVICE_ID, DeviceToolTerminalKind.TIMED_OUT,
        error = DeviceClientWireError("ATTENTION_EXPIRED", "Attention request expired"),
    )

    private fun cancelledResult(callId: String) = DeviceToolResultClientFrame(
        callId, TASK_ID, DEVICE_ID, DeviceToolTerminalKind.CANCELLED,
        error = DeviceClientWireError("ATTENTION_CANCELLED", "Attention request was cancelled"),
    )

    private fun failedResult(callId: String) = DeviceToolResultClientFrame(
        callId, TASK_ID, DEVICE_ID, DeviceToolTerminalKind.FAILED,
        error = DeviceClientWireError(
            "UNSUPPORTED_DEVICE_CAPABILITY",
            "Device capability is unavailable",
        ),
    )

    private fun activeStopCommand() = OutboundCommandEntity(
        requestId = REQUEST_ID,
        commandId = COMMAND_ID,
        kind = "session.stop",
        taskId = TASK_ID,
        canonicalPayload = "{}",
        payloadSha256 = "0".repeat(64),
        state = OutboundCommandState.ACCEPTED.name,
        responseJson = null,
        responseSha256 = null,
        stopFenceState = StopFenceState.ACTIVE.name,
        createdAtMillis = NOW,
        updatedAtMillis = NOW,
    )

    private fun task() = TaskEntity(
        taskId = TASK_ID,
        title = "Attention ledger",
        runState = "WAITING",
        recoveryState = "NORMAL",
        readState = "READ",
        attentionState = "PENDING",
        streamId = null,
        throughSequence = 0,
        snapshotVersion = null,
        windowStart = 0,
        windowEndExclusive = 0,
        nextStageBatchOrdinal = 1,
        queueJson = "[]",
        piSessionId = null,
        isStreaming = false,
        updatedAtMillis = NOW,
    )

    private fun openDatabase() {
        database = Room.databaseBuilder(context, MomodingDatabase::class.java, DATABASE_NAME)
            .allowMainThreadQueries()
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
            .build()
        ledger = RoomAttentionLedger(database) { clock.getAndIncrement() }
    }

    private fun reopenDatabase() {
        database.close()
        openDatabase()
    }

    private companion object {
        const val DATABASE_NAME = "p2-7c-attention-ledger-test.db"
        const val TASK_ID = "11111111-1111-4111-8111-111111111111"
        const val CALL_ID = "22222222-2222-4222-8222-222222222221"
        const val DEVICE_ID = "android-p2-7-device"
        const val OPERATION_ID = "33333333-3333-4333-8333-333333333333"
        const val PREPARED_ID = "66666666-6666-4666-8666-666666666666"
        const val RECEIPT_ID = "77777777-7777-4777-8777-777777777777"
        const val ITEM_OPERATION_ID = "88888888-8888-4888-8888-888888888888"
        const val UI_SNAPSHOT_ID = "ui-11111111111111111111111111111111"
        const val UI_AFTER_SNAPSHOT_ID = "ui-22222222222222222222222222222222"
        const val REQUEST_ID = "44444444-4444-4444-8444-444444444444"
        const val COMMAND_ID = "55555555-5555-4555-8555-555555555555"
        val NOW: Long = Instant.parse("2026-07-17T01:00:00.000Z").toEpochMilli()
        val EXPIRES_AT: Long = Instant.parse("2026-07-17T01:15:00.000Z").toEpochMilli()
        const val EXPIRES_AT_TEXT = "2026-07-17T01:15:00.000Z"

        fun callId(suffix: Int): String =
            "22222222-2222-4222-8222-${suffix.toString().padStart(12, '0')}"
    }
}
