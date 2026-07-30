package app.momoding.core.calendar

import java.time.Duration
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.ZoneId
import java.time.format.DateTimeParseException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

object CalendarToolRequestParser {
    private const val MAX_PURPOSE_CHARS = 160
    private const val MAX_TITLE_CHARS = 200
    private const val MAX_QUERY_CHARS = 120
    private const val MAX_LOCATION_CHARS = 256
    private const val MAX_DESCRIPTION_CHARS = 1_024
    private const val MAX_TIME_ZONE_CHARS = 64
    private val MAX_QUERY_WINDOW: Duration = Duration.ofDays(31)
    private val MAX_EVENT_DURATION: Duration = Duration.ofDays(366)
    private val CALENDAR_HANDLE = Regex("^calendar-[0-9a-f]{24}$")
    private val EVENT_HANDLE = Regex("^event-[0-9a-f]{24}$")
    private val PAGE_CURSOR = Regex("^calendar-page-[A-Za-z0-9_-]+$")
    private val RFC3339_DATE_TIME = Regex(
        "^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}" +
            "(?:\\.[0-9]{1,9})?(?:Z|[+-][0-9]{2}:[0-9]{2})$",
    )

    fun parse(arguments: JsonObject): CalendarToolRequest {
        val action = CalendarToolAction.fromWireValue(
            arguments.requiredString("action", 32),
        ) ?: invalid()
        val purpose = arguments.requiredString("purpose", MAX_PURPOSE_CHARS)
        return when (action) {
            CalendarToolAction.LIST_CALENDARS -> {
                arguments.requireExactKeys("action", "purpose")
                CalendarToolRequest.ListCalendars(purpose)
            }
            CalendarToolAction.LIST_EVENTS -> parseListEvents(arguments, purpose)
            CalendarToolAction.GET_EVENT -> {
                arguments.requireExactKeys("action", "purpose", "eventHandle")
                CalendarToolRequest.GetEvent(
                    purpose = purpose,
                    eventHandle = arguments.requiredHandle("eventHandle", EVENT_HANDLE),
                )
            }
            CalendarToolAction.CREATE_EVENT -> parseCreate(arguments, purpose)
            CalendarToolAction.UPDATE_EVENT -> parseUpdate(arguments, purpose)
            CalendarToolAction.DELETE_EVENT -> {
                arguments.requireExactKeys("action", "purpose", "eventHandle")
                CalendarToolRequest.DeleteEvent(
                    purpose = purpose,
                    eventHandle = arguments.requiredHandle("eventHandle", EVENT_HANDLE),
                )
            }
        }
    }

    private fun parseListEvents(
        arguments: JsonObject,
        purpose: String,
    ): CalendarToolRequest.ListEvents {
        arguments.requireExactKeys(
            "action",
            "purpose",
            "start",
            "end",
            "calendarHandle",
            "query",
            "cursor",
        )
        val start = arguments.requiredOffsetDateTime("start")
        val end = arguments.requiredOffsetDateTime("end")
        val duration = Duration.between(start.toInstant(), end.toInstant())
        if (duration.isZero || duration.isNegative || duration > MAX_QUERY_WINDOW) invalid()
        return CalendarToolRequest.ListEvents(
            purpose = purpose,
            start = start,
            end = end,
            calendarHandle = arguments.nullableHandle("calendarHandle", CALENDAR_HANDLE),
            query = arguments.nullableString("query", MAX_QUERY_CHARS),
            cursor = arguments.nullableString("cursor", 160)?.also {
                if (!PAGE_CURSOR.matches(it)) invalid()
            },
        )
    }

    private fun parseCreate(
        arguments: JsonObject,
        purpose: String,
    ): CalendarToolRequest.CreateEvent {
        arguments.requireExactKeys(
            "action",
            "purpose",
            "title",
            "schedule",
            "location",
            "description",
            "calendarHandle",
        )
        return CalendarToolRequest.CreateEvent(
            purpose = purpose,
            title = arguments.requiredString("title", MAX_TITLE_CHARS),
            schedule = parseSchedule(arguments.requiredObject("schedule")),
            location = arguments.nullableString("location", MAX_LOCATION_CHARS),
            description = arguments.nullableString("description", MAX_DESCRIPTION_CHARS),
            calendarHandle = arguments.nullableHandle("calendarHandle", CALENDAR_HANDLE),
        )
    }

