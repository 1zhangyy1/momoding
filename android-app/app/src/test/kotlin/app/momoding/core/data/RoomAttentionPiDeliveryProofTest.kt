package app.momoding.core.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.momoding.wire.AckReady
import app.momoding.wire.DeviceClientWireError
import app.momoding.wire.DeviceToolResultClientFrame
import app.momoding.wire.DeviceToolTerminalKind
import app.momoding.wire.ReceiverFailure
import app.momoding.wire.ReliabilityReceiver
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RoomAttentionPiDeliveryProofTest {
    private lateinit var context: Context
    private lateinit var database: MomodingDatabase
    private lateinit var ledger: RoomAttentionLedger
    private lateinit var store: RoomProjectionTransactionStore
    private lateinit var tempRoot: Path
    private val clock = AtomicLong(NOW)

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(DATABASE_NAME)
        tempRoot = Files.createTempDirectory("p2-7e-pi-proof-")
        openDatabase()
        database.p2Dao().upsertTask(task(TASK_ID))
    }

    @After
    fun tearDown() {
        database.close()
        context.deleteDatabase(DATABASE_NAME)
        tempRoot.toFile().deleteRecursively()
    }

    @Test
    fun `native exact proof commits before ACK is replay idempotent and survives restart`() {
        acceptQuestion(CALL_ID)
        ledger.recordTerminal(
            AttentionTerminalWrite(optionResult(CALL_ID), AttentionTerminalOrigin.USER, NOW),
        )
        val expectation = expectation(CALL_ID)
        val frame = nativeProofFrame(CALL_ID, expectation)

        val first = receive(frame, "native-first")
        assertTrue(first.last() is AckReady)
        val delivered = requireNotNull(ledger.record(CALL_ID))
        assertEquals(AttentionDeliveryState.PI_DELIVERED.name, delivered.operation.deliveryState)
        assertEquals(AttentionResponseState.RESOLVED.name, delivered.attention.responseState)
        val deliveredAt = delivered.operation.updatedAtMillis

        val replay = receive(frame, "native-replay")
        assertTrue(replay.last() is AckReady)
        assertEquals(deliveredAt, requireNotNull(ledger.record(CALL_ID)).operation.updatedAtMillis)

        val snapshot = directSnapshotFrame(
            snapshotVersion = 1,
            highWatermark = 1,
            messages = listOf(toolResultMessage(CALL_ID, expectation)),
        )
        val nativeAndSnapshot = receive(snapshot, "native-plus-snapshot")
        assertTrue(nativeAndSnapshot.last() is AckReady)
        assertEquals(deliveredAt, requireNotNull(ledger.record(CALL_ID)).operation.updatedAtMillis)

        reopenDatabase()
        val restored = requireNotNull(ledger.record(CALL_ID))
        assertEquals(AttentionDeliveryState.PI_DELIVERED.name, restored.operation.deliveryState)
        assertEquals(AttentionResponseState.RESOLVED.name, restored.attention.responseState)
    }

    @Test
    fun `native empty details stays durable and non-final until a later exact native proof`() {
        acceptQuestion(CALL_ID)
        ledger.recordTerminal(
            AttentionTerminalWrite(optionResult(CALL_ID), AttentionTerminalOrigin.USER, NOW),
        )
        ledger.markSent(CALL_ID)
        val expectation = expectation(CALL_ID)
        val before = requireNotNull(ledger.record(CALL_ID))
        assertEquals(AttentionDeliveryState.SENT_UNCONFIRMED.name, before.operation.deliveryState)
        assertEquals(AttentionResponseState.RESPONDING.name, before.attention.responseState)

        val emptyFrame = nativeProofFrame(
            CALL_ID,
            expectation,
            sequence = 1,
            detailsShape = DetailsShape.EMPTY,
        )
        val observed = receive(emptyFrame, "empty-native-observation")
        assertTrue(observed.last() is AckReady)
        val observedProjection = requireNotNull(store.read(TASK_ID))
        assertEquals(1L, observedProjection.throughSequence)
        assertEquals(setOf(1L), observedProjection.rawEvents.keys)
        assertEquals(before, requireNotNull(ledger.record(CALL_ID)))

        val emptyReplay = receive(emptyFrame, "empty-native-observation-replay")
        assertTrue(emptyReplay.last() is AckReady)
        assertEquals(1L, requireNotNull(store.read(TASK_ID)).throughSequence)
        assertEquals(before, requireNotNull(ledger.record(CALL_ID)))

        val exact = receive(
            nativeProofFrame(CALL_ID, expectation, sequence = 2),
            "empty-native-then-exact-native",
        )
        assertTrue(exact.last() is AckReady)
        assertEquals(2L, requireNotNull(store.read(TASK_ID)).throughSequence)
        assertDelivered(CALL_ID, AttentionResponseState.RESOLVED)
        val deliveredAt = requireNotNull(ledger.record(CALL_ID)).operation.updatedAtMillis

        val exactReplay = receive(
            nativeProofFrame(CALL_ID, expectation, sequence = 2),
            "empty-native-then-exact-native-replay",
        )
        assertTrue(exactReplay.last() is AckReady)
        assertEquals(deliveredAt, requireNotNull(ledger.record(CALL_ID)).operation.updatedAtMillis)
    }

    @Test
    fun `native empty details stays non-final until an authoritative exact snapshot`() {
        acceptQuestion(CALL_ID)
        ledger.recordTerminal(
            AttentionTerminalWrite(optionResult(CALL_ID), AttentionTerminalOrigin.USER, NOW),
        )
        ledger.markSent(CALL_ID)
        val expectation = expectation(CALL_ID)

        val observed = receive(
            nativeProofFrame(
                CALL_ID,
                expectation,
                sequence = 1,
                detailsShape = DetailsShape.EMPTY,
            ),
            "empty-native-before-snapshot",
        )
        assertTrue(observed.last() is AckReady)
        val beforeSnapshot = requireNotNull(ledger.record(CALL_ID))
        assertEquals(
            AttentionDeliveryState.SENT_UNCONFIRMED.name,
            beforeSnapshot.operation.deliveryState,
        )
        assertEquals(AttentionResponseState.RESPONDING.name, beforeSnapshot.attention.responseState)

        val snapshot = receive(
            directSnapshotFrame(
                snapshotVersion = 1,
                highWatermark = 1,
                messages = listOf(toolResultMessage(CALL_ID, expectation)),
            ),
            "empty-native-then-exact-snapshot",
        )
        assertTrue(snapshot.last() is AckReady)
        assertEquals(1L, requireNotNull(store.read(TASK_ID)).snapshotVersion)
        assertDelivered(CALL_ID, AttentionResponseState.RESOLVED)
    }

    @Test
    fun `native malformed details and authoritative snapshot empty details stay fail closed`() {
        acceptQuestion(CALL_ID)
        ledger.recordTerminal(
            AttentionTerminalWrite(optionResult(CALL_ID), AttentionTerminalOrigin.USER, NOW),
        )
        val expectation = expectation(CALL_ID)
        val before = requireNotNull(ledger.record(CALL_ID))
        val malformedNativeShapes = listOf(
            DetailsShape.MISSING,
            DetailsShape.ARRAY,
            DetailsShape.STRING,
            DetailsShape.NULL_VALUE,
            DetailsShape.PARTIAL,
            DetailsShape.EXTRA,
        )

        malformedNativeShapes.forEach { shape ->
            val actions = receive(
                nativeProofFrame(CALL_ID, expectation, detailsShape = shape),
                "malformed-native-${shape.name.lowercase()}",
            )
            assertEquals(shape.name, 1, actions.size)
            assertTrue(shape.name, actions.single() is ReceiverFailure)
            assertTrue(shape.name, actions.none { it is AckReady })
            assertEquals(shape.name, 0L, requireNotNull(store.read(TASK_ID)).throughSequence)
            assertEquals(shape.name, before, requireNotNull(ledger.record(CALL_ID)))
        }

        val malformedSnapshotShapes = listOf(
            DetailsShape.EMPTY,
            DetailsShape.MISSING,
            DetailsShape.ARRAY,
            DetailsShape.STRING,
            DetailsShape.NULL_VALUE,
            DetailsShape.PARTIAL,
            DetailsShape.EXTRA,
        )
        malformedSnapshotShapes.forEach { shape ->
            val actions = receive(
                directSnapshotFrame(
                    snapshotVersion = 1,
                    highWatermark = 0,
                    messages = listOf(
                        toolResultMessage(CALL_ID, expectation, detailsShape = shape),
                    ),
                ),
                "malformed-snapshot-${shape.name.lowercase()}",
            )
            assertEquals(shape.name, 1, actions.size)
            assertTrue(shape.name, actions.single() is ReceiverFailure)
            assertTrue(shape.name, actions.none { it is AckReady })
            assertNull(shape.name, store.read(TASK_ID)?.snapshotVersion)
            assertEquals(shape.name, before, requireNotNull(ledger.record(CALL_ID)))
        }
    }

    @Test
    fun `native empty details cannot bypass outer attention identity`() {
        acceptQuestion(CALL_ID)
        ledger.recordTerminal(
            AttentionTerminalWrite(optionResult(CALL_ID), AttentionTerminalOrigin.USER, NOW),
        )
        val before = requireNotNull(ledger.record(CALL_ID))
        val actions = receive(
            nativeProofFrame(
                CALL_ID,
                expectation(CALL_ID),
                mutation = ProofMutation.TOOL_NAME,
                detailsShape = DetailsShape.EMPTY,
            ),
            "empty-native-wrong-tool-name",
        )

        assertEquals(1, actions.size)
        assertTrue(actions.single() is ReceiverFailure)
        assertTrue(actions.none { it is AckReady })
        assertEquals(0L, requireNotNull(store.read(TASK_ID)).throughSequence)
        assertEquals(before, requireNotNull(ledger.record(CALL_ID)))
    }

    @Test
    fun `authoritative snapshot proof resolves without a native event and is idempotent`() {
        acceptQuestion(CALL_ID)
        ledger.recordTerminal(
            AttentionTerminalWrite(optionResult(CALL_ID), AttentionTerminalOrigin.USER, NOW),
        )
        val expectation = expectation(CALL_ID)
        val first = receive(
            directSnapshotFrame(1, 0, listOf(toolResultMessage(CALL_ID, expectation))),
            "snapshot-live",
        )
        assertTrue(first.last() is AckReady)
        val deliveredAt = requireNotNull(ledger.record(CALL_ID)).operation.updatedAtMillis
        assertDelivered(CALL_ID, AttentionResponseState.RESOLVED)

        val duplicate = receive(
            directSnapshotFrame(2, 0, listOf(toolResultMessage(CALL_ID, expectation))),
            "snapshot-live-duplicate",
        )
        assertTrue(duplicate.last() is AckReady)
        assertEquals(deliveredAt, requireNotNull(ledger.record(CALL_ID)).operation.updatedAtMillis)
    }

    @Test
    fun `recovery snapshot preserves rejected expired and cancelled terminal semantics`() {
        val declineCall = callId(2)
        val expiryCall = callId(3)
        val cancelCall = callId(4)
        acceptConfirmation(declineCall)
        ledger.recordTerminal(
            AttentionTerminalWrite(declineResult(declineCall), AttentionTerminalOrigin.USER, NOW),
        )
        acceptQuestion(expiryCall)
        ledger.recordTerminal(
            AttentionTerminalWrite(
                expiredResult(expiryCall),
                AttentionTerminalOrigin.EXPIRY,
                EXPIRES_AT_MILLIS + 1,
            ),
        )
        acceptQuestion(cancelCall)
        ledger.recordTerminal(
            AttentionTerminalWrite(
                cancelledResult(cancelCall),
                AttentionTerminalOrigin.HOST_CANCEL,
                NOW,
                cancelReason = "tool_abort",
            ),
        )

        val wrongError = toolResultMessage(
            declineCall,
            expectation(declineCall).copy(
                contentPayload = buildJsonObject {
                    put("code", "DEVICE_OPERATION_FAILED")
                    put("message", "Device operation failed")
                },
            ),
            recovery = true,
        )
        val rejected = receive(
            directSnapshotFrame(1, 0, listOf(wrongError)),
            "wrong-error-content",
        )
        assertTrue(rejected.single() is ReceiverFailure)
        assertTrue(rejected.none { it is AckReady })
        assertEquals(AttentionDeliveryState.READY_TO_SEND.name,
            requireNotNull(ledger.record(declineCall)).operation.deliveryState)

        val messages = listOf(declineCall, expiryCall, cancelCall).map { callId ->
            toolResultMessage(callId, expectation(callId), recovery = true)
        }
        val actions = receive(
            directSnapshotFrame(1, 0, messages),
            "recovery-outcomes",
        )
        assertTrue(actions.last() is AckReady)
        assertDelivered(declineCall, AttentionResponseState.REJECTED)
        assertDelivered(expiryCall, AttentionResponseState.EXPIRED)
        assertDelivered(cancelCall, AttentionResponseState.CANCELLED)
    }

    @Test
    fun `Host terminal without exact proof stays non-final and no local terminal stays observation-only`() {
        acceptQuestion(CALL_ID)
        ledger.recordTerminal(
            AttentionTerminalWrite(optionResult(CALL_ID), AttentionTerminalOrigin.USER, NOW),
        )
        val otherCall = callId(5)
        acceptQuestion(otherCall)
        val otherProof = toolResultMessage(
            callId = otherCall,
            expectation = AttentionPiDeliveryExpectation(
                terminalSemanticSha256 = "a".repeat(64),
                contentPayload = optionResultPayload(),
                isError = false,
                recoveryState = "succeeded",
            ),
        )
        val actions = receive(
            directSnapshotFrame(
                snapshotVersion = 1,
                highWatermark = 0,
                messages = listOf(otherProof),
                terminalCallIds = listOf(CALL_ID, otherCall),
            ),
            "host-only",
        )
        assertTrue(actions.last() is AckReady)

        val localTerminal = requireNotNull(ledger.record(CALL_ID))
        assertEquals(AttentionResponseState.RESPONDING.name, localTerminal.attention.responseState)
        assertFalse(localTerminal.operation.deliveryState == AttentionDeliveryState.PI_DELIVERED.name)
        ledger.markHostTerminalDurable(CALL_ID)
        assertEquals(
            AttentionResponseState.RESPONDING.name,
            requireNotNull(ledger.record(CALL_ID)).attention.responseState,
        )

        val observationOnly = requireNotNull(ledger.record(otherCall))
        assertEquals(AttentionDeliveryState.HOST_TERMINAL_DURABLE.name,
            observationOnly.operation.deliveryState)
        assertEquals(AttentionResponseState.ALREADY_ANSWERED.name,
            observationOnly.attention.responseState)
        assertNull(observationOnly.operation.terminalSha256)
    }

    @Test
    fun `wrong native identity hash content error and details all rollback without ACK`() {
        acceptQuestion(CALL_ID)
        ledger.recordTerminal(
            AttentionTerminalWrite(optionResult(CALL_ID), AttentionTerminalOrigin.USER, NOW),
        )
        val expectation = expectation(CALL_ID)
        ProofMutation.entries.forEach { mutation ->
            val before = requireNotNull(ledger.record(CALL_ID))
            val actions = receive(
                nativeProofFrame(CALL_ID, expectation, mutation),
                "wrong-${mutation.name.lowercase()}",
            )
            assertEquals(mutation.name, 1, actions.size)
            assertTrue(mutation.name, actions.single() is ReceiverFailure)
            assertTrue(mutation.name, actions.none { it is AckReady })
            assertEquals(mutation.name, 0L, requireNotNull(store.read(TASK_ID)).throughSequence)
            assertEquals(mutation.name, before, requireNotNull(ledger.record(CALL_ID)))
        }
    }

    @Test
    fun `conflicting snapshot proofs rollback the entire replacement`() {
        acceptQuestion(CALL_ID)
        ledger.recordTerminal(
            AttentionTerminalWrite(optionResult(CALL_ID), AttentionTerminalOrigin.USER, NOW),
        )
        val expectation = expectation(CALL_ID)
        val exact = toolResultMessage(CALL_ID, expectation)
        val conflict = toolResultMessage(
            CALL_ID,
            expectation.copy(terminalSemanticSha256 = "b".repeat(64)),
        )
        val actions = receive(
            directSnapshotFrame(1, 0, listOf(exact, conflict)),
            "conflicting-snapshot",
        )
        assertEquals(1, actions.size)
        assertTrue(actions.single() is ReceiverFailure)
        assertTrue(actions.none { it is AckReady })
        assertEquals(AttentionDeliveryState.READY_TO_SEND.name,
            requireNotNull(ledger.record(CALL_ID)).operation.deliveryState)
        assertNull(store.read(TASK_ID)?.snapshotVersion)
    }

    @Test
    fun `unrelated generic tool result remains durable but cannot resolve attention`() {
        acceptQuestion(CALL_ID)
        ledger.recordTerminal(
            AttentionTerminalWrite(optionResult(CALL_ID), AttentionTerminalOrigin.USER, NOW),
        )
        val unrelated = nativeProofFrame(
            callId = callId(99),
            expectation = AttentionPiDeliveryExpectation(
                terminalSemanticSha256 = "c".repeat(64),
                contentPayload = buildJsonObject { put("generic", true) },
                isError = false,
                recoveryState = "succeeded",
            ),
            toolName = "review",
            piToolCallId = "unrelated-tool-call",
        )
        val actions = receive(unrelated, "unrelated")
        assertTrue(actions.last() is AckReady)
        assertEquals(1L, requireNotNull(store.read(TASK_ID)).throughSequence)
        assertEquals(AttentionDeliveryState.READY_TO_SEND.name,
            requireNotNull(ledger.record(CALL_ID)).operation.deliveryState)
    }

    @Test
    fun `non-attention file tool result remains durable without entering attention proof validation`() {
        val callId = callId(98)
        val operation = DeviceOperationEntity(
            callId = callId,
            taskId = TASK_ID,
            piToolCallId = "pi-file-prepare",
            deviceId = DEVICE_ID,
            toolName = "device_files_prepare_changes",
            argumentsCanonicalJson = """{"grantId":"$OPERATION_ID","operations":[],"purpose":"test"}""",
            sideEffect = false,
            operationId = null,
            expiresAt = EXPIRES_AT_TEXT,
            capabilityVersion = 1,
            requestSha256 = "b".repeat(64),
            ledgerState = AttentionLedgerState.TERMINAL.name,
            terminalKind = DeviceToolTerminalKind.SUCCEEDED.name,
            terminalFrameCanonicalJson = """{"terminal":"succeeded"}""",
            terminalSha256 = "c".repeat(64),
            hostObservationState = null,
            cancelObservationReason = null,
            cancelObservedAtMillis = null,
            deliveryState = AttentionDeliveryState.HOST_TERMINAL_DURABLE.name,
            progressSequence = 1,
            receivedAtMillis = NOW,
            updatedAtMillis = NOW,
        )
        database.p2Dao().insertDeviceOperation(operation)
        val fileToolResult = buildJsonObject {
            put("role", "toolResult")
            put("toolCallId", operation.piToolCallId)
            put("toolName", operation.toolName)
            put("content", buildJsonArray {
                add(textContent(buildJsonObject { put("redactedFields", 6) }))
            })
            put("details", buildJsonObject {
                put("callId", operation.callId)
                put("toolName", operation.toolName)
                put("terminalSemanticSha256", "d".repeat(64))
                put("sideEffect", false)
                put("dataScope", "android_saf_task_grant")
            })
            put("isError", false)
            put("timestamp", NOW)
        }

        val actions = receive(
            directSnapshotFrame(1, 0, listOf(fileToolResult)),
            "local-file-tool-result",
        )

        assertTrue(actions.toString(), actions.last() is AckReady)
        assertEquals(operation, database.p2Dao().deviceOperation(callId))
        assertEquals(1, requireNotNull(store.read(TASK_ID)).messages.size)
    }

    @Test
    fun `exact file commit Pi proof resolves local review and clears task attention`() {
        val callId = callId(97)
        val resultPayload = fileCommitResultPayload()
        ledger.acceptRequest(
            request(
                callId,
                "device_files_commit_changes",
                buildJsonObject {
                    put("preparedId", OPERATION_ID)
                    put("planDigest", "a".repeat(64))
                },
            ).copy(
                sideEffect = true,
                operationId = OPERATION_ID,
            ),
            scope(),
        )
        ledger.recordTerminal(
            AttentionTerminalWrite(
                DeviceToolResultClientFrame(
                    callId = callId,
                    taskId = TASK_ID,
                    deviceId = DEVICE_ID,
                    terminal = DeviceToolTerminalKind.SUCCEEDED,
                    result = resultPayload,
                ),
                AttentionTerminalOrigin.USER,
                NOW,
            ),
        )
        val expectation = expectation(callId)
        val fileToolResult = buildJsonObject {
            put("role", "toolResult")
            put("toolCallId", "pi-$callId")
            put("toolName", "device_files_commit_changes")
            put("content", buildJsonArray { add(textContent(resultPayload)) })
            put("details", buildJsonObject {
                put("callId", callId)
                put("toolName", "device_files_commit_changes")
                put("terminalSemanticSha256", expectation.terminalSemanticSha256)
                put("sideEffect", true)
                put("operationId", OPERATION_ID)
                put("dataScope", "android_saf_task_grant")
                put("approvalOrigin", "user")
            })
            put("isError", false)
            put("timestamp", NOW)
        }

        val actions = receive(
            directSnapshotFrame(1, 0, listOf(fileToolResult)),
            "local-file-commit-proof",
        )

        assertTrue(actions.toString(), actions.last() is AckReady)
        assertDelivered(callId, AttentionResponseState.RESOLVED)
        val visible = validatedAttentionRecords(
            database.p2Dao().deviceOperations(TASK_ID),
            database.p2Dao().localPendingAttention(TASK_ID),
            ledger,
        ).filter { it.isTaskEntryVisible() }
        assertTrue(visible.isEmpty())
    }

    @Test
    fun `file commit preapproval failure proof uses explicit none origin`() {
        val callId = callId(94)
        ledger.acceptRequest(
            request(
                callId,
                "device_files_commit_changes",
                buildJsonObject {
                    put("preparedId", OPERATION_ID)
                    put("planDigest", "a".repeat(64))
                },
            ).copy(
                sideEffect = true,
                operationId = OPERATION_ID,
            ),
            scope(),
        )
        ledger.recordTerminal(
            AttentionTerminalWrite(
                DeviceToolResultClientFrame(
                    callId = callId,
                    taskId = TASK_ID,
                    deviceId = DEVICE_ID,
                    terminal = DeviceToolTerminalKind.FAILED,
                    error = DeviceClientWireError(
                        "AUTHORIZED_FOLDER_UNAVAILABLE",
                        "Mobile folder authorization is unavailable",
                    ),
                ),
                AttentionTerminalOrigin.FAILED_CLOSED,
                NOW,
            ),
        )
        val expectation = expectation(callId)
        val proof = buildJsonObject {
            put("role", "toolResult")
            put("toolCallId", "pi-$callId")
            put("toolName", "device_files_commit_changes")
            put("content", buildJsonArray { add(textContent(expectation.contentPayload)) })
            put("details", buildJsonObject {
                put("callId", callId)
                put("toolName", "device_files_commit_changes")
                put("terminalSemanticSha256", expectation.terminalSemanticSha256)
                put("sideEffect", true)
                put("operationId", OPERATION_ID)
                put("dataScope", "android_shared_storage_grant")
                put("approvalOrigin", "none")
            })
            put("isError", true)
            put("timestamp", NOW)
        }

        val actions = receive(
            directSnapshotFrame(1, 0, listOf(proof)),
            "file-commit-preapproval-failure-proof",
        )

        assertTrue(actions.toString(), actions.last() is AckReady)
        assertDelivered(callId, AttentionResponseState.CANCELLED)
    }

    @Test
    fun `cross-task callId binding rolls back and does not create another task`() {
        acceptQuestion(CALL_ID)
        ledger.recordTerminal(
            AttentionTerminalWrite(optionResult(CALL_ID), AttentionTerminalOrigin.USER, NOW),
        )
        val actions = receive(
            nativeProofFrame(
                callId = CALL_ID,
                expectation = expectation(CALL_ID),
                taskId = OTHER_TASK_ID,
                piToolCallId = "wrong-cross-task-tool-call",
            ),
            "cross-task",
        )
        assertEquals(1, actions.size)
        assertTrue(actions.single() is ReceiverFailure)
        assertTrue(actions.none { it is AckReady })
        assertNull(database.p2Dao().task(OTHER_TASK_ID))
        assertEquals(AttentionDeliveryState.READY_TO_SEND.name,
            requireNotNull(ledger.record(CALL_ID)).operation.deliveryState)
    }

    @Test
    fun `ambiguous toolCallId and details callId targets rollback both local pairs without ACK`() {
        val otherCall = callId(6)
        acceptQuestion(CALL_ID)
        ledger.recordTerminal(
            AttentionTerminalWrite(optionResult(CALL_ID), AttentionTerminalOrigin.USER, NOW),
        )
        acceptQuestion(otherCall)
        val beforePrimary = requireNotNull(ledger.record(CALL_ID))
        val beforeOther = requireNotNull(ledger.record(otherCall))

        val actions = receive(
            nativeProofFrame(
                callId = CALL_ID,
                expectation = expectation(CALL_ID),
                detailsCallId = otherCall,
            ),
            "ambiguous-identity",
        )

        assertEquals(1, actions.size)
        assertTrue(actions.single() is ReceiverFailure)
        assertTrue(actions.none { it is AckReady })
        assertEquals(0L, requireNotNull(store.read(TASK_ID)).throughSequence)
        assertEquals(beforePrimary, requireNotNull(ledger.record(CALL_ID)))
        assertEquals(beforeOther, requireNotNull(ledger.record(otherCall)))
    }

    @Test
    fun `Room failure during verified delivery rolls projection and ledger back without ACK`() {
        acceptQuestion(CALL_ID)
        ledger.recordTerminal(
            AttentionTerminalWrite(optionResult(CALL_ID), AttentionTerminalOrigin.USER, NOW),
        )
        database.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER fail_pi_delivery BEFORE UPDATE ON pending_attention " +
                "BEGIN SELECT RAISE(ABORT, 'injected proof rollback'); END",
        )
        val actions = receive(nativeProofFrame(CALL_ID, expectation(CALL_ID)), "write-fault")
        database.openHelper.writableDatabase.execSQL("DROP TRIGGER IF EXISTS fail_pi_delivery")

        assertEquals(1, actions.size)
        assertTrue(actions.single() is ReceiverFailure)
        assertTrue(actions.none { it is AckReady })
        assertEquals(0L, requireNotNull(store.read(TASK_ID)).throughSequence)
        val record = requireNotNull(ledger.record(CALL_ID))
        assertEquals(AttentionDeliveryState.READY_TO_SEND.name, record.operation.deliveryState)
        assertEquals(AttentionResponseState.RESPONDING.name, record.attention.responseState)
    }

    private fun nativeProofFrame(
        callId: String,
        expectation: AttentionPiDeliveryExpectation,
        mutation: ProofMutation? = null,
        taskId: String = TASK_ID,
        toolName: String = "request_user_question",
        piToolCallId: String = "pi-$callId",
        detailsCallId: String = callId,
        sequence: Long = 1,
        detailsShape: DetailsShape = DetailsShape.EXACT,
    ): ByteArray {
        val details = buildJsonObject {
            put(
                "callId",
                if (mutation == ProofMutation.CALL_ID) callId(88) else detailsCallId,
            )
            put("toolName", if (mutation == ProofMutation.TOOL_NAME) "request_user_confirmation" else toolName)
            put(
                "terminalSemanticSha256",
                if (mutation == ProofMutation.HASH) "d".repeat(64)
                else expectation.terminalSemanticSha256,
            )
            if (mutation == ProofMutation.STRING_SIDE_EFFECT) {
                put("sideEffect", "false")
            } else {
                put("sideEffect", mutation == ProofMutation.SIDE_EFFECT)
            }
            if (mutation == ProofMutation.EXTRA_DETAIL) put("operationId", OPERATION_ID)
            if (mutation == ProofMutation.RECOVERY_STATE) {
                put("recovery", true)
                put("reconciliationState", "cancelled")
                put("requiresManualReview", false)
            }
            if (mutation == ProofMutation.MANUAL_REVIEW) {
                put("recovery", true)
                put("reconciliationState", "succeeded")
                put("requiresManualReview", true)
            }
            if (mutation == ProofMutation.STRING_RECOVERY) {
                put("recovery", "true")
                put("reconciliationState", "succeeded")
                put("requiresManualReview", false)
            }
            if (mutation == ProofMutation.STRING_MANUAL_REVIEW) {
                put("recovery", true)
                put("reconciliationState", "succeeded")
                put("requiresManualReview", "false")
            }
        }
        val shapedDetails = shapeDetails(details, detailsShape)
        val content = buildJsonArray {
            add(textContent(
                if (mutation == ProofMutation.CONTENT) {
                    buildJsonObject { put("outcome", "skipped") }
                } else {
                    expectation.contentPayload
                },
            ))
            if (mutation == ProofMutation.MULTIPLE_CONTENT) add(textContent(expectation.contentPayload))
        }
        val event = buildJsonObject {
            put("type", "tool_execution_end")
            put("toolCallId", if (mutation == ProofMutation.TOOL_CALL_ID) "wrong-tool-call" else piToolCallId)
            put("toolName", if (mutation == ProofMutation.TOOL_NAME) "request_user_confirmation" else toolName)
            put("result", buildJsonObject {
                put("content", content)
                if (mutation != ProofMutation.MISSING_DETAILS && shapedDetails != null) {
                    put("details", shapedDetails)
                }
            })
            if (mutation == ProofMutation.STRING_IS_ERROR) {
                put("isError", "false")
            } else {
                put(
                    "isError",
                    if (mutation == ProofMutation.IS_ERROR) !expectation.isError
                    else expectation.isError,
                )
            }
        }
        return buildJsonObject {
            put("protocolVersion", 1)
            put("kind", "pi.event")
            put("taskId", taskId)
            put("piSessionId", PI_SESSION_ID)
            put("piVersion", "0.80.6")
            put("streamId", STREAM_ID)
            put("sequence", sequence)
            put("emittedAt", "2026-07-17T02:00:00.000Z")
            put("event", event)
        }.toString().encodeToByteArray()
    }

    private fun toolResultMessage(
        callId: String,
        expectation: AttentionPiDeliveryExpectation,
        recovery: Boolean = false,
        toolName: String = operation(callId).toolName,
        detailsShape: DetailsShape = DetailsShape.EXACT,
    ): JsonObject = buildJsonObject {
        put("role", "toolResult")
        put("toolCallId", operation(callId).piToolCallId)
        put("toolName", toolName)
        put("content", buildJsonArray { add(textContent(expectation.contentPayload)) })
        val exactDetails = buildJsonObject {
            put("callId", callId)
            put("toolName", toolName)
            put("terminalSemanticSha256", expectation.terminalSemanticSha256)
            put("sideEffect", false)
            if (recovery) {
                put("recovery", true)
                put("reconciliationState", expectation.recoveryState)
                put("requiresManualReview", false)
            }
        }
        shapeDetails(exactDetails, detailsShape)?.let { put("details", it) }
        put("isError", expectation.isError)
        put("timestamp", NOW)
    }

    private fun shapeDetails(
        exact: JsonObject,
        shape: DetailsShape,
    ): JsonElement? = when (shape) {
        DetailsShape.EXACT -> exact
        DetailsShape.EMPTY -> JsonObject(emptyMap())
        DetailsShape.MISSING -> null
        DetailsShape.ARRAY -> JsonArray(emptyList())
        DetailsShape.STRING -> JsonPrimitive("invalid")
        DetailsShape.NULL_VALUE -> JsonNull
        DetailsShape.PARTIAL -> buildJsonObject { put("callId", exact.getValue("callId")) }
        DetailsShape.EXTRA -> buildJsonObject {
            exact.forEach { (key, value) -> put(key, value) }
            put("operationId", OPERATION_ID)
        }
    }

    private fun textContent(payload: kotlinx.serialization.json.JsonElement) = buildJsonObject {
        put("type", "text")
        put("text", payload.toString())
    }

    private fun directSnapshotFrame(
        snapshotVersion: Long,
        highWatermark: Long,
        messages: List<JsonObject>,
        terminalCallIds: List<String> = emptyList(),
    ): ByteArray = buildJsonObject {
        put("kind", "task.snapshot")
        put("requestId", "snapshot-$snapshotVersion")
        put("taskId", TASK_ID)
        put("snapshotVersion", snapshotVersion)
        put("recoveryState", "normal")
        put("runState", "idle")
        put("piSessionId", PI_SESSION_ID)
        put("pi", buildJsonObject {
            put("messages", JsonArray(messages))
            put("isStreaming", false)
            put("queue", JsonArray(emptyList()))
        })
        put("pendingAttention", JsonArray(emptyList()))
        put("deviceCalls", buildJsonArray {
            terminalCallIds.forEach { callId ->
                add(buildJsonObject {
                    put("callId", callId)
                    put("state", "terminal")
                })
            }
        })
        put("cursor", buildJsonObject {
            put("streamId", STREAM_ID)
            put("highWatermarkSequence", highWatermark)
            put("oldestReplayableSequence", 1)
        })
    }.toString().encodeToByteArray()

    private fun receive(frame: ByteArray, directory: String) =
        ReliabilityReceiver(tempRoot.resolve(directory), store).use { receiver -> receiver.receive(frame) }

    private fun expectation(callId: String): AttentionPiDeliveryExpectation {
        val operation = operation(callId)
        return requireNotNull(ledger.piDeliveryExpectationForValidatedPair(operation))
    }

    private fun operation(callId: String): DeviceOperationEntity =
        requireNotNull(ledger.record(callId)).operation

    private fun assertDelivered(callId: String, responseState: AttentionResponseState) {
        val record = requireNotNull(ledger.record(callId))
        assertEquals(AttentionDeliveryState.PI_DELIVERED.name, record.operation.deliveryState)
        assertEquals(responseState.name, record.attention.responseState)
    }

    private fun acceptQuestion(callId: String) = ledger.acceptRequest(
        request(callId, "request_user_question", questionArguments()),
        scope(),
    )

    private fun acceptConfirmation(callId: String) = ledger.acceptRequest(
        request(
            callId,
            "request_user_confirmation",
            buildJsonObject { put("summary", "Allow this step?") },
        ),
        scope(),
    )

    private fun request(
        callId: String,
        toolName: String,
        arguments: kotlinx.serialization.json.JsonElement,
    ) = AttentionRequestRecord(
        callId = callId,
        taskId = TASK_ID,
        piToolCallId = "pi-$callId",
        deviceId = DEVICE_ID,
        toolName = toolName,
        arguments = arguments,
        sideEffect = false,
        operationId = null,
        expiresAt = EXPIRES_AT_TEXT,
        capabilityVersion = 1,
    )

    private fun scope() = AttentionAcceptanceScope(TASK_ID, DEVICE_ID, 1, "composer")

    private fun questionArguments() = buildJsonObject {
        put("question", "Choose an approach")
        put("options", buildJsonArray {
            add(buildJsonObject { put("label", "Balanced approach") })
            add(buildJsonObject { put("label", "Fast approach") })
        })
    }

    private fun optionResultPayload() = buildJsonObject {
        put("outcome", "answered")
        put("answer", buildJsonObject {
            put("kind", "option")
            put("index", 0)
            put("label", "Balanced approach")
        })
    }

    private fun fileCommitResultPayload() = buildJsonObject {
        put("preparedId", OPERATION_ID)
        put("planDigest", "a".repeat(64))
        put("outcome", "completed")
        put("appliedCount", 1)
        put("approvalReceiptId", callId(96))
        put("results", buildJsonArray {
            add(buildJsonObject {
                put("operationId", callId(95))
                put("kind", "create_file")
                put("state", "succeeded")
                put("resultAlias", "doc-${"b".repeat(24)}")
            })
        })
    }

    private fun optionResult(callId: String) = DeviceToolResultClientFrame(
        callId,
        TASK_ID,
        DEVICE_ID,
        DeviceToolTerminalKind.SUCCEEDED,
        result = optionResultPayload(),
    )

    private fun declineResult(callId: String) = DeviceToolResultClientFrame(
        callId,
        TASK_ID,
        DEVICE_ID,
        DeviceToolTerminalKind.REJECTED,
        error = DeviceClientWireError("USER_DECLINED", "User declined the confirmation"),
    )

    private fun expiredResult(callId: String) = DeviceToolResultClientFrame(
        callId,
        TASK_ID,
        DEVICE_ID,
        DeviceToolTerminalKind.TIMED_OUT,
        error = DeviceClientWireError("ATTENTION_EXPIRED", "Attention request expired"),
    )

    private fun cancelledResult(callId: String) = DeviceToolResultClientFrame(
        callId,
        TASK_ID,
        DEVICE_ID,
        DeviceToolTerminalKind.CANCELLED,
        error = DeviceClientWireError("ATTENTION_CANCELLED", "Attention request was cancelled"),
    )

    private fun task(taskId: String) = TaskEntity(
        taskId = taskId,
        title = "Pi proof",
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
            .build()
        ledger = RoomAttentionLedger(database) { clock.getAndIncrement() }
        store = RoomProjectionTransactionStore(database) { clock.getAndIncrement() }
    }

    private fun reopenDatabase() {
        database.close()
        openDatabase()
    }

    private enum class ProofMutation {
        TOOL_CALL_ID,
        TOOL_NAME,
        CALL_ID,
        HASH,
        CONTENT,
        IS_ERROR,
        SIDE_EFFECT,
        EXTRA_DETAIL,
        MISSING_DETAILS,
        MULTIPLE_CONTENT,
        RECOVERY_STATE,
        MANUAL_REVIEW,
        STRING_IS_ERROR,
        STRING_SIDE_EFFECT,
        STRING_RECOVERY,
        STRING_MANUAL_REVIEW,
    }

    private enum class DetailsShape {
        EXACT,
        EMPTY,
        MISSING,
        ARRAY,
        STRING,
        NULL_VALUE,
        PARTIAL,
        EXTRA,
    }

    private companion object {
        const val DATABASE_NAME = "p2-7e-pi-delivery-proof.db"
        const val TASK_ID = "11111111-1111-4111-8111-111111111111"
        const val OTHER_TASK_ID = "11111111-1111-4111-8111-111111111112"
        const val CALL_ID = "22222222-2222-4222-8222-222222222221"
        const val DEVICE_ID = "android-p2-7-device"
        const val OPERATION_ID = "33333333-3333-4333-8333-333333333333"
        const val PI_SESSION_ID = "44444444-4444-4444-8444-444444444444"
        const val STREAM_ID = "55555555-5555-4555-8555-555555555555"
        val NOW = Instant.parse("2026-07-17T01:00:00.000Z").toEpochMilli()
        val EXPIRES_AT_MILLIS = Instant.parse("2030-01-01T00:00:00.000Z").toEpochMilli()
        const val EXPIRES_AT_TEXT = "2030-01-01T00:00:00.000Z"

        fun callId(suffix: Int): String =
            "22222222-2222-4222-8222-${suffix.toString().padStart(12, '0')}"
    }
}
