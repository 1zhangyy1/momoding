package app.momoding.core.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.momoding.core.transport.ClientWireCodec
import app.momoding.wire.DeviceToolResultClientFrame
import app.momoding.wire.DeviceToolTerminalKind
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TaskDetailRepositoryTest {
    private lateinit var database: MomodingDatabase

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            MomodingDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `atomic task detail read includes ordered timeline and durable stop fence`() = runTest {
        val dao = database.p2Dao()
        dao.upsertTask(task(windowEnd = 2))
        dao.insertTimeline(
            listOf(
                timeline("message-1", 0, """{"role":"user","content":[{"type":"text","text":"first"}]}"""),
                timeline("message-2", 1, """{"role":"assistant","content":[{"type":"text","text":"second"}]}"""),
            ),
        )
        val attentionLedger = RoomAttentionLedger(database) { 10L }
        attentionLedger.acceptRequest(
            AttentionRequestRecord(
                callId = ATTENTION_CALL_ID,
                taskId = TASK_ID,
                piToolCallId = "pi-$ATTENTION_CALL_ID",
                deviceId = DEVICE_ID,
                toolName = "request_user_question",
                arguments = buildJsonObject { put("question", "Continue?") },
                sideEffect = false,
                operationId = null,
                expiresAt = "2030-01-01T00:00:00.000Z",
                capabilityVersion = 1,
            ),
            AttentionAcceptanceScope(TASK_ID, DEVICE_ID, 1, "composer"),
        )
        attentionLedger.recordTerminal(
            AttentionTerminalWrite(
                frame = DeviceToolResultClientFrame(
                    callId = ATTENTION_CALL_ID,
                    taskId = TASK_ID,
                    deviceId = DEVICE_ID,
                    terminal = DeviceToolTerminalKind.SUCCEEDED,
                    result = buildJsonObject { put("outcome", "skipped") },
                ),
                origin = AttentionTerminalOrigin.USER,
                nowMillis = 10L,
            ),
        )
        attentionLedger.acceptRequest(
            AttentionRequestRecord(
                callId = PENDING_ATTENTION_CALL_ID,
                taskId = TASK_ID,
                piToolCallId = "pi-$PENDING_ATTENTION_CALL_ID",
                deviceId = DEVICE_ID,
                toolName = "request_user_confirmation",
                arguments = buildJsonObject { put("summary", "Allow once?") },
                sideEffect = false,
                operationId = null,
                expiresAt = "2030-01-01T00:00:00.000Z",
                capabilityVersion = 1,
            ),
            AttentionAcceptanceScope(TASK_ID, DEVICE_ID, 1, "composer"),
        )
        dao.insertHostPendingAttention(
            listOf(
                HostPendingAttentionEntity(
                    taskId = TASK_ID,
                    ordinal = 0,
                    callId = PHANTOM_HOST_CALL_ID,
                    toolName = "request_user_confirmation",
                    argumentsJson = "{}",
                    state = "waiting",
                    expiresAt = null,
                    originFocusKey = null,
                    terminalResultJson = null,
                    rawPayload = "{\"secret\":\"must-not-project\"}",
                ),
            ),
        )
        val journal = RoomCommandDraftJournal(database, nowMillis = { 1L })
        journal.persistAccepted(
            REQUEST_ID,
            COMMAND_ID,
            "session.stop",
            TASK_ID,
            ClientWireCodec.encodeSessionStop(REQUEST_ID, COMMAND_ID, TASK_ID, "user"),
        )
        val repository = TaskDetailRepository(database)

        val fenced = repository.observe(TASK_ID).first()!!
        assertEquals(listOf("message-1", "message-2"), fenced.messages.map { it.stableItemId })
        assertEquals(
            listOf(PENDING_ATTENTION_CALL_ID, ATTENTION_CALL_ID),
            fenced.pendingAttention.map { it.callId },
        )
        assertEquals(AttentionResponseState.PENDING, fenced.pendingAttention.first().responseState)
        assertEquals(AttentionResponseState.RESPONDING, fenced.pendingAttention.last().responseState)
        assertTrue(fenced.activeStopFence)

        journal.markTerminal(
            REQUEST_ID,
            """{"protocolVersion":1,"kind":"response","requestId":"$REQUEST_ID","ok":true,"data":{"accepted":true,"runState":"stopped"}}""",
        )
        assertFalse(repository.observe(TASK_ID).first()!!.activeStopFence)
    }

    @Test
    fun `noncontiguous timeline fails closed`() {
        val dao = database.p2Dao()
        dao.upsertTask(task(windowEnd = 2))
        dao.insertTimeline(
            listOf(timeline("message-2", 1, """{"role":"user","content":[{"type":"text","text":"gap"}]}""")),
        )

        assertThrows(IllegalArgumentException::class.java) {
            kotlinx.coroutines.runBlocking { TaskDetailRepository(database).observe(TASK_ID).first() }
        }
    }

    private fun task(windowEnd: Long) = TaskEntity(
        taskId = TASK_ID,
        title = "Task shell",
        runState = "RUNNING",
        recoveryState = "NORMAL",
        readState = "READ",
        attentionState = "NONE",
        streamId = null,
        throughSequence = 0,
        snapshotVersion = 1,
        windowStart = 0,
        windowEndExclusive = windowEnd,
        nextStageBatchOrdinal = 0,
        queueJson = "[]",
        piSessionId = null,
        isStreaming = true,
        updatedAtMillis = 1,
    )

    private fun timeline(stableId: String, ordinal: Long, raw: String) = TimelineProjectionEntity(
        taskId = TASK_ID,
        stableItemId = stableId,
        ordinal = ordinal,
        kind = "pi-message",
        rawPayload = raw,
        presentationJson = "{}",
    )

    private companion object {
        const val TASK_ID = "11111111-1111-4111-8111-111111111111"
        const val REQUEST_ID = "22222222-2222-4222-8222-222222222222"
        const val COMMAND_ID = "33333333-3333-4333-8333-333333333333"
        const val ATTENTION_CALL_ID = "44444444-4444-4444-8444-444444444444"
        const val PENDING_ATTENTION_CALL_ID = "44444444-4444-4444-8444-444444444445"
        const val PHANTOM_HOST_CALL_ID = "55555555-5555-4555-8555-555555555555"
        const val DEVICE_ID = "android-p2-7-device"
    }
}
