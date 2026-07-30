package app.momoding.core.calendar

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.momoding.core.data.MomodingDatabase
import app.momoding.core.data.AttentionPrompt
import app.momoding.core.data.AttentionRecordState
import app.momoding.core.data.AttentionRepository
import app.momoding.core.data.AttentionLedgerState
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
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
class PhoneLocalCalendarToolRoutingTest {
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
    fun `calendar native request routes to the calendar handler exactly once`() = runTest {
        val handler = FixtureCalendarHandler()
        val bridge = PhoneLocalAttentionBridge(
            ledger = RoomAttentionLedger(database),
            calendarTools = handler,
            approvalModeForTask = { TaskApprovalMode.AUTO_APPROVE },
            ioDispatcher = Dispatchers.Unconfined,
        )
        val request = PiNativeToolRequest(
            id = "calendar-native-request",
            kind = "android_calendar_tool",
            toolCallId = "pi-calendar-call",
            toolName = PhoneLocalCalendarToolExecutor.TOOL_NAME,
            arguments = buildJsonObject {
                put("action", "list_calendars")
                put("purpose", "Choose a calendar")
            },
        )

        val result = bridge.handleNativeRequest(TASK_ID, request)

        assertEquals(1, handler.executionCount)
        assertEquals(TASK_ID, handler.taskId)
        assertEquals(request, handler.request)
        assertEquals(false, result?.isError)
        assertEquals("list_calendars", result?.contentPayload?.get("action")?.jsonPrimitive?.content)
    }

    @Test
    fun `request approval prompts without persisting query and executes only after confirm`() =
        runTest {
            val handler = FixtureCalendarHandler()
            val ledger = RoomAttentionLedger(database)
            val bridge = PhoneLocalAttentionBridge(
                ledger = ledger,
                calendarTools = handler,
                approvalModeForTask = { TaskApprovalMode.REQUEST_APPROVAL },
                ioDispatcher = Dispatchers.Unconfined,
            )
            val request = request(
                action = "list_events",
                extra = {
                    put("start", "2026-07-30T00:00:00+08:00")
                    put("end", "2026-07-31T00:00:00+08:00")
                    put("query", "PRIVATE_EVENT_QUERY")
                    put("purpose", "Find tomorrow's meetings")
                },
            )

            assertNull(bridge.handleNativeRequest(TASK_ID, request))
            val pending = ledger.unterminatedRecords().single()
            assertEquals("device_calendar", pending.operation.toolName)
            assertFalse(pending.operation.argumentsCanonicalJson.contains("PRIVATE_EVENT_QUERY"))
            assertEquals(0, handler.executionCount)
            val visible = AttentionRepository(
                database = database,
                ioDispatcher = Dispatchers.Unconfined,
            ).current(TASK_ID, pending.operation.callId) as AttentionRecordState.Available
            assertTrue(visible.record.prompt is AttentionPrompt.Confirmation)

            bridge.submitDecision(AttentionUserDecision.Confirm(pending.operation.callId))

            assertEquals(1, handler.executionCount)
            assertEquals(request, handler.request)
            assertTrue(ledger.record(pending.operation.callId)?.operation?.terminalSha256 == null)
            ledger.discardLiveApprovedRead(TASK_ID, pending.operation.callId)
            assertNull(ledger.record(pending.operation.callId))
        }

    @Test
    fun `request approval decline never reads calendar and closes with fixed rejection`() = runTest {
        val handler = FixtureCalendarHandler()
        val ledger = RoomAttentionLedger(database)
        val bridge = PhoneLocalAttentionBridge(
            ledger = ledger,
            calendarTools = handler,
            approvalModeForTask = { TaskApprovalMode.REQUEST_APPROVAL },
            ioDispatcher = Dispatchers.Unconfined,
        )

        assertNull(bridge.handleNativeRequest(TASK_ID, request()))
        val pending = ledger.unterminatedRecords().single()
        bridge.submitDecision(AttentionUserDecision.Decline(pending.operation.callId))

        assertEquals(0, handler.executionCount)
        assertEquals("rejected", ledger.record(pending.operation.callId)?.operation?.terminalKind)
    }

