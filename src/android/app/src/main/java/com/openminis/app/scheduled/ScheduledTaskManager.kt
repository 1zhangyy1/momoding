package com.openminis.app.scheduled

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.openminis.app.logging.AppLogger

/**
 * [T-android-scheduled-tasks-design] Schedules / cancels AlarmManager
 * entries for [ScheduledTask] rows held in [ScheduledTaskStore]. Pairs with
 * [ScheduledTaskAlarmReceiver] which fires on the trigger and hands the
 * task off to [ScheduledAgentRunner].
 *
 * Repeating tasks (DAILY / WEEKDAYS / CUSTOM) are NOT scheduled via
 * AlarmManager.setRepeating — that primitive is inexact on Android 19+
 * and Doze makes it worse. Instead the receiver re-schedules the next
 * occurrence after every fire, giving Doze-tolerant precision.
 */
class ScheduledTaskManager(private val context: Context) {

    private val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    private val store = ScheduledTaskStore(context)

    init { ensureNotificationChannel() }

    /** Read-only access to persistence for callers that only need data. */
    fun store(): ScheduledTaskStore = store

    fun list(): List<ScheduledTask> = store.all()
    fun get(taskId: String): ScheduledTask? = store.get(taskId)

    fun create(task: ScheduledTask): ScheduledTask {
        // [T-android-scheduled-triggers] A relative trigger counts from now.
        val armed = if ((task.usesAnchor || task.isCalendar) && task.anchorMs == null) {
            task.copy(anchorMs = System.currentTimeMillis())
        } else task
        store.upsert(armed)
        if (armed.enabled) registerAlarm(armed)
        if (armed.triggerKind == ScheduledTriggerKind.ON_COMPLETION) {
            ScheduledCompletionTriggers.ensureListening(context)
        }
        return armed
    }

    /**
     * Save an edited task.
     *
     * [T-android-scheduled-triggers] Bookkeeping the editor does not own is
     * carried over from the stored row: the editor rebuilds the task from its
     * fields, so saving used to reset the run history and fire count (and
     * would reset the new relative triggers' anchor and fire count). Only a
     * value the incoming task leaves empty is taken from the stored one.
     */
    fun update(task: ScheduledTask): ScheduledTask {
        cancelAlarm(task.id)
        val merged = store.update(task.id) { prev -> mergeBookkeeping(task, prev).let {
            if (it.isCalendar) it.copy(anchorMs = maxOf(System.currentTimeMillis(), it.anchorMs ?: 0)) else it
        } }
            ?: task.also { store.upsert(it) }
        if (merged.enabled) registerAlarm(merged)
        return merged
    }

    fun setEnabled(taskId: String, enabled: Boolean) {
        // [T-android-scheduled-triggers] Re-enabling a relative trigger starts
        // it over: "after 30m" counts from now again, and an interval gets its
        // full count back. A calendar task simply resumes its schedule.
        // The old immutable claim stays pending; these counters belong to the new schedule.
        val updated = store.update(taskId) { t -> if (enabled && t.usesAnchor) {
            t.copy(enabled = true, anchorMs = System.currentTimeMillis(), triggeredCount = 0)
        } else if (enabled && t.triggerKind == ScheduledTriggerKind.ON_COMPLETION) {
            t.copy(enabled = true, triggeredCount = 0)
        } else if (enabled && t.isCalendar) {
            t.copy(enabled = true, anchorMs = System.currentTimeMillis())
        } else {
            t.copy(enabled = enabled, pendingDispatch = if (enabled) t.pendingDispatch else null)
        } } ?: return
        if (enabled) registerAlarm(updated) else cancelAlarm(taskId)
    }

    /** Claim and keep the exact substituted input until its Room handoff commits. */
    fun claimCompletionFire(taskId: String, result: String?): PendingScheduledFire? =
        store.update(taskId) { t ->
            if (!t.enabled || t.triggerKind != ScheduledTriggerKind.ON_COMPLETION ||
                (t.triggeredCount ?: 0) >= 1 || t.pendingDispatch != null) null
            else t.copy(enabled = false, triggeredCount = 1,
                pendingDispatch = PendingScheduledFire.create(t.copy(prompt = ScheduledCompletionTriggers.substitute(t.prompt, result))))
        }?.pendingDispatch

    fun acknowledgeDispatch(taskId: String, dispatchId: String): Boolean {
        val accepted = store.update(taskId) { t ->
            if (t.pendingDispatch?.id != dispatchId) null else t.copy(pendingDispatch = null)
        } ?: return false
        // Scheduling may have been paused while Room could not accept this input.
        if (accepted.enabled) registerAlarm(accepted)
        return true
    }

