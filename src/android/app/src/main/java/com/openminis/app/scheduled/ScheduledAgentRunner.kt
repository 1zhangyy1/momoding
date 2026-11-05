package com.openminis.app.scheduled

import app.momoding.chat.InputContinuation

import app.momoding.chat.FireIf
import app.momoding.chat.NotifyDecision
import app.momoding.chat.RoutineAttention
import app.momoding.task.Inbox
import app.momoding.task.InboxEntity
import app.momoding.task.RoutineReceipt
import androidx.room.withTransaction
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

    /** Return only after the identified input (or helper task) has committed to Room. */
    suspend fun run(
        context: Context,
        task: ScheduledTask,
        waitForCompletion: Boolean = true,
        scheduledFire: Boolean = false,
        conditionChecked: Boolean = false,
    ): String? = runFire(context, task, waitForCompletion,
        RoutineFire(task.id, System.currentTimeMillis(), scheduledFire, java.util.UUID.randomUUID().toString()), conditionChecked)

    private suspend fun runFire(context: Context, task: ScheduledTask, wait: Boolean, fire: RoutineFire, conditionChecked: Boolean): String? {
        val app = context.applicationContext as? MinisApp ?: return null
        if (!app.subsystemsReady()) return null
        app.providerRepository.awaitConfigLoaded()
        TaskEngine.awaitRecovery()
        val condition = task.fireIf?.takeIf(String::isNotBlank)
        if (fire.scheduled && condition != null && !conditionChecked) {
            val checked: suspend () -> String? = {
                if (FireIf.holds(app, condition, task.targetMode.sessionIdOrNull)) runFire(app, task, wait, fire, true)
                else {
                    ScheduledTaskManager(app).markFired(task.id, task.targetMode.sessionIdOrNull,
                        app.getString(com.openminis.app.R.string.mo_routine_skipped), firedAt = fire.firedAt, scheduled = true, dispatchId = fire.dispatchId)
                    ScheduledCompletionTriggers.onScheduledRunFinished(app, task.id, null)
                    null
                }
            }
            if (wait) return checked()
            bgScope.launch { checked() }; return null
        }
        if (task.targetMode is ScheduledTargetMode.ChildOfCurrent) {
            val work = dispatchChild(app, task, task.targetMode.sessionId, fire) ?: return null
            if (wait) {
                val ended = TaskEngine.dao(app).taskFlow(work.id).filterNotNull().first { it.state == TaskEntity.State.ENDED.name }
                recordChildCompletion(app, ended)
            }
            return work.hand
        }
        val sid = runCatching { app.database.withTransaction {
            val dao = TaskEngine.dao(app)
            val id = requireNotNull(fire.dispatchId)
            dao.inbox(id)?.let { return@withTransaction it.toSession }
            dao.routineMessage(id)?.let { return@withTransaction it.sessionId }
            val session = resolveSessionId(app, task) ?: return@withTransaction null
            val (text, parts, prefill) = input(app, task, session, fire)
            Inbox.post(dao, session, text, sender = InboxEntity.Sender.ROUTINE, prefill = prefill, id = id)
            dao.inboxParts(id, RoutineReceipt.frame(parts, fire))
            task.thinkingLevel?.let { app.database.chatDao().updateThinkingOverride(session, it.name) }
            session
        } }.getOrElse {
            AppLogger.error(TAG, "task ${task.id} dispatch failed: ${it.message}"); null
        } ?: return null
        bgScope.launch { runCatching {
            if (task.thinkingLevel != null) withContext(Dispatchers.Main) {
                val vm = HeadlessChatRunner.viewModelFor(app, sid)
                kotlinx.coroutines.withTimeoutOrNull(5_000) { vm.activeEntryId.first { it != null } }
                vm.setThinkingLevel(task.thinkingLevel)
            }
            Inbox.wake(app, sid)
        }
            .onFailure { AppLogger.warning(TAG, "wake $sid: ${it.message}") } }
        if (wait) kotlinx.coroutines.withTimeoutOrNull(RUN_TIMEOUT_MS) {
            while (true) {
                val row = TaskEngine.dao(app).routineMessage(requireNotNull(fire.dispatchId))
                if (row != null && RoutineReceipt.result(row.partsJson) != null) {
                    recordMainCompletion(app, row); break
                }
                kotlinx.coroutines.delay(100)
            }
        }
        return sid
    }

    private data class Input(val text: String, val parts: String, val prefill: List<PrefilledToolCall>)
    /** RERUN repeats its saved input and attachments; it never rewinds history. */
    private suspend fun input(app: MinisApp, task: ScheduledTask, session: String, fire: RoutineFire): Input {
        val mode = task.targetMode
        val saved = if (mode is ScheduledTargetMode.RerunMessage) {
            val source = app.chatRepository.dao.loadMessages(session).firstOrNull { it.id == mode.messageId }
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
        val marker = ScheduledTaskMarker(task.id, task.label, task.nextTriggerMs(), words,
            prefilledTool = prefill.firstOrNull()?.toolName, firedAtMs = fire.firedAt)
        val parts = org.json.JSONArray()
        if (saved == null) parts.put(org.json.JSONObject().put("type", "text").put("value", marker.xml))
        else {
            parts.put(org.json.JSONObject().put("type", "text").put("value", marker.opening))
            for (i in 0 until saved.length()) {
                val part = saved.getJSONObject(i)
                parts.put(if (part.getString("type") == "text") org.json.JSONObject().put("type", "text").put("value", part.getString("value")) else part)
            }
            parts.put(org.json.JSONObject().put("type", "text").put("value", "\n</${ScheduledTaskMarker.TAG}>"))
        }
        return Input(marker.xml, parts.toString(), prefill)
    }

    private val deliveries = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.Job>()

    /** One pending payload bridges the preference claim and the Room transaction. Startup replays it. */
    fun deliverPending(context: Context, pending: PendingScheduledFire) {
        val job = bgScope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            val app = context.applicationContext as? MinisApp ?: return@launch
            if (!app.subsystemsReady()) return@launch
            app.providerRepository.awaitConfigLoaded(); TaskEngine.awaitRecovery()
            val manager = ScheduledTaskManager(app)
            if (manager.get(pending.task.id)?.pendingDispatch?.id != pending.id) return@launch
            val dao = TaskEngine.dao(app)
            if (dao.inbox(pending.id) != null || dao.routineMessage(pending.id) != null ||
                dao.routineTasks("\"dispatch\":" + org.json.JSONObject.quote(pending.id)).isNotEmpty()) {
                manager.acknowledgeDispatch(pending.task.id, pending.id)
                return@launch
            }
            val condition = pending.task.fireIf?.takeIf(String::isNotBlank)
            val holds = condition == null || FireIf.holds(app, condition, pending.task.targetMode.sessionIdOrNull)
            if (manager.get(pending.task.id)?.pendingDispatch?.id != pending.id) return@launch
            if (!holds) {
                manager.markFired(pending.task.id, pending.task.targetMode.sessionIdOrNull,
                    app.getString(com.openminis.app.R.string.mo_routine_skipped), firedAt = pending.at, scheduled = true, dispatchId = pending.id)
                manager.acknowledgeDispatch(pending.task.id, pending.id)
                ScheduledCompletionTriggers.onScheduledRunFinished(app, pending.task.id, null)
            } else if (runFire(app, pending.task, false, RoutineFire(pending.task.id, pending.at, true, pending.id), true) != null) {
                manager.acknowledgeDispatch(pending.task.id, pending.id)
            }
        }
        if (deliveries.putIfAbsent(pending.id, job) == null) {
            job.invokeOnCompletion { error ->
                deliveries.remove(pending.id, job)
                if (error != null) AppLogger.warning(TAG, "pending ${pending.id}: ${error.message}")
            }
            job.start()
        } else job.cancel()
    }

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
    private suspend fun dispatchChild(app: MinisApp, task: ScheduledTask, parentSid: String, fire: RoutineFire): TaskEntity? {
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
            prefilledTool = prefill.firstOrNull()?.toolName, firedAtMs = fire.firedAt)
        return runCatching {
            TaskEngine.openHelper(app, parentSid, task.label.ifBlank { task.prompt.take(40) }, marker.xml,
                prefill = prefill, routine = fire, thinkingLevel = task.thinkingLevel)
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
            val added = manager.markFired(routine.id, work.hand, decision.body,
                ok = work.outcome == TaskEntity.Outcome.DONE.name, firedAt = fire.firedAt, scheduled = fire.scheduled, workId = work.id, dispatchId = fire.dispatchId)
            if (decision.notify) postCompletionNotification(context, routine, work.conversation, decision.body)
            if (added && fire.scheduled) RoutineAttention.afterScheduledFire(context, routine, work.conversation, (context.applicationContext as MinisApp).isAppForeground())
        }
        TaskEngine.routineRecorded(context, work.id)
    }

    /** The completion receipt was committed with the reply, before its consumed inbox rows were removed. */
    suspend fun recordMainCompletion(context: Context, message: com.openminis.app.data.db.MessageEntity) = completionMutex.withLock {
        val dao = TaskEngine.dao(context)
        val row = dao.routineMessage(message.id) ?: return@withLock
        val fire = RoutineReceipt.fire(row) ?: return@withLock
        val receipt = RoutineReceipt.result(row.partsJson)?.takeIf { !it.recorded } ?: return@withLock
        val manager = ScheduledTaskManager(context)
        manager.get(fire.routineId)?.let { routine ->
            val text = receipt.response?.let { dao.routineMessage(it)?.partsJson }?.let(RoutineReceipt::text) ?: receipt.fallback
            val decision = NotifyDecision.decide(text, failed = !receipt.done)
            val added = manager.markFired(routine.id, row.sessionId, decision.body, receipt.done, fire.firedAt,
                scheduled = fire.scheduled, resultMessageId = receipt.response, dispatchId = fire.dispatchId)
            if (decision.notify) postCompletionNotification(context, routine, row.sessionId, decision.body)
            if (added && fire.scheduled) RoutineAttention.afterScheduledFire(context, routine, row.sessionId, (context.applicationContext as MinisApp).isAppForeground())
        }
        (context.applicationContext as MinisApp).database.withTransaction { RoutineReceipt.acknowledgeIn(dao, row.id, receipt.key) }
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
            .setOnlyAlertOnce(true)
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