    @Test
    fun `unready calendar returns capability result before asking for task approval`() = runTest {
        val handler = FixtureCalendarHandler().apply { readCapabilityReady = false }
        val ledger = RoomAttentionLedger(database)
        val bridge = PhoneLocalAttentionBridge(
            ledger = ledger,
            calendarTools = handler,
            approvalModeForTask = { TaskApprovalMode.REQUEST_APPROVAL },
            ioDispatcher = Dispatchers.Unconfined,
        )

        val result = bridge.handleNativeRequest(TASK_ID, request())

        assertEquals(1, handler.executionCount)
        assertEquals(false, result?.isError)
        assertTrue(ledger.unterminatedRecords().isEmpty())
    }

    @Test
    fun `calendar create waits for approval executes once and replays its verified terminal`() =
        runTest {
            val handler = FixtureCalendarHandler()
            val ledger = RoomAttentionLedger(database)
            val bridge = PhoneLocalAttentionBridge(
                ledger = ledger,
                calendarTools = handler,
                approvalModeForTask = { TaskApprovalMode.REQUEST_APPROVAL },
                ioDispatcher = Dispatchers.Unconfined,
            )
            val request = mutationRequest(
                nativeRequestId = "native-create",
                toolCallId = "pi-calendar-create",
            )

            assertNull(bridge.handleNativeRequest(TASK_ID, request))
            val pending = ledger.unterminatedRecords().single()
            assertEquals(AttentionLedgerState.AWAITING_USER.name, pending.operation.ledgerState)
            assertEquals(1, handler.preparationCount)
            assertEquals(0, handler.mutationExecutionCount)
            assertFalse(
                pending.operation.argumentsCanonicalJson
                    .contains("PRIVATE_MUTATION_DESCRIPTION"),
            )

            bridge.submitDecision(AttentionUserDecision.Confirm(pending.operation.callId))

            assertEquals(1, handler.mutationExecutionCount)
            assertEquals(
                AttentionLedgerState.TERMINAL.name,
                ledger.record(pending.operation.callId)?.operation?.ledgerState,
            )
            val terminal = requireNotNull(ledger.record(pending.operation.callId)).operation
            val deliveryExpectation = requireNotNull(
                ledger.piDeliveryExpectationForValidatedPair(terminal),
            )
            ledger.markPiDeliveredAfterVerifiedProof(
                taskId = TASK_ID,
                callId = terminal.callId,
                terminalSemanticSha256 = deliveryExpectation.terminalSemanticSha256,
            )
            val compacted = requireNotNull(ledger.record(pending.operation.callId)).operation
            assertFalse(compacted.argumentsCanonicalJson.contains("Project review"))
            assertFalse(compacted.argumentsCanonicalJson.contains("PRIVATE_MUTATION_DESCRIPTION"))
            assertFalse(compacted.terminalFrameCanonicalJson.orEmpty().contains("Project review"))
            assertTrue(compacted.terminalFrameCanonicalJson.orEmpty().contains("\"redacted\":true"))
            val replay = bridge.handleNativeRequest(
                TASK_ID,
                mutationRequest(
                    nativeRequestId = "native-create-replay",
                    toolCallId = "pi-calendar-create",
                ),
            )
            assertEquals(false, replay?.isError)
            assertEquals("create_event", replay?.contentPayload?.get("action")?.jsonPrimitive?.content)
            assertEquals(1, handler.preparationCount)
            assertEquals(1, handler.mutationExecutionCount)
        }

