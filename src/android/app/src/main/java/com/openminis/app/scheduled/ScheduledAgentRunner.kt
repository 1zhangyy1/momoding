package com.openminis.app.scheduled

import app.momoding.chat.InputContinuation

import app.momoding.chat.FireIf
import app.momoding.chat.NotifyDecision
import app.momoding.chat.RoutineAttention
import app.momoding.chat.RoutineWakes // momoding (a local `app` shadows the package root here)
import app.momoding.apps.MoApps
import app.momoding.task.RoutineFire
import app.momoding.task.TaskEngine
import app.momoding.task.TaskEntity
import app.momoding.task.TaskLogEntity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import com.openminis.app.MinisApp
import com.openminis.app.debug.HeadlessChatRunner
import com.openminis.app.logging.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * [T-android-scheduled-tasks-design] Headless agent launch for scheduled
 * tasks. Mirrors the shape of iOS SendPromptIntent.perform():
 *
 *  - resolve a session id (NEW_SESSION → create row; APPEND_TO → reuse)
 *  - run the prompt through the existing agent loop (via [HeadlessChatRunner])
 *  - post a "completed" notification with a deep-link back to the session
 *  - hand the result preview back to [ScheduledTaskManager] for the row
 *
 * Concurrency: serialised per-session by [HeadlessChatRunner]'s VM cache —
 * receives for the same chat enter its persisted inbox in arrival order.
 * Helpers additionally share TaskEngine's three slots.
 */
object ScheduledAgentRunner {

    private const val TAG = "ScheduledAgentRunner"
    private const val RUN_TIMEOUT_MS = 10 * 60 * 1000L  // 10 min ceiling

    /**
     * App-scoped scope for fire-and-forget completion work when a caller asks
     * NOT to wait (the "Run now" button). Outlives the editor screen so the
     * agent loop + completion notification finish even after the user leaves.
     */
    private val bgScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val completionMutex = Mutex()

