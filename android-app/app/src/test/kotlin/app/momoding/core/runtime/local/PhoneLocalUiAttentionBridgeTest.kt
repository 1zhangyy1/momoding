package app.momoding.core.runtime.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.momoding.wire.DeviceToolTerminalKind
import app.momoding.core.data.AttentionLedgerState
import app.momoding.core.data.AttentionResponseState
import app.momoding.core.data.MomodingDatabase
import app.momoding.core.data.RoomAttentionLedger
import app.momoding.core.data.TaskEntity
import app.momoding.core.policy.CapabilityAction
import app.momoding.core.policy.TaskApprovalMode
import app.momoding.core.transport.AttentionUserDecision
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PhoneLocalUiAttentionBridgeTest {
    private lateinit var database: MomodingDatabase
    private lateinit var ledger: RoomAttentionLedger

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            MomodingDatabase::class.java,
        ).allowMainThreadQueries().build()
        database.p2Dao().upsertTask(task())
        ledger = RoomAttentionLedger(database) { 1_000L }
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun autoAllowExecutesWithLocalPolicyOriginAndNoVisiblePrompt() = runTest {
        val handler = FixtureUiHandler()
        val bridge = bridge(TaskApprovalMode.AUTO_APPROVE, handler)

        val result = bridge.handleNativeRequest(TASK_ID, request())

        assertEquals(false, result?.isError)
        assertEquals("auto_policy", result?.details?.get("approvalOrigin")?.toString()?.trim('"'))
        val operation = database.p2Dao().deviceOperations(TASK_ID).single()
        assertEquals(AttentionLedgerState.TERMINAL.name, operation.ledgerState)
        assertEquals(AttentionResponseState.RESPONDING.name, ledger.record(operation.callId)?.attention?.responseState)
        assertEquals(1, handler.executionCount)
    }

    @Test
    fun duplicateAutomaticPiToolCallReplaysTerminalWithoutExecutingAgain() = runTest {
        val handler = FixtureUiHandler()
        val bridge = bridge(TaskApprovalMode.AUTO_APPROVE, handler)

        val first = bridge.handleNativeRequest(TASK_ID, request())
        val replay = bridge.handleNativeRequest(
            TASK_ID,
            request(nativeRequestId = "native-ui-action-replayed"),
        )

        assertEquals(false, first?.isError)
        assertEquals(false, replay?.isError)
        assertEquals(first?.contentPayload, replay?.contentPayload)
        assertEquals(1, handler.executionCount)
    }

    @Test
    fun requestApprovalWaitsAndExecutesOnlyAfterExactConfirmation() = runTest {
        val handler = FixtureUiHandler()
        val bridge = bridge(TaskApprovalMode.REQUEST_APPROVAL, handler)

        assertNull(bridge.handleNativeRequest(TASK_ID, request()))
        val pending = ledger.unterminatedRecords().single()
        assertEquals(AttentionLedgerState.AWAITING_USER.name, pending.operation.ledgerState)
        assertEquals(0, handler.executionCount)

        bridge.submitDecision(AttentionUserDecision.Confirm(pending.operation.callId))

        assertEquals(1, handler.executionCount)
        assertEquals(
            AttentionLedgerState.TERMINAL.name,
            ledger.record(pending.operation.callId)?.operation?.ledgerState,
        )

        val replay = bridge.handleNativeRequest(
            TASK_ID,
            request(nativeRequestId = "native-ui-action-user-replayed"),
        )
        assertEquals(false, replay?.isError)
        assertEquals(1, handler.executionCount)
    }

    @Test
    fun durableNonTerminalCallAfterCrashFailsClosedWithoutReplayingSideEffect() = runTest {
        val handler = FixtureUiHandler()
        val bridge = bridge(TaskApprovalMode.AUTO_APPROVE, handler)
        bridge.accept(TASK_ID, request(), awaitUser = false)

        val replay = bridge.handleNativeRequest(
            TASK_ID,
            request(nativeRequestId = "native-ui-action-after-crash"),
        )

        assertEquals(true, replay?.isError)
        assertEquals(
            "UI_ACTION_DUPLICATE_IN_PROGRESS",
            replay?.contentPayload?.get("errorCode")?.toString()?.trim('"'),
        )
        assertEquals(0, handler.executionCount)
    }

    @Test
    fun reapprovalRequiredClosesThroughTheExactRoomTerminalContract() = runTest {
        val handler = FixtureUiHandler().apply {
            failureCode = "UI_ACTION_REAPPROVAL_REQUIRED"
        }
        val bridge = bridge(TaskApprovalMode.AUTO_APPROVE, handler)

        val result = bridge.handleNativeRequest(TASK_ID, request())

        assertEquals(true, result?.isError)
        assertEquals(
            "UI_ACTION_REAPPROVAL_REQUIRED",
            result?.contentPayload?.get("errorCode")?.toString()?.trim('"'),
        )
        val operation = database.p2Dao().deviceOperations(TASK_ID).single()
        assertEquals(AttentionLedgerState.TERMINAL.name, operation.ledgerState)
        assertEquals(DeviceToolTerminalKind.FAILED.wireValue, operation.terminalKind)
        assertTrue(
            operation.terminalFrameCanonicalJson
                ?.contains("\"code\":\"UI_ACTION_REAPPROVAL_REQUIRED\"") == true,
        )
    }

    private fun bridge(
        mode: TaskApprovalMode,
        handler: FixtureUiHandler = FixtureUiHandler(),
    ) = PhoneLocalAttentionBridge(
        ledger = ledger,
        uiTools = handler,
        approvalModeForTask = { mode },
        ioDispatcher = Dispatchers.Unconfined,
        nowMillis = { 1_000L },
        idFactory = { CALL_ID },
        operationIdFactory = { OPERATION_ID },
    )

    private fun request(
        nativeRequestId: String = "native-ui-action",
    ) = PiNativeToolRequest(
        id = nativeRequestId,
        kind = "android_ui_tool",
        toolCallId = "pi-ui-action",
        toolName = PhoneLocalUiToolExecutor.ACTION_TOOL,
        arguments = buildJsonObject {
            put("snapshotId", SNAPSHOT_ID)
            put("nodeHandle", "$SNAPSHOT_ID:n2")
            put("action", "click")
        },
    )

    private class FixtureUiHandler : PhoneLocalUiToolHandler {
        var executionCount = 0
        var failureCode: String? = null

        override suspend fun inspect(
            taskId: String,
            request: PiNativeToolRequest,
        ): PiNativeAndroidToolResult = error("not used")

        override fun decideAction(
            taskId: String,
            request: PiNativeToolRequest,
            mode: TaskApprovalMode,
        ): PhoneLocalUiActionDisposition = if (mode == TaskApprovalMode.REQUEST_APPROVAL) {
            PhoneLocalUiActionDisposition.Prompt(
                CapabilityAction.CLICK_NAVIGATION,
                "Allow click?",
                "Fixture action",
            )
        } else {
            PhoneLocalUiActionDisposition.AutoAllow(CapabilityAction.CLICK_NAVIGATION)
        }

        override suspend fun executeAction(
            taskId: String,
            request: PiNativeToolRequest,
            authorization: PhoneLocalUiActionAuthorization,
        ): PiNativeAndroidToolResult {
            executionCount += 1
            assertTrue(authorization.userApproved || executionCount == 1)
            assertEquals(CapabilityAction.CLICK_NAVIGATION, authorization.approvedAction)
            failureCode?.let { code ->
                val payload = buildJsonObject {
                    put("ok", false)
                    put("errorCode", code)
                    put("errorMessage", "The action now requires a new approval.")
                }
                return PiNativeAndroidToolResult(payload, isError = true)
            }
            return PiNativeAndroidToolResult(
                buildJsonObject {
                    put("ok", true)
                    put("action", "click")
                    put("beforeSnapshotId", SNAPSHOT_ID)
                    put("afterSnapshotId", AFTER_SNAPSHOT_ID)
                    put("foregroundPackage", "dev.fixture")
                    put("targetChanged", false)
                    put("changed", true)
                    put("noChangeCount", 0)
                    put("sessionPaused", false)
                    put("actionCount", 1)
                },
            )
        }
    }

    private fun task() = TaskEntity(
        taskId = TASK_ID,
        title = "UI action",
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
        updatedAtMillis = 1,
    )

    private companion object {
        const val TASK_ID = "11111111-1111-4111-8111-111111111111"
        const val CALL_ID = "22222222-2222-4222-8222-222222222222"
        const val OPERATION_ID = "33333333-3333-4333-8333-333333333333"
        const val SNAPSHOT_ID = "ui-11111111111111111111111111111111"
        const val AFTER_SNAPSHOT_ID = "ui-22222222222222222222222222222222"
    }
}