    @Test
    fun `automatic modes create and update without a visible prompt`() = runTest {
        listOf(
            TaskApprovalMode.AUTO_APPROVE to "create_event",
            TaskApprovalMode.FULL_ACCESS to "update_event",
        ).forEachIndexed { index, (mode, action) ->
            val scopedDatabase = Room.inMemoryDatabaseBuilder(
                ApplicationProvider.getApplicationContext<Context>(),
                MomodingDatabase::class.java,
            ).allowMainThreadQueries().build()
            try {
                scopedDatabase.momodingDao().upsertTask(task())
                val handler = FixtureCalendarHandler()
                val scopedLedger = RoomAttentionLedger(scopedDatabase)
                val bridge = PhoneLocalAttentionBridge(
                    ledger = scopedLedger,
                    calendarTools = handler,
                    approvalModeForTask = { mode },
                    ioDispatcher = Dispatchers.Unconfined,
                )

                val result = bridge.handleNativeRequest(
                    TASK_ID,
                    mutationRequest(
                        nativeRequestId = "native-auto-$index",
                        toolCallId = "pi-calendar-auto-$index",
                        action = action,
                    ),
                )

                assertEquals(false, result?.isError)
                assertEquals(
                    "auto_policy",
                    result?.details?.get("approvalOrigin")?.jsonPrimitive?.content,
                )
                assertEquals(1, handler.mutationExecutionCount)
                assertTrue(scopedLedger.unterminatedRecords().isEmpty())
            } finally {
                scopedDatabase.close()
            }
        }
    }

    @Test
    fun `calendar delete always prompts and decline performs zero provider mutations`() = runTest {
        val handler = FixtureCalendarHandler()
        val ledger = RoomAttentionLedger(database)
        val bridge = PhoneLocalAttentionBridge(
            ledger = ledger,
            calendarTools = handler,
            approvalModeForTask = { TaskApprovalMode.FULL_ACCESS },
            ioDispatcher = Dispatchers.Unconfined,
        )

        assertNull(
            bridge.handleNativeRequest(
                TASK_ID,
                mutationRequest(
                    nativeRequestId = "native-delete",
                    toolCallId = "pi-calendar-delete",
                    action = "delete_event",
                ),
            ),
        )
        val pending = ledger.unterminatedRecords().single()
        assertEquals(0, handler.mutationExecutionCount)

        bridge.submitDecision(AttentionUserDecision.Decline(pending.operation.callId))

        assertEquals(0, handler.mutationExecutionCount)
        assertEquals(
            "rejected",
            ledger.record(pending.operation.callId)?.operation?.terminalKind,
        )
    }

    @Test
    fun `duplicate mutation with changed arguments fails closed before another plan`() = runTest {
        val handler = FixtureCalendarHandler()
        val ledger = RoomAttentionLedger(database)
        val bridge = PhoneLocalAttentionBridge(
            ledger = ledger,
            calendarTools = handler,
            approvalModeForTask = { TaskApprovalMode.REQUEST_APPROVAL },
            ioDispatcher = Dispatchers.Unconfined,
        )
        assertNull(
            bridge.handleNativeRequest(
                TASK_ID,
                mutationRequest("native-first", "pi-stable"),
            ),
        )

        val conflict = bridge.handleNativeRequest(
            TASK_ID,
            mutationRequest(
                nativeRequestId = "native-conflict",
                toolCallId = "pi-stable",
                title = "Different event",
            ),
        )

        assertEquals(true, conflict?.isError)
        assertEquals(
            "CALENDAR_DUPLICATE_CONFLICT",
            conflict?.contentPayload?.get("error")?.jsonObject
                ?.get("code")?.jsonPrimitive?.content,
        )
        assertEquals(1, handler.preparationCount)
        assertEquals(0, handler.mutationExecutionCount)
    }

