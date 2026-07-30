package app.momoding.core.calendar

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.os.Bundle
import android.os.CancellationSignal
import android.provider.CalendarContract
import java.security.MessageDigest
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.FutureTask
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

class AndroidCalendarGateway(
    private val resolver: ContentResolver,
    private val uris: CalendarProviderUris = CalendarProviderUris.SYSTEM,
) : CalendarGateway {
    override suspend fun listCalendars(limit: Int): List<CalendarRecord> =
        withContext(Dispatchers.IO) {
            require(limit in 1..MAX_CALENDAR_QUERY_ITEMS)
            queryWithCancellation(
                uri = uris.calendars,
                projection = CALENDAR_PROJECTION,
                queryArgs = Bundle().apply {
                    putStringArray(
                        ContentResolver.QUERY_ARG_SORT_COLUMNS,
                        arrayOf(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME),
                    )
                    putInt(
                        ContentResolver.QUERY_ARG_SORT_DIRECTION,
                        ContentResolver.QUERY_SORT_DIRECTION_ASCENDING,
                    )
                    putInt(ContentResolver.QUERY_ARG_LIMIT, limit)
                },
            ) { cursor ->
                val id = cursor.column(CalendarContract.Calendars._ID)
                val displayName =
                    cursor.column(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME)
                val accountName = cursor.column(CalendarContract.Calendars.ACCOUNT_NAME)
                val accountType = cursor.column(CalendarContract.Calendars.ACCOUNT_TYPE)
                val accessLevel =
                    cursor.column(CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL)
                val primary = cursor.column(CalendarContract.Calendars.IS_PRIMARY)
                val timeZone = cursor.column(CalendarContract.Calendars.CALENDAR_TIME_ZONE)
                buildList {
                    while (cursor.moveToNext() && size < limit) {
                        add(
                            CalendarRecord(
                                id = cursor.getLong(id),
                                displayName = cursor.safeString(displayName, MAX_DISPLAY_NAME),
                                accountKey = accountKey(
                                    cursor.nullableString(accountName),
                                    cursor.nullableString(accountType),
                                ),
                                writable = cursor.getInt(accessLevel) >=
                                    CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR,
                                primary = cursor.nullableBoolean(primary),
                                timeZone = cursor.nullableString(timeZone)
                                    ?.takeIf(::validTimeZone),
                            ),
                        )
                    }
                }
            }
        }

    override suspend fun getCalendar(calendarId: Long): CalendarRecord? =
        withContext(Dispatchers.IO) {
            queryWithCancellation(
                uri = uris.calendars,
                projection = CALENDAR_PROJECTION,
                queryArgs = Bundle().apply {
                    putString(
                        ContentResolver.QUERY_ARG_SQL_SELECTION,
                        "${CalendarContract.Calendars._ID} = ?",
                    )
                    putStringArray(
                        ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS,
                        arrayOf(calendarId.toString()),
                    )
                    putInt(ContentResolver.QUERY_ARG_LIMIT, 1)
                },
            ) { cursor ->
                if (!cursor.moveToFirst()) {
                    null
                } else {
                    cursor.calendarRecord()
                }
            }
        }

    override suspend fun listEvents(
        startMillis: Long,
        endMillis: Long,
        calendarId: Long?,
        query: String?,
        offset: Int,
        limit: Int,
    ): CalendarEventPage = withContext(Dispatchers.IO) {
        require(startMillis < endMillis)
        require(offset in 0..MAX_CURSOR_OFFSET)
        require(limit in 1..MAX_EVENTS)
        val selection = buildList {
            calendarId?.let { add("${CalendarContract.Instances.CALENDAR_ID} = ?") }
            query?.let {
                add("${CalendarContract.Instances.TITLE} LIKE ? ESCAPE '\\'")
            }
        }.joinToString(" AND ").takeIf(String::isNotEmpty)
        val selectionArgs = buildList {
            calendarId?.let { add(it.toString()) }
            query?.let { add("%${escapeLike(it)}%") }
        }.toTypedArray()
        val uri = uris.instances.buildUpon()
            .appendPath(startMillis.toString())
            .appendPath(endMillis.toString())
            .build()
        val records = queryWithCancellation(
            uri = uri,
            projection = EVENT_PROJECTION,
            queryArgs = Bundle().apply {
                selection?.let {
                    putString(ContentResolver.QUERY_ARG_SQL_SELECTION, it)
                    putStringArray(
                        ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS,
                        selectionArgs,
                    )
                }
                putStringArray(
                    ContentResolver.QUERY_ARG_SORT_COLUMNS,
                    arrayOf(
                        CalendarContract.Instances.BEGIN,
                        CalendarContract.Instances.EVENT_ID,
                    ),
                )
                putInt(
                    ContentResolver.QUERY_ARG_SORT_DIRECTION,
                    ContentResolver.QUERY_SORT_DIRECTION_ASCENDING,
                )
                putInt(ContentResolver.QUERY_ARG_OFFSET, offset)
                putInt(ContentResolver.QUERY_ARG_LIMIT, limit + 1)
            },
        ) { cursor ->
            cursor.eventRecords(limit + 1, includeDetails = false)
        }
        CalendarEventPage(
            items = records.take(limit),
            hasMore = records.size > limit,
        )
    }

    override suspend fun getEvent(target: CalendarEventTarget): CalendarEventRecord? =
        withContext(Dispatchers.IO) {
            val queryEnd = target.instanceEndMillis.coerceAtLeast(
                target.instanceBeginMillis + 1,
            )
            val uri = uris.instances.buildUpon()
                .appendPath(target.instanceBeginMillis.toString())
                .appendPath(queryEnd.toString())
                .build()
            queryWithCancellation(
                uri = uri,
                projection = EVENT_PROJECTION_WITH_DETAILS,
                queryArgs = Bundle().apply {
                    putString(
                        ContentResolver.QUERY_ARG_SQL_SELECTION,
                        "${CalendarContract.Instances.EVENT_ID} = ? AND " +
                            "${CalendarContract.Instances.BEGIN} = ?",
                    )
                    putStringArray(
                        ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS,
                        arrayOf(
                            target.eventId.toString(),
                            target.instanceBeginMillis.toString(),
                        ),
                    )
                    putInt(ContentResolver.QUERY_ARG_LIMIT, 1)
                },
            ) { cursor ->
                cursor.eventRecords(1, includeDetails = true).firstOrNull()
            }
        }

    override suspend fun getEventById(eventId: Long): CalendarEventRecord? =
        withContext(Dispatchers.IO) {
            val raw = queryWithCancellation(
                uri = ContentUris.withAppendedId(uris.events, eventId),
                projection = RAW_EVENT_PROJECTION,
                queryArgs = Bundle().apply {
                    putInt(ContentResolver.QUERY_ARG_LIMIT, 1)
                },
            ) { cursor ->
                if (!cursor.moveToFirst()) null else cursor.rawEventRecord()
            } ?: return@withContext null
            val calendar = getCalendar(raw.calendarId) ?: return@withContext null
            CalendarEventRecord(
                target = CalendarEventTarget(
                    eventId = raw.eventId,
                    instanceBeginMillis = raw.startMillis,
                    instanceEndMillis = raw.endMillis,
                ),
                calendarId = calendar.id,
                calendarDisplayName = calendar.displayName,
                calendarWritable = calendar.writable,
                title = raw.title,
                startMillis = raw.startMillis,
                endMillis = raw.endMillis,
                allDay = raw.allDay,
                timeZone = raw.timeZone,
                location = raw.location,
                description = raw.description,
                recurring = raw.recurring,
            )
        }

    override suspend fun createEvent(values: CalendarEventWrite): Long =
        blockingMutation {
            val uri = resolver.insert(uris.events, values.toContentValues())
                ?: throw CalendarProviderUnavailable()
            runCatching { ContentUris.parseId(uri) }
                .getOrElse { throw CalendarProviderUnavailable() }
        }

    override suspend fun updateEvent(
        eventId: Long,
        values: CalendarEventWrite,
    ): Boolean = blockingMutation {
        resolver.update(
            ContentUris.withAppendedId(uris.events, eventId),
            values.toContentValues(),
            null,
            null,
        ) == 1
    }

    override suspend fun deleteEvent(eventId: Long): Boolean = blockingMutation {
        resolver.delete(
            ContentUris.withAppendedId(uris.events, eventId),
            null,
            null,
        ) == 1
    }

    private fun CalendarEventWrite.toContentValues() = ContentValues().apply {
        put(CalendarContract.Events.CALENDAR_ID, calendarId)
        put(CalendarContract.Events.TITLE, title)
        put(CalendarContract.Events.DTSTART, startMillis)
        put(CalendarContract.Events.DTEND, endMillis)
        put(CalendarContract.Events.ALL_DAY, if (allDay) 1 else 0)
        put(CalendarContract.Events.EVENT_TIMEZONE, timeZone)
        put(CalendarContract.Events.EVENT_LOCATION, location)
        put(CalendarContract.Events.DESCRIPTION, description)
    }

    /**
     * Calendar mutation APIs have no CancellationSignal overload. A bounded worker pool lets the
     * coroutine deadline return OUTCOME_UNKNOWN even if a broken Provider ignores interruption.
     * The durable dispatch fence prevents any automatic replay of that operation.
     */
    private suspend fun <T> blockingMutation(block: () -> T): T =
        suspendCancellableCoroutine { continuation ->
            val task = FutureTask {
                try {
                    val result = block()
                    if (continuation.isActive) {
                        continuation.resumeWith(Result.success(result))
                    }
                } catch (error: Throwable) {
                    if (continuation.isActive) {
                        continuation.resumeWith(Result.failure(error))
                    }
                }
            }
            try {
                MUTATION_EXECUTOR.execute(task)
            } catch (error: RejectedExecutionException) {
                continuation.resumeWith(Result.failure(error))
                return@suspendCancellableCoroutine
            }
            continuation.invokeOnCancellation {
                task.cancel(true)
                MUTATION_EXECUTOR.remove(task)
            }
        }

    private suspend fun <T> queryWithCancellation(
        uri: android.net.Uri,
        projection: Array<String>,
        queryArgs: Bundle,
        transform: (android.database.Cursor) -> T,
    ): T {
        val cancellation = CancellationSignal()
        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { cancellation.cancel() }
            try {
                val cursor = resolver.query(uri, projection, queryArgs, cancellation)
                    ?: throw CalendarProviderUnavailable()
                val result = cursor.use(transform)
                continuation.resumeWith(Result.success(result))
            } catch (error: Throwable) {
                if (continuation.isActive) {
                    continuation.resumeWith(Result.failure(error))
                }
            }
        }
    }

    private fun android.database.Cursor.eventRecords(
        maximum: Int,
        includeDetails: Boolean,
    ): List<CalendarEventRecord> {
        val eventId = column(CalendarContract.Instances.EVENT_ID)
        val calendarId = column(CalendarContract.Instances.CALENDAR_ID)
        val calendarName = column(CalendarContract.Instances.CALENDAR_DISPLAY_NAME)
        val calendarAccess = column(CalendarContract.Instances.CALENDAR_ACCESS_LEVEL)
        val title = column(CalendarContract.Instances.TITLE)
        val begin = column(CalendarContract.Instances.BEGIN)
        val end = column(CalendarContract.Instances.END)
        val allDay = column(CalendarContract.Instances.ALL_DAY)
        val timeZone = column(CalendarContract.Instances.EVENT_TIMEZONE)
        val rrule = column(CalendarContract.Instances.RRULE)
        val location = if (includeDetails) {
            column(CalendarContract.Instances.EVENT_LOCATION)
        } else {
            null
        }
        val description = if (includeDetails) {
            column(CalendarContract.Instances.DESCRIPTION)
        } else {
            null
        }
        return buildList {
            while (moveToNext() && size < maximum) {
                val startMillis = getLong(begin)
                val endMillis = getLong(end).coerceAtLeast(startMillis)
                add(
                    CalendarEventRecord(
                        target = CalendarEventTarget(
                            eventId = getLong(eventId),
                            instanceBeginMillis = startMillis,
                            instanceEndMillis = endMillis,
                        ),
                        calendarId = getLong(calendarId),
                        calendarDisplayName = safeString(
                            calendarName,
                            MAX_DISPLAY_NAME,
                        ),
                        calendarWritable = getInt(calendarAccess) >=
                            CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR,
                        title = safeString(title, MAX_TITLE),
                        startMillis = startMillis,
                        endMillis = endMillis,
                        allDay = getInt(allDay) != 0,
                        timeZone = nullableString(timeZone)?.takeIf(::validTimeZone),
                        location = location?.let {
                            nullableString(it)?.bounded(MAX_LOCATION)
                        },
                        description = description?.let {
                            nullableString(it)?.bounded(MAX_DESCRIPTION)
                        },
                        recurring = !nullableString(rrule).isNullOrBlank(),
                    ),
                )
            }
        }
    }

    private fun android.database.Cursor.calendarRecord(): CalendarRecord {
        val id = column(CalendarContract.Calendars._ID)
        val displayName = column(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME)
        val accountName = column(CalendarContract.Calendars.ACCOUNT_NAME)
        val accountType = column(CalendarContract.Calendars.ACCOUNT_TYPE)
        val accessLevel = column(CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL)
        val primary = column(CalendarContract.Calendars.IS_PRIMARY)
        val timeZone = column(CalendarContract.Calendars.CALENDAR_TIME_ZONE)
        return CalendarRecord(
            id = getLong(id),
            displayName = safeString(displayName, MAX_DISPLAY_NAME),
            accountKey = accountKey(
                nullableString(accountName),
                nullableString(accountType),
            ),
            writable = getInt(accessLevel) >=
                CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR,
            primary = nullableBoolean(primary),
            timeZone = nullableString(timeZone)?.takeIf(::validTimeZone),
        )
    }

    private fun android.database.Cursor.rawEventRecord(): RawEventRecord {
        val eventId = column(CalendarContract.Events._ID)
        val calendarId = column(CalendarContract.Events.CALENDAR_ID)
        val title = column(CalendarContract.Events.TITLE)
        val start = column(CalendarContract.Events.DTSTART)
        val end = column(CalendarContract.Events.DTEND)
        val allDay = column(CalendarContract.Events.ALL_DAY)
        val timeZone = column(CalendarContract.Events.EVENT_TIMEZONE)
        val rrule = column(CalendarContract.Events.RRULE)
        val location = column(CalendarContract.Events.EVENT_LOCATION)
        val description = column(CalendarContract.Events.DESCRIPTION)
        val startMillis = getLong(start)
        return RawEventRecord(
            eventId = getLong(eventId),
            calendarId = getLong(calendarId),
            title = safeString(title, MAX_TITLE),
            startMillis = startMillis,
            endMillis = getLong(end).coerceAtLeast(startMillis),
            allDay = getInt(allDay) != 0,
            timeZone = nullableString(timeZone)?.takeIf(::validTimeZone),
            location = nullableString(location)?.bounded(MAX_LOCATION),
            description = nullableString(description)?.bounded(MAX_DESCRIPTION),
            recurring = !nullableString(rrule).isNullOrBlank(),
        )
    }

    private fun android.database.Cursor.column(name: String): Int =
        getColumnIndexOrThrow(name)

    private fun android.database.Cursor.safeString(index: Int, maximum: Int): String =
        nullableString(index)?.bounded(maximum).orEmpty()

    private fun android.database.Cursor.nullableString(index: Int): String? =
        if (isNull(index)) null else getString(index)

    private fun android.database.Cursor.nullableBoolean(index: Int): Boolean? =
        if (isNull(index)) null else getInt(index) != 0

    private fun String.bounded(maximum: Int): String =
        trim().replace(CONTROL_CHARACTERS, " ").take(maximum)

    private fun accountKey(accountName: String?, accountType: String?): String {
        val value = "${accountType.orEmpty()}\u001f${accountName.orEmpty()}"
        return MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun escapeLike(value: String): String = value
        .replace("\\", "\\\\")
        .replace("%", "\\%")
        .replace("_", "\\_")

    private fun validTimeZone(value: String): Boolean =
        value.length <= MAX_TIME_ZONE && value in java.time.ZoneId.getAvailableZoneIds()

    private companion object {
        const val MAX_CALENDAR_QUERY_ITEMS = 21
        const val MAX_EVENTS = 10
        const val MAX_CURSOR_OFFSET = 10_000
        const val MAX_DISPLAY_NAME = 160
        const val MAX_TITLE = 200
        const val MAX_LOCATION = 256
        const val MAX_DESCRIPTION = 1_024
        const val MAX_TIME_ZONE = 64
        val MUTATION_EXECUTOR = ThreadPoolExecutor(
            2,
            2,
            0L,
            TimeUnit.MILLISECONDS,
            ArrayBlockingQueue(8),
            { runnable ->
                Thread(runnable, "momoding-calendar-provider-write").apply {
                    isDaemon = true
                }
            },
            ThreadPoolExecutor.AbortPolicy(),
        )
        val CONTROL_CHARACTERS = Regex("[\\p{Cc}\\p{Cf}]+")
        val CALENDAR_PROJECTION = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.ACCOUNT_NAME,
            CalendarContract.Calendars.ACCOUNT_TYPE,
            CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL,
            CalendarContract.Calendars.IS_PRIMARY,
            CalendarContract.Calendars.CALENDAR_TIME_ZONE,
        )
        val EVENT_PROJECTION = arrayOf(
            CalendarContract.Instances.EVENT_ID,
            CalendarContract.Instances.CALENDAR_ID,
            CalendarContract.Instances.CALENDAR_DISPLAY_NAME,
            CalendarContract.Instances.CALENDAR_ACCESS_LEVEL,
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.EVENT_TIMEZONE,
            CalendarContract.Instances.RRULE,
        )
        val EVENT_PROJECTION_WITH_DETAILS = EVENT_PROJECTION + arrayOf(
            CalendarContract.Instances.EVENT_LOCATION,
            CalendarContract.Instances.DESCRIPTION,
        )
        val RAW_EVENT_PROJECTION = arrayOf(
            CalendarContract.Events._ID,
            CalendarContract.Events.CALENDAR_ID,
            CalendarContract.Events.TITLE,
            CalendarContract.Events.DTSTART,
            CalendarContract.Events.DTEND,
            CalendarContract.Events.ALL_DAY,
            CalendarContract.Events.EVENT_TIMEZONE,
            CalendarContract.Events.RRULE,
            CalendarContract.Events.EVENT_LOCATION,
            CalendarContract.Events.DESCRIPTION,
        )
    }
}

private data class RawEventRecord(
    val eventId: Long,
    val calendarId: Long,
    val title: String,
    val startMillis: Long,
    val endMillis: Long,
    val allDay: Boolean,
    val timeZone: String?,
    val location: String?,
    val description: String?,
    val recurring: Boolean,
)

class CalendarProviderUnavailable : IllegalStateException()

data class CalendarProviderUris(
    val calendars: android.net.Uri,
    val instances: android.net.Uri,
    val events: android.net.Uri = CalendarContract.Events.CONTENT_URI,
) {
    companion object {
        val SYSTEM = CalendarProviderUris(
            calendars = CalendarContract.Calendars.CONTENT_URI,
            instances = CalendarContract.Instances.CONTENT_URI,
            events = CalendarContract.Events.CONTENT_URI,
        )
    }
}
