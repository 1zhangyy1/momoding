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
                ScheduledRepeatMode.CUSTOM -> context.getString(R.string.mo_routine_custom, time)
                ScheduledRepeatMode.ONCE -> context.getString(R.string.mo_routine_once_at, time)
            }
        }
        if (!task.enabled && RoutineAttention.isAutoPaused(context, task.id)) {
            return "$`when` · " + context.getString(R.string.mo_routine_auto_paused)
        }
        val last = task.runHistory.firstOrNull()
        val lastLine = last?.preview?.let { firstLine(NotifyDecision.visible(it)) }?.takeIf { it.isNotBlank() }
        return when {
            last == null -> `when`
            !last.ok -> "$`when` · " + context.getString(R.string.mo_routine_last_failed)
            lastLine != null -> "$`when` · " + context.getString(R.string.mo_routine_last, lastLine)
            else -> `when`
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
