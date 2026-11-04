package com.openminis.app.scheduled

import android.content.Context
import app.momoding.task.TaskLogEntity
import app.momoding.task.TaskEngine
import com.openminis.app.logging.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.combine
import java.util.concurrent.atomic.AtomicBoolean

/** Routine dependencies watch persisted task completion, including the initial snapshot after restart. */
object ScheduledCompletionTriggers {

    private const val TAG = "ScheduledCompletion"

    /** Cap on the upstream result pasted into `{{result}}`. */
    const val RESULT_CAP_CHARS = 8_000

    private val listening = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Observe task ends and routine definitions once the repositories are ready. */
    fun ensureListening(context: Context) {
        val app = context.applicationContext as? com.openminis.app.MinisApp ?: return
        // Alarm rescheduling also runs before the chat database is initialized.
        // MinisApp calls again once the repositories are ready.
        if (!app.subsystemsReady() || !listening.compareAndSet(false, true)) return
        scope.launch {
            combine(TaskEngine.dao(app).endedFlow(), ScheduledTaskStore(app).observe()) { ended, routines -> ended to routines }
                .collect { (ended, routines) ->
                    for (work in ended) runCatching {
                        ScheduledAgentRunner.recordChildCompletion(app, work)
                        if (dependents(routines, work.id).isNotEmpty()) {
                            val text = TaskEngine.dao(app).log(work.id).lastOrNull { it.kind == TaskLogEntity.Kind.END.name }?.body
                            onUpstreamFinished(app, work.id, text)
                        }
                    }.onFailure { AppLogger.warning(TAG, "completion ${work.id}: ${it.message}") }
                }
        }
    }

    /** Enabled on-completion tasks waiting for [upstreamId]. */
    internal fun dependents(tasks: List<ScheduledTask>, upstreamId: String): List<ScheduledTask> =
        tasks.filter {
            it.enabled && it.triggerKind == ScheduledTriggerKind.ON_COMPLETION && it.onCompletionOf == upstreamId
        }

    /**
     * [T-android-scheduled-oncompletion-finish] A scheduled task's run has
     * finished: release the tasks waiting on it only when its SCHEDULE is over
     * ([scheduleFinished]), not after every run.
     *
     * iOS posts `agentJobDidFinish` once per job: a once job after its fire, a
     * loop when its count runs out, a cron job never. Android fired dependents
     * after EVERY run, so `--of` a `--interval 10m --count 6` watch ran after
     * check 1 of 6 with check 1's result, and "Run now" released it too.
     */
    suspend fun onScheduledRunFinished(context: Context, taskId: String, result: String?) {
        val task = ScheduledTaskManager(context).get(taskId)
        if (!scheduleFinished(task, System.currentTimeMillis())) return
        onUpstreamFinished(context, taskId, result)
    }

    /**
     * [T-android-scheduled-oncompletion-finish] Whether [task]'s schedule has
     * no fire left, read after a scheduled run. Pure, for tests.
     *  - AFTER / ON_COMPLETION / a calendar ONCE: their one fire was this run.
     *  - INTERVAL: only with a --count, once it is used up (counted at the
     *    alarm, so a run finishing after the LAST fire sees 0 left).
     *  - Repeating calendar: only when an end date leaves no next slot.
     * A deleted task is not "finished": nothing waiting on it fires.
     */
    internal fun scheduleFinished(task: ScheduledTask?, now: Long): Boolean {
        if (task == null) return false
        return when (task.triggerKind) {
            ScheduledTriggerKind.AFTER, ScheduledTriggerKind.ON_COMPLETION -> true
            ScheduledTriggerKind.INTERVAL -> task.remainingFires == 0
            // Watches until deleted, like a daily task.
            ScheduledTriggerKind.ON_NOTIFICATION -> false
            ScheduledTriggerKind.CALENDAR ->
                task.repeatMode == ScheduledRepeatMode.ONCE ||
                    (task.endDateMs != null && task.copy(enabled = true).nextTriggerMs(now) == null)
        }
    }

    /** The prompt with `{{result}}` replaced, as iOS does; the result is capped. */
    internal fun substitute(prompt: String, result: String?): String =
        prompt.replace(ScheduledTask.RESULT_PLACEHOLDER, (result ?: "").take(RESULT_CAP_CHARS))

    /**
     * [upstreamId] finished with [result]: fire every task waiting for it. Each
     * is CLAIMED first (disabled, counted) so a repeated completion event or a
     * concurrent caller cannot fire it twice; that is also what makes it a
     * one-shot, like iOS finishing the job after its fire.
     */
    suspend fun onUpstreamFinished(context: Context, upstreamId: String, result: String?) {
        val manager = ScheduledTaskManager(context)
        val waiting = dependents(manager.list(), upstreamId)
        if (waiting.isEmpty()) return
        for (dep in waiting) {
            if (!manager.claimOneShotFire(dep.id)) continue
            AppLogger.info(TAG, "upstream ${upstreamId.take(8)} finished -> firing ${dep.id.take(8)} \"${dep.label}\"")
            ScheduledAgentRunner.run(
                context,
                dep.copy(prompt = substitute(dep.prompt, result)),
                waitForCompletion = false,
                // Its own fire, so a task waiting on IT is released in turn.
                scheduledFire = true,
            )
        }
    }
}
