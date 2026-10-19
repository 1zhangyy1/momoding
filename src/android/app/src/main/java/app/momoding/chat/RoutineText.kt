package app.momoding.chat

import android.content.Context
import com.openminis.app.R
import com.openminis.app.scheduled.ScheduledRepeatMode
import com.openminis.app.scheduled.ScheduledTask
import com.openminis.app.scheduled.ScheduledTriggerKind

/**
 * A routine in one line, as the person said it: "Every day around 8:00 · Last: nothing new in
 * your calendar" (Grok Bot writes times as "around"; the run lands within a minute or two).
 * A routine that paused itself because nobody read it says so instead of the last result.
 */
object RoutineText {
    fun summary(context: Context, task: ScheduledTask): String {
        val time = "%d:%02d".format(task.timeOfDayHour, task.timeOfDayMinute)
        val `when` = when (task.triggerKind) {
            ScheduledTriggerKind.INTERVAL -> context.getString(R.string.mo_routine_every, minutes(context, task.intervalSec ?: 0L))
            ScheduledTriggerKind.AFTER, ScheduledTriggerKind.ON_COMPLETION -> context.getString(R.string.mo_routine_once)
            ScheduledTriggerKind.CALENDAR -> when (task.repeatMode) {
                ScheduledRepeatMode.DAILY -> context.getString(R.string.mo_routine_daily, time)
                ScheduledRepeatMode.WEEKDAYS -> context.getString(R.string.mo_routine_weekdays, time)
                ScheduledRepeatMode.CUSTOM -> context.getString(R.string.mo_routine_custom, days(task.customDays, context.resources.configuration.locales[0]), time)
                ScheduledRepeatMode.ONCE -> context.getString(R.string.mo_routine_once_at, time)
            }
        }
        if (!task.enabled && RoutineAttention.isAutoPaused(context, task.id)) {
            return "$`when` · " + context.getString(R.string.mo_routine_auto_paused)
        }
        if (!task.enabled) return "$`when` · " + context.getString(R.string.mo_routine_paused)
        val next = task.nextTriggerMs()?.let { context.getString(R.string.mo_routine_next, dayAndTime(context, it)) }
        val last = task.runHistory.firstOrNull()
        val lastLine = last?.preview?.let { firstLine(NotifyDecision.visible(it)) }?.takeIf { it.isNotBlank() }
        val head = listOfNotNull(`when`, next).joinToString(" · ")
        return when {
            last == null -> head
            !last.ok -> head + "\n" + context.getString(R.string.mo_routine_last_failed)
            lastLine != null -> head + "\n" + context.getString(R.string.mo_routine_last, lastLine)
            else -> head
        }
    }

    /** "周五" / "周一、周三" from Calendar.DAY_OF_WEEK values, Monday first, in the app's language. */
    internal fun days(customDays: Set<Int>, locale: java.util.Locale = java.util.Locale.getDefault()): String {
        val names = java.text.DateFormatSymbols(locale).shortWeekdays
        val sep = if (locale.language == "zh") "、" else ", "
        return customDays.sortedBy { (it + 5) % 7 }.joinToString(sep) { names[it] }
    }

    /** "今天 8:00" / "明天 8:00" / "10月23日 8:00". */
    internal fun dayAndTime(context: Context, ms: Long, now: java.time.LocalDate = java.time.LocalDate.now()): String {
        val at = java.time.Instant.ofEpochMilli(ms).atZone(java.time.ZoneId.systemDefault())
        val time = "%d:%02d".format(at.hour, at.minute)
        return when (at.toLocalDate()) {
            now -> context.getString(R.string.mo_routine_today, time)
            now.plusDays(1) -> context.getString(R.string.mo_routine_tomorrow, time)
            else -> context.getString(R.string.mo_routine_on_day, at.monthValue, at.dayOfMonth, time)
        }
    }

    private fun minutes(context: Context, sec: Long): String =
        if (sec >= 3600 && sec % 3600 == 0L) context.getString(R.string.mo_routine_hours, sec / 3600)
        else context.getString(R.string.mo_routine_minutes, (sec / 60).coerceAtLeast(1))

    /** First meaningful line of a reply, markdown stripped, short. */
    internal fun firstLine(text: String, max: Int = 40): String {
        val line = text.lineSequence().map { it.trim().trimStart('#', '-', '*', '>', ' ').replace("**", "") }
            .firstOrNull { it.isNotEmpty() } ?: return ""
        return if (line.length <= max) line else line.take(max).trimEnd() + "…"
    }
}
