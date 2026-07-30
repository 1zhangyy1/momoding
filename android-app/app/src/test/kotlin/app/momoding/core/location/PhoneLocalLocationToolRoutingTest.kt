package app.momoding.core.location

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.momoding.core.data.AttentionLedgerState
import app.momoding.core.data.MomodingDatabase
import app.momoding.core.data.RoomAttentionLedger
import app.momoding.core.data.TaskEntity
import app.momoding.core.policy.TaskApprovalMode
import app.momoding.core.runtime.local.PhoneLocalAttentionBridge
import app.momoding.core.runtime.local.PiNativeAndroidToolResult
import app.momoding.core.runtime.local.PiNativeToolRequest
import app.momoding.core.transport.AttentionUserDecision
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
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
class PhoneLocalLocationToolRoutingTest {
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
    fun `request approval stores only redacted intent and reads once after confirmation`() =
        runTest {
            val ledger = RoomAttentionLedger(database)
            val handler = FixtureLocationHandler()
            val bridge = bridge(ledger, handler, TaskApprovalMode.REQUEST_APPROVAL)
            val request = request(purpose = "PRIVATE_LOCATION_PURPOSE")

            assertNull(bridge.handleNativeRequest(TASK_ID, request))
            val pending = ledger.unterminatedRecords().single()
            assertEquals(AttentionLedgerState.AWAITING_USER.name, pending.operation.ledgerState)
            assertFalse(
                pending.operation.argumentsCanonicalJson.contains("PRIVATE_LOCATION_PURPOSE"),
            )
            assertTrue(pending.operation.argumentsCanonicalJson.contains("\"precision\":\"approximate\""))
            assertEquals(0, handler.executionCount)

            bridge.submitDecision(AttentionUserDecision.Confirm(pending.operation.callId))

            assertEquals(1, handler.executionCount)
            assertEquals(request, handler.request)
            assertTrue(ledger.record(pending.operation.callId)?.operation?.terminalSha256 == null)
            ledger.discardLiveApprovedRead(TASK_ID, pending.operation.callId)
            assertNull(ledger.record(pending.operation.callId))
        }

    @Test
    fun `approximate auto reads while precise auto prompts and full access reads`() =
        runTest {
            listOf(TaskApprovalMode.AUTO_APPROVE, TaskApprovalMode.FULL_ACCESS).forEach { mode ->
                val handler = FixtureLocationHandler()
                val result = bridge(RoomAttentionLedger(database), handler, mode)
                    .handleNativeRequest(TASK_ID, request(id = "native-$mode"))
                assertEquals(false, result?.isError)
                assertEquals(1, handler.executionCount)
            }

            val preciseAutoLedger = RoomAttentionLedger(database)
            val preciseAutoHandler = FixtureLocationHandler()
            val preciseAutoBridge = bridge(
                preciseAutoLedger,
                preciseAutoHandler,
                TaskApprovalMode.AUTO_APPROVE,
            )
            assertNull(
                preciseAutoBridge.handleNativeRequest(
                    TASK_ID,
                    request(id = "native-precise-auto", precision = "precise"),
                ),
            )
            val precisePending = preciseAutoLedger.unterminatedRecords().single()
            assertEquals(0, preciseAutoHandler.executionCount)
            preciseAutoBridge.submitDecision(
                AttentionUserDecision.Confirm(precisePending.operation.callId),
            )
            assertEquals(1, preciseAutoHandler.executionCount)
            preciseAutoLedger.discardLiveApprovedRead(
                TASK_ID,
                precisePending.operation.callId,
            )

            val preciseFullHandler = FixtureLocationHandler()
            val preciseFull = bridge(
                RoomAttentionLedger(database),
                preciseFullHandler,
                TaskApprovalMode.FULL_ACCESS,
            ).handleNativeRequest(
                TASK_ID,
                request(id = "native-precise-full", precision = "precise"),
            )
            assertEquals(false, preciseFull?.isError)
            assertEquals(1, preciseFullHandler.executionCount)

            val declineLedger = RoomAttentionLedger(database)
            val declineHandler = FixtureLocationHandler()
            val declineBridge = bridge(
                declineLedger,
                declineHandler,
                TaskApprovalMode.REQUEST_APPROVAL,
            )
            assertNull(
                declineBridge.handleNativeRequest(
                    TASK_ID,
                    request(id = "native-decline"),
                ),
            )
            val pending = declineLedger.unterminatedRecords().single()
            declineBridge.submitDecision(AttentionUserDecision.Decline(pending.operation.callId))
            assertEquals(0, declineHandler.executionCount)
            assertEquals(
                "rejected",
                declineLedger.record(pending.operation.callId)?.operation?.terminalKind,
            )

            val unreadyHandler = FixtureLocationHandler().apply { capabilityReady = false }
            val unreadyLedger = RoomAttentionLedger(database)
            val unready = bridge(
                unreadyLedger,
                unreadyHandler,
                TaskApprovalMode.REQUEST_APPROVAL,
            ).handleNativeRequest(TASK_ID, request(id = "native-unready"))
            assertEquals(1, unreadyHandler.executionCount)
            assertEquals(false, unready?.isError)
            assertTrue(unreadyLedger.unterminatedRecords().isEmpty())
        }

