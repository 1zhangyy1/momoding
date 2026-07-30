package app.momoding.core.calendar

import app.momoding.core.capabilities.CalendarCapabilityAccess
import app.momoding.core.runtime.local.PiNativeToolRequest
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CalendarToolRequestParserTest {
    @Test
    fun `all six actions parse into the expected access and mutation classes`() {
        val requests = listOf(
            parse(buildJsonObject {
                put("action", "list_calendars")
                put("purpose", "Choose a writable calendar")
            }),
            parse(buildJsonObject {
                put("action", "list_events")
                put("purpose", "Find appointments next week")
                put("start", "2026-07-29T00:00:00+08:00")
                put("end", "2026-08-05T00:00:00+08:00")
                put("calendarHandle", JsonNull)
                put("query", JsonNull)
                put("cursor", JsonNull)
            }),
            parse(buildJsonObject {
                put("action", "get_event")
                put("purpose", "Inspect the selected appointment")
                put("eventHandle", EVENT_HANDLE)
            }),
            parse(buildJsonObject {
                put("action", "create_event")
                put("purpose", "Add the appointment the user requested")
                put("title", "Design review")
                put("schedule", timedSchedule())
                put("location", JsonNull)
                put("description", JsonNull)
                put("calendarHandle", JsonNull)
            }),
            parse(buildJsonObject {
                put("action", "update_event")
                put("purpose", "Move the selected appointment")
                put("eventHandle", EVENT_HANDLE)
                put(
                    "changes",
                    buildJsonObject {
                        put("schedule", allDaySchedule())
                        put("location", JsonNull)
                    },
                )
            }),
            parse(buildJsonObject {
                put("action", "delete_event")
                put("purpose", "Remove the appointment the user selected")
                put("eventHandle", EVENT_HANDLE)
            }),
        )

        assertEquals(CalendarToolAction.entries.toList(), requests.map { it.action })
        assertEquals(
            listOf(false, false, false, true, true, true),
            requests.map { it.action.isMutation },
        )
        assertEquals(
            listOf(
                CalendarCapabilityAccess.READ,
                CalendarCapabilityAccess.READ,
                CalendarCapabilityAccess.READ,
                CalendarCapabilityAccess.WRITE,
                CalendarCapabilityAccess.WRITE,
                CalendarCapabilityAccess.WRITE,
            ),
            requests.map { it.action.requiredAccess },
        )
    }

    @Test
    fun `update distinguishes omitted nullable fields from an explicit clear`() {
        val request = parse(buildJsonObject {
            put("action", "update_event")
            put("purpose", "Clear the old location")
            put("eventHandle", EVENT_HANDLE)
            put(
                "changes",
                buildJsonObject {
                    put("location", JsonNull)
                },
            )
        }) as CalendarToolRequest.UpdateEvent

        assertTrue(request.changes.location is CalendarNullableChange.Clear)
        assertTrue(request.changes.description is CalendarNullableChange.Unchanged)
        assertNull(request.changes.title)
        assertNull(request.changes.schedule)
    }

    @Test
    fun `semantic bounds reject invalid windows schedules handles and extra fields`() {
        val invalidArguments = listOf(
            buildJsonObject {
                put("action", "list_events")
                put("purpose", "Reject timestamps without RFC3339 seconds")
                put("start", "2026-07-29T00:00Z")
                put("end", "2026-07-30T00:00Z")
                put("calendarHandle", JsonNull)
                put("query", JsonNull)
                put("cursor", JsonNull)
            },
            buildJsonObject {
                put("action", "list_events")
                put("purpose", "Read an excessive window")
                put("start", "2026-01-01T00:00:00Z")
                put("end", "2026-02-02T00:00:00Z")
                put("calendarHandle", JsonNull)
                put("query", JsonNull)
                put("cursor", JsonNull)
            },
            buildJsonObject {
                put("action", "create_event")
                put("purpose", "Create an invalid reversed event")
                put("title", "Invalid")
                put(
                    "schedule",
                    buildJsonObject {
                        put("kind", "timed")
                        put("start", "2026-07-29T11:00:00+08:00")
                        put("end", "2026-07-29T10:00:00+08:00")
                        put("timeZone", "Asia/Shanghai")
                    },
                )
                put("location", JsonNull)
                put("description", JsonNull)
                put("calendarHandle", JsonNull)
            },
            buildJsonObject {
                put("action", "create_event")
                put("purpose", "Reject a fixed offset in place of an IANA zone")
                put("title", "Invalid fixed zone")
                put(
                    "schedule",
                    buildJsonObject {
                        put("kind", "timed")
                        put("start", "2026-07-29T10:00:00+08:00")
                        put("end", "2026-07-29T11:00:00+08:00")
                        put("timeZone", "+08:00")
                    },
                )
                put("location", JsonNull)
                put("description", JsonNull)
                put("calendarHandle", JsonNull)
            },
            buildJsonObject {
                put("action", "create_event")
                put("purpose", "Reject offsets that conflict with the IANA zone")
                put("title", "Invalid zone offset")
                put(
                    "schedule",
                    buildJsonObject {
                        put("kind", "timed")
                        put("start", "2026-07-29T10:00:00+08:00")
                        put("end", "2026-07-29T11:00:00+08:00")
                        put("timeZone", "America/New_York")
                    },
                )
                put("location", JsonNull)
                put("description", JsonNull)
                put("calendarHandle", JsonNull)
            },
            buildJsonObject {
                put("action", "get_event")
                put("purpose", "Read an invalid handle")
                put("eventHandle", "content://calendar/events/42")
            },
            buildJsonObject {
                put("action", "list_calendars")
                put("purpose", "Read calendars")
                put("unexpected", "not allowed")
            },
            buildJsonObject {
                put("action", "update_event")
                put("purpose", "Update nothing")
                put("eventHandle", EVENT_HANDLE)
                put("changes", buildJsonObject {})
            },
        )

        invalidArguments.forEach { arguments ->
            assertThrows(CalendarToolArgumentsException::class.java) {
                CalendarToolRequestParser.parse(arguments)
            }
        }
    }

    @Test
    fun `timed schedule accepts matching offsets across an IANA daylight saving transition`() {
        val request = parse(buildJsonObject {
            put("action", "create_event")
            put("purpose", "Create a meeting across the daylight saving transition")
            put("title", "DST review")
            put(
                "schedule",
                buildJsonObject {
                    put("kind", "timed")
                    put("start", "2026-11-01T01:30:00-04:00")
                    put("end", "2026-11-01T02:30:00-05:00")
                    put("timeZone", "America/New_York")
                },
            )
            put("location", JsonNull)
            put("description", JsonNull)
            put("calendarHandle", JsonNull)
        }) as CalendarToolRequest.CreateEvent

        val schedule = request.schedule as CalendarSchedule.Timed
        assertEquals("America/New_York", schedule.timeZone.id)
        assertEquals("-04:00", schedule.start.offset.id)
        assertEquals("-05:00", schedule.end.offset.id)
    }

    @Test
    fun `contract executor returns compact stable errors without echoing raw arguments`() = runTest {
        val executor = PhoneLocalCalendarToolExecutor()
        val valid = executor.execute(
            TASK_ID,
            nativeRequest(
                buildJsonObject {
                    put("action", "get_event")
                    put("purpose", "Inspect appointment")
                    put("eventHandle", EVENT_HANDLE)
                },
            ),
        )
        val secret = "private-user-calendar-text"
        val invalid = executor.execute(
            TASK_ID,
            nativeRequest(
                buildJsonObject {
                    put("action", "create_event")
                    put("purpose", secret)
                    put("title", secret)
                },
            ),
        )
        val unknown = executor.execute(
            TASK_ID,
            nativeRequest(
                buildJsonObject {
                    put("action", "unknown_action")
                    put("purpose", secret)
                },
            ),
        )

        assertTrue(valid.isError)
        assertEquals(
            "PROVIDER_UNAVAILABLE",
            valid.contentPayload.getValue("error").jsonObject.getValue("code").jsonPrimitive.content,
        )
        assertEquals("get_event", valid.contentPayload.getValue("action").jsonPrimitive.content)
        assertTrue(invalid.isError)
        assertEquals(
            "INVALID_ARGUMENTS",
            invalid.contentPayload.getValue("error").jsonObject.getValue("code").jsonPrimitive.content,
        )
        assertEquals("create_event", invalid.contentPayload.getValue("action").jsonPrimitive.content)
        assertEquals(JsonNull, unknown.contentPayload.getValue("action"))
        assertFalse(invalid.contentPayload.toString().contains(secret))
        assertFalse(unknown.contentPayload.toString().contains(secret))
    }

    private fun parse(arguments: JsonObject): CalendarToolRequest =
        CalendarToolRequestParser.parse(arguments)

    private fun nativeRequest(arguments: JsonObject) = PiNativeToolRequest(
        id = "calendar-native-request",
        kind = "android_calendar_tool",
        toolCallId = "pi-calendar-call",
        toolName = PhoneLocalCalendarToolExecutor.TOOL_NAME,
        arguments = arguments,
    )

    private fun timedSchedule() = buildJsonObject {
        put("kind", "timed")
        put("start", "2026-07-29T10:00:00+08:00")
        put("end", "2026-07-29T11:00:00+08:00")
        put("timeZone", "Asia/Shanghai")
    }

    private fun allDaySchedule() = buildJsonObject {
        put("kind", "all_day")
        put("startDate", "2026-08-01")
        put("endDateExclusive", "2026-08-02")
        put("timeZone", "Asia/Shanghai")
    }

    private companion object {
        const val TASK_ID = "11111111-1111-4111-8111-111111111111"
        const val EVENT_HANDLE = "event-0123456789abcdef01234567"
    }
}