    /**
     * Fire a scheduled task.
     *
     * @param waitForCompletion when true, suspend until the agent loop
     *   finishes, then mark-fired + post the completion notification before
     *   returning. When false, return as soon as the session is resolved and
     *   the prompt is dispatched — the agent keeps running in the background
     *   and the completion (mark-fired + notification) is finished off an
     *   app-scoped coroutine. Mirrors iOS SendPromptIntent's waitForResult
     *   flag.
     *
     *   [GH#197] NEVER pass true from a BroadcastReceiver. Waiting here can
     *   take up to [RUN_TIMEOUT_MS] (10 min) and the wait lands on the main
     *   thread (HeadlessChatRunner.prompt/retry are
     *   `withContext(Dispatchers.Main)`), so a receiver that waits blows its
     *   ~10s broadcast budget and gets the whole process ANR-killed, along
     *   with every PRoot sandbox child. The alarm path passing the default
     *   `true` was exactly that bug. Waiting is only safe off a broadcast —
     *   e.g. the minis-scheduled CLI, which runs in its own offload thread.
     * @return the session id once the action has been DISPATCHED (resolved +
     *   prompt sent), or null when the runner couldn't even start (no provider,
     *   target chat gone, MinisApp not initialized).
     * @param scheduledFire true for a fire of the task's own schedule (its
     *   alarm, or its on-completion trigger); false for "Run now". Only a
     *   scheduled fire can finish the schedule and release the tasks waiting
     *   on it ([ScheduledCompletionTriggers.onScheduledRunFinished]).
     */
    suspend fun run(
        context: Context,
        task: ScheduledTask,
        waitForCompletion: Boolean = true,
        scheduledFire: Boolean = false,
        /** momoding: the task's --if was already checked for this fire. */
        conditionChecked: Boolean = false,
    ): String? {
        // [T-android-scheduled-lateinit-crash-156] `as? MinisApp` only rules out
        // a null / wrong-type Application — it does NOT mean the Application is
        // INITIALIZED, which is what the old comment here claimed. Every
        // `app.chatRepository` read below goes through a lateinit getter that
        // throws UninitializedPropertyAccessException when onCreate
        // early-returned (safe-mode) or aborted partway.
        //
        // GH#156: an alarm fires while the process is in that state and the app
        // crashes. A scheduled task is the worst case for this because the alarm
        // can START the process — Android creates the Application, onCreate
        // early-returns under safe-mode, and the receiver runs against a
        // permanently half-built app with no Activity anywhere in the picture
        // (so MainActivity's guard never gets a say).
        //
        // Skipping the run is the right degradation: the task stays scheduled
        // and its next occurrence was already armed by the receiver before this
        // call, so a skipped fire self-heals on the following launch.
        val app = context.applicationContext as? MinisApp ?: run {
            AppLogger.error(TAG, "Application is not MinisApp — skipping task ${task.id}")
            return null
        }
        if (!app.subsystemsReady()) {
            AppLogger.error(
                TAG,
                "MinisApp subsystems not initialized (safe-mode or failed init) — skipping task ${task.id}",
            )
            return null
        }

        // momoding: a routine's --if, checked before anything runs (manual "Run now" is not). It is a
        // model call, so a caller that does not wait (the alarm's few-second handoff) gets it on the
        // background scope; a fire whose condition does not hold is recorded as skipped, says nothing.
        val condition = task.fireIf?.takeIf { it.isNotBlank() }
        if (scheduledFire && condition != null && !conditionChecked) {
            val checked: suspend () -> String? = {
                if (FireIf.holds(app, condition, task.targetMode.sessionIdOrNull)) {
                    run(app, task, waitForCompletion, scheduledFire = true, conditionChecked = true)
                } else {
                    ScheduledTaskManager(app).markFired(task.id, task.targetMode.sessionIdOrNull, app.getString(com.openminis.app.R.string.mo_routine_skipped), ok = true)
                    ScheduledCompletionTriggers.onScheduledRunFinished(app, task.id, null)
                    null
                }
            }
            if (waitForCompletion) return checked()
            bgScope.launch { checked() }
            return null
        }

        // [T-scheduled-task-detail] One timestamp for this fire, written into
        // its envelope and its run record so the two can be matched.
        val firedAt = System.currentTimeMillis()
        if (task.targetMode is ScheduledTargetMode.ChildOfCurrent) {
            val work = dispatchChild(app, task, task.targetMode.sessionId, firedAt, scheduledFire) ?: return null
            if (waitForCompletion) {
                val ended = TaskEngine.dao(app).taskFlow(work.id).filterNotNull().first { it.state == TaskEntity.State.ENDED.name }
                recordChildCompletion(app, ended)
            }
            return work.hand
        }
        val sessionId = withContext(Dispatchers.IO) {
            resolveSessionId(app, task)
        } ?: return null

        AppLogger.info(
            TAG,
            "running task=${task.id} label=\"${task.label}\" " +
                "mode=${task.targetMode.encode()} session=$sessionId wait=$waitForCompletion",
        )

        if (waitForCompletion) {
            val result = dispatch(app, task, sessionId, wait = true, firedAt = firedAt, scheduledFire = scheduledFire)
            val ok = result.status != "Error" && result.status != "Timeout" && result.status != "Rejected"
            // momoding: the routine's reply says whether it is worth a notification (NotifyDecision)
            val decision = NotifyDecision.decide(result.responseText, failed = !ok)
            ScheduledTaskManager(app).markFired(task.id, sessionId, decision.body, ok = ok, firedAt = firedAt)
            if (decision.notify) postCompletionNotification(app, task, sessionId, decision.body)
            // momoding: a routine nobody reads pauses itself (replaces the notice above when it does)
            if (scheduledFire) RoutineAttention.afterScheduledFire(app, task, sessionId, app.isAppForeground())
            // [T-android-scheduled-triggers] Tasks waiting on this one run
            // once its whole schedule is over.
            if (scheduledFire) ScheduledCompletionTriggers.onScheduledRunFinished(app, task.id, result.responseText)
            return sessionId
        }

        // Fire-and-forget: the session is resolved and the FGS is up, so the
        // task HAS started. Run the actual dispatch + completion entirely off
        // the app scope (wait=true so we still mark-fired + notify when it
        // finishes), and return the session id immediately so the UI can show
        // "task started" without blocking on the agent loop. Leaving the editor
        // can't cancel it because bgScope outlives the screen.
        bgScope.launch {
            val result = dispatch(app, task, sessionId, wait = true, firedAt = firedAt, scheduledFire = scheduledFire)
            val ok = result.status != "Error" && result.status != "Timeout" && result.status != "Rejected"
            // momoding: the routine's reply says whether it is worth a notification (NotifyDecision)
            val decision = NotifyDecision.decide(result.responseText, failed = !ok)
            ScheduledTaskManager(app).markFired(task.id, sessionId, decision.body, ok = ok, firedAt = firedAt)
            if (decision.notify) postCompletionNotification(app, task, sessionId, decision.body)
            // momoding: a routine nobody reads pauses itself (replaces the notice above when it does)
            if (scheduledFire) RoutineAttention.afterScheduledFire(app, task, sessionId, app.isAppForeground())
            // [T-android-scheduled-triggers] Tasks waiting on this one run
            // once its whole schedule is over.
            if (scheduledFire) ScheduledCompletionTriggers.onScheduledRunFinished(app, task.id, result.responseText)
        }
        return sessionId
    }