    @Test
    fun `runtime rebuild turns a durably dispatched mutation into outcome unknown`() = runTest {
        val handler = FixtureCalendarHandler()
        val ledger = RoomAttentionLedger(database)
        val firstBridge = PhoneLocalAttentionBridge(
            ledger = ledger,
            calendarTools = handler,
            approvalModeForTask = { TaskApprovalMode.REQUEST_APPROVAL },
            ioDispatcher = Dispatchers.Unconfined,
        )
        val request = mutationRequest("native-crash", "pi-calendar-crash")
        assertNull(firstBridge.handleNativeRequest(TASK_ID, request))
        val pending = ledger.unterminatedRecords().single()
        val durableArguments = Json.parseToJsonElement(
            pending.operation.argumentsCanonicalJson,
        ).jsonObject
        ledger.markCalendarMutationDispatched(
            pending.operation.callId,
            durableArguments.getValue("planDigest").jsonPrimitive.content,
        )

        val rebuiltBridge = PhoneLocalAttentionBridge(
            ledger = ledger,
            calendarTools = handler,
            approvalModeForTask = { TaskApprovalMode.REQUEST_APPROVAL },
            ioDispatcher = Dispatchers.Unconfined,
        )
        rebuiltBridge.recoverDestroyedRuntime()

        val recovered = requireNotNull(ledger.record(pending.operation.callId))
        assertEquals(AttentionLedgerState.FAILED_CLOSED.name, recovered.operation.ledgerState)
        assertTrue(
            recovered.operation.terminalFrameCanonicalJson
                ?.contains("\"code\":\"OUTCOME_UNKNOWN\"") == true,
        )
        val replay = rebuiltBridge.handleNativeRequest(
            TASK_ID,
            mutationRequest("native-crash-replay", "pi-calendar-crash"),
        )
        assertEquals(true, replay?.isError)
        assertEquals(
            "OUTCOME_UNKNOWN",
            replay?.contentPayload?.get("error")?.jsonObject
                ?.get("code")?.jsonPrimitive?.content,
        )
        assertEquals(1, handler.preparationCount)
        assertEquals(0, handler.mutationExecutionCount)
    }

    private fun request(
        action: String = "list_calendars",
        extra: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit = {
            put("purpose", "Choose a calendar")
        },
    ) = PiNativeToolRequest(
        id = "calendar-native-${action}-${System.nanoTime()}",
        kind = "android_calendar_tool",
        toolCallId = "pi-calendar-$action",
        toolName = PhoneLocalCalendarToolExecutor.TOOL_NAME,
        arguments = buildJsonObject {
            put("action", action)
            extra()
        },
    )

    private fun mutationRequest(
        nativeRequestId: String,
        toolCallId: String,
        action: String = "create_event",
        title: String = "Project review",
    ) = PiNativeToolRequest(
        id = nativeRequestId,
        kind = "android_calendar_tool",
        toolCallId = toolCallId,
        toolName = PhoneLocalCalendarToolExecutor.TOOL_NAME,
        arguments = when (action) {
            "create_event" -> buildJsonObject {
                put("action", action)
                put("purpose", "Schedule the project review")
                put("title", title)
                put(
                    "schedule",
                    buildJsonObject {
                        put("kind", "timed")
                        put("start", "2026-07-30T09:00:00+08:00")
                        put("end", "2026-07-30T10:00:00+08:00")
                        put("timeZone", "Asia/Shanghai")
                    },
                )
                put("location", "Room 2")
                put("description", "PRIVATE_MUTATION_DESCRIPTION")
                put("calendarHandle", JsonNull)
            }
            "update_event" -> buildJsonObject {
                put("action", action)
                put("purpose", "Rename the selected review")
                put("eventHandle", "event-${"1".repeat(24)}")
                put("changes", buildJsonObject { put("title", title) })
            }
            "delete_event" -> buildJsonObject {
                put("action", action)
                put("purpose", "Delete the selected review")
                put("eventHandle", "event-${"1".repeat(24)}")
            }
            else -> error("unsupported fixture action")
        },
    )

    private class FixtureCalendarHandler : PhoneLocalCalendarToolHandler {
        var executionCount = 0
        var preparationCount = 0
        var mutationExecutionCount = 0
        var taskId: String? = null
        var request: PiNativeToolRequest? = null
        var readCapabilityReady = true

