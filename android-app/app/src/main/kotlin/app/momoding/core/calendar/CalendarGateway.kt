package app.momoding.core.calendar

/**
 * Typed, Android-local Calendar boundary. Raw Provider identities never cross into Pi results.
 */
interface CalendarGateway {
    suspend fun listCalendars(limit: Int): List<CalendarRecord>
    suspend fun getCalendar(calendarId: Long): CalendarRecord? =
        listCalendars(21).firstOrNull { it.id == calendarId }

    suspend fun listEvents(
        startMillis: Long,
        endMillis: Long,
        calendarId: Long?,
        query: String?,
        offset: Int,
        limit: Int,
    ): CalendarEventPage

    suspend fun getEvent(target: CalendarEventTarget): CalendarEventRecord?
    suspend fun getEventById(eventId: Long): CalendarEventRecord? = null

    suspend fun createEvent(values: CalendarEventWrite): Long =
        throw CalendarProviderUnavailable()

    suspend fun updateEvent(eventId: Long, values: CalendarEventWrite): Boolean =
        throw CalendarProviderUnavailable()

    suspend fun deleteEvent(eventId: Long): Boolean =
        throw CalendarProviderUnavailable()
}

data class CalendarRecord(
    val id: Long,
    val displayName: String,
    val accountKey: String,
    val writable: Boolean,
    val primary: Boolean?,
    val timeZone: String?,
)

data class CalendarEventTarget(
    val eventId: Long,
    val instanceBeginMillis: Long,
    val instanceEndMillis: Long,
)

data class CalendarEventRecord(
    val target: CalendarEventTarget,
    val calendarId: Long,
    val calendarDisplayName: String,
    val calendarWritable: Boolean,
    val title: String,
    val startMillis: Long,
    val endMillis: Long,
    val allDay: Boolean,
    val timeZone: String?,
    val location: String?,
    val description: String?,
    val recurring: Boolean,
)

data class CalendarEventPage(
    val items: List<CalendarEventRecord>,
    val hasMore: Boolean,
)

data class CalendarEventWrite(
    val calendarId: Long,
    val title: String,
    val startMillis: Long,
    val endMillis: Long,
    val allDay: Boolean,
    val timeZone: String,
    val location: String?,
    val description: String?,
)