    /** Every fire appends an identified routine input; saved-message schedules retain their source payload. */
    private suspend fun dispatch(
        app: MinisApp,
        task: ScheduledTask,
        sessionId: String,
        wait: Boolean,
        firedAt: Long,
        scheduledFire: Boolean,
    ): HeadlessChatRunner.PromptResult = runCatching {
        val mode = task.targetMode
        // RERUN is an existing stored target. Repeat its input, never rewind its conversation.
        val saved = if (mode is ScheduledTargetMode.RerunMessage) {
            val source = app.chatRepository.dao.loadMessages(sessionId).firstOrNull { it.id == mode.messageId }
            require(source?.role == "user") { "The saved message is no longer available." }
            require(InputContinuation.source(source.partsJson) == null) { "A continuation is not a saved input." }
            org.json.JSONArray(source.partsJson).also { parts ->
                require(parts.length() > 0 && (0 until parts.length()).all {
                    parts.getJSONObject(it).getString("type") in listOf("text", "mediaRef")
                }) { "Only a saved input can be repeated." }
                val base = com.openminis.app.data.storage.MediaStore(app).mediaBaseDir.canonicalFile
                for (i in 0 until parts.length()) {
                    val part = parts.getJSONObject(i)
                    if (part.getString("type") == "mediaRef") {
                        val file = java.io.File(base, part.getJSONObject("value").getString("relativePath")).canonicalFile
                        require(file.path.startsWith(base.path + java.io.File.separator) && file.isFile) { "A saved attachment is no longer available." }
                    }
                }
            }
        } else null
        val prefill = if (saved == null) listOfNotNull(task.prefillToolCall?.takeIf { it.validationError() == null }) else emptyList()
        val words = saved?.let { parts -> (0 until parts.length()).mapNotNull {
            parts.getJSONObject(it).takeIf { part -> part.getString("type") == "text" }?.getString("value")
        }.joinToString("") } ?: task.prompt
        val marker = ScheduledTaskMarker(
            taskId = task.id,
            label = task.label,
            nextFireAtMs = task.nextTriggerMs(),
            prompt = words,
            prefilledTool = prefill.firstOrNull()?.toolName,
            firedAtMs = firedAt,
        )
        val marked = marker.xml
        val partsJson = saved?.let { original ->
            val parts = org.json.JSONArray().put(org.json.JSONObject().put("type", "text").put("value", marker.opening))
            for (i in 0 until original.length()) {
                val part = original.getJSONObject(i)
                parts.put(if (part.getString("type") == "text") org.json.JSONObject().put("type", "text").put("value", part.getString("value")) else part)
            }
            parts.put(org.json.JSONObject().put("type", "text").put("value", "\n</${ScheduledTaskMarker.TAG}>"))
            parts.toString()
        }
        // momoding: the row this sends is the routine's, not the person's: the chat draws it as
        // a quiet line and the model is told so for this one turn (RoutineWakes)
        RoutineWakes.arm(app, sessionId, task.label)
        HeadlessChatRunner.prompt(
            context = app,
            sessionId = sessionId,
            text = marked,
            attachments = emptyList(),
            // [T-android-scheduled-task-thinking] Was hardcoded null, so a
            // task could not run with any reasoning effort. null still means
            // "inherit" inside HeadlessChatRunner.
            thinkingLevel = task.thinkingLevel,
            wait = wait,
            timeoutMs = RUN_TIMEOUT_MS,
            prefill = prefill,
            partsJson = partsJson,
        )
    }.getOrElse { t ->
        AppLogger.error(TAG, "task ${task.id} dispatch failed: ${t.message}")
        HeadlessChatRunner.PromptResult(
            status = "Error",
            responseText = "Error: ${t.message}",
            timedOut = false,
        )
    }.also { if (it.status == "Error") RoutineWakes.disarm(sessionId) } // momoding: a prompt that never went out leaves no mark

