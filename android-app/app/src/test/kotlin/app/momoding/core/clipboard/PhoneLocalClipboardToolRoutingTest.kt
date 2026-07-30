package app.momoding.core.clipboard

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.momoding.core.data.AttentionLedgerState
import app.momoding.core.data.MomodingDatabase
import app.momoding.core.data.RoomAttentionLedger
import app.momoding.core.data.TaskEntity
import app.momoding.core.policy.TaskApprovalMode
import app.momoding.core.runtime.local.PhoneLocalAttentionBridge
import app.momoding.core.runtime.local.PiNativeToolRequest
import app.momoding.core.transport.AttentionUserDecision
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PhoneLocalClipboardToolRoutingTest {
    private lateinit var database: MomodingDatabase

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            MomodingDatabase::class.java,
        ).allowMainThreadQueries().build()
        database.momodingDao().upsertTask(task())
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `request approval stores no clipboard text and reads only after confirmation`() = runTest {
        val gateway = FakeGateway(
            ClipboardSnapshot.Text("PRIVATE_CLIPBOARD_READ", false),
        )
        val ledger = RoomAttentionLedger(database)
        val bridge = bridge(ledger, gateway, TaskApprovalMode.REQUEST_APPROVAL)

        assertNull(
            bridge.handleNativeRequest(
                TASK_ID,
                request("get", purpose = "PRIVATE_CLIPBOARD_PURPOSE"),
            ),
        )
        val pending = ledger.unterminatedRecords().single()
        assertEquals(AttentionLedgerState.AWAITING_USER.name, pending.operation.ledgerState)
        assertFalse(pending.operation.argumentsCanonicalJson.contains("PRIVATE_CLIPBOARD_READ"))
        assertFalse(pending.operation.argumentsCanonicalJson.contains("PRIVATE_CLIPBOARD_PURPOSE"))
        assertEquals(0, gateway.reads)

        bridge.submitDecision(AttentionUserDecision.Confirm(pending.operation.callId))

        assertEquals(1, gateway.reads)
        assertTrue(ledger.record(pending.operation.callId)?.operation?.terminalSha256 == null)
        ledger.discardLiveApprovedRead(TASK_ID, pending.operation.callId)
        assertNull(ledger.record(pending.operation.callId))
    }

    @Test
    fun `set approval persists only bounded metadata then dispatches and verifies once`() = runTest {
        val gateway = FakeGateway(ClipboardSnapshot.Empty)
        val ledger = RoomAttentionLedger(database)
        val bridge = bridge(ledger, gateway, TaskApprovalMode.REQUEST_APPROVAL)
        val privateText = "PRIVATE_CLIPBOARD_TEXT"

        assertNull(
            bridge.handleNativeRequest(
                TASK_ID,
                request("set", text = privateText, toolCallId = "pi-set"),
            ),
        )
        val pending = ledger.unterminatedRecords().single()
        assertFalse(pending.operation.argumentsCanonicalJson.contains(privateText))
        assertTrue(pending.operation.argumentsCanonicalJson.contains("\"characterCount\":22"))
        assertEquals(0, gateway.writes)

        bridge.submitDecision(AttentionUserDecision.Confirm(pending.operation.callId))

        assertEquals(1, gateway.writes)
        assertEquals(privateText, gateway.text)
        val terminal = requireNotNull(ledger.record(pending.operation.callId)).operation
        assertEquals(AttentionLedgerState.TERMINAL.name, terminal.ledgerState)
        assertFalse(terminal.terminalFrameCanonicalJson.orEmpty().contains(privateText))
        assertTrue(ledger.clipboardMutationWasDispatched(terminal))
        val expectation = requireNotNull(
            ledger.piDeliveryExpectationForValidatedPair(terminal),
        )
        assertEquals(
            "set",
            expectation.contentPayload.jsonObject["action"]?.jsonPrimitive?.content,
        )
    }

    @Test
    fun `automatic modes execute get set and clear without a visible prompt`() = runTest {
        val gateway = FakeGateway(ClipboardSnapshot.Text("ordinary note", false))
        val ledger = RoomAttentionLedger(database)
        val bridge = bridge(ledger, gateway, TaskApprovalMode.AUTO_APPROVE)

        val read = bridge.handleNativeRequest(TASK_ID, request("get", id = "native-get"))
        assertEquals(false, read?.isError)
        val set = bridge.handleNativeRequest(
            TASK_ID,
            request(
                "set",
                id = "native-set",
                toolCallId = "pi-set-auto",
                text = "replacement",
            ),
        )
        assertEquals(false, set?.isError)
        assertEquals("auto_policy", set?.details?.get("approvalOrigin")?.jsonPrimitive?.content)
        val clear = bridge.handleNativeRequest(
            TASK_ID,
            request("clear", id = "native-clear", toolCallId = "pi-clear-auto"),
        )
        assertEquals(false, clear?.isError)
        assertNull(gateway.text)
        assertTrue(ledger.unterminatedRecords().isEmpty())
    }

    @Test
    fun `verified duplicate mutation replays terminal without writing twice`() = runTest {
        val gateway = FakeGateway(ClipboardSnapshot.Empty)
        val ledger = RoomAttentionLedger(database)
        val bridge = bridge(ledger, gateway, TaskApprovalMode.REQUEST_APPROVAL)
        val original = request(
            "set",
            id = "native-original",
            toolCallId = "pi-stable-set",
            text = "write once",
        )
        assertNull(bridge.handleNativeRequest(TASK_ID, original))
        val pending = ledger.unterminatedRecords().single()
        bridge.submitDecision(AttentionUserDecision.Confirm(pending.operation.callId))
        assertEquals(1, gateway.writes)

        val replay = bridge.handleNativeRequest(
            TASK_ID,
            request(
                "set",
                id = "native-replay",
                toolCallId = "pi-stable-set",
                text = "write once",
            ),
        )

        assertEquals(false, replay?.isError)
        assertEquals(1, gateway.writes)
        assertEquals(
            "set",
            replay?.contentPayload?.get("action")?.jsonPrimitive?.content,
        )
    }

    @Test
    fun `dispatched host recovery becomes outcome unknown and never repeats the write`() =
        runTest {
            val gateway = FakeGateway(ClipboardSnapshot.Empty)
            val ledger = RoomAttentionLedger(database)
            val bridge = bridge(ledger, gateway, TaskApprovalMode.REQUEST_APPROVAL)
            val request = request(
                "set",
                id = "native-pending",
                toolCallId = "pi-recovery",
                text = "do not replay",
            )
            assertNull(bridge.handleNativeRequest(TASK_ID, request))
            val pending = ledger.unterminatedRecords().single()
            val durableArguments = Json.parseToJsonElement(
                pending.operation.argumentsCanonicalJson,
            ).jsonObject
            ledger.markClipboardMutationDispatched(
                pending.operation.callId,
                durableArguments.getValue("planDigest").jsonPrimitive.content,
            )

            val rebuilt = bridge(
                ledger,
                gateway,
                TaskApprovalMode.REQUEST_APPROVAL,
            )
            rebuilt.recoverDestroyedRuntime()

            val recovered = requireNotNull(ledger.record(pending.operation.callId))
            assertEquals(
                AttentionLedgerState.FAILED_CLOSED.name,
                recovered.operation.ledgerState,
            )
            assertTrue(
                recovered.operation.terminalFrameCanonicalJson
                    ?.contains("\"code\":\"OUTCOME_UNKNOWN\"") == true,
            )
            val replay = rebuilt.handleNativeRequest(
                TASK_ID,
                request.copy(id = "native-recovered-replay"),
            )
            assertEquals(true, replay?.isError)
            assertEquals(
                "OUTCOME_UNKNOWN",
                replay?.contentPayload?.get("error")?.jsonObject
                    ?.get("code")?.jsonPrimitive?.content,
            )
            assertEquals(0, gateway.writes)
        }

    @Test
    fun `Stop reaches the clipboard executor`() = runTest {
        val handler = RecordingHandler()
        val bridge = PhoneLocalAttentionBridge(
            ledger = RoomAttentionLedger(database),
            clipboardTools = handler,
            approvalModeForTask = { TaskApprovalMode.FULL_ACCESS },
            ioDispatcher = Dispatchers.Unconfined,
        )

        bridge.cancelTask(TASK_ID, "user_stop")

        assertEquals(TASK_ID, handler.stoppedTaskId)
        assertEquals("user_stop", handler.stopReason)
    }

    private fun bridge(
        ledger: RoomAttentionLedger,
        gateway: ClipboardGateway,
        mode: TaskApprovalMode,
    ) = PhoneLocalAttentionBridge(
        ledger = ledger,
        clipboardTools = PhoneLocalClipboardToolExecutor(gateway = gateway),
        approvalModeForTask = { mode },
        ioDispatcher = Dispatchers.Unconfined,
    )

    private fun request(
        action: String,
        id: String = "native-clipboard",
        toolCallId: String = "pi-clipboard",
        text: String? = null,
        purpose: String = "Use the clipboard",
    ) = PiNativeToolRequest(
        id = id,
        kind = "android_clipboard_tool",
        toolCallId = toolCallId,
        toolName = PhoneLocalClipboardToolExecutor.TOOL_NAME,
        arguments = buildJsonObject {
            put("action", action)
            if (text != null) put("text", text)
            put("purpose", purpose)
        },
    )

    private class FakeGateway(initial: ClipboardSnapshot) : ClipboardGateway {
        var text: String? = (initial as? ClipboardSnapshot.Text)?.value
        var sensitive: Boolean =
            (initial as? ClipboardSnapshot.Text)?.sourceMarkedSensitive == true
        var reads = 0
        var writes = 0
        private var unsupported = initial is ClipboardSnapshot.Unsupported

        override suspend fun read(): ClipboardSnapshot {
            reads += 1
            return when {
                unsupported -> ClipboardSnapshot.Unsupported
                text == null -> ClipboardSnapshot.Empty
                else -> ClipboardSnapshot.Text(requireNotNull(text), sensitive)
            }
        }

        override suspend fun setText(text: String, sensitive: Boolean) {
            writes += 1
            this.text = text
            this.sensitive = sensitive
            unsupported = false
        }

        override suspend fun clear() {
            writes += 1
            text = null
            sensitive = false
            unsupported = false
        }
    }

    private class RecordingHandler : PhoneLocalClipboardToolHandler {
        var stoppedTaskId: String? = null
        var stopReason: String? = null

        override fun handles(toolName: String) = true
        override fun isRead(request: PiNativeToolRequest) = true
        override fun isMutation(request: PiNativeToolRequest) = false
        override fun mutationRequestDigest(request: PiNativeToolRequest) = "0".repeat(64)
        override suspend fun prepareMutation(
            taskId: String,
            request: PiNativeToolRequest,
        ) = error("not used")
        override suspend fun executeRead(
            taskId: String,
            request: PiNativeToolRequest,
        ) = error("not used")
        override suspend fun executeMutation(
            taskId: String,
            request: PiNativeToolRequest,
            plan: ClipboardMutationPlan,
            onProviderDispatch: suspend () -> Unit,
        ) = error("not used")

        override fun stopTask(taskId: String, reason: String) {
            stoppedTaskId = taskId
            stopReason = reason
        }
    }

    private fun task() = TaskEntity(
        taskId = TASK_ID,
        title = "Clipboard fixture",
        runState = "WAITING",
        recoveryState = "NORMAL",
        readState = "READ",
        attentionState = "NONE",
        streamId = null,
        throughSequence = 0,
        snapshotVersion = null,
        windowStart = 0,
        windowEndExclusive = 0,
        nextStageBatchOrdinal = 0,
        queueJson = "[]",
        piSessionId = null,
        isStreaming = false,
        updatedAtMillis = 1L,
    )

    private companion object {
        const val TASK_ID = "44444444-4444-4444-8444-444444444444"
    }
}