    /**
     * Keep the matched notification with the cooldown claim, before starting any model work.
     * Another event cannot replace a claimed input that Room has not accepted yet.
     */
    fun claimNotificationFire(taskId: String, now: Long, posted: ScheduledNotificationTriggers.Posted): PendingScheduledFire? =
        store.update(taskId) { t ->
            if (!t.enabled || t.triggerKind != ScheduledTriggerKind.ON_NOTIFICATION || t.pendingDispatch != null) null
            else if (t.anchorMs != null && now - t.anchorMs < NOTIFICATION_COOLDOWN_MS) null
            else t.copy(anchorMs = now, triggeredCount = (t.triggeredCount ?: 0) + 1,
                pendingDispatch = PendingScheduledFire.create(t.copy(prompt = ScheduledNotificationTriggers.substitute(t.prompt, posted)), now))
        }?.pendingDispatch

    fun delete(taskId: String) {
        cancelAlarm(taskId)
        store.delete(taskId)
    }

    /**
     * Re-register every enabled task with AlarmManager. Called from
     * [com.openminis.app.offload.AlarmReceiver]'s BOOT_COMPLETED branch
     * so persisted tasks survive a device reboot.
     */
    fun rescheduleAll() {
        val tasks = store.all()
        if (tasks.isEmpty()) {
            AppLogger.info(TAG, "rescheduleAll: no tasks")
            return
        }
        var count = 0
        // [T-android-scheduled-triggers] On-completion tasks have no alarm; they
        // observe persisted task completion in this process.
        if (tasks.any { it.pendingDispatch != null || (it.enabled && it.triggerKind == ScheduledTriggerKind.ON_COMPLETION) }) {
            ScheduledCompletionTriggers.ensureListening(context)
        }
        for (t in tasks) {
            if (!t.enabled) continue
            registerAlarm(t)
            count++
        }
        AppLogger.info(TAG, "rescheduleAll: re-registered $count enabled task(s)")
    }

    /** Advance the schedule and save its exact input in one claim. Repeated delivery is harmless. */
    fun claimAlarmFire(taskId: String, now: Long = System.currentTimeMillis()): PendingScheduledFire? {
        val claimed = store.update(taskId) { t ->
            if (!t.enabled || t.pendingDispatch != null) return@update null
            val next = if (t.isCalendar) {
                val slot = t.nextTriggerMs(maxOf(t.createdAt, now - 8 * 86_400_000L)) ?: return@update null
                if (slot > now + ALARM_EARLY_SLACK_MS) return@update null
                t.copy(enabled = t.repeatMode != ScheduledRepeatMode.ONCE, anchorMs = maxOf(now, slot),
                    triggeredCount = (t.triggeredCount ?: 0) + 1)
            } else {
                if (!relativeAlarmDue(t, now)) return@update null
                afterAlarmFire(t, now)?.first ?: return@update null
            }
            next.copy(pendingDispatch = PendingScheduledFire.create(next, now))
        }
        // A duplicate may have replaced our PendingIntent. Restore the real next slot,
        // unless an unaccepted input deliberately holds this definition at its claim.
        if (claimed == null) store.get(taskId)?.let(::registerAlarm)
        return claimed?.pendingDispatch
    }

    /** Record the same fire once, including when completion is replayed after process death. */
    fun markFired(
        taskId: String, sessionId: String?, resultPreview: String?, ok: Boolean = true,
        firedAt: Long = System.currentTimeMillis(),
        scheduled: Boolean? = null, workId: String? = null, resultMessageId: String? = null, dispatchId: String? = null,
    ): Boolean {
        val now = firedAt
        val run = ScheduledRun(firedAt = now, sessionId = sessionId, preview = resultPreview, ok = ok,
            scheduled = scheduled, workId = workId, resultMessageId = resultMessageId, dispatchId = dispatchId)
        var added = false
        store.update(taskId) { task ->
            if (task.runHistory.any { it == run }) null
            // Older records outside the retained window cannot become "new" on a replay.
            else if (task.runHistory.size >= ScheduledTask.MAX_RUN_HISTORY && firedAt < task.runHistory.last().firedAt) null
            else {
                added = task.runHistory.none { sameFire(it, run) }
                recordRun(task, run)
            }
        }
        return added
    }

    private fun registerAlarm(task: ScheduledTask) {
        if (!task.enabled || task.pendingDispatch != null || (!task.usesAnchor && !task.isCalendar)) return
        val triggerAt = task.nextTriggerMs() ?: run {
            AppLogger.warning(TAG, "task ${task.id} has no next trigger — skipping register")
            return
        }
        val pi = buildPendingIntent(task.id)
        try {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            AppLogger.info(TAG, "registered task=${task.id} label=\"${task.label}\" triggerAt=$triggerAt")
        } catch (e: SecurityException) {
            // S+ users may have revoked SCHEDULE_EXACT_ALARM — fall back
            // to inexact so we still fire eventually rather than dropping.
            AppLogger.warning(
                TAG,
                "exact-alarm denied for task=${task.id} (${e.message}); falling back to inexact",
            )
            try {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            } catch (t2: Throwable) {
                AppLogger.error(TAG, "inexact fallback failed for task=${task.id}: ${t2.message}")
            }
        }
    }

    private fun cancelAlarm(taskId: String) {
        val pi = buildPendingIntent(taskId)
        alarmManager.cancel(pi)
        pi.cancel()
    }