    private suspend fun resolveSessionId(app: MinisApp, task: ScheduledTask): String? {
        return when (val mode = task.targetMode) {
            is ScheduledTargetMode.AppendToSession -> {
                // Follow-up: the target session must still exist. If the user
                // deleted it, abort rather than silently spawning a new chat.
                if (app.chatRepository.getSession(mode.sessionId) == null) {
                    AppLogger.warning(TAG, "task ${task.id}: follow-up session ${mode.sessionId} gone — abort")
                    null
                } else {
                    mode.sessionId
                }
            }
            is ScheduledTargetMode.RerunMessage -> {
                if (app.chatRepository.getSession(mode.sessionId) == null) {
                    AppLogger.warning(TAG, "task ${task.id}: re-run session ${mode.sessionId} gone — abort")
                    null
                } else {
                    mode.sessionId
                }
            }
            is ScheduledTargetMode.ChildOfCurrent -> null // Its hand is created with the task, never by session resolution.
            ScheduledTargetMode.NewSession -> {
                // Resolution priority (mirrors the UI's normal-chat boot):
                //   1. task.modelBinding (user picked a group or specific entry
                //      on the task editor) → write it through verbatim so the
                //      new session lights up the same group/entry chip.
                //   2. legacy task.modelId (pre-binding, kept for backcompat) →
                //      pin to that entry, no binding.
                //   3. default primary group → write a group binding so the
                //      session boots through the user's default group.
                //   4. first visible entry → last-resort fallback.
                val explicitBinding = task.modelBinding
                val pinnedModelId = task.modelId
                val defaultGroupId =
                    if (explicitBinding == null && pinnedModelId == null) {
                        app.providerRepository.defaultPrimaryGroupId
                    } else null

                // Derive the seed modelId for the session row (must be valid
                // even before restoreFromBinding runs).
                val seedModelId: String = pinnedModelId
                    ?: run {
                        // Try to peek a member from whichever binding we'll
                        // write — explicit takes priority, then default group.
                        val groupIdForSeed: String? = explicitBinding
                            ?.let { parseGroupIdFromBinding(it) }
                            ?: defaultGroupId
                        val entryIdForSeed: String? = explicitBinding
                            ?.let { parseEntryIdFromBinding(it) }

                        // Entry binding → use that entry's model id.
                        entryIdForSeed
                            ?.let { eid -> app.providerRepository.config.value.modelEntries.firstOrNull { it.id == eid }?.model?.id }
                            ?: groupIdForSeed
                                ?.let { gid -> app.providerRepository.group(gid) }
                                // [T-android-group-resolve-skip-uncredentialed]
                                // Credential-aware filter — a scheduled run
                                // seeded with an uncredentialed member would
                                // fail unattended, with no user around to see
                                // the auth error.
                                ?.let { g -> app.providerRepository.availableMemberEntries(g).firstOrNull()?.model?.id }
                    }
                    ?: app.providerRepository.allVisibleEntries().firstOrNull()?.baseModel?.id
                    ?: run {
                        AppLogger.warning(TAG, "task ${task.id}: no provider — abort")
                        return null
                    }
                val title = task.label.ifBlank { "Scheduled task" }
                val memoryOn = com.openminis.app.data.MemoryGlobalPrefs.isGlobalEnabled(app)
                val session = app.chatRepository.createSession(
                    modelId = seedModelId,
                    title = title,
                    memoryEnabled = memoryOn,
                )
                app.chatRepository.dao.updateSource(session.id, "scheduled")

                // Write a model_binding when the task carried one OR when we
                // fell back to defaultPrimaryGroupId. Pinned modelId (no
                // binding) leaves modelBinding null so the chat layer treats
                // it as a hard-pinned entry, matching what the user picked.
                val bindingToWrite: String? = explicitBinding
                    ?: defaultGroupId?.let { """{"type":"group","groupId":"$it"}""" }
                if (bindingToWrite != null) {
                    app.chatRepository.updateSessionBinding(session.id, bindingToWrite, seedModelId)
                }
                session.id
            }
        }
    }

