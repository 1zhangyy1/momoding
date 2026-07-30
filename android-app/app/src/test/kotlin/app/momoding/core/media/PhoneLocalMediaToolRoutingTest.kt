package app.momoding.core.media

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.momoding.wire.DeviceToolRequestFrame
import app.momoding.wire.DeviceToolResultClientFrame
import app.momoding.wire.DeviceToolTerminalKind
import app.momoding.core.data.AttentionLedgerState
import app.momoding.core.data.MomodingDatabase
import app.momoding.core.data.RoomAttentionLedger
import app.momoding.core.data.TaskEntity
import app.momoding.core.policy.TaskApprovalMode
import app.momoding.core.runtime.local.PhoneLocalAttentionBridge
import app.momoding.core.runtime.local.PiNativeToolRequest
import app.momoding.core.runtime.local.PiNativeAndroidToolResult
import app.momoding.core.transport.AttentionUserDecision
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
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
class PhoneLocalMediaToolRoutingTest {
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
    fun `approved list result and media handles are live only`() = runTest {
        val ledger = RoomAttentionLedger(database)
        val handler = FixtureMediaHandler()
        val bridge = PhoneLocalAttentionBridge(
            ledger = ledger,
            mediaTools = handler,
            approvalModeForTask = { TaskApprovalMode.REQUEST_APPROVAL },
            ioDispatcher = Dispatchers.Unconfined,
        )

        assertNull(bridge.handleNativeRequest(TASK_ID, request()))
        val pending = ledger.unterminatedRecords().single()
        assertEquals(AttentionLedgerState.AWAITING_USER.name, pending.operation.ledgerState)

        bridge.submitDecision(AttentionUserDecision.Confirm(pending.operation.callId))

        assertEquals(1, handler.executionCount)
        assertTrue(ledger.record(pending.operation.callId)?.operation?.terminalSha256 == null)
        ledger.discardLiveApprovedRead(TASK_ID, pending.operation.callId)
        assertNull(ledger.record(pending.operation.callId))
    }

    @Test
    fun `media mutation always prompts dispatches once and replays verified terminal`() = runTest {
        val ledger = RoomAttentionLedger(database)
        val handler = FixtureMediaMutationHandler()
        val bridge = PhoneLocalAttentionBridge(
            ledger = ledger,
            mediaMutationTools = handler,
            approvalModeForTask = { TaskApprovalMode.FULL_ACCESS },
            ioDispatcher = Dispatchers.Unconfined,
        )
        val request = mutationRequest("native-mutation")

        assertNull(bridge.handleNativeRequest(TASK_ID, request))
        val pending = ledger.unterminatedRecords().single()
        assertEquals(AttentionLedgerState.AWAITING_USER.name, pending.operation.ledgerState)
        assertTrue(pending.operation.sideEffect)
        assertFalse(pending.operation.argumentsCanonicalJson.contains("media-"))

        bridge.submitDecision(AttentionUserDecision.Confirm(pending.operation.callId))

        val terminal = requireNotNull(ledger.record(pending.operation.callId)).operation
        assertTrue(ledger.mediaMutationWasDispatched(terminal))
        assertEquals(1, handler.executions)
        assertEquals("succeeded", terminal.terminalKind)

        val replay = bridge.handleNativeRequest(
            TASK_ID,
            mutationRequest("native-replay"),
        )
        assertEquals(false, replay?.isError)
        assertEquals(
            "set_favorite",
            replay?.contentPayload?.get("action")?.jsonPrimitive?.content,
        )
        assertEquals(
            true,
            replay?.details?.get("systemConsent")?.jsonPrimitive?.content?.toBoolean(),
        )
        assertEquals(1, handler.executions)
    }

    @Test
    fun `user decline reports that Android system consent was not dispatched`() = runTest {
        val ledger = RoomAttentionLedger(database)
        val handler = FixtureMediaMutationHandler()
        val bridge = PhoneLocalAttentionBridge(
            ledger = ledger,
            mediaMutationTools = handler,
            approvalModeForTask = { TaskApprovalMode.FULL_ACCESS },
            ioDispatcher = Dispatchers.Unconfined,
        )
        val request = mutationRequest("native-decline")

        assertNull(bridge.handleNativeRequest(TASK_ID, request))
        val pending = ledger.unterminatedRecords().single()
        bridge.submitDecision(AttentionUserDecision.Decline(pending.operation.callId))

        val replay = bridge.handleNativeRequest(
            TASK_ID,
            mutationRequest("native-decline-replay"),
        )
        assertEquals(true, replay?.isError)
        assertEquals(
            false,
            replay?.details?.get("systemConsent")?.jsonPrimitive?.content?.toBoolean(),
        )
        assertEquals(0, handler.executions)
    }

