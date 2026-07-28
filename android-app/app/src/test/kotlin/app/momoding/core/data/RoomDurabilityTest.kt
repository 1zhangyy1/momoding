package app.momoding.core.data

import android.content.Context
import android.database.sqlite.SQLiteException
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.momoding.wire.AckReady
import app.momoding.wire.ProjectionTransaction
import app.momoding.wire.ProjectionTransactionStore
import app.momoding.wire.ProjectionWriteStage
import app.momoding.wire.RawFrameRetentionPolicy
import app.momoding.wire.RawPiEventRecord
import app.momoding.wire.ReceiverFailure
import app.momoding.wire.ReliabilityReceiver
import app.momoding.wire.SnapshotRequired
import app.momoding.wire.DurableTaskProjection
import app.momoding.wire.RecoveryState
import app.momoding.wire.StagedRawFrame
import app.momoding.wire.StagedRawFrameBatch
import app.momoding.wire.TaskRunState
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.ExecutionException
import java.util.concurrent.atomic.AtomicLong
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertArrayEquals
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
class RoomDurabilityTest {
    private lateinit var context: Context
    private lateinit var database: MomodingDatabase
    private lateinit var tempRoot: Path
    private val executor = Executors.newSingleThreadExecutor()
    private val clock = AtomicLong(1_000)

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(DATABASE_NAME)
        tempRoot = Files.createTempDirectory("momoding-room-test-")
        database = openRobolectricDatabase()
    }

    @After
    fun tearDown() {
        onIo { database.close() }
        context.deleteDatabase(DATABASE_NAME)
        tempRoot.toFile().deleteRecursively()
        executor.shutdownNow()
    }

    @Test
    fun `commit precedes ACK and exact state survives restart duplicate and resync fence`() {
        val firstStore = RoomProjectionTransactionStore(database) { clock.getAndIncrement() }
        val event = eventFrame(1, "first")
        val firstActions = onIo {
            ReliabilityReceiver(tempRoot.resolve("first"), firstStore).use { receiver ->
                receiver.receive(event)
            }
        }
        assertEquals(2, firstActions.size)
        assertEquals(1, (firstActions[1] as AckReady).frame.throughSequence)

        val beforeRestart = onIo { firstStore.read(TASK_ID) }
        assertNotNull(beforeRestart)
        val firstRecord = beforeRestart!!.rawEvents.getValue(1)
        assertArrayEquals(event, firstRecord.rawBytes)
        assertEquals("first", firstRecord.event["fixture"]?.let { (it as JsonPrimitive).content })
        assertEquals(1, beforeRestart.stagedRawFrameBatches.size)
        assertArrayEquals(event, beforeRestart.stagedRawFrameBatches.single().frames.single().rawBytes)

        reopenDatabase()
        val counting = CountingStore(RoomProjectionTransactionStore(database) { clock.getAndIncrement() })
        val afterRestart = onIo { counting.read(TASK_ID) }
        assertProjectionBytesEqual(beforeRestart, afterRestart!!)
        val duplicateActions = onIo {
            ReliabilityReceiver(tempRoot.resolve("duplicate"), counting).use { receiver ->
                receiver.receive(event)
            }
        }
        assertEquals(2, duplicateActions.size)
        assertEquals(0, counting.transactionCount)

        val resyncActions = onIo {
            ReliabilityReceiver(tempRoot.resolve("resync"), counting).use { receiver ->
                receiver.receive(resyncRequiredFrame())
            }
        }
        assertTrue(resyncActions.none { it is AckReady })
        assertNotNull(onIo { counting.read(TASK_ID) }?.pendingResync)

        reopenDatabase()
        val restarted = CountingStore(RoomProjectionTransactionStore(database) { clock.getAndIncrement() })
        val fencedActions = onIo {
            ReliabilityReceiver(tempRoot.resolve("fenced"), restarted).use { receiver ->
                receiver.receive(eventFrame(2, "must-not-commit"))
            }
        }
        assertTrue(fencedActions.single() is ReceiverFailure)
        assertEquals(0, restarted.transactionCount)
        assertEquals(1L, onIo { restarted.read(TASK_ID) }?.throughSequence)

        val replacementActions = onIo {
            ReliabilityReceiver(tempRoot.resolve("replace"), restarted).use { receiver ->
                receiver.receive(directSnapshotFrame(highWatermark = 2, snapshotVersion = 7))
            }
        }
        assertEquals(2, replacementActions.size)
        assertEquals(2, (replacementActions[1] as AckReady).frame.throughSequence)
        val replaced = onIo { restarted.read(TASK_ID) }!!
        assertNull(replaced.pendingResync)
        assertTrue(replaced.rawEvents.isEmpty())
        assertEquals(listOf(3L), replaced.stagedRawFrameBatches.map { it.batchOrdinal })
    }

    @Test
    fun `command and draft journals are idempotent exact and restart durable`() {
        var journal = RoomCommandDraftJournal(database) { clock.getAndIncrement() }
        val createPayload = "{\"kind\":\"task.create\",\"prompt\":\"hello\"}"
        val accepted = onIo {
            journal.persistAccepted("request-create", CREATE_COMMAND_ID, "task.create", null, createPayload)
        }
        assertEquals(OutboundCommandState.ACCEPTED, accepted.state)
        val terminal = onIo {
            journal.markTerminal(
                "request-create",
                successResponse(
                    "request-create",
                    "{\"taskId\":\"$TASK_ID\",\"piSessionId\":\"$PI_SESSION_ID\"}",
                ),
            )
        }
        assertEquals(OutboundCommandState.TERMINAL, terminal.state)
        assertEquals(terminal, onIo {
            journal.persistAccepted("request-create", CREATE_COMMAND_ID, "task.create", null, createPayload)
        })
        assertThrows(JournalConflictException::class.java) {
            onIo {
                journal.persistAccepted(
                    "request-create",
                    CREATE_COMMAND_ID,
                    "task.create",
                    null,
                    "{\"kind\":\"task.create\",\"prompt\":\"changed\"}",
                )
            }
        }

        val draft = DraftRecord(
            draftId = "draft-1",
            text = "line one\nline two",
            selectedHostId = "host-local",
            selectedModelId = "deepseek/deepseek-v4-pro",
            selectedMode = "ask-before-edits",
            createCommandId = CREATE_COMMAND_ID,
            promptCommandId = PROMPT_COMMAND_ID,
            taskId = null,
            updatedAtMillis = clock.getAndIncrement(),
        )
        onIo { journal.saveDraft(draft) }
        val bound = onIo { journal.bindDraftTask("draft-1", TASK_ID) }
        assertEquals(TASK_ID, bound.taskId)
        assertThrows(JournalConflictException::class.java) {
            onIo { journal.bindDraftTask("draft-1", OTHER_TASK_ID) }
        }
        onIo {
            journal.persistAccepted(
                "request-prompt",
                PROMPT_COMMAND_ID,
                "session.prompt",
                TASK_ID,
                "{\"kind\":\"session.prompt\",\"taskId\":\"$TASK_ID\"}",
            )
        }

        reopenDatabase()
        journal = RoomCommandDraftJournal(database) { clock.getAndIncrement() }
        assertEquals(terminal, onIo { journal.command("request-create") })
        assertEquals(listOf(PROMPT_COMMAND_ID), onIo { journal.pendingCommands() }.map { it.commandId })
        val restoredDraft = onIo { journal.draft("draft-1") }!!
        assertEquals("line one\nline two", restoredDraft.text)
        assertEquals(TASK_ID, restoredDraft.taskId)
        assertEquals(CREATE_COMMAND_ID, restoredDraft.createCommandId)
        assertEquals(PROMPT_COMMAND_ID, restoredDraft.promptCommandId)

        onIo {
            database.openHelper.writableDatabase.execSQL(
                "UPDATE outbound_commands SET responseJson = '{\"ok\":false}' " +
                    "WHERE requestId = 'request-create'",
            )
        }
        assertThrows(JournalConflictException::class.java) {
            onIo { journal.command("request-create") }
        }
        onIo {
            database.openHelper.writableDatabase.execSQL(
                "UPDATE outbound_commands SET state = 'BROKEN' WHERE requestId = 'request-prompt'",
            )
        }
        assertThrows(JournalConflictException::class.java) {
            onIo { journal.pendingCommands() }
        }
    }

    @Test
    fun `mutating identities and draft UUIDs fail closed before durable insert`() {
        var journal = RoomCommandDraftJournal(database) { clock.getAndIncrement() }
        val payload = "{\"kind\":\"session.stop\",\"taskId\":\"$TASK_ID\"}"
        listOf<String?>(null, "", "not-a-uuid").forEachIndexed { index, commandId ->
            assertThrows(IllegalArgumentException::class.java) {
                onIo {
                    journal.persistAccepted(
                        "invalid-stop-$index",
                        commandId,
                        "session.stop",
                        TASK_ID,
                        payload,
                    )
                }
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            onIo {
                journal.persistAccepted(
                    "unsupported-kind",
                    STOP_COMMAND_ID,
                    "session.unknown",
                    TASK_ID,
                    "{\"kind\":\"session.unknown\"}",
                )
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            onIo {
                journal.persistAccepted(
                    "read-only-with-command",
                    STOP_COMMAND_ID,
                    "task.open",
                    TASK_ID,
                    "{\"kind\":\"task.open\",\"taskId\":\"$TASK_ID\"}",
                )
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            onIo {
                journal.persistAccepted(
                    "stop-without-task",
                    STOP_COMMAND_ID,
                    "session.stop",
                    null,
                    "{\"kind\":\"session.stop\"}",
                )
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            onIo {
                journal.persistAccepted(
                    "payload-kind-mismatch",
                    STOP_COMMAND_ID,
                    "session.stop",
                    TASK_ID,
                    "{\"kind\":\"session.prompt\",\"taskId\":\"$TASK_ID\"}",
                )
            }
        }
        assertTrue(onIo { database.p2Dao().outboundCommands() }.isEmpty())

        val canonical = onIo {
            journal.persistAccepted(
                "canonical-create",
                CREATE_COMMAND_ID.uppercase(),
                "task.create",
                null,
                "{\"kind\":\"task.create\"}",
            )
        }
        assertEquals(CREATE_COMMAND_ID, canonical.commandId)
        assertThrows(JournalConflictException::class.java) {
            onIo {
                journal.persistAccepted(
                    "duplicate-command-id",
                    CREATE_COMMAND_ID,
                    "task.create",
                    null,
                    "{\"kind\":\"task.create\"}",
                )
            }
        }
        val readOnly = onIo {
            journal.persistAccepted(
                "read-only",
                null,
                "task.open",
                TASK_ID,
                "{\"kind\":\"task.open\",\"taskId\":\"$TASK_ID\"}",
            )
        }
        assertNull(readOnly.commandId)

        val invalidDrafts = listOf(
            "" to PROMPT_COMMAND_ID,
            "not-a-uuid" to PROMPT_COMMAND_ID,
            CREATE_COMMAND_ID to "not-a-uuid",
        )
        invalidDrafts.forEachIndexed { index, (createId, promptId) ->
            assertThrows(IllegalArgumentException::class.java) {
                onIo {
                    journal.saveDraft(
                        DraftRecord(
                            draftId = "invalid-draft-$index",
                            text = "invalid",
                            selectedHostId = null,
                            selectedModelId = null,
                            selectedMode = null,
                            createCommandId = createId,
                            promptCommandId = promptId,
                            taskId = null,
                            updatedAtMillis = clock.getAndIncrement(),
                        ),
                    )
                }
            }
        }
        assertTrue(onIo { database.p2Dao().drafts() }.isEmpty())

        val normalizedDraft = onIo {
            journal.saveDraft(
                DraftRecord(
                    draftId = "canonical-draft",
                    text = "ready",
                    selectedHostId = null,
                    selectedModelId = null,
                    selectedMode = null,
                    createCommandId = CREATE_COMMAND_ID.uppercase(),
                    promptCommandId = PROMPT_COMMAND_ID.uppercase(),
                    taskId = null,
                    updatedAtMillis = clock.getAndIncrement(),
                ),
            )
        }
        assertEquals(CREATE_COMMAND_ID, normalizedDraft.createCommandId)
        assertEquals(PROMPT_COMMAND_ID, normalizedDraft.promptCommandId)

        reopenDatabase()
        journal = RoomCommandDraftJournal(database) { clock.getAndIncrement() }
        assertEquals(canonical, onIo {
            journal.persistAccepted(
                "canonical-create",
                CREATE_COMMAND_ID,
                "task.create",
                null,
                "{\"kind\":\"task.create\"}",
            )
        })
        assertEquals(CREATE_COMMAND_ID, onIo { journal.draft("canonical-draft") }?.createCommandId)
    }

    @Test
    fun `stop fence survives invalid error and stopping responses until authoritative stopped`() {
        var journal = RoomCommandDraftJournal(database) { clock.getAndIncrement() }
        val payload = "{\"kind\":\"session.stop\",\"taskId\":\"$TASK_ID\"}"
        onIo {
            journal.persistAccepted("stop-invalid", STOP_COMMAND_ID, "session.stop", TASK_ID, payload)
        }
        listOf(
            "true",
            "[]",
            "{\"protocolVersion\":1,\"kind\":\"error\",\"requestId\":\"stop-invalid\",\"error\":{\"code\":\"BAD_REQUEST\",\"message\":\"fixture\",\"retryable\":false}}",
            successResponse(
                "wrong-request",
                "{\"accepted\":true,\"runState\":\"stopping\"}",
            ),
            successResponse("stop-invalid", "{\"accepted\":true}"),
        ).forEach { invalidResponse ->
            assertThrows(IllegalArgumentException::class.java) {
                onIo { journal.markTerminal("stop-invalid", invalidResponse) }
            }
        }
        assertEquals(OutboundCommandState.ACCEPTED, onIo { journal.command("stop-invalid") }?.state)
        assertTrue(onIo { journal.hasPendingStopFence(TASK_ID) })
        onIo {
            database.openHelper.writableDatabase.execSQL(
                "UPDATE outbound_commands SET stopFenceState = 'RELEASED' " +
                    "WHERE requestId = 'stop-invalid'",
            )
        }
        assertThrows(JournalConflictException::class.java) {
            onIo { journal.hasPendingStopFence(TASK_ID) }
        }
        onIo {
            database.openHelper.writableDatabase.execSQL(
                "UPDATE outbound_commands SET stopFenceState = 'ACTIVE' " +
                    "WHERE requestId = 'stop-invalid'",
            )
        }

        onIo {
            journal.persistAccepted(
                "stop-error",
                "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb",
                "session.stop",
                OTHER_TASK_ID,
                "{\"kind\":\"session.stop\",\"taskId\":\"$OTHER_TASK_ID\"}",
            )
            journal.markTerminal("stop-error", errorResponse("stop-error"))
        }
        assertTrue(onIo { journal.hasPendingStopFence(OTHER_TASK_ID) })
        onIo {
            database.openHelper.writableDatabase.execSQL(
                "UPDATE outbound_commands SET stopFenceState = 'RELEASED' " +
                    "WHERE requestId = 'stop-error'",
            )
        }
        assertThrows(JournalConflictException::class.java) {
            onIo { journal.hasPendingStopFence(OTHER_TASK_ID) }
        }
        onIo {
            database.openHelper.writableDatabase.execSQL(
                "UPDATE outbound_commands SET stopFenceState = 'ACTIVE' " +
                    "WHERE requestId = 'stop-error'",
            )
        }

        val stoppingTaskId = "12121212-1212-4121-8121-121212121212"
        onIo {
            journal.persistAccepted(
                "stop-stopping",
                "cccccccc-cccc-4ccc-8ccc-cccccccccccc",
                "session.stop",
                stoppingTaskId,
                "{\"kind\":\"session.stop\",\"taskId\":\"$stoppingTaskId\"}",
            )
            journal.markTerminal(
                "stop-stopping",
                successResponse(
                    "stop-stopping",
                    "{\"accepted\":true,\"runState\":\"stopping\"}",
                ),
            )
        }
        assertTrue(onIo { journal.hasPendingStopFence(stoppingTaskId) })
        onIo {
            database.openHelper.writableDatabase.execSQL(
                "UPDATE outbound_commands SET stopFenceState = 'RELEASED' " +
                    "WHERE requestId = 'stop-stopping'",
            )
        }
        assertThrows(JournalConflictException::class.java) {
            onIo { journal.hasPendingStopFence(stoppingTaskId) }
        }
        onIo {
            database.openHelper.writableDatabase.execSQL(
                "UPDATE outbound_commands SET stopFenceState = 'ACTIVE' " +
                    "WHERE requestId = 'stop-stopping'",
            )
        }

        val stoppedTaskId = "13131313-1313-4131-8131-131313131313"
        onIo {
            journal.persistAccepted(
                "stop-stopped",
                "dddddddd-dddd-4ddd-8ddd-dddddddddddd",
                "session.stop",
                stoppedTaskId,
                "{\"kind\":\"session.stop\",\"taskId\":\"$stoppedTaskId\"}",
            )
            journal.markTerminal(
                "stop-stopped",
                successResponse(
                    "stop-stopped",
                    "{\"accepted\":true,\"runState\":\"stopped\"}",
                ),
            )
        }
        assertFalse(onIo { journal.hasPendingStopFence(stoppedTaskId) })

        reopenDatabase()
        journal = RoomCommandDraftJournal(database) { clock.getAndIncrement() }
        assertTrue(onIo { journal.hasPendingStopFence(TASK_ID) })
        assertTrue(onIo { journal.hasPendingStopFence(OTHER_TASK_ID) })
        assertTrue(onIo { journal.hasPendingStopFence(stoppingTaskId) })

        val store = RoomProjectionTransactionStore(database) { clock.getAndIncrement() }
        val stoppingProjection = DurableTaskProjection(
            taskId = stoppingTaskId,
            recoveryState = RecoveryState.NORMAL,
            runState = TaskRunState.STOPPING,
        )
        onIo { writeProjection(store, stoppingProjection) }
        assertTrue(onIo { journal.hasPendingStopFence(stoppingTaskId) })
        assertEquals(TaskRunState.STOPPING, onIo { store.read(stoppingTaskId) }?.runState)

        val stoppedProjection = stoppingProjection.copy(runState = TaskRunState.STOPPED)
        installFaultTrigger("fail_stop_fence_release", "outbound_commands", "UPDATE")
        assertThrows(SQLiteException::class.java) {
            onIo { writeProjection(store, stoppedProjection) }
        }
        dropTrigger("fail_stop_fence_release")
        assertProjectionBytesEqual(stoppingProjection, onIo { store.read(stoppingTaskId) }!!)
        assertTrue(onIo { journal.hasPendingStopFence(stoppingTaskId) })

        onIo { writeProjection(store, stoppedProjection) }
        assertFalse(onIo { journal.hasPendingStopFence(stoppingTaskId) })
        assertProjectionBytesEqual(stoppedProjection, onIo { store.read(stoppingTaskId) }!!)
        onIo { writeProjection(store, stoppedProjection) }
        assertProjectionBytesEqual(stoppedProjection, onIo { store.read(stoppingTaskId) }!!)
        assertFalse(onIo { journal.hasPendingStopFence(stoppingTaskId) })

        reopenDatabase()
        journal = RoomCommandDraftJournal(database) { clock.getAndIncrement() }
        assertFalse(onIo { journal.hasPendingStopFence(stoppingTaskId) })
        assertTrue(onIo { journal.hasPendingStopFence(OTHER_TASK_ID) })
        onIo {
            database.openHelper.writableDatabase.execSQL(
                "UPDATE outbound_commands SET stopFenceState = 'BROKEN' " +
                    "WHERE requestId = 'stop-error'",
            )
        }
        assertThrows(JournalConflictException::class.java) {
            onIo { journal.hasPendingStopFence(OTHER_TASK_ID) }
        }
    }

    @Test
    fun `direct chunk snapshot and history faults rollback every write and emit zero ACK`() {
        val store = RoomProjectionTransactionStore(database) { clock.getAndIncrement() }
        val baseline = DurableTaskProjection(
            taskId = TASK_ID,
            streamId = STREAM_ID,
            throughSequence = 0,
            snapshotVersion = 1,
            windowStart = 1,
            windowEndExclusive = 2,
            messages = listOf(JsonObject(mapOf("type" to JsonPrimitive("assistant")))),
            recoveryState = RecoveryState.NORMAL,
            runState = TaskRunState.IDLE,
            piSessionId = PI_SESSION_ID,
            nextStageBatchOrdinal = 2,
            stagedRawFrameBatches = listOf(
                StagedRawFrameBatch(
                    batchOrdinal = 1,
                    batchKind = "baseline",
                    frames = listOf(StagedRawFrame("baseline", "baseline".encodeToByteArray())),
                ),
            ),
        )
        onIo { writeProjection(store, baseline) }

        installFaultTrigger("fail_direct_raw", "raw_pi_events", "INSERT")
        val direct = receiveOnce(store, "fault-direct", eventFrame(1, "direct-fault"))
        dropTrigger("fail_direct_raw")
        assertRollbackFailure(direct, baseline, store)

        installFaultTrigger("fail_chunk_raw", "raw_pi_events", "INSERT")
        val chunkActions = onIo {
            ReliabilityReceiver(tempRoot.resolve("fault-chunk"), store).use { receiver ->
                chunkedEventFrames(1).flatMap(receiver::receive)
            }
        }
        dropTrigger("fail_chunk_raw")
        assertTrue(chunkActions.last() is ReceiverFailure)
        assertTrue(chunkActions.none { it is AckReady })
        assertProjectionBytesEqual(baseline, onIo { store.read(TASK_ID) }!!)

        installFaultTrigger("fail_snapshot_timeline", "timeline_projections", "INSERT")
        val snapshot = receiveOnce(
            store,
            "fault-snapshot",
            directSnapshotFrame(highWatermark = 0, snapshotVersion = 2),
        )
        dropTrigger("fail_snapshot_timeline")
        assertRollbackFailure(snapshot, baseline, store)

        installFaultTrigger("fail_history_staged", "staged_raw_frames", "INSERT")
        val history = onIo {
            ReliabilityReceiver(tempRoot.resolve("fault-history"), store).use { receiver ->
                receiver.registerHistoryRequest(
                    requestId = "history-fault",
                    taskId = TASK_ID,
                    snapshotVersion = 1,
                    limitBytes = 8L * 1024 * 1024,
                )
                historyFrames().flatMap(receiver::receive)
            }
        }
        dropTrigger("fail_history_staged")
        assertTrue(history.last() is ReceiverFailure)
        assertTrue(history.none { it is AckReady })
        assertProjectionBytesEqual(baseline, onIo { store.read(TASK_ID) }!!)

        installFaultTrigger("fail_cursor", "tasks", "UPDATE")
        val cursor = receiveOnce(store, "fault-cursor", eventFrame(1, "cursor-fault"))
        dropTrigger("fail_cursor")
        assertRollbackFailure(cursor, baseline, store)
    }

    @Test
    fun `prior batch compaction faults rollback exact state for every mutation path`() {
        val store = RoomProjectionTransactionStore(database) { clock.getAndIncrement() }
        val baseline = compactionBaseline()
        onIo { writeProjection(store, baseline) }

        installFaultTrigger("fail_direct_compaction", "staged_raw_frames", "DELETE", "AFTER")
        val direct = receiveOnce(store, "compact-direct", eventFrame(2, "direct-compaction"))
        dropTrigger("fail_direct_compaction")
        assertRollbackFailure(direct, baseline, store)

        installFaultTrigger("fail_chunk_compaction", "staged_raw_frames", "DELETE", "AFTER")
        val chunk = onIo {
            ReliabilityReceiver(tempRoot.resolve("compact-chunk"), store).use { receiver ->
                chunkedEventFrames(2).flatMap(receiver::receive)
            }
        }
        dropTrigger("fail_chunk_compaction")
        assertTrue(chunk.last() is ReceiverFailure)
        assertTrue(chunk.none { it is AckReady })
        assertProjectionBytesEqual(baseline, onIo { store.read(TASK_ID) }!!)

        installFaultTrigger("fail_snapshot_compaction", "staged_raw_frames", "DELETE", "AFTER")
        val snapshot = receiveOnce(
            store,
            "compact-snapshot",
            directSnapshotFrame(highWatermark = 1, snapshotVersion = 2),
        )
        dropTrigger("fail_snapshot_compaction")
        assertRollbackFailure(snapshot, baseline, store)

        installFaultTrigger("fail_history_compaction", "staged_raw_frames", "DELETE", "AFTER")
        val history = onIo {
            ReliabilityReceiver(tempRoot.resolve("compact-history"), store).use { receiver ->
                receiver.registerHistoryRequest(
                    requestId = "history-fault",
                    taskId = TASK_ID,
                    snapshotVersion = 1,
                    limitBytes = 8L * 1024 * 1024,
                )
                historyFrames(highWatermark = 1).flatMap(receiver::receive)
            }
        }
        dropTrigger("fail_history_compaction")
        assertTrue(history.last() is ReceiverFailure)
        assertTrue(history.none { it is AckReady })
        assertProjectionBytesEqual(baseline, onIo { store.read(TASK_ID) }!!)

        val committed = receiveOnce(store, "compact-success", eventFrame(2, "compaction-success"))
        assertEquals(2, committed.size)
        assertTrue(committed.last() is AckReady)
        val afterCompaction = onIo { store.read(TASK_ID) }!!
        assertEquals(listOf(2L, 3L), afterCompaction.stagedRawFrameBatches.map { it.batchOrdinal })
        assertEquals(
            RawFrameRetentionPolicy.MAX_STAGED_AUDIT_FRAMES,
            afterCompaction.stagedRawFrameBatches.sumOf { it.frames.size },
        )
        assertEquals("pi.event", afterCompaction.stagedRawFrameBatches.last().batchKind)

        reopenDatabase()
        assertProjectionBytesEqual(
            afterCompaction,
            onIo { RoomProjectionTransactionStore(database).read(TASK_ID) }!!,
        )
    }

    @Test
    fun `successful history keeps existing stable IDs and restarts as one complete batch`() {
        val store = RoomProjectionTransactionStore(database) { clock.getAndIncrement() }
        val recent = JsonObject(
            mapOf("type" to JsonPrimitive("assistant"), "text" to JsonPrimitive("recent")),
        )
        val baseline = DurableTaskProjection(
            taskId = TASK_ID,
            streamId = STREAM_ID,
            snapshotVersion = 1,
            windowStart = 1,
            windowEndExclusive = 2,
            messages = listOf(recent),
            recoveryState = RecoveryState.NORMAL,
            runState = TaskRunState.IDLE,
            piSessionId = PI_SESSION_ID,
            nextStageBatchOrdinal = 2,
            stagedRawFrameBatches = listOf(
                StagedRawFrameBatch(1, "baseline", listOf(StagedRawFrame("baseline", byteArrayOf(1)))),
            ),
        )
        onIo { writeProjection(store, baseline) }
        val stableBefore = onIo { database.p2Dao().timeline(TASK_ID).single().stableItemId }

        val actions = onIo {
            ReliabilityReceiver(tempRoot.resolve("history-success"), store).use { receiver ->
                receiver.registerHistoryRequest(
                    requestId = "history-fault",
                    taskId = TASK_ID,
                    snapshotVersion = 1,
                    limitBytes = 8L * 1024 * 1024,
                )
                historyFrames().flatMap(receiver::receive)
            }
        }
        assertTrue(actions.none { it is AckReady })
        assertFalse(actions.any { it is ReceiverFailure })
        val committed = onIo { store.read(TASK_ID) }!!
        assertEquals(0L, committed.windowStart)
        assertEquals(2L, committed.windowEndExclusive)
        assertEquals(2, committed.messages.size)
        assertEquals(listOf(1L, 2L), committed.stagedRawFrameBatches.map { it.batchOrdinal })
        assertEquals(3, committed.stagedRawFrameBatches.last().frames.size)
        val stableAfter = onIo {
            database.p2Dao().timeline(TASK_ID).single { it.ordinal == 1L }.stableItemId
        }
        assertEquals(stableBefore, stableAfter)

        reopenDatabase()
        val restarted = onIo { RoomProjectionTransactionStore(database).read(TASK_ID) }!!
        assertProjectionBytesEqual(committed, restarted)
    }

    @Test
    fun `host list merge preserves local projection orders by host time and rolls back atomically`() {
        var store = RoomProjectionTransactionStore(database) { clock.getAndIncrement() }
        val message = JsonObject(
            mapOf("type" to JsonPrimitive("assistant"), "text" to JsonPrimitive("local detail")),
        )
        onIo {
            writeProjection(
                store,
                DurableTaskProjection(
                    taskId = TASK_ID,
                    streamId = STREAM_ID,
                    snapshotVersion = 1,
                    windowStart = 0,
                    windowEndExclusive = 1,
                    messages = listOf(message),
                    recoveryState = RecoveryState.NORMAL,
                    runState = TaskRunState.IDLE,
                    piSessionId = PI_SESSION_ID,
                ),
            )
        }
        val localBeforeList = onIo { database.p2Dao().task(TASK_ID) }!!
        val merger = RoomTaskListMerger(
            database = database,
            nowMillis = { clock.getAndIncrement() },
            generationFactory = { "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa" },
        )
        val firstGeneration = onIo {
            merger.mergeCompleteList(
                listRevision = 7,
                summaries = listOf(
                    hostSummary(OTHER_TASK_ID, "newer", 3_000, 2),
                    hostSummary(TASK_ID, "existing", 2_000, 3),
                ),
            )
        }
        assertEquals("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", firstGeneration)
        val firstListed = onIo { merger.listedTasks() }
        assertEquals(listOf(OTHER_TASK_ID, TASK_ID), firstListed.map { it.taskId })
        val mergedExisting = firstListed.single { it.taskId == TASK_ID }
        assertEquals(localBeforeList.updatedAtMillis, mergedExisting.updatedAtMillis)
        assertEquals(STREAM_ID, mergedExisting.streamId)
        assertEquals("existing", mergedExisting.title)
        assertEquals(2_000L, mergedExisting.hostUpdatedAtMillis)
        assertEquals(7L, mergedExisting.lastListRevision)
        assertEquals(1, onIo { database.p2Dao().timeline(TASK_ID) }.size)

        onIo {
            writeProjection(
                store,
                DurableTaskProjection(
                    taskId = TASK_ID,
                    streamId = STREAM_ID,
                    snapshotVersion = 4,
                    windowStart = 0,
                    windowEndExclusive = 1,
                    messages = listOf(message),
                    recoveryState = RecoveryState.NORMAL,
                    runState = TaskRunState.RUNNING,
                    piSessionId = PI_SESSION_ID,
                    isStreaming = true,
                ),
            )
        }
        val afterProjection = onIo { database.p2Dao().task(TASK_ID) }!!
        assertTrue(afterProjection.updatedAtMillis > localBeforeList.updatedAtMillis)
        assertEquals("existing", afterProjection.title)
        assertEquals(2_000L, afterProjection.hostUpdatedAtMillis)
        assertEquals(firstGeneration, afterProjection.lastListSyncGeneration)
        assertEquals(7L, afterProjection.lastListRevision)

        reopenDatabase()
        store = RoomProjectionTransactionStore(database) { clock.getAndIncrement() }
        assertEquals(
            listOf(OTHER_TASK_ID, TASK_ID),
            onIo { RoomTaskListMerger(database).listedTasks() }.map { it.taskId },
        )
        assertEquals(2_000L, onIo { database.p2Dao().task(TASK_ID) }?.hostUpdatedAtMillis)
        assertEquals(1, onIo { database.p2Dao().timeline(TASK_ID) }.size)
        assertNotNull(onIo { store.read(TASK_ID) })

        val secondMerger = RoomTaskListMerger(
            database = database,
            nowMillis = { clock.getAndIncrement() },
            generationFactory = { "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb" },
        )
        onIo {
            secondMerger.mergeCompleteList(
                listRevision = 8,
                summaries = listOf(hostSummary(OTHER_TASK_ID, null, 4_000, 3)),
            )
        }
        assertEquals(listOf(OTHER_TASK_ID), onIo { secondMerger.listedTasks() }.map { it.taskId })
        assertEquals("", onIo { database.p2Dao().task(OTHER_TASK_ID) }?.title)
        assertFalse(onIo { database.p2Dao().task(TASK_ID) }!!.listedByHost)
        assertEquals(1, onIo { database.p2Dao().timeline(TASK_ID) }.size)

        val beforeFault = onIo { database.p2Dao().allTasks() }
        installFaultTrigger("fail_list_merge", "tasks", "UPDATE")
        assertThrows(SQLiteException::class.java) {
            onIo {
                RoomTaskListMerger(
                    database = database,
                    nowMillis = { clock.getAndIncrement() },
                    generationFactory = { "cccccccc-cccc-4ccc-8ccc-cccccccccccc" },
                ).mergeCompleteList(
                    listRevision = 9,
                    summaries = listOf(
                        hostSummary(TASK_ID, "would reorder", 5_000, 5),
                        hostSummary(OTHER_TASK_ID, "unchanged", 4_000, 3),
                    ),
                )
            }
        }
        dropTrigger("fail_list_merge")
        assertEquals(beforeFault, onIo { database.p2Dao().allTasks() })
        assertEquals(1, onIo { database.p2Dao().timeline(TASK_ID) }.size)
    }

    @Test
    fun `schema is versioned adds Android private file state and rejects main-thread access`() {
        val tables = onIo { database.p2Dao().tableNames() }.filterNot { it.startsWith("room_") }
        assertEquals(16, MomodingDatabase.SCHEMA_VERSION)
        assertEquals(
            setOf(
                "tasks",
                "raw_pi_events",
                "pending_resync",
                "staged_raw_frames",
                "timeline_projections",
                "pending_attention",
                "device_operations",
                "host_pending_attention_observations",
                "host_device_call_observations",
                "outbound_commands",
                "drafts",
                "authorized_folders",
                "task_content_grants",
                "file_change_sets",
                "pi_session_snapshots",
                "task_goals",
                "task_child_agents",
                "task_child_agent_events",
                "skills",
                "attachments",
                "android_metadata",
            ),
            tables.toSet(),
        )
        assertEquals(listOf("authorized_folders"), tables.filter { it == "authorized_folders" })
        assertEquals(listOf("task_content_grants"), tables.filter { it == "task_content_grants" })
        assertEquals(listOf("file_change_sets"), tables.filter { it == "file_change_sets" })
        assertEquals(listOf("pi_session_snapshots"), tables.filter { it == "pi_session_snapshots" })
        assertEquals(listOf("task_goals"), tables.filter { it == "task_goals" })
        assertEquals(listOf("task_child_agents"), tables.filter { it == "task_child_agents" })
        assertEquals(
            listOf("task_child_agent_events"),
            tables.filter { it == "task_child_agent_events" },
        )
        assertEquals(listOf("skills"), tables.filter { it == "skills" })
        assertEquals(listOf("attachments"), tables.filter { it == "attachments" })
        assertThrows(IllegalStateException::class.java) {
            database.p2Dao().task(TASK_ID)
        }
    }

    private fun reopenDatabase() {
        onIo { database.close() }
        database = openRobolectricDatabase()
    }

    private fun writeProjection(
        store: ProjectionTransactionStore,
        projection: DurableTaskProjection,
    ) {
        store.transaction(projection.taskId) { transaction ->
            transaction.checkpoint(ProjectionWriteStage.RAW)
            transaction.checkpoint(ProjectionWriteStage.PROJECTION)
            transaction.checkpoint(ProjectionWriteStage.CURSOR)
            transaction.replace(projection)
            transaction.checkpoint(ProjectionWriteStage.FINAL)
        }
    }

    private fun receiveOnce(
        store: ProjectionTransactionStore,
        directory: String,
        frame: ByteArray,
    ) = onIo {
        ReliabilityReceiver(tempRoot.resolve(directory), store).use { receiver ->
            receiver.receive(frame)
        }
    }

    private fun installFaultTrigger(
        name: String,
        table: String,
        operation: String,
        timing: String = "BEFORE",
    ) = onIo {
        require(name.matches(Regex("[a-z_]+")))
        require(
            table in setOf(
                "tasks",
                "raw_pi_events",
                "timeline_projections",
                "staged_raw_frames",
                "outbound_commands",
            ),
        )
        require(operation in setOf("INSERT", "UPDATE", "DELETE"))
        require(timing in setOf("BEFORE", "AFTER"))
        database.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER $name $timing $operation ON $table " +
                "BEGIN SELECT RAISE(ABORT, 'injected P2 rollback fault'); END",
        )
    }

    private fun dropTrigger(name: String) = onIo {
        database.openHelper.writableDatabase.execSQL("DROP TRIGGER IF EXISTS $name")
    }

    private fun assertRollbackFailure(
        actions: List<Any>,
        baseline: DurableTaskProjection,
        store: ProjectionTransactionStore,
    ) {
        assertEquals(1, actions.size)
        assertTrue(actions.single() is ReceiverFailure)
        assertTrue(actions.none { it is AckReady })
        assertProjectionBytesEqual(baseline, onIo { store.read(TASK_ID) }!!)
    }

    private fun openRobolectricDatabase(): MomodingDatabase = onIo {
        // BundledSQLiteDriver loads Android JNI and therefore cannot execute in Robolectric's
        // host JVM. The production factory remains fixed to that driver; these tests exercise
        // the same generated Room schema and DAO against Robolectric's file-backed driver.
        Room.databaseBuilder(context, MomodingDatabase::class.java, DATABASE_NAME).build()
    }

    private fun eventFrame(sequence: Long, fixture: String): ByteArray =
        """
        {"protocolVersion":1,"kind":"pi.event","taskId":"$TASK_ID","piSessionId":"$PI_SESSION_ID","piVersion":"0.80.6","streamId":"$STREAM_ID","sequence":$sequence,"emittedAt":"2026-07-16T00:00:00.000Z","event":{"type":"message_update","fixture":"$fixture"}}
        """.trimIndent().encodeToByteArray()

    private fun resyncRequiredFrame(): ByteArray =
        """
        {"protocolVersion":1,"kind":"pi.resync_required","taskId":"$TASK_ID","reason":"cursor_expired","requestedStreamId":"$STREAM_ID","currentStreamId":"$STREAM_ID","snapshotVersion":7}
        """.trimIndent().encodeToByteArray()

    private fun directSnapshotFrame(highWatermark: Long, snapshotVersion: Long): ByteArray =
        """
        {"kind":"task.snapshot","requestId":"snapshot-$snapshotVersion","taskId":"$TASK_ID","snapshotVersion":$snapshotVersion,"recoveryState":"normal","runState":"idle","piSessionId":"$PI_SESSION_ID","pi":{"messages":[{"type":"assistant","text":"restored"}],"isStreaming":false,"queue":[{"type":"queued","text":"keep"}]},"pendingAttention":[{"callId":"$ATTENTION_ID","toolName":"question","arguments":{"value":1},"state":"waiting","expiresAt":"2026-07-17T00:00:00.000Z","originFocusKey":"composer"}],"deviceCalls":[{"callId":"$DEVICE_CALL_ID","operationId":"$OPERATION_ID","state":"running"}],"cursor":{"streamId":"$STREAM_ID","highWatermarkSequence":$highWatermark,"oldestReplayableSequence":1}}
        """.trimIndent().encodeToByteArray()

    private fun chunkedEventFrames(sequence: Long): List<ByteArray> {
        val largeFixture = "x".repeat(1_100_000)
        val logical = eventFrame(sequence, largeFixture)
        val rawChunks = logical.asList().chunked(500_000).map { values ->
            values.toByteArray()
        }
        val transferId = "99999999-9999-4999-8999-999999999999"
        val start =
            "{\"protocolVersion\":1,\"kind\":\"transport.chunk.start\",\"transferId\":\"$transferId\",\"contentKind\":\"pi.event\",\"taskId\":\"$TASK_ID\",\"streamId\":\"$STREAM_ID\",\"sequence\":$sequence,\"totalBytes\":${logical.size},\"sha256\":\"${sha256(logical)}\",\"chunkCount\":${rawChunks.size}}"
                .encodeToByteArray()
        val data = rawChunks.mapIndexed { index, chunk ->
            val encoded = Base64.getEncoder().encodeToString(chunk)
            "{\"protocolVersion\":1,\"kind\":\"transport.chunk.data\",\"transferId\":\"$transferId\",\"chunkIndex\":$index,\"data\":\"$encoded\"}"
                .encodeToByteArray()
        }
        val end =
            "{\"protocolVersion\":1,\"kind\":\"transport.chunk.end\",\"transferId\":\"$transferId\"}"
                .encodeToByteArray()
        return listOf(start) + data + end
    }

    private fun historyFrames(highWatermark: Long = 0): List<ByteArray> {
        val begin =
            "{\"protocolVersion\":1,\"kind\":\"task.snapshot.begin\",\"requestId\":\"history-fault\",\"transferMode\":\"prepend_history\",\"taskId\":\"$TASK_ID\",\"snapshotVersion\":1,\"recoveryState\":\"normal\",\"runState\":\"idle\",\"piSessionId\":\"$PI_SESSION_ID\",\"isStreaming\":false,\"queue\":[],\"pendingAttention\":[],\"deviceCalls\":[],\"cursor\":{\"streamId\":\"$STREAM_ID\",\"highWatermarkSequence\":$highWatermark,\"oldestReplayableSequence\":1},\"totalMessages\":2,\"window\":{\"messageStartIndex\":0,\"messageEndExclusive\":1,\"hasMoreBefore\":false}}"
                .encodeToByteArray()
        val page =
            "{\"protocolVersion\":1,\"kind\":\"task.snapshot.page\",\"taskId\":\"$TASK_ID\",\"snapshotVersion\":1,\"pageIndex\":0,\"messageStartIndex\":0,\"messages\":[{\"type\":\"user\",\"text\":\"older\"}]}"
                .encodeToByteArray()
        val end =
            "{\"protocolVersion\":1,\"kind\":\"task.snapshot.end\",\"taskId\":\"$TASK_ID\",\"snapshotVersion\":1,\"pageCount\":1,\"sha256\":\"${orderedPageSha(listOf(page))}\"}"
                .encodeToByteArray()
        return listOf(begin, page, end)
    }

    private fun hostSummary(
        taskId: String,
        title: String?,
        updatedAtMillis: Long,
        snapshotVersion: Long,
    ) = HostTaskSummary(
        taskId = taskId,
        title = title,
        hostUpdatedAtMillis = updatedAtMillis,
        runState = "idle",
        recoveryState = "normal",
        snapshotVersion = snapshotVersion,
    )

    private fun compactionBaseline(): DurableTaskProjection {
        val rawFrame = eventFrame(1, "retained-before-compaction")
        val rawEvent = JsonObject(
            mapOf(
                "type" to JsonPrimitive("message_update"),
                "fixture" to JsonPrimitive("retained-before-compaction"),
            ),
        )
        val recent = JsonObject(
            mapOf("type" to JsonPrimitive("assistant"), "text" to JsonPrimitive("recent")),
        )
        return DurableTaskProjection(
            taskId = TASK_ID,
            streamId = STREAM_ID,
            throughSequence = 1,
            snapshotVersion = 1,
            windowStart = 1,
            windowEndExclusive = 2,
            messages = listOf(recent),
            recoveryState = RecoveryState.NORMAL,
            runState = TaskRunState.IDLE,
            piSessionId = PI_SESSION_ID,
            rawEvents = mapOf(
                1L to RawPiEventRecord(
                    sequence = 1,
                    digest = sha256(rawFrame),
                    rawBytes = rawFrame,
                    event = rawEvent,
                ),
            ),
            nextStageBatchOrdinal = 3,
            stagedRawFrameBatches = listOf(
                StagedRawFrameBatch(
                    batchOrdinal = 1,
                    batchKind = "oldest",
                    frames = listOf(StagedRawFrame("oldest", byteArrayOf(1))),
                ),
                StagedRawFrameBatch(
                    batchOrdinal = 2,
                    batchKind = "near-limit",
                    frames = List(RawFrameRetentionPolicy.MAX_STAGED_AUDIT_FRAMES - 1) {
                        StagedRawFrame("near-limit", byteArrayOf(2))
                    },
                ),
            ),
        )
    }

    private fun successResponse(requestId: String, data: String): String =
        "{\"protocolVersion\":1,\"kind\":\"response\",\"requestId\":\"$requestId\",\"ok\":true,\"data\":$data}"

    private fun errorResponse(requestId: String): String =
        "{\"protocolVersion\":1,\"kind\":\"response\",\"requestId\":\"$requestId\",\"ok\":false,\"error\":{\"code\":\"INVALID_SESSION_STATE\",\"message\":\"fixture\",\"retryable\":false}}"

    private fun orderedPageSha(pages: List<ByteArray>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        pages.forEach { page ->
            digest.update(ByteBuffer.allocate(Long.SIZE_BYTES).putLong(page.size.toLong()).array())
            digest.update(page)
        }
        return digest.digest().toHex()
    }

    private fun assertProjectionBytesEqual(
        expected: DurableTaskProjection,
        actual: DurableTaskProjection,
    ) {
        assertEquals(expected.taskId, actual.taskId)
        assertEquals(expected.streamId, actual.streamId)
        assertEquals(expected.throughSequence, actual.throughSequence)
        assertEquals(expected.snapshotVersion, actual.snapshotVersion)
        assertEquals(expected.windowStart, actual.windowStart)
        assertEquals(expected.windowEndExclusive, actual.windowEndExclusive)
        assertEquals(expected.messages, actual.messages)
        assertEquals(expected.queue, actual.queue)
        assertEquals(expected.pendingAttention, actual.pendingAttention)
        assertEquals(expected.deviceCalls, actual.deviceCalls)
        assertEquals(expected.recoveryState, actual.recoveryState)
        assertEquals(expected.runState, actual.runState)
        assertEquals(expected.piSessionId, actual.piSessionId)
        assertEquals(expected.isStreaming, actual.isStreaming)
        assertEquals(expected.pendingResync, actual.pendingResync)
        assertEquals(expected.nextStageBatchOrdinal, actual.nextStageBatchOrdinal)
        assertEquals(expected.rawEvents.keys, actual.rawEvents.keys)
        expected.rawEvents.forEach { (sequence, record) ->
            val restored = actual.rawEvents.getValue(sequence)
            assertEquals(record.digest, restored.digest)
            assertEquals(record.event, restored.event)
            assertArrayEquals(record.rawBytes, restored.rawBytes)
        }
        assertEquals(
            expected.stagedRawFrameBatches.map { it.batchOrdinal to it.batchKind },
            actual.stagedRawFrameBatches.map { it.batchOrdinal to it.batchKind },
        )
        expected.stagedRawFrameBatches.zip(actual.stagedRawFrameBatches).forEach { (left, right) ->
            assertEquals(left.frames.map { it.kind }, right.frames.map { it.kind })
            left.frames.zip(right.frames).forEach { (leftFrame, rightFrame) ->
                assertEquals(leftFrame.sha256, rightFrame.sha256)
                assertArrayEquals(leftFrame.rawBytes, rightFrame.rawBytes)
            }
        }
    }

    private fun <T> onIo(block: () -> T): T = try {
        executor.submit<T> { block() }.get()
    } catch (error: ExecutionException) {
        throw error.cause ?: error
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

    private fun ByteArray.toHex(): String = joinToString("") { byte ->
        "%02x".format(byte.toInt() and 0xff)
    }

    private class CountingStore(
        private val delegate: ProjectionTransactionStore,
    ) : ProjectionTransactionStore {
        var transactionCount: Int = 0
            private set

        override fun read(taskId: String): DurableTaskProjection? = delegate.read(taskId)

        override fun <T> transaction(
            taskId: String,
            block: (ProjectionTransaction) -> T,
        ): T {
            transactionCount += 1
            return delegate.transaction(taskId, block)
        }
    }

    private companion object {
        const val DATABASE_NAME = "room-durability-test.db"
        const val TASK_ID = "11111111-1111-4111-8111-111111111111"
        const val OTHER_TASK_ID = "99999999-9999-4999-8999-999999999999"
        const val PI_SESSION_ID = "22222222-2222-4222-8222-222222222222"
        const val STREAM_ID = "33333333-3333-4333-8333-333333333333"
        const val CREATE_COMMAND_ID = "44444444-4444-4444-8444-444444444444"
        const val PROMPT_COMMAND_ID = "55555555-5555-4555-8555-555555555555"
        const val STOP_COMMAND_ID = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        const val ATTENTION_ID = "66666666-6666-4666-8666-666666666666"
        const val DEVICE_CALL_ID = "77777777-7777-4777-8777-777777777777"
        const val OPERATION_ID = "88888888-8888-4888-8888-888888888888"
    }
}