    @Test
    fun `task stop cancels the active Android location request`() = runTest {
        val handler = FixtureLocationHandler()
        val bridge = bridge(
            RoomAttentionLedger(database),
            handler,
            TaskApprovalMode.FULL_ACCESS,
        )

        bridge.cancelTask(TASK_ID, "user_stop")

        assertEquals(TASK_ID, handler.stoppedTaskId)
        assertEquals("user_stop", handler.stopReason)
    }

    private fun bridge(
        ledger: RoomAttentionLedger,
        handler: PhoneLocalLocationToolHandler,
        mode: TaskApprovalMode,
    ) = PhoneLocalAttentionBridge(
        ledger = ledger,
        locationTools = handler,
        approvalModeForTask = { mode },
        ioDispatcher = Dispatchers.Unconfined,
    )

    private fun request(
        id: String = "native-location",
        precision: String = "approximate",
        purpose: String = "Estimate my current area",
    ) = PiNativeToolRequest(
        id = id,
        kind = "android_location_tool",
        toolCallId = "pi-$id",
        toolName = PhoneLocalLocationToolExecutor.TOOL_NAME,
        arguments = buildJsonObject {
            put("action", "get_current")
            put("precision", precision)
            put("purpose", purpose)
        },
    )

    private class FixtureLocationHandler : PhoneLocalLocationToolHandler {
        var capabilityReady = true
        var executionCount = 0
        var request: PiNativeToolRequest? = null
        var stoppedTaskId: String? = null
        var stopReason: String? = null

        override fun handles(toolName: String): Boolean =
            toolName == PhoneLocalLocationToolExecutor.TOOL_NAME

        override fun isPersonalDataRead(request: PiNativeToolRequest): Boolean = true

        override suspend fun isCapabilityReady(request: PiNativeToolRequest): Boolean =
            capabilityReady

        override suspend fun execute(
            taskId: String,
            request: PiNativeToolRequest,
        ): PiNativeAndroidToolResult {
            executionCount += 1
            this.request = request
            val payload = buildJsonObject {
                put("ok", true)
                put("action", "get_current")
                put(
                    "data",
                    buildJsonObject {
                        put("precision", request.arguments.getValue("precision").jsonPrimitive.content)
                        put("latitude", 31.23)
                        put("longitude", 121.47)
                        put("accuracyMeters", 1000.0)
                        put("capturedAt", "2026-07-29T04:00:00Z")
                        put("ageMillis", 1_000)
                        put("providerCategory", "network")
                    },
                )
                put(
                    "verification",
                    buildJsonObject {
                        put("status", "observed")
                        put("observedAt", "2026-07-29T04:00:01Z")
                    },
                )
            }
            return PiNativeAndroidToolResult(
                contentPayload = payload,
                details = buildJsonObject {
                    put("liveOnly", true)
                    put("dataClass", "location")
                    put("contentSha256", sha256(payload.toString()))
                    put("precision", request.arguments.getValue("precision").jsonPrimitive.content)
                },
            )
        }

        override fun stopTask(taskId: String, reason: String) {
            stoppedTaskId = taskId
            stopReason = reason
        }

        private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun task() = TaskEntity(
        taskId = TASK_ID,
        title = "Location fixture",
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
        const val TASK_ID = "33333333-3333-4333-8333-333333333333"
    }
}
