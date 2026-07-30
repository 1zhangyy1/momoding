package app.momoding.core.notification

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
class PhoneLocalNotificationToolRoutingTest {
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
    fun `request mode persists no notification content then dispatches and verifies once`() =
        runTest {
            val gateway = FakeGateway()
            val ledger = RoomAttentionLedger(database)
            val bridge = bridge(ledger, gateway, TaskApprovalMode.REQUEST_APPROVAL)
            val request = request(
                title = "PRIVATE_NOTIFICATION_TITLE",
                message = "PRIVATE_NOTIFICATION_MESSAGE",
            )

            assertNull(bridge.handleNativeRequest(TASK_ID, request))
            val pending = ledger.unterminatedRecords().single()
            assertEquals(AttentionLedgerState.AWAITING_USER.name, pending.operation.ledgerState)
            assertFalse(
                pending.operation.argumentsCanonicalJson.contains("PRIVATE_NOTIFICATION_TITLE"),
            )
            assertFalse(
                pending.operation.argumentsCanonicalJson.contains("PRIVATE_NOTIFICATION_MESSAGE"),
            )
            assertTrue(pending.operation.argumentsCanonicalJson.contains("\"titleLength\":26"))
            assertTrue(pending.operation.argumentsCanonicalJson.contains("\"messageLength\":28"))
            assertEquals(0, gateway.posts)

            bridge.submitDecision(AttentionUserDecision.Confirm(pending.operation.callId))

            assertEquals(1, gateway.posts)
            val terminal = requireNotNull(ledger.record(pending.operation.callId)).operation
            assertEquals(AttentionLedgerState.TERMINAL.name, terminal.ledgerState)
            assertTrue(ledger.notificationMutationWasDispatched(terminal))
            assertFalse(
                terminal.terminalFrameCanonicalJson.orEmpty()
                    .contains("PRIVATE_NOTIFICATION_MESSAGE"),
            )
            val expectation = requireNotNull(
                ledger.piDeliveryExpectationForValidatedPair(terminal),
            )
            assertEquals(
                "post",
                expectation.contentPayload.jsonObject["action"]?.jsonPrimitive?.content,
            )
        }

    @Test
    fun `automatic post replays a verified duplicate without posting twice`() = runTest {
        val gateway = FakeGateway()
        val ledger = RoomAttentionLedger(database)
        val bridge = bridge(ledger, gateway, TaskApprovalMode.FULL_ACCESS)
        val original = request(
            id = "native-original",
            toolCallId = "pi-stable-notification",
        )

        val first = bridge.handleNativeRequest(TASK_ID, original)
        assertEquals(false, first?.isError)
        assertEquals("auto_policy", first?.details?.get("approvalOrigin")?.jsonPrimitive?.content)
        assertEquals(1, gateway.posts)

        val replay = bridge.handleNativeRequest(
            TASK_ID,
            original.copy(id = "native-replay"),
        )

        assertEquals(false, replay?.isError)
        assertEquals(1, gateway.posts)
        assertEquals("post", replay?.contentPayload?.get("action")?.jsonPrimitive?.content)
    }

    @Test
    fun `dispatched host recovery becomes outcome unknown and never posts`() = runTest {
        val gateway = FakeGateway()
        val ledger = RoomAttentionLedger(database)
        val bridge = bridge(ledger, gateway, TaskApprovalMode.REQUEST_APPROVAL)
        val request = request(id = "native-pending", toolCallId = "pi-recovery")
        assertNull(bridge.handleNativeRequest(TASK_ID, request))
        val pending = ledger.unterminatedRecords().single()
        val durableArguments = Json.parseToJsonElement(
            pending.operation.argumentsCanonicalJson,
        ).jsonObject
        ledger.markNotificationMutationDispatched(
            pending.operation.callId,
            durableArguments.getValue("planDigest").jsonPrimitive.content,
        )

        val rebuilt = bridge(ledger, gateway, TaskApprovalMode.REQUEST_APPROVAL)
        rebuilt.recoverDestroyedRuntime()

        val recovered = requireNotNull(ledger.record(pending.operation.callId))
        assertEquals(AttentionLedgerState.FAILED_CLOSED.name, recovered.operation.ledgerState)
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
        assertEquals(0, gateway.posts)
    }