    @Test
    fun `dispatched media consent recovery becomes outcome unknown without mutation retry`() =
        runTest {
            val ledger = RoomAttentionLedger(database)
            val handler = FixtureMediaMutationHandler()
            val bridge = PhoneLocalAttentionBridge(
                ledger = ledger,
                mediaMutationTools = handler,
                approvalModeForTask = { TaskApprovalMode.REQUEST_APPROVAL },
                ioDispatcher = Dispatchers.Unconfined,
            )
            val request = mutationRequest("native-recovery")

            assertNull(bridge.handleNativeRequest(TASK_ID, request))
            val pending = ledger.unterminatedRecords().single()
            val durableArguments = Json.parseToJsonElement(
                pending.operation.argumentsCanonicalJson,
            ).jsonObject
            ledger.markMediaMutationDispatched(
                pending.operation.callId,
                durableArguments.getValue("planDigest").jsonPrimitive.content,
            )

            val rebuilt = PhoneLocalAttentionBridge(
                ledger = ledger,
                mediaMutationTools = handler,
                approvalModeForTask = { TaskApprovalMode.REQUEST_APPROVAL },
                ioDispatcher = Dispatchers.Unconfined,
            )
            rebuilt.recoverDestroyedRuntime()

            val recovered = requireNotNull(ledger.record(pending.operation.callId))
            assertEquals(AttentionLedgerState.FAILED_CLOSED.name, recovered.operation.ledgerState)
            assertTrue(
                recovered.operation.terminalFrameCanonicalJson
                    ?.contains("\"code\":\"OUTCOME_UNKNOWN\"") == true,
            )
            val replay = rebuilt.handleNativeRequest(
                TASK_ID,
                mutationRequest("native-recovery-replay"),
            )
            assertEquals(true, replay?.isError)
            assertEquals(
                "OUTCOME_UNKNOWN",
                replay?.contentPayload?.get("error")?.jsonObject
                    ?.get("code")?.jsonPrimitive?.content,
            )
            assertEquals(
                true,
                replay?.details?.get("systemConsent")?.jsonPrimitive?.content?.toBoolean(),
            )
            assertEquals(0, handler.executions)
        }

    private fun request() = PiNativeToolRequest(
        id = "native-media",
        kind = "android_media_tool",
        toolCallId = "pi-media",
        toolName = DeviceMediaListExecutor.TOOL_NAME,
        arguments = buildJsonObject {
            put("purpose", "Find a recent screenshot")
            put("limit", 1)
        },
    )

    private fun mutationRequest(id: String) = PiNativeToolRequest(
        id = id,
        kind = "android_media_tool",
        toolCallId = "pi-media-mutation",
        toolName = PhoneLocalMediaToolExecutor.TOOL_NAME,
        arguments = buildJsonObject {
            put("action", "set_favorite")
            put("mediaHandle", "media-${"a".repeat(24)}")
            put("favorite", true)
        },
    )

    private class FixtureMediaHandler : DeviceMediaListHandler {
        var executionCount = 0

        override fun handles(toolName: String): Boolean =
            toolName == DeviceMediaListExecutor.TOOL_NAME

        override fun currentScope(): PhotoLibraryScope = PhotoLibraryScope.FULL

        override suspend fun execute(
            frame: DeviceToolRequestFrame,
        ): DeviceToolResultClientFrame {
            executionCount += 1
            return DeviceToolResultClientFrame(
                callId = frame.callId,
                taskId = frame.taskId,
                deviceId = frame.deviceId,
                terminal = DeviceToolTerminalKind.SUCCEEDED,
                result = buildJsonObject {
                    put("access", "full")
                    put("limit", 1)
                    put("returnedCount", 1)
                    put(
                        "items",
                        buildJsonArray {
                            add(
                                buildJsonObject {
                                    put("index", 1)
                                    put("mediaHandle", "media-${"a".repeat(24)}")
                                    put("mimeType", "image/png")
                                },
                            )
                        },
                    )
                },
            )
        }
    }

    private class FixtureMediaMutationHandler : PhoneLocalMediaToolHandler {
        var executions = 0

        override fun handles(toolName: String): Boolean =
            toolName == PhoneLocalMediaToolExecutor.TOOL_NAME

        override fun action(request: PiNativeToolRequest): MediaToolAction =
            MediaToolAction.SET_FAVORITE

        override fun mutationRequestDigest(request: PiNativeToolRequest): String =
            "1".repeat(64)

        override suspend fun prepareMutation(
            taskId: String,
            request: PiNativeToolRequest,
        ): MediaMutationPreparation = MediaMutationPreparation.Ready(
            MediaMutationPlan(
                taskId = taskId,
                piToolCallId = request.toolCallId,
                action = MediaToolAction.SET_FAVORITE,
                mediaHandle = "media-${"a".repeat(24)}",
                mediaId = 42L,
                desired = true,
                snapshotDigest = "2".repeat(64),
                requestDigest = mutationRequestDigest(request),
                planDigest = "3".repeat(64),
                summary = "Favorite this photo?",
                details = "Android system confirmation and live verification are required.",
            ),
        )

        override suspend fun executeMutation(
            taskId: String,
            request: PiNativeToolRequest,
            plan: MediaMutationPlan,
            onProviderDispatch: suspend () -> Unit,
        ): PiNativeAndroidToolResult {
            onProviderDispatch()
            executions += 1
            return PiNativeAndroidToolResult(
                buildJsonObject {
                    put("ok", true)
                    put("action", "set_favorite")
                    put(
                        "data",
                        buildJsonObject {
                            put("changed", true)
                            put("favorite", true)
                        },
                    )
                    put(
                        "verification",
                        buildJsonObject {
                            put("status", "verified")
                            put("observedAt", "2026-07-29T08:00:00Z")
                            put("planDigest", plan.planDigest)
                        },
                    )
                },
            )
        }

        override fun stopTask(taskId: String, reason: String) = Unit
    }

    private fun task() = TaskEntity(
        taskId = TASK_ID,
        title = "Media fixture",
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
        const val TASK_ID = "22222222-2222-4222-8222-222222222222"
    }
}
