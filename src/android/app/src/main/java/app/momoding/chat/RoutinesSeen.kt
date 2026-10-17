package app.momoding.chat

import android.content.Context
import com.openminis.app.scheduled.ScheduledTaskManager

/** The dot on the header's routines icon: a routine ran since the person last opened the routines list. */
object RoutinesSeen {
    private const val PREFS = "momoding"
    private const val KEY = "routines.listSeenAt"

    fun markSeen(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong(KEY, System.currentTimeMillis()).apply()
    }

    fun hasNew(context: Context): Boolean {
        val seen = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY, 0L)
        return runCatching { ScheduledTaskManager(context).list().any { (it.lastFiredAt ?: 0L) > seen } }.getOrDefault(false)
    }
}
