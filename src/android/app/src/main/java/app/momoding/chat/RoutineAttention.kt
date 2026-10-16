package app.momoding.chat

import android.content.Context
import androidx.core.app.NotificationCompat
import com.openminis.app.R
import com.openminis.app.scheduled.ScheduledTask
import com.openminis.app.scheduled.ScheduledTaskManager

/**
 * A routine nobody reads pauses itself (Grok Bot: "paused to avoid wasting usage while nobody
 * looks at the results"). Every scheduled fire of a repeating routine counts one; opening the app
 * resets every count. Once a routine has fired [UNSEEN_LIMIT] times with the app never opened,
 * and the person has been away at least [AWAY_MS], it is disabled, the person gets one quiet
 * notification, and the next turn in its chat is told so the Bot brings it up and can resume it
 * (`minis-scheduled enable <id>`).
 */
object RoutineAttention {
    const val UNSEEN_LIMIT = 3
    const val AWAY_MS = 48L * 60 * 60 * 1000

    private const val PREFS = "momoding"
    private const val KEY_SEEN_AT = "routines.lastSeenAt"
    private const val KEY_UNSEEN = "routines.unseen."

    /** The app came to the foreground: the person can see their routines' results. */
    fun onSeen(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val edit = prefs.edit().putLong(KEY_SEEN_AT, System.currentTimeMillis())
        prefs.all.keys.filter { it.startsWith(KEY_UNSEEN) }.forEach { edit.remove(it) }
        edit.apply()
    }

    internal fun shouldPause(unseen: Int, lastSeenAt: Long, now: Long): Boolean =
        unseen >= UNSEEN_LIMIT && (lastSeenAt == 0L || now - lastSeenAt >= AWAY_MS)

    /** A scheduled (not manual) fire of [task] finished in [sessionId]. Returns true when it paused the task. */
    fun afterScheduledFire(context: Context, task: ScheduledTask, sessionId: String, isForeground: Boolean): Boolean {
        if (task.isOneShot) return false
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (isForeground) { onSeen(context); return false }
        val key = KEY_UNSEEN + task.id
        val unseen = prefs.getInt(key, 0) + 1
        prefs.edit().putInt(key, unseen).apply()
        if (prefs.getLong(KEY_SEEN_AT, 0L) == 0L) prefs.edit().putLong(KEY_SEEN_AT, System.currentTimeMillis()).apply()
        if (!shouldPause(unseen, prefs.getLong(KEY_SEEN_AT, 0L), System.currentTimeMillis())) return false

        ScheduledTaskManager(context).setEnabled(task.id, false)
        prefs.edit().remove(key).apply()
        val label = task.label.ifBlank { context.getString(R.string.app_name) }
        SessionAddenda.add(
            sessionId, "routine-paused-${task.id}",
            "## A routine was paused (Momoding)\nThe routine \"$label\" (id ${task.id}) fired $unseen times while the person never opened the app, " +
                "so the app paused it. Mention it in one line when they come back and offer to resume it (`minis-scheduled enable ${task.id}`) or drop it.",
            turns = 1,
        )
        runCatching {
            val n = NotificationCompat.Builder(context, ScheduledTaskManager.CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_media_pause)
                .setContentTitle(context.getString(R.string.mo_routine_paused_title, label))
                .setContentText(context.getString(R.string.mo_routine_paused_body, label, unseen))
                .setStyle(NotificationCompat.BigTextStyle().bigText(context.getString(R.string.mo_routine_paused_body, label, unseen)))
                .setContentIntent(
                    android.app.PendingIntent.getActivity(
                        context, task.id.hashCode(),
                        app.momoding.deeplink.OwnScheme.intent(context, android.net.Uri.parse("minis://session/$sessionId"), flags = 0),
                        android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
                    )
                )
                .setAutoCancel(true)
                .build()
            (context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager)
                .notify(task.id.hashCode() and 0x7FFFFFFF, n)
        }
        return true
    }
}