    /** A routine's helper is an ordinary persisted task hand, with the same model and slots. */
    private suspend fun dispatchChild(app: MinisApp, task: ScheduledTask, parentSid: String, firedAt: Long, scheduled: Boolean): TaskEntity? {
        if (!com.openminis.app.tools.AgentToolSwitch.AGENTS.isEnabled(app)) {
            AppLogger.warning(TAG, "task ${task.id}: agents are turned off — abort child-of-current")
            return null
        }
        val parent = app.chatRepository.getSession(parentSid) ?: return null
        if (parent.isChild || MoApps.ofSession(app, parentSid) != null) {
            AppLogger.warning(TAG, "task ${task.id}: only the main chat can start helpers")
            return null
        }
        val prefill = listOfNotNull(task.prefillToolCall?.takeIf { it.validationError() == null })
        val marker = ScheduledTaskMarker(task.id, task.label, task.nextTriggerMs(), task.prompt,
            prefilledTool = prefill.firstOrNull()?.toolName, firedAtMs = firedAt)
        return runCatching {
            TaskEngine.openHelper(app, parentSid, task.label.ifBlank { task.prompt.take(40) }, marker.xml,
                prefill = prefill, routine = RoutineFire(task.id, firedAt, scheduled), thinkingLevel = task.thinkingLevel)
        }.getOrElse { error ->
            AppLogger.error(TAG, "task ${task.id} helper handoff failed: ${error.message}")
            null
        }
    }

    /** Replayed from the task log at startup too. The record and acknowledgement are idempotent. */
    suspend fun recordChildCompletion(context: Context, work: TaskEntity) = completionMutex.withLock {
        val logs = TaskEngine.dao(context).log(work.id)
        if (logs.any { it.kind == TaskLogEntity.Kind.ROUTINE_RECORDED.name }) return@withLock
        val fire = logs.firstOrNull { it.kind == TaskLogEntity.Kind.ROUTINE.name }?.body?.let(RoutineFire::parse)
            ?: return@withLock
        val manager = ScheduledTaskManager(context)
        val routine = manager.get(fire.routineId)
        if (routine != null) {
            val text = logs.lastOrNull { it.kind == TaskLogEntity.Kind.END.name }?.body
            val decision = NotifyDecision.decide(text, failed = work.outcome != TaskEntity.Outcome.DONE.name)
            if (manager.markFired(routine.id, work.hand, decision.body, ok = work.outcome == TaskEntity.Outcome.DONE.name, firedAt = fire.firedAt)) {
                if (decision.notify) postCompletionNotification(context, routine, work.conversation, decision.body)
                if (fire.scheduled) RoutineAttention.afterScheduledFire(context, routine, work.conversation, (context.applicationContext as MinisApp).isAppForeground())
            }
            // The history may already have been committed before process death.
            // Claiming downstream schedules is itself idempotent, so replay this part.
            if (fire.scheduled) ScheduledCompletionTriggers.onScheduledRunFinished(context, routine.id, text)
        }
        TaskEngine.routineRecorded(context, work.id)
    }

    private fun parseGroupIdFromBinding(json: String): String? = runCatching {
        val o = org.json.JSONObject(json)
        if (o.optString("type") == "group") {
            o.optString("groupId").takeIf { it.isNotEmpty() }
        } else null
    }.getOrNull()

    private fun parseEntryIdFromBinding(json: String): String? = runCatching {
        val o = org.json.JSONObject(json)
        if (o.optString("type") == "entry") {
            o.optString("entryId").takeIf { it.isNotEmpty() }
        } else null
    }.getOrNull()

    private fun postCompletionNotification(
        context: Context,
        task: ScheduledTask,
        sessionId: String,
        preview: String,
    ) {
        val deepLink = Uri.parse("minis://session/$sessionId")
        // momoding: to MainActivity by name (the manifest registers `momoding`, not `minis`)
        val openIntent = app.momoding.deeplink.OwnScheme.intent(context, deepLink, flags = 0)
        val notificationId = task.id.hashCode() and 0x7FFFFFFF
        val contentPi = PendingIntent.getActivity(
            context,
            notificationId,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val title = task.label.ifBlank { context.getString(com.openminis.app.R.string.app_name) } // momoding: no "Minis:" prefix
        val notification = NotificationCompat.Builder(context, ScheduledTaskManager.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_recent_history)
            .setContentTitle(title)
            .setContentText(preview)
            .setStyle(NotificationCompat.BigTextStyle().bigText(preview))
            .setContentIntent(contentPi)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE)
                as android.app.NotificationManager
            nm.notify(notificationId, notification)
        } catch (e: SecurityException) {
            AppLogger.warning(TAG, "POST_NOTIFICATIONS denied — completion notice suppressed")
        }
    }
}
