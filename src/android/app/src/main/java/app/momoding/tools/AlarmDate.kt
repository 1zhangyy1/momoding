package app.momoding.tools

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId

/**
 * The system Clock app's alarms carry only a time of day; a one-off rings at the next HH:MM.
 * When asked for a one-off on a day that is not that next occurrence, say so instead of
 * setting the wrong day, and point at the calendar (which takes a date and can notify).
 */
object AlarmDate {
    fun refusal(timeArg: String, repeat: String?, now: LocalDateTime = LocalDateTime.now()): String? {
        if (!(repeat == null || repeat.equals("ONCE", ignoreCase = true))) return null
        val at = parse(timeArg.trim()) ?: return null
        val nextOccurrence = if (at.toLocalTime() > now.toLocalTime()) now.toLocalDate() else now.toLocalDate().plusDays(1)
        if (at.toLocalDate() == nextOccurrence) return null
        return "Clock alarms have no date: a one-off alarm rings at the next ${"%02d:%02d".format(at.hour, at.minute)} " +
            "($nextOccurrence), not on ${at.toLocalDate()}. For a reminder on that day use " +
            "`android-calendar create --title <T> --start ${at.toLocalDate()}T${"%02d:%02d".format(at.hour, at.minute)} --alarm 0` instead."
    }

    /** A link's time: epoch ms, or a local ISO date-time ("2026-10-19T15:00") the model can write without arithmetic. */
    fun epochMs(raw: String?): Long? {
        val s = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        s.toLongOrNull()?.let { return it }
        return parse(if (s.contains('T') || s.contains(' ')) s else "${s}T00:00")?.atZone(ZoneId.systemDefault())?.toInstant()?.toEpochMilli()
    }

    private fun parse(s: String): LocalDateTime? {
        if (!s.contains('T') && !Regex("""^\d{4}-\d{2}-\d{2} """).containsMatchIn(s)) return null
        val t = s.replace(' ', 'T')
        return runCatching { OffsetDateTime.parse(t).atZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime() }.getOrNull()
            ?: runCatching { LocalDateTime.parse(if (t.length == 16) "$t:00" else t) }.getOrNull()
            ?: runCatching { LocalDate.parse(t.substringBefore('T')).atStartOfDay() }.getOrNull()
    }
}
