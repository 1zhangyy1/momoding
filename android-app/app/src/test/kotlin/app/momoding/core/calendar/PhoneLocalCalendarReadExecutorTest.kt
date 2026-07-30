package app.momoding.core.calendar

import app.momoding.core.capabilities.AndroidCapabilityId
import app.momoding.core.capabilities.AndroidCapabilityProbe
import app.momoding.core.capabilities.AndroidCapabilityRegistry
import app.momoding.core.capabilities.AndroidCapabilityState
import app.momoding.core.capabilities.CapabilityAvailability
import app.momoding.core.runtime.local.PiNativeToolRequest
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneLocalCalendarReadExecutorTest {
    @Test
    fun `read capability is fail closed with one typed recovery instruction`() = runTest {
        var gatewayCalls = 0
        val executor = executor(
            availability = CapabilityAvailability.NOT_GRANTED,
            scope = backgroundScope,
            gateway = object : FakeCalendarGateway() {
                override suspend fun listCalendars(limit: Int): List<CalendarRecord> {
                    gatewayCalls += 1
                    return emptyList()
                }
            },
        )

        val result = executor.execute(
            TASK_ID,
            request(listCalendarsArguments()),
        )

        assertTrue(result.isError)
        val error = result.contentPayload.getValue("error").jsonObject
        assertEquals("CAPABILITY_NOT_READY", error.getValue("code").jsonPrimitive.content)
        assertEquals(
            "calendar",
            error.getValue("resolution").jsonObject
                .getValue("capability").jsonPrimitive.content,
        )
        assertEquals(
            "read",
            error.getValue("resolution").jsonObject
                .getValue("requiredAccess").jsonPrimitive.content,
        )
        assertEquals(0, gatewayCalls)
    }

    @Test
    fun `calendar and event pages expose bounded summaries through task handles`() = runTest {
        val gateway = RecordingCalendarGateway()
        val executor = executor(
            availability = CapabilityAvailability.PARTIAL,
            scope = backgroundScope,
            gateway = gateway,
        )

        val calendars = executor.execute(TASK_ID, request(listCalendarsArguments()))
        assertFalse(calendars.isError)
        val calendarItems = calendars.contentPayload
            .getValue("data").jsonObject
            .getValue("items").jsonArray
        assertEquals(20, calendarItems.size)
        val firstCalendarHandle = calendarItems.first().jsonObject
            .getValue("calendarHandle").jsonPrimitive.content
        assertTrue(firstCalendarHandle.matches(Regex("^calendar-[0-9a-f]{24}$")))
        assertFalse(calendars.contentPayload.toString().contains("private-account"))
        assertFalse(calendars.contentPayload.toString().contains("\"id\":"))
        assertEquals(
            "true",
            calendars.contentPayload.getValue("page").jsonObject
                .getValue("truncated").jsonPrimitive.content,
        )

        val firstPage = executor.execute(
            TASK_ID,
            request(listEventsArguments(firstCalendarHandle)),
        )
        assertFalse(firstPage.isError)
        assertEquals(7L, gateway.requestedCalendarId)
        assertEquals(0, gateway.requestedOffset)
        val event = firstPage.contentPayload.getValue("data").jsonObject
            .getValue("items").jsonArray.first().jsonObject
        val eventHandle = event.getValue("eventHandle").jsonPrimitive.content
        val nextCursor = firstPage.contentPayload.getValue("page").jsonObject
            .getValue("nextCursor").jsonPrimitive.content
        assertTrue(eventHandle.matches(Regex("^event-[0-9a-f]{24}$")))
        assertTrue(nextCursor.matches(Regex("^calendar-page-[0-9a-f]{24}$")))
        assertTrue(
            event.getValue("schedule").jsonObject.getValue("start").jsonPrimitive.content
                .matches(RFC3339_MILLIS),
        )
        assertNull(event["location"])
        assertNull(event["description"])
        assertFalse(firstPage.contentPayload.toString().contains("private description"))

        val secondPage = executor.execute(
            TASK_ID,
            request(listEventsArguments(firstCalendarHandle, nextCursor)),
        )
        assertFalse(secondPage.isError)
        assertEquals(10, gateway.requestedOffset)

        val details = executor.execute(
            TASK_ID,
            request(
                buildJsonObject {
                    put("action", "get_event")
                    put("purpose", "Inspect one meeting")
                    put("eventHandle", eventHandle)
                },
            ),
        )
        assertFalse(details.isError)
        val detailedEvent = details.contentPayload.getValue("data").jsonObject
            .getValue("event").jsonObject
        assertEquals("Room 3", detailedEvent.getValue("location").jsonPrimitive.content)
        assertEquals(
            "private description",
            detailedEvent.getValue("description").jsonPrimitive.content,
        )
        assertEquals(
            "observed",
            details.contentPayload.getValue("verification").jsonObject
                .getValue("status").jsonPrimitive.content,
        )
    }

    @Test
    fun `event handles stay task scoped across turns and provider timeout stays compact`() =
        runTest {
        val gateway = RecordingCalendarGateway()
        val executor = executor(
            availability = CapabilityAvailability.PARTIAL,
            scope = backgroundScope,
            gateway = gateway,
        )
        val listed = executor.execute(
            TASK_ID,
            request(listEventsArguments(calendarHandle = null)),
        )
        val eventHandle = listed.contentPayload.getValue("data").jsonObject
            .getValue("items").jsonArray.first().jsonObject
            .getValue("eventHandle").jsonPrimitive.content

        val crossTask = executor.execute(
            "other-task",
            request(
                buildJsonObject {
                    put("action", "get_event")
                    put("purpose", "Inspect one meeting")
                    put("eventHandle", eventHandle)
                },
            ),
        )
        assertEquals(
            "STALE_HANDLE",
            crossTask.contentPayload.getValue("error").jsonObject
                .getValue("code").jsonPrimitive.content,
        )
        executor.stopTask(TASK_ID, "tool_abort")
        val nextTurn = executor.execute(
            TASK_ID,
            request(
                buildJsonObject {
                    put("action", "get_event")
                    put("purpose", "Inspect one meeting in the next turn")
                    put("eventHandle", eventHandle)
                },
            ),
        )
        assertFalse(nextTurn.isError)

        executor.stopTask(TASK_ID, "session_stop")
        val afterStop = executor.execute(
            TASK_ID,
            request(
                buildJsonObject {
                    put("action", "get_event")
                    put("purpose", "Inspect one meeting")
                    put("eventHandle", eventHandle)
                },
            ),
        )
        assertEquals(
            "STALE_HANDLE",
            afterStop.contentPayload.getValue("error").jsonObject
                .getValue("code").jsonPrimitive.content,
        )

        val timeout = PhoneLocalCalendarToolExecutor(
            gateway = object : FakeCalendarGateway() {
                override suspend fun listCalendars(limit: Int): List<CalendarRecord> {
                    delay(Long.MAX_VALUE)
                    return emptyList()
                }
            },
            capabilityRegistry = registry(CapabilityAvailability.PARTIAL, backgroundScope),
            timeoutMillis = 1,
        ).execute(TASK_ID, request(listCalendarsArguments()))
        assertEquals(
            "DEVICE_TOOL_TIMEOUT",
            timeout.contentPayload.getValue("error").jsonObject
                .getValue("code").jsonPrimitive.content,
        )
    }

    private fun executor(
        availability: CapabilityAvailability,
        scope: CoroutineScope,
        gateway: CalendarGateway,
    ) = PhoneLocalCalendarToolExecutor(
        gateway = gateway,
        capabilityRegistry = registry(availability, scope),
        now = { Instant.parse("2026-07-29T08:00:00Z") },
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

    private fun listCalendarsArguments() = buildJsonObject {
        put("action", "list_calendars")
        put("purpose", "Find my calendars")
    }

    private fun listEventsArguments(
        calendarHandle: String?,
        cursor: String? = null,
    ) = buildJsonObject {
        put("action", "list_events")
        put("purpose", "Find meetings tomorrow")
        put("start", "2026-07-29T00:00:00Z")
        put("end", "2026-07-30T00:00:00Z")
        if (calendarHandle == null) put("calendarHandle", JsonNull)
        else put("calendarHandle", calendarHandle)
        put("query", JsonNull)
        if (cursor == null) put("cursor", JsonNull) else put("cursor", cursor)
    }

    private fun request(arguments: JsonObject) = PiNativeToolRequest(
        id = "native-calendar",
        kind = "android_calendar_tool",
        toolCallId = "calendar-call",
        toolName = PhoneLocalCalendarToolExecutor.TOOL_NAME,
        arguments = arguments,
    )

    private open class FakeCalendarGateway : CalendarGateway {
        override suspend fun listCalendars(limit: Int): List<CalendarRecord> = emptyList()

        override suspend fun listEvents(
            startMillis: Long,
            endMillis: Long,
            calendarId: Long?,
            query: String?,
            offset: Int,
            limit: Int,
        ) = CalendarEventPage(emptyList(), false)

        override suspend fun getEvent(target: CalendarEventTarget): CalendarEventRecord? = null
    }

    private class RecordingCalendarGateway : FakeCalendarGateway() {
        var requestedCalendarId: Long? = null
        var requestedOffset: Int? = null
        private val target = CalendarEventTarget(101, START, END)

        override suspend fun listCalendars(limit: Int): List<CalendarRecord> =
            (0 until 21).map { index ->
                CalendarRecord(
                    id = if (index == 0) 7 else index.toLong() + 7,
                    displayName = "Calendar ${index + 1}",
                    accountKey = "private-account-${index / 2}",
                    writable = index % 2 == 0,
                    primary = index == 0,
                    timeZone = "Asia/Shanghai",
                )
            }.take(limit)

        override suspend fun listEvents(
            startMillis: Long,
            endMillis: Long,
            calendarId: Long?,
            query: String?,
            offset: Int,
            limit: Int,
        ): CalendarEventPage {
            requestedCalendarId = calendarId
            requestedOffset = offset
            return CalendarEventPage(
                items = listOf(event()),
                hasMore = offset == 0,
            )
        }

        override suspend fun getEvent(target: CalendarEventTarget): CalendarEventRecord? =
            event().takeIf { target == this.target }

        private fun event() = CalendarEventRecord(
            target = target,
            calendarId = 7,
            calendarDisplayName = "Work",
            calendarWritable = false,
            title = "Design review",
            startMillis = START,
            endMillis = END,
            allDay = false,
            timeZone = "Asia/Shanghai",
            location = "Room 3",
            description = "private description",
            recurring = false,
        )
    }

    private companion object {
        const val TASK_ID = "task-calendar"
        const val START = 1_775_014_400_000L
        const val END = 1_775_018_000_000L
        val RFC3339_MILLIS =
            Regex("^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}\\.000[+-][0-9]{2}:[0-9]{2}$")
    }
}
