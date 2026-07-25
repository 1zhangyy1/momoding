package app.momoding.core.runtime.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.momoding.wire.DeviceClientWireError
import app.momoding.wire.DeviceToolRequestFrame
import app.momoding.wire.DeviceToolResultClientFrame
import app.momoding.wire.DeviceToolTerminalKind
import app.momoding.core.data.AttentionDeliveryState
import app.momoding.core.data.AttentionLedgerState
import app.momoding.core.data.AttentionResponseState
import app.momoding.core.data.MomodingDatabase
import app.momoding.core.data.RoomAttentionLedger
import app.momoding.core.media.DeviceMediaListHandler
import app.momoding.core.media.PhotoLibraryScope
import app.momoding.core.policy.TaskApprovalMode
import app.momoding.core.transport.AttentionUserDecision
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PhoneLocalAttentionBridgeInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun mediaListPromptsOnlyInRequestModeAndExecutesAfterConfirmation() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(context, MomodingDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val ledger = RoomAttentionLedger(database)
        val taskId = UUID.randomUUID().toString()
        val callId = UUID.randomUUID().toString()
        PhoneLocalPiEventProjector(database).createTask(
            taskId = taskId,
            title = "Photo metadata task",
            piSessionId = UUID.randomUUID().toString(),
            streamId = UUID.randomUUID().toString(),
            initialPrompt = "Inspect recent photo metadata",
        )
        val media = FakeMediaListHandler()
        try {
            val requestBridge = PhoneLocalAttentionBridge(
                ledger = ledger,
                mediaTools = media,
                approvalModeForTask = { TaskApprovalMode.REQUEST_APPROVAL },
                idFactory = { callId },
            )
            assertEquals(null, requestBridge.handleNativeRequest(taskId, mediaRequest("media-prompt")))
            assertEquals(0, media.executions)
            assertTrue(requestBridge.owns(callId))

            requestBridge.submitDecision(AttentionUserDecision.Confirm(callId))
            assertEquals(1, media.executions)
            assertEquals(AttentionLedgerState.TERMINAL.name, ledger.record(callId)?.operation?.ledgerState)

            val autoBridge = PhoneLocalAttentionBridge(
                ledger = ledger,
                mediaTools = media,
                approvalModeForTask = { TaskApprovalMode.AUTO_APPROVE },
            )
            val immediate = requireNotNull(
                autoBridge.handleNativeRequest(taskId, mediaRequest("media-auto")),
            )
            assertFalse(immediate.isError)
            assertEquals(
                "partial",
                immediate.contentPayload.getValue("access").toString().trim('"'),
            )
            assertEquals(2, media.executions)

            media.scope = PhotoLibraryScope.DENIED
            val denied = requireNotNull(
                autoBridge.handleNativeRequest(taskId, mediaRequest("media-denied")),
            )
            assertTrue(denied.isError)
            assertTrue(denied.contentPayload.toString().contains("PHOTO_LIBRARY_PERMISSION_REQUIRED"))
            assertEquals(3, media.executions)
        } finally {
            database.close()
        }
    }

    @Test
    fun processRecoveryCancelsPendingAndAbandonsDurableUndeliveredResultWithoutReplay() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(context, MomodingDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val ledger = RoomAttentionLedger(database)
        val taskId = UUID.randomUUID().toString()
        PhoneLocalPiEventProjector(database).createTask(
            taskId = taskId,
            title = "Recovery task",
            piSessionId = UUID.randomUUID().toString(),
            streamId = UUID.randomUUID().toString(),
            initialPrompt = "Ask before continuing",
        )
        try {
            val oldBridge = PhoneLocalAttentionBridge(ledger)
            val answeredCallId = oldBridge.accept(taskId, questionRequest("native-answered"))
            oldBridge.submitDecision(
                AttentionUserDecision.Custom(answeredCallId, "Use the safe option"),
            )
            val pendingCallId = oldBridge.accept(taskId, questionRequest("native-pending"))

            val recreated = PhoneLocalAttentionBridge(ledger)
            recreated.recoverDestroyedRuntime()
            recreated.recoverDestroyedRuntime()

            val abandoned = requireNotNull(ledger.record(answeredCallId))
            assertEquals(AttentionLedgerState.TERMINAL.name, abandoned.operation.ledgerState)
            assertEquals(
                AttentionDeliveryState.ABANDONED.name,
                abandoned.operation.deliveryState,
            )
            assertEquals(
                AttentionResponseState.CANCELLED.name,
                abandoned.attention.responseState,
            )
            assertFalse(recreated.owns(answeredCallId))

            val cancelled = requireNotNull(ledger.record(pendingCallId))
            assertEquals(AttentionLedgerState.CANCELLED.name, cancelled.operation.ledgerState)
            assertEquals(
                AttentionResponseState.CANCELLED.name,
                cancelled.attention.responseState,
            )
            assertFalse(recreated.owns(pendingCallId))
            assertEquals(0, ledger.terminalOperationsReadyForDelivery("phone-local-android").size)
        } finally {
            database.close()
        }
    }

    @Test
    fun invalidKnownToolArgumentsFailClosedWithoutCreatingAnActionableCard() {
        val database = Room.inMemoryDatabaseBuilder(context, MomodingDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val ledger = RoomAttentionLedger(database)
        val taskId = UUID.randomUUID().toString()
        val callId = UUID.randomUUID().toString()
        PhoneLocalPiEventProjector(database).createTask(
            taskId = taskId,
            title = "Invalid tool task",
            piSessionId = UUID.randomUUID().toString(),
            streamId = UUID.randomUUID().toString(),
            initialPrompt = "Try an invalid question",
        )
        try {
            val bridge = PhoneLocalAttentionBridge(ledger, idFactory = { callId })
            assertThrows(IllegalArgumentException::class.java) {
                bridge.accept(
                    taskId,
                    questionRequest("native-invalid").copy(
                        arguments = buildJsonObject {
                            put("question", "Valid text")
                            put("unknown", "must fail closed")
                        },
                    ),
                )
            }
            val failed = requireNotNull(ledger.record(callId))
            assertEquals(AttentionLedgerState.FAILED_CLOSED.name, failed.operation.ledgerState)
            assertEquals(AttentionDeliveryState.ABANDONED.name, failed.operation.deliveryState)
            assertEquals(AttentionResponseState.CANCELLED.name, failed.attention.responseState)
            assertFalse(bridge.owns(callId))
        } finally {
            database.close()
        }
    }

    @Test
    fun contentReadDenyAndStopRemainLocalDurableAndNeverBecomeAnApprovedRead() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(context, MomodingDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val ledger = RoomAttentionLedger(database)
        val taskId = UUID.randomUUID().toString()
        val deniedCallId = UUID.randomUUID().toString()
        val cancelledCallId = UUID.randomUUID().toString()
        val ids = java.util.ArrayDeque(listOf(deniedCallId, cancelledCallId))
        PhoneLocalPiEventProjector(database).createTask(
            taskId = taskId,
            title = "Content consent task",
            piSessionId = UUID.randomUUID().toString(),
            streamId = UUID.randomUUID().toString(),
            initialPrompt = "Read only after consent",
        )
        try {
            val bridge = PhoneLocalAttentionBridge(ledger, idFactory = { ids.removeFirst() })
            assertEquals(deniedCallId, bridge.accept(taskId, contentReadRequest("native-deny")))
            bridge.submitDecision(AttentionUserDecision.DenyContentRead(deniedCallId))

            val denied = requireNotNull(ledger.record(deniedCallId))
            assertEquals(AttentionLedgerState.TERMINAL.name, denied.operation.ledgerState)
            assertTrue(
                requireNotNull(denied.operation.terminalFrameCanonicalJson)
                    .contains("CONTENT_READ_DECLINED"),
            )
            assertEquals(null, database.momodingDao().taskContentGrant(deniedCallId))

            assertEquals(
                cancelledCallId,
                bridge.accept(taskId, contentReadRequest("native-stop")),
            )
            bridge.cancelTask(taskId, "session_stop")

            val cancelled = requireNotNull(ledger.record(cancelledCallId))
            assertEquals(AttentionLedgerState.CANCELLED.name, cancelled.operation.ledgerState)
            assertTrue(
                requireNotNull(cancelled.operation.terminalFrameCanonicalJson)
                    .contains("CONTENT_READ_CANCELLED"),
            )
            assertEquals(null, database.momodingDao().taskContentGrant(cancelledCallId))
            assertFalse(bridge.owns(deniedCallId))
            assertFalse(bridge.owns(cancelledCallId))
        } finally {
            database.close()
        }
    }

    private fun questionRequest(id: String) = PiNativeToolRequest(
        id = id,
        kind = "android_attention",
        toolCallId = "pi-$id",
        toolName = "request_user_question",
        arguments = buildJsonObject { put("question", "Which approach should I use?") },
    )

    private fun contentReadRequest(id: String) = PiNativeToolRequest(
        id = id,
        kind = "android_file_tool",
        toolCallId = "pi-$id",
        toolName = "device_files_read",
        arguments = buildJsonObject {
            put("grantId", "11111111-1111-4111-8111-111111111111")
            put("purpose", "Read the selected project note")
            put(
                "documents",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("alias", "doc-1234567890abcdef12345678")
                            put("expectedMimeType", "text/plain")
                            put("maxBytes", 4_096)
                        },
                    )
                },
            )
            put("totalMaxBytes", 4_096)
        },
    )

    private fun mediaRequest(id: String) = PiNativeToolRequest(
        id = id,
        kind = "android_media_tool",
        toolCallId = "pi-$id",
        toolName = "device_media_list",
        arguments = buildJsonObject {
            put("purpose", "Find the newest screenshots")
            put("limit", 5)
        },
    )

    private class FakeMediaListHandler : DeviceMediaListHandler {
        var executions = 0
        var scope = PhotoLibraryScope.PARTIAL

        override fun handles(toolName: String): Boolean = toolName == "device_media_list"

        override fun currentScope(): PhotoLibraryScope = scope

        override suspend fun execute(frame: DeviceToolRequestFrame): DeviceToolResultClientFrame {
            executions += 1
            if (scope == PhotoLibraryScope.DENIED) {
                return DeviceToolResultClientFrame(
                    callId = frame.callId,
                    taskId = frame.taskId,
                    deviceId = frame.deviceId,
                    terminal = DeviceToolTerminalKind.FAILED,
                    error = DeviceClientWireError(
                        "PHOTO_LIBRARY_PERMISSION_REQUIRED",
                        "Enable photo-library access in Device capabilities",
                    ),
                )
            }
            return DeviceToolResultClientFrame(
                callId = frame.callId,
                taskId = frame.taskId,
                deviceId = frame.deviceId,
                terminal = DeviceToolTerminalKind.SUCCEEDED,
                result = buildJsonObject {
                    put("access", "partial")
                    put("limit", 5)
                    put("returnedCount", 0)
                    put("items", buildJsonArray {})
                },
            )
        }
    }
}
