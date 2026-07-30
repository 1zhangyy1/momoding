package app.momoding.core.calendar

import app.momoding.core.capabilities.AndroidCapabilityId
import app.momoding.core.capabilities.AndroidCapabilityProbe
import app.momoding.core.capabilities.AndroidCapabilityRegistry
import app.momoding.core.capabilities.AndroidCapabilityState
import app.momoding.core.capabilities.CapabilityAvailability
import app.momoding.core.runtime.local.PiNativeAndroidToolResult
import app.momoding.core.runtime.local.PiNativeToolRequest
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneLocalCalendarMutationExecutorTest {
    @Test
    fun `create plans against one writable calendar and returns a verified bounded event`() =
        runTest {
            val gateway = MutableCalendarGateway()
            val executor = executor(backgroundScope, gateway)
            val request = request("create-call", createArguments())

            val preparation = executor.prepareMutation(TASK_ID, request)
            assertTrue(preparation is CalendarMutationPreparation.Ready)
            val plan = (preparation as CalendarMutationPreparation.Ready).plan
            assertEquals(CalendarToolAction.CREATE_EVENT, plan.action)
            assertFalse(plan.summary.contains("account-key"))

            val result = executor.executeMutation(TASK_ID, request, plan) {}

            assertFalse(result.isError)
            assertEquals(1, gateway.createCalls)
            assertEquals(
                "verified",
                result.contentPayload.getValue("verification").jsonObject
                    .getValue("status").jsonPrimitive.content,
            )
            val event = result.contentPayload.getValue("data").jsonObject
                .getValue("event").jsonObject
            assertTrue(
                event.getValue("eventHandle").jsonPrimitive.content
                    .matches(Regex("^event-[0-9a-f]{24}$")),
            )
            assertFalse(result.contentPayload.toString().contains("account-key"))
            assertFalse(result.contentPayload.toString().contains("\"id\":"))
        }

    @Test
    fun `real clock precision is normalized to the RFC3339 ledger contract`() = runTest {
        val gateway = MutableCalendarGateway()
        val executor = executor(
            scope = backgroundScope,
            gateway = gateway,
            now = { Instant.parse("2026-07-29T08:00:00.123456789Z") },
        )
        val request = request("fractional-clock", createArguments())
        val plan = (
            executor.prepareMutation(TASK_ID, request) as CalendarMutationPreparation.Ready
            ).plan

        val result = executor.executeMutation(TASK_ID, request, plan) {}

        assertEquals(
            "2026-07-29T08:00:00.123Z",
            result.contentPayload.getValue("verification").jsonObject
                .getValue("observedAt").jsonPrimitive.content,
        )
    }

    @Test
    fun `update detects a changed live event before issuing the provider write`() = runTest {
        val gateway = MutableCalendarGateway()
        val executor = executor(backgroundScope, gateway)
        val eventHandle = listEventHandle(executor)
        val request = request("update-call", updateArguments(eventHandle))
        val plan = (
            executor.prepareMutation(TASK_ID, request) as CalendarMutationPreparation.Ready
            ).plan
        gateway.replaceTitle("Changed elsewhere")

        val result = executor.executeMutation(TASK_ID, request, plan) {}

        assertEquals("CONFLICT", result.errorCode())
        assertEquals(0, gateway.updateCalls)
    }

    @Test
    fun `delete rejects recurring events before approval or provider mutation`() = runTest {
        val gateway = MutableCalendarGateway(recurring = true)
        val executor = executor(backgroundScope, gateway)
        val eventHandle = listEventHandle(executor)

        val preparation = executor.prepareMutation(
            TASK_ID,
            request("delete-call", deleteArguments(eventHandle)),
        )

        val result = (preparation as CalendarMutationPreparation.Failed).result
        assertEquals("UNSUPPORTED_ACTION", result.errorCode())
        assertEquals(0, gateway.deleteCalls)
    }

    @Test
    fun `post verification failure and uncertain provider dispatch never claim success`() = runTest {
        val verificationGateway = MutableCalendarGateway().apply {
            hidePostMutationRead = true
        }
        val verificationExecutor = executor(backgroundScope, verificationGateway)
        val verificationRequest = request("verify-call", createArguments())
        val verificationPlan = (
            verificationExecutor.prepareMutation(TASK_ID, verificationRequest)
                as CalendarMutationPreparation.Ready
            ).plan

        val verificationResult = verificationExecutor.executeMutation(
            TASK_ID,
            verificationRequest,
            verificationPlan,
        ) {}

        assertEquals("VERIFICATION_FAILED", verificationResult.errorCode())
        assertEquals(1, verificationGateway.createCalls)

        val uncertainGateway = MutableCalendarGateway().apply {
            throwAfterCreate = true
        }
        val uncertainExecutor = executor(backgroundScope, uncertainGateway)
        val uncertainRequest = request("uncertain-call", createArguments())
        val uncertainPlan = (
            uncertainExecutor.prepareMutation(TASK_ID, uncertainRequest)
                as CalendarMutationPreparation.Ready
            ).plan

        val uncertainResult = uncertainExecutor.executeMutation(
            TASK_ID,
            uncertainRequest,
            uncertainPlan,
        ) {}

        assertEquals("OUTCOME_UNKNOWN", uncertainResult.errorCode())
        assertEquals(1, uncertainGateway.createCalls)
    }

    @Test
    fun `write preparation fails closed when Android has read access only`() = runTest {
        val gateway = MutableCalendarGateway()
        val executor = PhoneLocalCalendarToolExecutor(
            gateway = gateway,
            capabilityRegistry = registry(
                CapabilityAvailability.PARTIAL,
                backgroundScope,
            ),
        )

        val preparation = executor.prepareMutation(
            TASK_ID,
            request("create-call", createArguments()),
        )

        val result = (preparation as CalendarMutationPreparation.Failed).result
        assertEquals("CAPABILITY_NOT_READY", result.errorCode())
        assertEquals(0, gateway.createCalls)
        assertEquals(
            "write",
            result.contentPayload.getValue("error").jsonObject
                .getValue("resolution").jsonObject
                .getValue("requiredAccess").jsonPrimitive.content,
        )
    }

    @Test
    fun `cancellation during the live precondition issues zero provider writes`() = runTest {
        val gateway = MutableCalendarGateway()
        val executor = executor(backgroundScope, gateway)
        val request = request("cancel-call", createArguments())
        val plan = (
            executor.prepareMutation(TASK_ID, request) as CalendarMutationPreparation.Ready
            ).plan
        gateway.blockPrecondition = true

        val execution = async {
            executor.executeMutation(TASK_ID, request, plan) {}
        }
        gateway.preconditionStarted.await()
        execution.cancel()
        val failure = runCatching { execution.await() }.exceptionOrNull()

        assertTrue(failure is CancellationException)
        assertEquals(0, gateway.createCalls)
    }

    @Test
    fun `cancellation during mutation preparation is rethrown and never becomes provider failure`() =
        runTest {
            val gateway = MutableCalendarGateway()
            val executor = executor(backgroundScope, gateway)
            gateway.blockCalendarList = true

            val preparation = async {
                executor.prepareMutation(
                    TASK_ID,
                    request("prepare-cancel-call", createArguments()),
                )
            }
            gateway.calendarListStarted.await()
            preparation.cancel()
            val failure = runCatching { preparation.await() }.exceptionOrNull()

            assertTrue(failure is CancellationException)
            assertEquals(0, gateway.createCalls)
        }

    @Test
    fun `provider deadline after durable dispatch returns outcome unknown without replay`() = runTest {
        val gateway = MutableCalendarGateway()
        val executor = executor(backgroundScope, gateway, timeoutMillis = 1)
        val request = request("deadline-call", createArguments())
        val plan = (
            executor.prepareMutation(TASK_ID, request) as CalendarMutationPreparation.Ready
            ).plan
        gateway.hangCreate = true
        var dispatchFenced = false

        val result = executor.executeMutation(TASK_ID, request, plan) {
            dispatchFenced = true
        }

        assertTrue(dispatchFenced)
        assertEquals("OUTCOME_UNKNOWN", result.errorCode())
        assertEquals(1, gateway.createCalls)
    }

    private suspend fun listEventHandle(executor: PhoneLocalCalendarToolExecutor): String {
        val result = executor.execute(
            TASK_ID,
            request(
                "list-call",
                buildJsonObject {
                    put("action", "list_events")
                    put("purpose", "Choose one event")
                    put("start", "2026-07-29T00:00:00Z")
                    put("end", "2026-07-31T00:00:00Z")
                    put("calendarHandle", JsonNull)
                    put("query", JsonNull)
                    put("cursor", JsonNull)
                },
            ),
        )
        return result.contentPayload.getValue("data").jsonObject
            .getValue("items").jsonArray.single().jsonObject
            .getValue("eventHandle").jsonPrimitive.content
    }

    private fun executor(
        scope: CoroutineScope,
        gateway: CalendarGateway,
        timeoutMillis: Long = 5_000L,
        now: () -> Instant = { Instant.parse("2026-07-29T08:00:00Z") },
    ) = PhoneLocalCalendarToolExecutor(
        gateway = gateway,
        capabilityRegistry = registry(CapabilityAvailability.READY, scope),
        now = now,
        timeoutMillis = timeoutMillis,
    )

    private fun registry(
        calendarAvailability: CapabilityAvailability,
        scope: CoroutineScope,
    ) = AndroidCapabilityRegistry(
        probes = AndroidCapabilityId.entries.associateWith { id ->
            AndroidCapabilityProbe { checkedAt ->
                AndroidCapabilityState(
                    id = id,
                    availability = if (id == AndroidCapabilityId.CALENDAR) {
                        calendarAvailability
                    } else {
                        CapabilityAvailability.NOT_GRANTED
                    },
                    source = "test",
                    checkedAtMillis = checkedAt,
                    safeMessage = "test ${id.name.lowercase()} state",
                )
            }
        },
        scope = scope,
        nowMillis = { 42L },
    )

    private fun createArguments() = buildJsonObject {
        put("action", "create_event")
        put("purpose", "Schedule the project review")
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
        put("location", "Room 2")
        put("description", "Review the mobile milestone")
        put("calendarHandle", JsonNull)
    }

    private fun updateArguments(eventHandle: String) = buildJsonObject {
        put("action", "update_event")
        put("purpose", "Rename the selected review")
        put("eventHandle", eventHandle)
        put(
            "changes",
            buildJsonObject {
                put("title", "Updated project review")
            },
        )
    }

    private fun deleteArguments(eventHandle: String) = buildJsonObject {
        put("action", "delete_event")
        put("purpose", "Remove the selected review")
        put("eventHandle", eventHandle)
    }

    private fun request(callId: String, arguments: JsonObject) = PiNativeToolRequest(
        id = "native-$callId",
        kind = "android_calendar_tool",
        toolCallId = callId,
        toolName = PhoneLocalCalendarToolExecutor.TOOL_NAME,
        arguments = arguments,
    )

    private fun PiNativeAndroidToolResult.errorCode(): String =
        contentPayload.getValue("error").jsonObject.getValue("code").jsonPrimitive.content

    private class MutableCalendarGateway(
        recurring: Boolean = false,
    ) : CalendarGateway {
        private val calendar = CalendarRecord(
            id = 7,
            displayName = "Work",
            accountKey = "account-key",
            writable = true,
            primary = true,
            timeZone = "Asia/Shanghai",
        )
        private var nextEventId = 102L
        private val events = linkedMapOf(
            101L to record(
                eventId = 101,
                values = CalendarEventWrite(
                    calendarId = 7,
                    title = "Existing review",
                    startMillis = START,
                    endMillis = END,
                    allDay = false,
                    timeZone = "Asia/Shanghai",
                    location = "Room 1",
                    description = "Existing description",
                ),
                recurring = recurring,
            ),
        )
        var createCalls = 0
        var updateCalls = 0
        var deleteCalls = 0
        var hidePostMutationRead = false
        var throwAfterCreate = false
        var blockPrecondition = false
        var blockCalendarList = false
        var hangCreate = false
        val preconditionStarted = CompletableDeferred<Unit>()
        val calendarListStarted = CompletableDeferred<Unit>()
        private var mutationIssued = false

        override suspend fun listCalendars(limit: Int): List<CalendarRecord> {
            if (blockCalendarList) {
                calendarListStarted.complete(Unit)
                awaitCancellation()
            }
            return listOf(calendar).take(limit)
        }

        override suspend fun getCalendar(calendarId: Long): CalendarRecord? =
            if (blockPrecondition) {
                preconditionStarted.complete(Unit)
                awaitCancellation()
            } else {
                calendar.takeIf { it.id == calendarId }
            }

        override suspend fun listEvents(
            startMillis: Long,
            endMillis: Long,
            calendarId: Long?,
            query: String?,
            offset: Int,
            limit: Int,
        ): CalendarEventPage = CalendarEventPage(
            items = events.values
                .filter { calendarId == null || it.calendarId == calendarId }
                .drop(offset)
                .take(limit),
            hasMore = false,
        )

        override suspend fun getEvent(target: CalendarEventTarget): CalendarEventRecord? =
            events[target.eventId]

        override suspend fun getEventById(eventId: Long): CalendarEventRecord? =
            if (hidePostMutationRead && mutationIssued) null else events[eventId]

        override suspend fun createEvent(values: CalendarEventWrite): Long {
            createCalls += 1
            mutationIssued = true
            val eventId = nextEventId++
            events[eventId] = record(eventId, values)
            if (hangCreate) delay(Long.MAX_VALUE)
            if (throwAfterCreate) error("provider response lost after dispatch")
            return eventId
        }

        override suspend fun updateEvent(
            eventId: Long,
            values: CalendarEventWrite,
        ): Boolean {
            updateCalls += 1
            mutationIssued = true
            if (eventId !in events) return false
            events[eventId] = record(eventId, values)
            return true
        }

        override suspend fun deleteEvent(eventId: Long): Boolean {
            deleteCalls += 1
            mutationIssued = true
            return events.remove(eventId) != null
        }

        fun replaceTitle(title: String) {
            val current = events.getValue(101)
            events[101] = current.copy(title = title)
        }

        companion object {
            private const val START = 1_775_014_400_000L
            private const val END = 1_775_018_000_000L

            private fun record(
                eventId: Long,
                values: CalendarEventWrite,
                recurring: Boolean = false,
            ) = CalendarEventRecord(
                target = CalendarEventTarget(
                    eventId = eventId,
                    instanceBeginMillis = values.startMillis,
                    instanceEndMillis = values.endMillis,
                ),
                calendarId = values.calendarId,
                calendarDisplayName = "Work",
                calendarWritable = true,
                title = values.title,
                startMillis = values.startMillis,
                endMillis = values.endMillis,
                allDay = values.allDay,
                timeZone = values.timeZone,
                location = values.location,
                description = values.description,
                recurring = recurring,
            )
        }
    }

    private companion object {
        const val TASK_ID = "task-calendar-mutation"
        const val START = 1_775_014_400_000L
        const val END = 1_775_018_000_000L
    }
}