    private fun buildPendingIntent(taskId: String): PendingIntent {
        val intent = Intent(context, ScheduledTaskAlarmReceiver::class.java).apply {
            action = ACTION_FIRE
            putExtra(EXTRA_TASK_ID, taskId)
        }
        val requestCode = taskId.hashCode() and 0x7FFFFFFF
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(context, requestCode, intent, flags)
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "Posts when a scheduled task finishes running."
        }
        nm.createNotificationChannel(channel)
    }

    companion object {
        private fun sameFire(a: ScheduledRun, b: ScheduledRun): Boolean =
            if (a.dispatchId != null && b.dispatchId != null) a.dispatchId == b.dispatchId
            else a.firedAt == b.firedAt && a.sessionId == b.sessionId

        internal fun recordRun(task: ScheduledTask, run: ScheduledRun): ScheduledTask {
            val same = task.runHistory.firstOrNull { sameFire(it, run) }
            if (same == run) return task
            val history = (task.runHistory.filterNot { it === same } + run).sortedByDescending { it.firedAt }.take(ScheduledTask.MAX_RUN_HISTORY)
            val latest = history.first()
            return task.copy(lastFiredAt = latest.firedAt, lastResultPreview = latest.preview,
                lastResultSessionId = latest.sessionId, runHistory = history, fireCount = task.firesSoFar + if (same == null) 1 else 0)
        }

        private const val TAG = "ScheduledTaskManager"

        /**
         * [T-android-scheduled-fire-claim] Tolerance for an alarm delivered a
         * moment before its slot (clock granularity). Exact alarms are not
         * delivered early, so anything well before the slot is a stale
         * registration, not the fire.
         */
        private const val ALARM_EARLY_SLACK_MS = 5_000L

        /**
         * [T-android-scheduled-fire-claim] Whether an alarm delivered at [now]
         * is [t]'s pending fire: the slot has come, and a --after has not
         * fired / an interval has count left. Pure, for tests. Calendar and
         * on-completion kinds are not decided here.
         */
        internal fun relativeAlarmDue(t: ScheduledTask, now: Long): Boolean {
            val anchor = t.anchorMs ?: t.createdAt
            return when (t.triggerKind) {
                ScheduledTriggerKind.AFTER ->
                    (t.triggeredCount ?: 0) < 1 && anchor + (t.delaySec ?: 0L) * 1000 <= now + ALARM_EARLY_SLACK_MS
                ScheduledTriggerKind.INTERVAL -> {
                    val iv = t.intervalSec ?: return false
                    (t.maxFires == null || (t.triggeredCount ?: 0) < t.maxFires) &&
                        anchor + iv * 1000 <= now + ALARM_EARLY_SLACK_MS
                }
                ScheduledTriggerKind.ON_COMPLETION, ScheduledTriggerKind.ON_NOTIFICATION, ScheduledTriggerKind.CALENDAR -> true
            }
        }

        /**
         * [T-android-scheduled-triggers] What a relative trigger's row becomes
         * when its alarm fires at [now], and whether to arm the next one. Pure,
         * for tests; null for kinds the alarm does not drive.
         *  - AFTER: fired once -> disabled (kept in the list, like ONCE).
         *  - INTERVAL: counted and re-anchored to [now]; the last of --count
         *    disables it instead of arming another.
         */
        internal fun afterAlarmFire(t: ScheduledTask, now: Long): Pair<ScheduledTask, Boolean>? = when (t.triggerKind) {
            ScheduledTriggerKind.AFTER -> t.copy(enabled = false, triggeredCount = 1) to false
            ScheduledTriggerKind.INTERVAL -> {
                val n = (t.triggeredCount ?: 0) + 1
                val next = t.copy(triggeredCount = n, anchorMs = now)
                if (t.maxFires != null && n >= t.maxFires) next.copy(enabled = false) to false else next to true
            }
            ScheduledTriggerKind.ON_COMPLETION, ScheduledTriggerKind.ON_NOTIFICATION, ScheduledTriggerKind.CALENDAR -> null
        }

        /**
         * [T-android-scheduled-triggers] [incoming] with the bookkeeping it
         * leaves empty taken from [prev]. Pure, for tests.
         */
        internal fun mergeBookkeeping(incoming: ScheduledTask, prev: ScheduledTask): ScheduledTask =
            incoming.copy(
                runHistory = incoming.runHistory.ifEmpty { prev.runHistory },
                fireCount = incoming.fireCount ?: prev.fireCount,
                anchorMs = incoming.anchorMs ?: prev.anchorMs,
                triggeredCount = incoming.triggeredCount ?: prev.triggeredCount,
                pendingDispatch = prev.pendingDispatch,
            )
        /** Momoding: an on-notification task runs at most once a minute. */
        const val NOTIFICATION_COOLDOWN_MS = 60_000L
        const val ACTION_FIRE = "com.openminis.app.scheduled.FIRE"
        const val EXTRA_TASK_ID = "task_id"
        const val CHANNEL_ID = "minis_scheduled_tasks"
        private const val CHANNEL_NAME = "Scheduled Tasks"
    }
}