    @Test
    fun `request mode prompts before opening settings while full access opens directly`() =
        runTest {
            val gateway = FakeGateway()
            val ledger = RoomAttentionLedger(database)
            val requestBridge = bridge(ledger, gateway, TaskApprovalMode.REQUEST_APPROVAL)
            val settings = settingsRequest()

            assertNull(requestBridge.handleNativeRequest(TASK_ID, settings))
            assertEquals(0, gateway.settingsOpened)
            val pending = ledger.unterminatedRecords().single()
            assertFalse(pending.operation.sideEffect)
            assertTrue(
                pending.operation.argumentsCanonicalJson
                    .contains("\"approvalKind\":\"open_settings\""),
            )

            requestBridge.submitDecision(
                AttentionUserDecision.Confirm(pending.operation.callId),
            )

            assertEquals(1, gateway.settingsOpened)
            val terminal = requireNotNull(ledger.record(pending.operation.callId)).operation
            assertEquals(AttentionLedgerState.TERMINAL.name, terminal.ledgerState)
            assertEquals(
                "open_settings",
                ledger.piDeliveryExpectationForValidatedPair(terminal)
                    ?.contentPayload?.jsonObject
                    ?.get("action")?.jsonPrimitive?.content,
            )

            database.momodingDao().upsertTask(task())
            val fullBridge = bridge(
                RoomAttentionLedger(database),
                gateway,
                TaskApprovalMode.FULL_ACCESS,
            )
            val direct = fullBridge.handleNativeRequest(
                TASK_ID,
                settings.copy(id = "native-settings-full", toolCallId = "pi-settings-full"),
            )
            assertFalse(requireNotNull(direct).isError)
            assertEquals(2, gateway.settingsOpened)
        }

    private fun bridge(
        ledger: RoomAttentionLedger,
        gateway: NotificationGateway,
        mode: TaskApprovalMode,
    ) = PhoneLocalAttentionBridge(
        ledger = ledger,
        notificationTools = PhoneLocalNotificationToolExecutor(
            gateway = gateway,
            identityFactory = { prefix ->
                NotificationIdentity(prefix + "d".repeat(32), 41_820)
            },
        ),
        approvalModeForTask = { mode },
        ioDispatcher = Dispatchers.Unconfined,
    )

    private fun request(
        id: String = "native-notification",
        toolCallId: String = "pi-notification",
        title: String = "Export complete",
        message: String = "The export is ready.",
    ) = PiNativeToolRequest(
        id = id,
        kind = "android_notification_tool",
        toolCallId = toolCallId,
        toolName = PhoneLocalNotificationToolExecutor.TOOL_NAME,
        arguments = buildJsonObject {
            put("action", "post")
            put("title", title)
            put("message", message)
        },
    )

    private fun settingsRequest() = PiNativeToolRequest(
        id = "native-notification-settings",
        kind = "android_notification_tool",
        toolCallId = "pi-notification-settings",
        toolName = PhoneLocalNotificationToolExecutor.TOOL_NAME,
        arguments = buildJsonObject {
            put("action", "open_settings")
        },
    )

    private class FakeGateway : NotificationGateway {
        val active = linkedMapOf<NotificationIdentity, ActiveAgentNotification>()
        var posts = 0
        var settingsOpened = 0

        override suspend fun capability() = READY

        override suspend fun listActive(namespacePrefix: String) = active.values
            .filter { it.identity.tag.startsWith(namespacePrefix) }

        override suspend fun post(
            identity: NotificationIdentity,
            title: String,
            message: String,
        ) {
            posts += 1
            active[identity] = ActiveAgentNotification(
                identity,
                title,
                message,
                posts.toLong(),
            )
        }

        override suspend fun cancel(identity: NotificationIdentity) {
            active.remove(identity)
        }

        override suspend fun openAppNotificationSettings() {
            settingsOpened += 1
        }
    }

    private fun task() = TaskEntity(
        taskId = TASK_ID,
        title = "Notification fixture",
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
        const val TASK_ID = "77777777-7777-4777-8777-777777777777"
        val READY = NotificationCapabilitySnapshot(
            declared = true,
            runtimePermissionGranted = true,
            appNotificationsEnabled = true,
            channelState = NotificationChannelState.ENABLED,
        )
    }
}
