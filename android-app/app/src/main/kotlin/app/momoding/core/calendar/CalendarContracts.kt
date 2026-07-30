package app.momoding.core.calendar

import app.momoding.core.capabilities.CalendarCapabilityAccess
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId

enum class CalendarToolAction(
    val wireValue: String,
    val requiredAccess: CalendarCapabilityAccess,
    val isMutation: Boolean,
) {
    LIST_CALENDARS("list_calendars", CalendarCapabilityAccess.READ, false),
    LIST_EVENTS("list_events", CalendarCapabilityAccess.READ, false),
    GET_EVENT("get_event", CalendarCapabilityAccess.READ, false),
    CREATE_EVENT("create_event", CalendarCapabilityAccess.WRITE, true),
    UPDATE_EVENT("update_event", CalendarCapabilityAccess.WRITE, true),
    DELETE_EVENT("delete_event", CalendarCapabilityAccess.WRITE, true),
    ;

    companion object {
        fun fromWireValue(value: String): CalendarToolAction? =
            entries.firstOrNull { it.wireValue == value }
    }
}

sealed interface CalendarSchedule {
    val timeZone: ZoneId

    data class Timed(
        val start: OffsetDateTime,
        val end: OffsetDateTime,
        override val timeZone: ZoneId,
    ) : CalendarSchedule

    data class AllDay(
        val startDate: LocalDate,
        val endDateExclusive: LocalDate,
        override val timeZone: ZoneId,
    ) : CalendarSchedule
}

sealed interface CalendarToolRequest {
    val action: CalendarToolAction
    val purpose: String

    data class ListCalendars(
        override val purpose: String,
    ) : CalendarToolRequest {
        override val action = CalendarToolAction.LIST_CALENDARS
    }

    data class ListEvents(
        override val purpose: String,
        val start: OffsetDateTime,
        val end: OffsetDateTime,
        val calendarHandle: String?,
        val query: String?,
        val cursor: String?,
    ) : CalendarToolRequest {
        override val action = CalendarToolAction.LIST_EVENTS
    }

    data class GetEvent(
        override val purpose: String,
        val eventHandle: String,
    ) : CalendarToolRequest {
        override val action = CalendarToolAction.GET_EVENT
    }

    data class CreateEvent(
        override val purpose: String,
        val title: String,
        val schedule: CalendarSchedule,
        val location: String?,
        val description: String?,
        val calendarHandle: String?,
    ) : CalendarToolRequest {
        override val action = CalendarToolAction.CREATE_EVENT
    }

    data class UpdateEvent(
        override val purpose: String,
        val eventHandle: String,
        val changes: CalendarEventChanges,
    ) : CalendarToolRequest {
        override val action = CalendarToolAction.UPDATE_EVENT
    }

    data class DeleteEvent(
        override val purpose: String,
        val eventHandle: String,
    ) : CalendarToolRequest {
        override val action = CalendarToolAction.DELETE_EVENT
    }
}

data class CalendarEventChanges(
    val title: String? = null,
    val schedule: CalendarSchedule? = null,
    val location: CalendarNullableChange<String> = CalendarNullableChange.Unchanged,
    val description: CalendarNullableChange<String> = CalendarNullableChange.Unchanged,
) {
    init {
        require(
            title != null ||
                schedule != null ||
                location !is CalendarNullableChange.Unchanged ||
                description !is CalendarNullableChange.Unchanged,
        ) { "Calendar update has no changes" }
    }
}

sealed interface CalendarNullableChange<out T> {
    data object Unchanged : CalendarNullableChange<Nothing>
    data object Clear : CalendarNullableChange<Nothing>
    data class Set<T>(val value: T) : CalendarNullableChange<T>
}

class CalendarToolArgumentsException(
    message: String = "Calendar arguments are invalid",
) : IllegalArgumentException(message)