        override fun handles(toolName: String): Boolean =
            toolName == PhoneLocalCalendarToolExecutor.TOOL_NAME

        override fun isPersonalDataRead(request: PiNativeToolRequest): Boolean =
            request.arguments["action"]?.jsonPrimitive?.content in
                setOf("list_calendars", "list_events", "get_event")

        override fun isMutation(request: PiNativeToolRequest): Boolean =
            request.arguments["action"]?.jsonPrimitive?.content in
                setOf("create_event", "update_event", "delete_event")

        override fun mutationRequestDigest(request: PiNativeToolRequest): String =
            sha256(request.arguments.toString())

        override suspend fun isReadCapabilityReady(): Boolean = readCapabilityReady

        override suspend fun prepareMutation(
            taskId: String,
            request: PiNativeToolRequest,
        ): CalendarMutationPreparation {
            preparationCount += 1
            val action = requireNotNull(
                CalendarToolAction.fromWireValue(
                    request.arguments.getValue("action").jsonPrimitive.content,
                ),
            )
            val requestDigest = mutationRequestDigest(request)
            return CalendarMutationPreparation.Ready(
                CalendarMutationPlan(
                    taskId = taskId,
                    piToolCallId = request.toolCallId,
                    action = action,
                    requestDigest = requestDigest,
                    snapshotDigest = "2".repeat(64),
                    planDigest = sha256("$taskId:${request.toolCallId}:$requestDigest"),
                    summary = "${action.wireValue} one Calendar event?",
                    details = "Apply one bounded change and verify live Calendar state.",
                    calendar = CalendarRecord(
                        id = 7,
                        displayName = "Work",
                        accountKey = "private-account",
                        writable = true,
                        primary = true,
                        timeZone = "Asia/Shanghai",
                    ),
                    before = null,
                    write = null,
                ),
            )
        }

        override suspend fun executeMutation(
            taskId: String,
            request: PiNativeToolRequest,
            plan: CalendarMutationPlan,
            onProviderDispatch: suspend () -> Unit,
        ): PiNativeAndroidToolResult {
            onProviderDispatch()
            mutationExecutionCount += 1
            val data = if (plan.action == CalendarToolAction.DELETE_EVENT) {
                buildJsonObject { put("deleted", true) }
            } else {
                buildJsonObject {
                    put(
                        "event",
                        buildJsonObject {
                            put("eventHandle", "event-${"a".repeat(24)}")
                            put("title", "Project review")
                            put(
                                "schedule",
                                buildJsonObject {
                                    put("kind", "timed")
                                    put("start", "2026-07-30T09:00:00+08:00")
                                    put("end", "2026-07-30T10:00:00+08:00")
                                    put("timeZone", "Asia/Shanghai")
                                },
                            )
                            put(
                                "calendar",
                                buildJsonObject {
                                    put("calendarHandle", "calendar-${"b".repeat(24)}")
                                    put("displayName", "Work")
                                },
                            )
                            put("readOnly", false)
                            put("recurring", false)
                        },
                    )
                }
            }
            return PiNativeAndroidToolResult(
                contentPayload = buildJsonObject {
                    put("ok", true)
                    put("action", plan.action.wireValue)
                    put("data", data)
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

        override suspend fun execute(
            taskId: String,
            request: PiNativeToolRequest,
        ): PiNativeAndroidToolResult {
            executionCount += 1
            this.taskId = taskId
            this.request = request
            return PiNativeAndroidToolResult(
                contentPayload = buildJsonObject {
                    put("ok", true)
                    put("action", "list_calendars")
                },
            )
        }

        private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun task() = TaskEntity(
        taskId = TASK_ID,
        title = "Calendar fixture",
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
        const val TASK_ID = "11111111-1111-4111-8111-111111111111"
    }
}