    private fun parseUpdate(
        arguments: JsonObject,
        purpose: String,
    ): CalendarToolRequest.UpdateEvent {
        arguments.requireExactKeys("action", "purpose", "eventHandle", "changes")
        val changes = arguments.requiredObject("changes")
        if (changes.isEmpty()) invalid()
        changes.requireAllowedKeys("title", "schedule", "location", "description")
        return CalendarToolRequest.UpdateEvent(
            purpose = purpose,
            eventHandle = arguments.requiredHandle("eventHandle", EVENT_HANDLE),
            changes = CalendarEventChanges(
                title = changes.optionalString("title", MAX_TITLE_CHARS),
                schedule = changes.optionalObject("schedule")?.let(::parseSchedule),
                location = changes.nullableChange("location", MAX_LOCATION_CHARS),
                description = changes.nullableChange("description", MAX_DESCRIPTION_CHARS),
            ),
        )
    }

    private fun parseSchedule(value: JsonObject): CalendarSchedule {
        val kind = value.requiredString("kind", 16)
        val zone = value.requiredZoneId("timeZone")
        return when (kind) {
            "timed" -> {
                value.requireExactKeys("kind", "start", "end", "timeZone")
                val start = value.requiredOffsetDateTime("start")
                val end = value.requiredOffsetDateTime("end")
                val duration = Duration.between(start.toInstant(), end.toInstant())
                if (duration.isZero || duration.isNegative || duration > MAX_EVENT_DURATION) invalid()
                if (!zone.matches(start) || !zone.matches(end)) invalid()
                CalendarSchedule.Timed(start, end, zone)
            }
            "all_day" -> {
                value.requireExactKeys("kind", "startDate", "endDateExclusive", "timeZone")
                val start = value.requiredDate("startDate")
                val end = value.requiredDate("endDateExclusive")
                if (!end.isAfter(start) || end.toEpochDay() - start.toEpochDay() > 366) invalid()
                CalendarSchedule.AllDay(start, end, zone)
            }
            else -> invalid()
        }
    }

    private fun JsonObject.requiredString(name: String, maximum: Int): String {
        val primitive = this[name] as? JsonPrimitive ?: invalid()
        if (!primitive.isString) invalid()
        return primitive.content.trim().takeIf { it.isNotEmpty() && it.length <= maximum }
            ?: invalid()
    }

    private fun JsonObject.optionalString(name: String, maximum: Int): String? =
        if (name in this) requiredString(name, maximum) else null

    private fun JsonObject.nullableString(name: String, maximum: Int): String? {
        val value = this[name] ?: invalid()
        if (value === JsonNull) return null
        return requiredString(name, maximum)
    }

    private fun JsonObject.requiredObject(name: String): JsonObject =
        this[name] as? JsonObject ?: invalid()

    private fun JsonObject.optionalObject(name: String): JsonObject? =
        if (name in this) requiredObject(name) else null

    private fun JsonObject.requiredOffsetDateTime(name: String): OffsetDateTime {
        val value = requiredString(name, 35)
        if (!RFC3339_DATE_TIME.matches(value)) invalid()
        return try {
            OffsetDateTime.parse(value)
        } catch (_: DateTimeParseException) {
            invalid()
        }
    }

    private fun JsonObject.requiredDate(name: String): LocalDate = try {
        LocalDate.parse(requiredString(name, 10))
    } catch (_: DateTimeParseException) {
        invalid()
    }

    private fun JsonObject.requiredZoneId(name: String): ZoneId {
        val value = requiredString(name, MAX_TIME_ZONE_CHARS)
        if (value !in ZoneId.getAvailableZoneIds()) invalid()
        return runCatching { ZoneId.of(value) }.getOrElse { invalid() }
    }

    private fun ZoneId.matches(value: OffsetDateTime): Boolean {
        val expectedOffset: ZoneOffset = rules.getOffset(value.toInstant())
        return expectedOffset == value.offset
    }

    private fun JsonObject.requiredHandle(name: String, pattern: Regex): String =
        requiredString(name, 160).also {
            if (!pattern.matches(it)) invalid()
        }

    private fun JsonObject.nullableHandle(name: String, pattern: Regex): String? =
        nullableString(name, 160)?.also {
            if (!pattern.matches(it)) invalid()
        }

    private fun JsonObject.nullableChange(
        name: String,
        maximum: Int,
    ): CalendarNullableChange<String> {
        val value: JsonElement = this[name] ?: return CalendarNullableChange.Unchanged
        return if (value === JsonNull) {
            CalendarNullableChange.Clear
        } else {
            CalendarNullableChange.Set(requiredString(name, maximum))
        }
    }

    private fun JsonObject.requireExactKeys(vararg allowed: String) {
        if (keys != allowed.toSet()) invalid()
    }

    private fun JsonObject.requireAllowedKeys(vararg allowed: String) {
        if (!allowed.toSet().containsAll(keys)) invalid()
    }

    private fun invalid(): Nothing = throw CalendarToolArgumentsException()
}
