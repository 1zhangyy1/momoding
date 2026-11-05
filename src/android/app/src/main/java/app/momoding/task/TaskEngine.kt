package app.momoding.task

import android.content.Context
import androidx.room.withTransaction
import app.momoding.apps.AppBots
import app.momoding.apps.MoApps
import com.openminis.app.MinisApp
import com.openminis.app.logging.AppLogger
import com.openminis.app.ui.chat.ChatViewModelStore
import com.openminis.app.ui.chat.rendersAsUserBubble
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * Momoding (调研/M39, M41): the only code that changes [TaskEntity] rows. Each change is one
 * transaction; what it sets off (waking a hand, waking the conversation) runs after it commits.
 *
 * A hand holds one task at a time, so its session is enough to find the task it is on: a report
 * names no task, and more work for a busy hand waits in line ([TaskEntity.State.QUEUED]).
 */
object TaskEngine {
    private const val TAG = "TaskEngine"
    internal const val LAST_ALIVE = "work.last_alive_at"
    /** How a hand's report starts, in the conversation it goes to: `[task <id> · <hand> · <kind>] <words>`. */
    const val REPORT_CUE = "[task "

    enum class Report { PROGRESS, ASK, DONE, FAILED }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _active = MutableStateFlow<List<TaskEntity>>(emptyList())
    val active: StateFlow<List<TaskEntity>> = _active.asStateFlow()
    private val recovered = kotlinx.coroutines.CompletableDeferred<Unit>()

    /** Reconcile old effects before a new executor or a restored chat can use the database. */
    suspend fun awaitRecovery() = recovered.await()

    fun dao(context: Context): TaskDao = (context.applicationContext as MinisApp).database.taskDao()

    private val _parentsWithWork = MutableStateFlow<Set<String>>(emptySet())
    val parentsWithWork: StateFlow<Set<String>> = _parentsWithWork.asStateFlow()

    /** Keep the UI's view of persisted work current; a loop explicitly reports its end. */
    fun start(context: Context) {
        val app = context.applicationContext
        val prefs = app.getSharedPreferences("momoding", Context.MODE_PRIVATE)
        scope.launch { dao(app).activeFlow().collect {
            _active.value = it
            _parentsWithWork.value = it.map { task -> task.conversation }.toSet()
        } }
        scope.launch {
            val approvalHands: Set<String>
            val reads: List<ReadRecovery.Pending>
            try {
                change(app, recovering = true) { dao, _ ->
                    recoverClocksIn(dao, prefs.getLong(LAST_ALIVE, 0).takeIf { it > 0 })
                }
                reads = change(app, recovering = true) { dao, _ -> recoverCommandsIn(dao) }
                recoverActions(app)
                recoverScreens(app)
                approvalHands = recoverApprovals(app)
                val db = (app as MinisApp).database
                val legacy = prefs.getString(LegacyWork.KEY, null)
                val saved = db.withTransaction {
                    val interrupted = TaskTranscript.recoveryTurnsIn(db.chatDao(), legacy)
                    val saved = recoverLegacyIn(db.chatDao(), db.taskDao(), legacy, interrupted)
                    recoverOrphansIn(db.taskDao(), interrupted)
                    TaskTranscript.recoverIn(db.chatDao(), db.taskDao(), interrupted)
                    Inbox.recoverIn(db.taskDao(), ReadRecovery.deferredInputsIn(db.taskDao(), reads))
                    saved
                }
                if (!LegacyWork.acknowledge(prefs, legacy, saved)) AppLogger.warning(TAG, "could not acknowledge legacy work; the saved recovery is idempotent")
                recovered.complete(Unit)
            } catch (error: Throwable) {
                recovered.completeExceptionally(error)
                AppLogger.error(TAG, "startup recovery: ${error.message}")
                return@launch
            }
            reads.forEach { ShellWork.resume(app, it) }
            ScreenWork.start(app)
            Inbox.wakeAll(app)
            approvalHands.forEach { tellChat(app, it) }
            kotlinx.coroutines.delay(3000)
            dao(app).queuedHands().forEach { hand -> handIdle(app, hand) }
        }
        scope.launch {
            runCatching { awaitRecovery() }.getOrElse { return@launch }
            dao(app).runningClockFlow().distinctUntilChanged().collectLatest { running ->
                if (running) while (true) {
                    // One process-liveness timestamp, not a second task store or a log row per tick.
                    if (!prefs.edit().putLong(LAST_ALIVE, System.currentTimeMillis()).commit())
                        AppLogger.warning(TAG, "could not save the live clock checkpoint")
                    kotlinx.coroutines.delay(1_000)
                }
            }
        }
    }

    /** Freeze before any recovery writes; a second crash cannot charge the same interval twice. */
    internal suspend fun recoverClocksIn(dao: TaskDao, lastAliveAt: Long?, now: Long = System.currentTimeMillis()) {
        for (task in dao.unfinishedTasks()) {
            val history = dao.log(task.id)
            val mark = TaskClock.latest(history)
            if (mark?.kind == TaskLogEntity.Kind.PAUSED.name || (mark == null && task.state == TaskEntity.State.QUEUED.name)) continue
            dao.insertLog(TaskLogEntity(taskId = task.id, at = now, kind = TaskLogEntity.Kind.PAUSED.name,
                body = JSONObject().put("elapsed_ms", TaskClock.interrupted(task, history, lastAliveAt, now))
                    .put("reason", "app restarted").toString()))
        }
    }

    /** Retire only entries whose existing conversation can hold a durable explanation. */
    internal suspend fun recoverLegacyIn(chat: com.openminis.app.data.db.ChatDao, dao: TaskDao, raw: String?,
        interrupted: List<com.openminis.app.data.db.MessageEntity>,
    ): Set<String> {
        val saved = mutableSetOf<String>()
        for (entry in LegacyWork.entries(raw)) {
            if (dao.recovered(entry.key) != null) { saved += entry.session; continue }
            val session = chat.getSession(entry.session) ?: continue
            val owner = session.parentSessionId ?: session.id
            if (chat.getSession(owner) == null) continue
            val held = dao.onHand(session.id).let { tasks -> tasks.singleOrNull() ?: tasks.singleOrNull { it.conversation != it.hand } }
            val now = System.currentTimeMillis()
            val task = held ?: TaskEntity(java.util.UUID.nameUUIDFromBytes(entry.key.toByteArray()).toString(), owner,
                title = "Interrupted older activity", hand = session.id, state = TaskEntity.State.RUNNING.name,
                createdAt = now, updatedAt = now).also { dao.insertTask(it) }
            parkRecoveryIn(dao, task, entry.key,
                "An older app left this conversation active without a durable continuation. Its remaining work and effects are unverified. Check what happened before continuing.",
                interrupted, setOf(session.id), "Legacy work.running entry: " + entry.raw)
            saved += entry.session
        }
        return saved
    }

    /** A running row alone cannot reconstruct a lost model loop. Keep recorded sub-results intact. */
    internal suspend fun recoverOrphansIn(dao: TaskDao, interrupted: List<com.openminis.app.data.db.MessageEntity>) {
        for (task in dao.unfinishedTasks()) {
            if (task.state != TaskEntity.State.RUNNING.name || task.hand == TaskEntity.SCREEN || task.ask != null || task.hold != null || pendingWork(dao, task.id)) continue
            if (dao.inboxFor(task.hand).any { it.failedAt == null && (it.taskId == task.id || it.id == task.sourceMessageId) }) continue
            val attempt = dao.log(task.id).lastOrNull { it.kind in listOf("AUTHORIZED", "PASSED_ON", "END") }?.id ?: 0
            parkRecoveryIn(dao, task, "attempt:${task.id}:$attempt",
                "The app restarted with this task active, but its original continuation is missing. Recorded results remain available; check the remaining work before continuing.",
                interrupted, setOf(task.hand))
        }
    }

    /** Park before bypassing a broken reference. Nothing here supplies execution parameters or authorization. */
    internal suspend fun parkRecoveryIn(dao: TaskDao, task: TaskEntity, key: String, words: String,
        interrupted: List<com.openminis.app.data.db.MessageEntity>, sessions: Set<String>, evidence: String? = null,
    ) {
        if (dao.recovered(key) != null) return
        log(dao, task.id, TaskLogEntity.Kind.RECOVERY, key)
        if (task.state != TaskEntity.State.ENDED.name) {
            for (call in interrupted.filter { it.sessionId in sessions })
                log(dao, task.id, TaskLogEntity.Kind.RECOVERY, "call:" + call.id)
            val consumed = dao.interruptedInbox().filter { it.toSession in sessions }
            consumed.forEach { log(dao, task.id, TaskLogEntity.Kind.RECOVERY, "input:" + it.id) }
            // The transcript remains. Only the interrupted receive is retired; new unread input is independent.
            dao.deleteInbox(consumed.map { it.id })
            if (task.ask == null) actionUnknownIn(dao, requireNotNull(dao.task(task.id)), words)
            val parked = requireNotNull(dao.task(task.id))
            clock(dao, parked, false, "recovery parked")
            dao.updateTask(parked.copy(state = TaskEntity.State.WAITING.name))
        }
        log(dao, task.id, TaskLogEntity.Kind.REPORT, words + evidence?.let { "\n$it" }.orEmpty())
        if (task.state == TaskEntity.State.ENDED.name) return
        val target = TaskReceipt.target(dao, task) ?: return
        Inbox.post(dao, target.conversation, "$REPORT_CUE${target.task} · recovery · ask] $words", target.task)
    }

    /** The task the person's words started: a new one for [hand], or in line behind the one it is on. */
    suspend fun open(context: Context, conversation: String, title: String, hand: String, brief: String,
        prefill: List<com.openminis.app.scheduled.PrefilledToolCall> = emptyList(), routine: RoutineFire? = null,
        call: ToolCall? = null,
    ): TaskEntity {
        val helper = Helpers.isHelper(context, hand)
        val invocation = call ?: kotlinx.coroutines.currentCoroutineContext()[ToolCall]
        return change(context) { dao, after ->
            val source = if (routine != null) null else source(dao, conversation, invocation)
            openIn(context, dao, after, conversation, title, hand, brief, helper, source?.id, source?.words, prefill, routine, invocation)
        }
    }

    /** Creating the hidden chat and handing work to it are one committed operation. */
    suspend fun openHelper(context: Context, conversation: String, title: String, brief: String, call: ToolCall? = null,
        prefill: List<com.openminis.app.scheduled.PrefilledToolCall> = emptyList(), routine: RoutineFire? = null,
        thinkingLevel: com.openminis.app.data.model.ThinkingLevel? = null,
    ): TaskEntity {
        val invocation = call ?: kotlinx.coroutines.currentCoroutineContext()[ToolCall]
        return change(context) { dao, after ->
            openHelperIn(context, dao, after, conversation, title, brief, prefill, routine, invocation) {
                Helpers.create(context, conversation, title).also { hand ->
                    val chat = (context.applicationContext as MinisApp).database.chatDao()
                    if (routine != null) chat.updateSource(hand, "scheduled")
                    thinkingLevel?.let { chat.updateThinkingOverride(hand, it.name) }
                }
            }
        }
    }

    internal suspend fun openHelperIn(context: Context, dao: TaskDao, after: MutableList<suspend () -> Unit>,
        conversation: String, title: String, brief: String, prefill: List<com.openminis.app.scheduled.PrefilledToolCall>,
        routine: RoutineFire?, call: ToolCall?, createHand: suspend () -> String,
    ): TaskEntity {
        call?.requireStoredIn(dao, conversation)
        val source = if (routine == null) source(dao, conversation, call) else null
        val hand = createHand()
        return openIn(context, dao, after, conversation, title, hand, brief, true, source?.id, source?.words, prefill, routine, call)
    }

    private data class Source(val id: String, val words: String?)

    /** The consumed receive owns the work, even when its bubble precedes newer history. */
    private suspend fun source(dao: TaskDao, conversation: String, call: ToolCall?): Source? {
        val input = if (call != null) dao.inputForCall(conversation, call.message) else dao.consumedInput(conversation)
        if (input != null) return Source(input.id, input.text.takeIf { input.sender == InboxEntity.Sender.USER.name })
        // An old invocation cannot borrow authorization from a newer visible message.
        if (call != null) return null
        return ChatViewModelStore.existing(conversation)?.messages?.value
            ?.lastOrNull { it.rendersAsUserBubble() && !it.unread }?.let { Source(it.id, it.content) }
    }

    private suspend fun openIn(context: Context, dao: TaskDao, after: MutableList<suspend () -> Unit>,
        conversation: String, title: String, hand: String, brief: String, helper: Boolean, sourceId: String?, authorized: String?,
        prefill: List<com.openminis.app.scheduled.PrefilledToolCall> = emptyList(), routine: RoutineFire? = null, call: ToolCall? = null,
    ): TaskEntity {
        call?.requireStoredIn(dao, conversation)
        TaskGuard.checkIn(dao, TaskGuard.Binding(conversation, null, call?.message), outward = true)?.let { throw TaskAction.NotStarted(it) }
        if (helper) check(dao.heldBy(hand) == null) { "a helper keeps one task; use tell to add instructions" }
        val busy = !free(dao, hand, helper)
        val now = System.currentTimeMillis()
        val task = TaskEntity(
            id = newId(), conversation = conversation, sourceMessageId = sourceId, title = title.trim(), hand = hand,
            state = (if (busy) TaskEntity.State.QUEUED else TaskEntity.State.RUNNING).name, createdAt = now, updatedAt = now,
        )
        dao.insertTask(task)
        authorized?.let { log(dao, task.id, TaskLogEntity.Kind.AUTHORIZED, it) }
        log(dao, task.id, TaskLogEntity.Kind.PASSED_ON, brief)
        handoffIn(dao, task, call)
        if (!busy) log(dao, task.id, TaskLogEntity.Kind.STARTED, "started")
        routine?.let { log(dao, task.id, TaskLogEntity.Kind.ROUTINE, it.json()) }
        // Each helper has its own chat: its queued payload can safely be stored now.
        // A busy Bot must not see another task's instructions until that task starts.
        if (helper || !busy) postForHand(context, dao, task, brief, prefill)
        if (!busy) after += { deliver(context, task) }
        return task
    }

    /**
     * More words for a task's hand. It reads them before its next step when it is at work; they
     * answer what it waits on; a task that is done or did not work picks up again with the same hand.
     */
    suspend fun tell(context: Context, id: String, text: String, call: ToolCall? = null): TaskEntity {
        val helper = dao(context).task(id)?.let { Helpers.isHelper(context, it.hand) } ?: false
        return change(context) { dao, after ->
            val owner = requireNotNull(dao.task(id)) { "no task $id" }.conversation
            call?.requireStoredIn(dao, owner)
            TaskGuard.checkIn(dao, TaskGuard.Binding(owner, null, call?.message), outward = true, targetTaskId = id)?.let { throw TaskAction.NotStarted(it) }
            tellIn(context, dao, after, id, text, helper).also { handoffIn(dao, it, call) }
        }
    }

    private suspend fun tellIn(context: Context, dao: TaskDao, after: MutableList<suspend () -> Unit>, id: String, text: String, helper: Boolean): TaskEntity {
        val task = dao.task(id) ?: throw IllegalArgumentException("no task $id (the table of tasks lists them)")
        if (task.outcome == TaskEntity.Outcome.STOPPED.name) throw IllegalStateException("task $id was stopped: open a new one")
        invalidateApprovals(dao, id)
        log(dao, id, TaskLogEntity.Kind.PASSED_ON, text)
        val waitingScreen = task.ask?.let { runCatching { JSONObject(it).optString("screen") }.getOrNull() }?.takeIf { it.isNotBlank() }
        if (waitingScreen != null && dao.children(id).any { it.id == waitingScreen && it.state == TaskEntity.State.WAITING.name }) {
            tellIn(context, dao, after, waitingScreen, text, helper = false)
            return dao.task(id) ?: task
        }
        if (task.hand == TaskEntity.SCREEN) {
            val live = ScreenWork.isRunning(id)
            clock(dao, task, live, "screen continued", reset = task.state == TaskEntity.State.ENDED.name)
            val next = task.copy(state = (if (live) TaskEntity.State.RUNNING else TaskEntity.State.QUEUED).name,
                ask = null, outcome = null, updatedAt = System.currentTimeMillis())
            dao.updateTask(next)
            screenParentReady(dao, id)
            after += { if (!ScreenWork.continueIfRunning(id)) ScreenWork.wake() }
            return next
        }
        val state = when (TaskEntity.State.valueOf(task.state)) {
            TaskEntity.State.QUEUED -> TaskEntity.State.QUEUED // read when it starts, with the brief
            TaskEntity.State.ENDED -> if (free(dao, task.hand, helper)) TaskEntity.State.RUNNING else TaskEntity.State.QUEUED
            else -> TaskEntity.State.RUNNING
        }
        val next = task.copy(state = state.name, ask = null, outcome = null, updatedAt = System.currentTimeMillis())
        if (task.state == TaskEntity.State.WAITING.name || task.state == TaskEntity.State.ENDED.name) {
            clock(dao, task, state == TaskEntity.State.RUNNING, "resumed", reset = task.state == TaskEntity.State.ENDED.name)
        }
        dao.updateTask(next)
        if (helper || state == TaskEntity.State.RUNNING) postForHand(context, dao, next, text)
        if (state == TaskEntity.State.RUNNING) {
            if (task.state == TaskEntity.State.ENDED.name) dao.retryTaskInbox(task.id, task.hand)
            after += { deliver(context, next) }
        }
        return next
    }

    /** What [hand] says of the task it is on. Progress only changes the task's row; the rest wake its conversation. */
    suspend fun report(context: Context, hand: String, kind: Report, text: String): TaskEntity {
        val helper = Helpers.isHelper(context, hand)
        return change(context) { dao, after -> reportIn(context, dao, after, hand, kind, text, helper) }
    }

    private suspend fun reportIn(context: Context, dao: TaskDao, after: MutableList<suspend () -> Unit>, hand: String, kind: Report, text: String, helper: Boolean, taskId: String? = null): TaskEntity {
        val task = (if (taskId == null) dao.heldBy(hand) else dao.task(taskId)?.takeIf { it.hand == hand }) ?: throw IllegalStateException("you are on no task: just answer in your chat")
        if (kind == Report.DONE && pendingWork(dao, task.id)) {
            throw IllegalStateException("Work for this task is still running or waiting. Its result will arrive automatically; do not report done yet.")
        }
        if (kind == Report.DONE && dao.unread(hand).isNotEmpty()) {
            throw IllegalStateException("There are unread instructions for this hand. Read them at the next step before reporting done.")
        }
        val now = System.currentTimeMillis()
        val draft = when (kind) {
            Report.PROGRESS -> task.copy(progress = text.lineSequence().first().take(120))
            Report.ASK -> task.copy(state = TaskEntity.State.WAITING.name, ask =
                if (Approval.pending(task.ask).isNotEmpty()) JSONObject(task.ask!!).put("question", text).toString()
                else JSONObject().put("question", text).put("screen", task.ask?.let { runCatching { JSONObject(it).optString("screen") }.getOrNull() }?.takeIf { it.isNotBlank() }).toString())
            Report.DONE, Report.FAILED -> task.copy(
                state = TaskEntity.State.ENDED.name, ask = null,
                outcome = (if (kind == Report.DONE) TaskEntity.Outcome.DONE else TaskEntity.Outcome.FAILED).name,
            )
        }.copy(updatedAt = now)
        val next = if (kind == Report.ASK) setAskIn(dao, task, draft.ask)
            else draft.also { dao.updateTask(it) }
        if (kind == Report.FAILED) {
            for (child in dao.children(task.id)) stopTree(context, dao, after, child.id, "The task it belonged to ended.")
            ShellWork.pending(dao.log(task.id)).forEach { command -> after += { ShellWork.stop(command.process) } }
        }
        if (kind != Report.PROGRESS) {
            log(dao, task.id, if (next.state == TaskEntity.State.ENDED.name) TaskLogEntity.Kind.END else TaskLogEntity.Kind.REPORT, text)
            val words = "$REPORT_CUE${task.id} · ${handName(context, hand)} · ${kind.name.lowercase()}] $text"
            Inbox.post(dao, task.conversation, words, task.id)
            after += { tellChat(context, task.conversation) }
            if (next.state == TaskEntity.State.ENDED.name) {
                after += {
                    MoApps.ofSession(context, hand)?.let { AppBots.reported(context, it, task.conversation, text) }
                }
                // The hand may still be inside the report's shell call. Start its next task only
                // when the loop actually returns, so that old tools cannot act for a new task.
            }
        }
        return next
    }

    /** The conversation whose task [hand] is on, if any: where its questions to the person show. */
    fun conversationOf(hand: String): String? =
        _active.value.firstOrNull { it.hand == hand && it.state != TaskEntity.State.ENDED.name && it.state != TaskEntity.State.QUEUED.name }?.conversation

    /**
     * The main chat's table of tasks, at the end of every request: what is open, and what ended
     * lately (it can be picked up again with tell), so the model never has to look them up.
     */
    suspend fun table(context: Context, conversation: String): String? {
        val db = (context.applicationContext as MinisApp).database
        val (open, ended) = db.withTransaction { dao(context).openIn(conversation) to dao(context).endedIn(conversation) }
        if (open.isEmpty() && ended.isEmpty()) return null
        return buildString {
            append("## Tasks\nWork tracked in this chat. A message starting $REPORT_CUE<id> · <hand> · done|ask|failed|stopped] is that task's report, not the person: ")
            append("tell the person what came of it in your own words, in a line or two (with a link to open a Momoding's app when there is something to see); for an ask, ask the person. ")
            append("A command_result is a background tool result for the current task: use it to continue before reporting the task done. ")
            append("A late_result is a return from an earlier interrupted or ended operation: tell the person once, reconcile it with the stop snapshot, and do not restart that operation. A stop snapshot records checkpoints and returns, not independent delivery confirmation. ")
            append("Their answer, or anything more they want of a task, goes to its hand with `momoding-task tell <id> \"<their words>\"`.\n")
            (open + ended).forEach { t ->
                append("- ").append(t.id).append(" · ").append(if (t.hand == t.conversation) "this chat" else handName(context, t.hand)).append(" · ").append(t.title).append(" · ")
                append(t.outcome?.lowercase() ?: t.state.lowercase())
                t.progress?.takeIf { t.state == TaskEntity.State.RUNNING.name }?.let { append(" (").append(it).append(")") }
                t.ask?.let { runCatching { JSONObject(it).optString("question") }.getOrNull() }?.let { append(" — asks: ").append(it) }
                append("\n")
            }
        }.trimEnd()
    }

    data class DeletedMessages(val ids: Set<String>, val stopped: Int)

    /** The state, stop receipts and removal commit together; cancellation follows that commit. */
    suspend fun deleteMessagesFrom(context: Context, session: String, source: String, visibleIds: Set<String>): DeletedMessages = change(context) { dao, after ->
        deleteMessagesFromIn(context, (context.applicationContext as MinisApp).database.chatDao(), dao, after, session, source, visibleIds)
    }

    internal suspend fun deleteMessagesFromIn(context: Context, chat: com.openminis.app.data.db.ChatDao, dao: TaskDao,
        after: MutableList<suspend () -> Unit>, session: String, source: String, visibleIds: Set<String>,
    ): DeletedMessages {
        val rows = chat.loadMessages(session)
        val inbox = dao.inboxFor(session)
        val selected = visibleIds + source
        val original = rows.firstOrNull { it.id == source }
        if (original == null && inbox.none { it.id == source }) return DeletedMessages(emptySet(), 0)
        // An inbox-only failure may precede later persisted rows. Its displayed tail
        // supplies those identities; absence from transcript is never an ordering rule.
        val anchor = original ?: rows.firstOrNull { it.id in selected }
        val removedRows = if (anchor == null) emptyList() else rows.filter { it.sortOrder >= anchor.sortOrder }
        val rowIds = rows.map { it.id }.toSet()
        val ids = removedRows.map { it.id }.toMutableSet()
        val removedInbox = inbox.filter { it.id in ids || (it.id !in rowIds && it.id in selected) }
        ids += removedInbox.map { it.id }
        val handed = removedInbox.mapNotNull { it.taskId }.toSet()
        val candidates = dao.relatedTo(listOf(session)).filter { it.state != TaskEntity.State.ENDED.name }
        val roots = candidates.filter { task ->
            task.sourceMessageId?.substringBefore('#') in ids || task.id in handed || dao.log(task.id).any { row ->
                row.kind in listOf(TaskLogEntity.Kind.HANDOFF.name, TaskLogEntity.Kind.ABOUT_TO.name) &&
                    runCatching { JSONObject(row.body).optJSONObject("tool")?.optString("message") in ids }.getOrDefault(false)
            }
        }
        val stopped = descendants(dao, roots).count { it.state != TaskEntity.State.ENDED.name }
        for (task in roots) stopIn(context, dao, after, task.id, "Stopped because its source messages were deleted.")
        dao.deleteInbox(ids.toList())
        if (anchor != null) chat.deleteMessagesAfter(session, anchor.sortOrder)
        val last = (if (anchor == null) rows else rows.filter { it.sortOrder < anchor.sortOrder }).lastOrNull()
        chat.updateLastMessage(session, last?.let { com.openminis.app.data.repository.ChatRepository.extractTextPreview(it.partsJson) }, System.currentTimeMillis())
        return DeletedMessages(ids, stopped)
    }

    /** Existing inputs disappear with their task state; only the new stop receipts remain. */
    suspend fun clearMessages(context: Context, session: String): DeletedMessages = change(context) { dao, after ->
        clearMessagesIn(context, (context.applicationContext as MinisApp).database.chatDao(), dao, after, session)
    }

    internal suspend fun clearMessagesIn(context: Context, chat: com.openminis.app.data.db.ChatDao, dao: TaskDao,
        after: MutableList<suspend () -> Unit>, session: String,
    ): DeletedMessages {
        if (chat.getSession(session) == null) return DeletedMessages(emptySet(), 0)
        val ids = (chat.loadMessages(session).map { it.id } + dao.inboxFor(session).map { it.id }).toSet()
        val roots = dao.relatedTo(listOf(session)).filter { it.state != TaskEntity.State.ENDED.name }
        val stopped = descendants(dao, roots).count { it.state != TaskEntity.State.ENDED.name }
        dao.clearInbox(session)
        for (task in roots) stopIn(context, dao, after, task.id, "Stopped because the chat history was cleared.")
        chat.deleteMessages(session)
        chat.deleteCompactMarkers(session)
        chat.updateLastMessage(session, null, System.currentTimeMillis())
        return DeletedMessages(ids, stopped)
    }

    data class DeletedSessions(val ids: List<String>, val tasks: List<TaskEntity>, val stopped: Int)

    /** Rows disappear in the same commit as their tasks stop. A late receive/report cannot
     * recreate a deleted conversation: inbox, task and message writes all require that row. */
    suspend fun deleteSessionTree(context: Context, root: String): DeletedSessions = change(context) { dao, after ->
        val app = context.applicationContext as MinisApp
        val ids = app.chatRepository.sessionSubtreeIds(root)
        val tasks = descendants(dao, dao.relatedTo(ids))
        val unfinished = tasks.filter { it.state != TaskEntity.State.ENDED.name }
        val stoppingIds = unfinished.map { it.id }.toSet()
        for (task in unfinished) {
            ShellWork.pending(dao.log(task.id)).forEach { command -> after += { ShellWork.stop(command.process) } }
            if (task.hand == TaskEntity.SCREEN) after += { ScreenWork.stopAndJoin(task.id, "the conversation was deleted") }
            val words = "Stopped because the conversation or its hand was deleted."
            dao.updateTask(task.copy(state = TaskEntity.State.ENDED.name, outcome = TaskEntity.Outcome.STOPPED.name,
                ask = null, updatedAt = System.currentTimeMillis()))
            log(dao, task.id, TaskLogEntity.Kind.END, words)
            dao.deleteTaskInbox(task.id)
            val coveredByParent = task.hand == TaskEntity.SCREEN && screenSpec(context, task.id).parent in stoppingIds
            if (task.conversation !in ids && !coveredByParent) {
                Inbox.post(dao, task.conversation, "$REPORT_CUE${task.id} · ${handName(context, task.hand)} · stopped] $words", task.id)
                after += { tellChat(context, task.conversation) }
            }
        }
        unfinished.map { it.hand }.distinct().filter { it !in ids }.forEach { hand -> after += { handIdle(context, hand) } }
        // The FK cascades remove messages, inbox and tasks owned by these sessions.
        for (id in ids) app.database.chatDao().deleteSession(id)
        DeletedSessions(ids, tasks, unfinished.size)
    }

    suspend fun pendingWork(dao: TaskDao, id: String): Boolean {
        val history = dao.log(id)
        return TaskReceipt.pending(history, TaskLogEntity.Kind.COMMAND, TaskLogEntity.Kind.COMMAND_RESULT).isNotEmpty() || Approval.unclaimed(history).isNotEmpty() ||
            TaskAction.pending(history).isNotEmpty() || dao.task(id)?.ask != null ||
            dao.children(id).any { it.state != TaskEntity.State.ENDED.name }
    }

    suspend fun actionStarted(context: Context, spec: Approval.Spec, command: ShellWork.Command? = null,
        sourceId: String? = null, authorization: String? = null, draft: String? = null,
    ): TaskAction {
        awaitRecovery()
        val call = kotlinx.coroutines.currentCoroutineContext()[ToolCall]
        return try {
            change(context) { dao, _ ->
                val source = if (spec.mode == Approval.Mode.SHELL) source(dao, spec.request.sessionId, call) else null
                actionStartedIn(dao, spec, command, sourceId ?: source?.id, authorization ?: source?.words ?: spec.request.summary ?: spec.request.preview, draft, call)
            }
        } catch (e: TaskAction.NotStarted) {
            // Claiming approval may already have registered COMMAND. A later hold or
            // question can still prevent execution, so settle that known non-action.
            val id = spec.request.taskId
            if (command != null && id != null) kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                commandFinished(context, id, command,
                    com.openminis.app.tools.ToolExecutionResult("The action did not start: ${e.message}", false),
                    announce = false, onlyIfNotStarted = true)
            }
            throw e
        }
    }

    internal suspend fun actionStartedIn(dao: TaskDao, spec: Approval.Spec, approved: ShellWork.Command? = null,
        sourceId: String? = null, authorized: String = spec.request.preview, draft: String? = null, call: ToolCall? = null,
    ): TaskAction {
        val request = spec.request
        call?.requireStoredIn(dao, request.sessionId)
        if (draft != null) {
            require(!sourceId.isNullOrBlank()) { "A draft send needs its source card." }
            check(dao.draftTask(request.sessionId, sourceId, draftNeedle(draft)) == null) { "This draft already has a task. Inspect its result before sending again." }
        }
        require(spec.mode != Approval.Mode.SCREEN || request.taskId != null) { "An outward screen action needs its persisted screen task." }
        val id = request.taskId ?: request.id
        val task = dao.task(id) ?: run {
            check(request.taskId == null) { "The action's task was deleted." }
            val now = System.currentTimeMillis()
            TaskEntity(id, request.sessionId, sourceId, (request.summary ?: request.preview).lineSequence().first().take(60),
                request.sessionId, TaskEntity.State.RUNNING.name, createdAt = now, updatedAt = now).also {
                dao.insertTask(it)
                log(dao, id, TaskLogEntity.Kind.AUTHORIZED, authorized)
                log(dao, id, TaskLogEntity.Kind.PASSED_ON, request.preview)
            }
        }
        val hand = if (spec.mode == Approval.Mode.SCREEN) TaskEntity.SCREEN else request.sessionId
        TaskGuard.checkIn(dao, TaskGuard.Binding(hand, id, call?.message), outward = true,
            allowWaiting = Approval.pending(task.ask).isNotEmpty())?.let { throw TaskAction.NotStarted(it) }
        val history = dao.log(id)
        check(history.none { it.kind == TaskLogEntity.Kind.ABOUT_TO.name && JSONObject(it.body).getString("id") == request.id }) {
            "This action already started. Inspect its recorded result; do not execute it again."
        }
        if (approved != null) check(ShellWork.pending(history).any { it.id == approved.id }) {
            "This approved command has already settled. Request approval again before acting."
        }
        val command = if (spec.mode == Approval.Mode.SCREEN) null else approved ?: ShellWork.Command(request.id,
            request.sessionId + "~command~" + request.id, JSONObject(requireNotNull(spec.shellArgs)).getString("command"))
        if (command != null && history.none { it.kind == TaskLogEntity.Kind.COMMAND.name && ShellWork.Command.parse(it.body)?.id == command.id }) {
            log(dao, id, TaskLogEntity.Kind.COMMAND, command.json())
        }
        val owner = request.conversation ?: if (spec.mode == Approval.Mode.SCREEN) {
            dao.screenSpec(id)?.let { ScreenWork.Spec.parse(it).parent }?.let { dao.task(it)?.conversation }
        } else null
        log(dao, id, TaskLogEntity.Kind.ABOUT_TO, spec.copy(request = request.copy(taskId = id,
            conversation = owner ?: task.conversation)).json().apply { call?.let { put("tool", it.json()) } }.toString())
        if (draft != null) log(dao, id, TaskLogEntity.Kind.DRAFT, draft)
        refreshBudget(dao, id)
        return TaskAction(id, request.id, command)
    }

    suspend fun actionFinished(context: Context, action: TaskAction, output: String, success: Boolean, unknown: Boolean = false) {
        if (action.command != null) {
            commandFinished(context, action.taskId, action.command, com.openminis.app.tools.ToolExecutionResult(output, success),
                announce = false, unknown = unknown)
        } else change(context) { dao, after ->
            actionFinishedIn(dao, action, output, success, unknown)?.let { owner -> after += { tellChat(context, owner) } }
        }
    }

    internal suspend fun actionFinishedIn(dao: TaskDao, action: TaskAction, output: String, success: Boolean, unknown: Boolean = false): String? {
        val task = dao.task(action.taskId) ?: return null
        val history = dao.log(task.id)
        val previous = TaskReceipt.result(history, TaskLogEntity.Kind.RESULT, action.id)
        if (!actionResultIn(dao, task.id, action.id, output, success, unknown)) return null
        val late = task.state == TaskEntity.State.ENDED.name || previous?.optBoolean("unknown") == true ||
            TaskReceipt.belongsToEarlierAttempt(history, TaskLogEntity.Kind.ABOUT_TO, action.id)
        if (late && !unknown) return lateReceiptIn(dao, task, "action", action.id, output, success)
        if (unknown && !late) actionUnknownIn(dao, task, output)
        return null
    }

    internal suspend fun actionResultIn(dao: TaskDao, task: String, id: String, output: String, success: Boolean, unknown: Boolean = false): Boolean {
        val history = dao.log(task)
        if (TaskReceipt.entries(history, TaskLogEntity.Kind.ABOUT_TO).none { it.id == id }) return false
        val previous = TaskReceipt.result(history, TaskLogEntity.Kind.RESULT, id)
        if (previous != null && (!previous.optBoolean("unknown") || unknown)) return false
        val result = JSONObject().put("id", id).put("output", output)
        if (unknown) result.put("unknown", true) else result.put("success", success)
        log(dao, task, TaskLogEntity.Kind.RESULT, result.toString())
        return true
    }

    /** The result write and this delivery share the caller's transaction. Never reopen work. */
    internal suspend fun lateReceiptIn(dao: TaskDao, task: TaskEntity, kind: String, id: String, output: String, success: Boolean): String? {
        val target = TaskReceipt.target(dao, task) ?: return null
        val words = TaskReceipt.late(task, kind, id, output, success)
        log(dao, task.id, TaskLogEntity.Kind.REPORT, words)
        Inbox.post(dao, target.conversation, "$REPORT_CUE${target.task} · $kind · late_result] $words", target.task,
            id = "late:${task.id}:$kind:$id")
        return target.conversation
    }

    private suspend fun actionUnknownIn(dao: TaskDao, task: TaskEntity, words: String) {
        invalidateApprovals(dao, task.id)
        val ask = JSONObject().put("kind", "action_outcome_unknown").put("question", words)
            .put("screen", task.id.takeIf { task.hand == TaskEntity.SCREEN }).toString()
        setAskIn(dao, task, ask)
        if (task.hand == TaskEntity.SCREEN) dao.screenSpec(task.id)?.let { ScreenWork.Spec.parse(it).parent }
            ?.let { dao.task(it) }?.takeIf { it.state != TaskEntity.State.ENDED.name }?.let {
                invalidateApprovals(dao, it.id)
                setAskIn(dao, it, ask)
            }
    }

    internal suspend fun recoverActionsIn(dao: TaskDao, task: TaskEntity): String? {
        val pending = TaskAction.pending(dao.log(task.id))
        if (pending.isEmpty()) return null
        val words = "An outward action has no recorded result; its outcome is unknown. Verify the current external state before continuing; do not repeat it.\n" +
            pending.joinToString("\n") { it.value?.optString("preview").orEmpty().ifBlank { "Unrecorded action details (${it.id}); verify its effect." } }
        val commands = TaskReceipt.pending(dao.log(task.id), TaskLogEntity.Kind.COMMAND, TaskLogEntity.Kind.COMMAND_RESULT).map { it.id }.toSet()
        for (action in pending) {
            if (action.id in commands) log(dao, task.id, TaskLogEntity.Kind.COMMAND_RESULT,
                JSONObject().put("id", action.id).put("unknown", true).toString())
            actionResultIn(dao, task.id, action.id, words, false, unknown = true)
        }
        if (task.state != TaskEntity.State.ENDED.name) actionUnknownIn(dao, task, words)
        return words
    }

    private suspend fun recoverActions(context: Context) = change(context, recovering = true) { dao, _ ->
        for (task in dao.unfinishedTasks()) {
            val words = recoverActionsIn(dao, task) ?: continue
            val owner = dao.screenSpec(task.id)?.let { ScreenWork.Spec.parse(it).parent }?.let { dao.task(it)?.conversation } ?: task.conversation
            Inbox.post(dao, owner, "$REPORT_CUE${task.id} · action · ask] $words", task.id)
        }
    }

    /** Native commands retain the task of the process that launched them, even after its model turn. */
    suspend fun executionTask(context: Context, chat: String, process: String?): TaskEntity? =
        (context.applicationContext as MinisApp).database.withTransaction {
            val dao = dao(context)
            val command = process?.let { dao.byExecution(chat, "\"process\":" + JSONObject.quote(it)) }
            val model = if (process != null) ChatViewModelStore.existing(chat)?.activeTaskId?.let { dao.task(it) } else null
            (command ?: model)?.also { check(it.state != TaskEntity.State.ENDED.name) { "the task ended; this process may not start new work" } }
        }

    private suspend fun descendants(dao: TaskDao, roots: List<TaskEntity>): List<TaskEntity> {
        val found = linkedMapOf<String, TaskEntity>()
        suspend fun add(task: TaskEntity) {
            if (found.putIfAbsent(task.id, task) != null) return
            for (child in dao.children(task.id)) add(child)
        }
        roots.forEach { add(it) }
        return found.values.toList()
    }

    /** State commits first; cancellation reaches only these runs, never the next task on a Bot. */
    suspend fun stop(context: Context, id: String, reason: String = "Stopped by the person.", outcome: TaskEntity.Outcome = TaskEntity.Outcome.STOPPED): TaskEntity? =
        change(context) { dao, after -> stopIn(context, dao, after, id, reason, outcome) }

    internal suspend fun stopIn(context: Context, dao: TaskDao, after: MutableList<suspend () -> Unit>, id: String,
        reason: String = "Stopped by the person.", outcome: TaskEntity.Outcome = TaskEntity.Outcome.STOPPED,
    ): TaskEntity? {
        val root = dao.task(id) ?: return null
        stopTree(context, dao, after, id, reason)
        if (root.state != TaskEntity.State.ENDED.name) {
            if (outcome != TaskEntity.Outcome.STOPPED) dao.task(id)?.let { dao.updateTask(it.copy(outcome = outcome.name)) }
            val words = dao.log(id).last { it.kind == TaskLogEntity.Kind.END.name }.body
            Inbox.post(dao, root.conversation, "$REPORT_CUE$id · ${handName(context, root.hand)} · ${outcome.name.lowercase()}] $words", id)
            after += { tellChat(context, root.conversation) }
        }
        return dao.task(id)
    }

    private suspend fun stopTree(context: Context, dao: TaskDao, after: MutableList<suspend () -> Unit>, id: String, reason: String) {
        val root = dao.task(id) ?: return
        for (task in descendants(dao, listOf(root))) {
            if (task.state == TaskEntity.State.ENDED.name) continue
            val commands = ShellWork.pending(dao.log(task.id))
            val words = reason + "\n" + TaskReceipt.stopped(dao, task)
            dao.updateTask(task.copy(state = TaskEntity.State.ENDED.name, outcome = TaskEntity.Outcome.STOPPED.name,
                ask = null, updatedAt = System.currentTimeMillis()))
            log(dao, task.id, TaskLogEntity.Kind.END, words)
            dao.deleteTaskInbox(task.id)
            commands.forEach { command -> after += { ShellWork.stop(command.process) } }
            if (task.hand == TaskEntity.SCREEN) after += { ScreenWork.stop(task.id, reason) }
            else after += { kotlinx.coroutines.withContext(Dispatchers.Main) { ChatViewModelStore.existing(task.hand)?.cancelTaskIfRunning(task.id) } }
            after += { handIdle(context, task.hand) }
        }
    }

    suspend fun openScreen(context: Context, options: app.momoding.hands.HandsOperator.Options, parentId: String? = null,
        sourceId: String? = null, authorization: String? = null, draft: String? = null, call: ToolCall? = null,
    ): TaskEntity = change(context) { dao, after ->
        val chat = requireNotNull(options.sessionId) { "a screen task needs its conversation" }
        val invocation = call ?: kotlinx.coroutines.currentCoroutineContext()[ToolCall]
        invocation?.requireStoredIn(dao, chat)
        TaskGuard.checkIn(dao, TaskGuard.Binding(chat, null, invocation?.message), outward = true)?.let { throw TaskAction.NotStarted(it) }
        if (draft != null) {
            require(!sourceId.isNullOrBlank()) { "A draft send needs its source card." }
            dao.draftTask(chat, sourceId, draftNeedle(draft))?.let { return@change it }
        }
        val parent = parentId?.let { dao.task(it) ?: error("the parent task was deleted") }
        require(parent == null || parent.hand == chat) { "the parent task belongs to another hand" }
        check(parent == null || parent.state != TaskEntity.State.ENDED.name) { "the parent task ended" }
        val source = source(dao, chat, invocation)
        val origin = sourceId ?: if (parent?.conversation == chat) parent.sourceMessageId else source?.id
        val now = System.currentTimeMillis()
        val task = TaskEntity(newId(), chat, origin, options.task.lineSequence().first().take(60), TaskEntity.SCREEN,
            TaskEntity.State.QUEUED.name, createdAt = now, updatedAt = now)
        dao.insertTask(task)
        (authorization ?: source?.words)?.let { log(dao, task.id, TaskLogEntity.Kind.AUTHORIZED, it) }
        if (draft != null) log(dao, task.id, TaskLogEntity.Kind.DRAFT, draft)
        log(dao, task.id, TaskLogEntity.Kind.SCREEN, ScreenWork.Spec(options, parent?.id).json())
        log(dao, task.id, TaskLogEntity.Kind.PASSED_ON, options.task)
        handoffIn(dao, task, invocation)
        parent?.let { log(dao, it.id, TaskLogEntity.Kind.CHILD, task.id) }
        after += { ScreenWork.wake() }
        task
    }

    internal suspend fun acquireScreen(dao: TaskDao, claim: () -> Boolean): TaskEntity? {
        if (dao.heldBy(TaskEntity.SCREEN) != null) return null
        val task = dao.nextFor(TaskEntity.SCREEN) ?: return null
        if (!claim()) return null
        clock(dao, task, true, "screen lease acquired")
        val next = task.copy(state = TaskEntity.State.RUNNING.name, updatedAt = System.currentTimeMillis())
        dao.updateTask(next)
        dao.screenSpec(task.id)?.let { JSONObject(it).optString("parent").takeIf(String::isNotBlank) }
            ?.let { refreshBudget(dao, it) }
        return next
    }

    internal suspend fun acquireDiagnosticScreen(dao: TaskDao, claim: () -> Boolean): Boolean =
        dao.heldBy(TaskEntity.SCREEN) == null && dao.nextFor(TaskEntity.SCREEN) == null && claim()

    suspend fun claimDiagnosticScreen(context: Context): Boolean = change(context) { dao, _ ->
        acquireDiagnosticScreen(dao) { app.momoding.hands.Hands.claim() }
    }

    internal suspend fun takeScreen(context: Context): TaskEntity? {
        var claimed = false
        try {
            return change(context) { dao, _ -> acquireScreen(dao) { app.momoding.hands.Hands.claim().also { claimed = it } } }
        } catch (e: Throwable) {
            if (claimed) app.momoding.hands.Hands.setActive(false)
            throw e
        }
    }

    suspend fun screenSpec(context: Context, id: String): ScreenWork.Spec =
        ScreenWork.Spec.parse(requireNotNull(dao(context).screenSpec(id)) { "no screen task $id" })

    data class ScreenCheckpoint(val active: Boolean, val instructions: List<TaskLogEntity>)
    suspend fun screenCheckpoint(context: Context, id: String, after: Long): ScreenCheckpoint =
        (context.applicationContext as MinisApp).database.withTransaction {
            val dao = dao(context)
            ScreenCheckpoint(dao.task(id)?.state in listOf(TaskEntity.State.RUNNING.name, TaskEntity.State.WAITING.name),
                dao.log(id).filter { it.id > after && it.kind == TaskLogEntity.Kind.PASSED_ON.name })
        }

    suspend fun screenProgress(context: Context, id: String, progress: String) = change(context) { dao, _ ->
        dao.task(id)?.takeIf { it.state == TaskEntity.State.RUNNING.name }?.let { dao.updateTask(it.copy(progress = progress, updatedAt = System.currentTimeMillis())) }
    }

    suspend fun screenWaiting(context: Context, id: String, question: String) = change(context) { dao, _ ->
        screenWaitingIn(dao, id, question)
    }

    internal suspend fun screenWaitingIn(dao: TaskDao, id: String, question: String) {
        val task = dao.task(id)?.takeIf { it.state != TaskEntity.State.ENDED.name } ?: return
        val ask = JSONObject().put("kind", "screen_takeover").put("screen", id).put("question", question).toString()
        setAskIn(dao, task, ask)
        log(dao, id, TaskLogEntity.Kind.REPORT, question)
        dao.screenSpec(id)?.let { JSONObject(it).optString("parent").takeIf(String::isNotBlank) }?.let { parent -> dao.task(parent)?.takeIf { it.state != TaskEntity.State.ENDED.name }?.let {
            setAskIn(dao, it, ask)
        } }
    }

    private suspend fun screenParentReady(dao: TaskDao, id: String) {
        val spec = dao.log(id).firstOrNull { it.kind == TaskLogEntity.Kind.SCREEN.name }?.body?.let { ScreenWork.Spec.parse(it) } ?: return
        spec.parent?.let { parent -> dao.task(parent)?.takeIf {
            it.state in listOf(TaskEntity.State.RUNNING.name, TaskEntity.State.WAITING.name) &&
                it.ask?.let { ask -> JSONObject(ask).optString("screen") } == id
        }?.let {
            clock(dao, it, working(dao, it, ignoreModel = false), "screen continued")
            dao.updateTask(it.copy(state = TaskEntity.State.RUNNING.name, ask = null, updatedAt = System.currentTimeMillis()))
        } }
    }

    /** An old run's result cannot settle words that arrived after its last checkpoint. */
    internal suspend fun requeueScreenFollowUp(dao: TaskDao, id: String, through: Long?): Boolean {
        val task = dao.task(id) ?: return false
        if (through == null || task.state == TaskEntity.State.ENDED.name ||
            dao.log(id).none { it.kind == TaskLogEntity.Kind.PASSED_ON.name && it.id > through }) return false
        clock(dao, task, false, "screen queued")
        dao.updateTask(task.copy(state = TaskEntity.State.QUEUED.name, ask = null, outcome = null,
            progress = null, updatedAt = System.currentTimeMillis()))
        screenParentReady(dao, id)
        return true
    }

    suspend fun screenFinished(context: Context, id: String, result: app.momoding.hands.HandsOperator.Result) =
        change(context) { dao, after -> screenFinishedIn(context, dao, after, id, result) }

    internal suspend fun screenFinishedIn(context: Context, dao: TaskDao, after: MutableList<suspend () -> Unit>, id: String, result: app.momoding.hands.HandsOperator.Result) {
        var task = dao.task(id) ?: return
        val recorded = dao.log(id).any { it.kind == TaskLogEntity.Kind.SCREEN_RESULT.name &&
            runCatching { JSONObject(it.body).optString("run_id") == result.runId }.getOrDefault(false) }
        if (recorded && task.state == TaskEntity.State.ENDED.name) return
        if (task.state != TaskEntity.State.ENDED.name && TaskAction.pending(dao.log(id)).isNotEmpty() &&
            result.outcome !is app.momoding.hands.HandsOperator.Outcome.Stopped) {
            recoverActionsIn(dao, task)
            task = dao.task(id) ?: return
        }
        val uncertain = task.ask?.let { JSONObject(it).takeIf { a -> a.optString("kind") == "action_outcome_unknown" }?.optString("question") }
        val observed = if (uncertain != null && result.outcome !is app.momoding.hands.HandsOperator.Outcome.Stopped)
            result.copy(outcome = app.momoding.hands.HandsOperator.Outcome.NeedsUser(uncertain)) else result
        val body = ScreenWork.resultJson(observed).toString()
        if (!recorded) log(dao, task.id, TaskLogEntity.Kind.SCREEN_RESULT, body)
        if (task.state == TaskEntity.State.ENDED.name) {
            if (result.outcome !is app.momoding.hands.HandsOperator.Outcome.Stopped)
                lateReceiptIn(dao, task, "screen", result.runId, body, result.outcome is app.momoding.hands.HandsOperator.Outcome.Done)
                    ?.let { owner -> after += { tellChat(context, owner) } }
            return
        }
        if (requeueScreenFollowUp(dao, id, result.instructionsThrough)) {
            after += { ScreenWork.wake() }
            return
        }
        val question = (observed.outcome as? app.momoding.hands.HandsOperator.Outcome.NeedsUser)?.question
        val outcome = when (observed.outcome) {
            is app.momoding.hands.HandsOperator.Outcome.Done -> TaskEntity.Outcome.DONE
            is app.momoding.hands.HandsOperator.Outcome.Stopped -> TaskEntity.Outcome.STOPPED
            else -> TaskEntity.Outcome.FAILED
        }
        if (question != null) setAskIn(dao, task.copy(progress = null),
            JSONObject().put("kind", "screen_question").put("screen", id).put("question", question).toString())
        else dao.updateTask(task.copy(state = TaskEntity.State.ENDED.name, ask = null,
            outcome = outcome.name, progress = null, updatedAt = System.currentTimeMillis()))
        log(dao, id, if (question == null) TaskLogEntity.Kind.END else TaskLogEntity.Kind.REPORT, body)
        val parent = ScreenWork.Spec.parse(requireNotNull(dao.screenSpec(id))).parent
        if (parent != null && dao.task(parent)?.state !in listOf(TaskEntity.State.RUNNING.name, TaskEntity.State.WAITING.name)) return
        if (question != null && parent != null) dao.task(parent)?.let {
            setAskIn(dao, it, JSONObject().put("kind", "screen_question").put("screen", id).put("question", question).toString())
        }
        if (question == null) screenParentReady(dao, id)
        parent?.let { refreshBudget(dao, it) }
        val ownParent = parent?.let { dao.task(it) }?.takeIf { it.conversation == it.hand }
        val closed = if (question == null && ownParent != null) completeOwnIn(dao, ownParent.id, outcome, body) else null
        val reportedOutcome = closed?.outcome ?: outcome
        val receipt = ownParent?.id ?: id
        val words = closed?.words ?: body
        Inbox.post(dao, task.conversation, "$REPORT_CUE$receipt · screen · ${if (question != null) "ask" else reportedOutcome.name.lowercase()}] $words", receipt)
        after += { tellChat(context, task.conversation) }
    }

    internal data class Closeout(val outcome: TaskEntity.Outcome, val words: String)

    internal suspend fun closeoutIn(dao: TaskDao, id: String, outcome: TaskEntity.Outcome, words: String): Closeout {
        val history = dao.log(id)
        // A pause/resume is still the same attempt. Only an END separates an explicit retry.
        val current = history.drop(history.indexOfLast { it.kind == TaskLogEntity.Kind.END.name } + 1)
        val commands = current.filter { it.kind == TaskLogEntity.Kind.COMMAND_RESULT.name }
            .mapNotNull { runCatching { JSONObject(it.body) }.getOrNull() }
            .filter { !it.optBoolean("success", true) && !TaskReceipt.belongsToEarlierAttempt(history, TaskLogEntity.Kind.COMMAND, it.optString("id")) }
        val childIds = current.filter { it.kind == TaskLogEntity.Kind.CHILD.name }.map { it.body }.toSet()
        val children = dao.children(id).filter { it.id in childIds && it.outcome in listOf(TaskEntity.Outcome.FAILED.name, TaskEntity.Outcome.STOPPED.name) }
        val result = when {
            outcome == TaskEntity.Outcome.FAILED || commands.isNotEmpty() || children.any { it.outcome == TaskEntity.Outcome.FAILED.name } -> TaskEntity.Outcome.FAILED
            outcome == TaskEntity.Outcome.STOPPED || children.isNotEmpty() -> TaskEntity.Outcome.STOPPED
            else -> outcome
        }
        val details = commands.map { it.optString("output").ifBlank { "A background command failed." } }.toMutableList()
        for (child in children) {
            val last = dao.log(child.id).lastOrNull { it.kind == TaskLogEntity.Kind.END.name }?.body
            details += child.title + ": " + child.outcome.orEmpty().lowercase() + last?.let { "\n$it" }.orEmpty()
        }
        val other = details.filter { it.isNotBlank() && it != words }.distinct()
        return Closeout(result, if (other.isEmpty()) words else "Other results in this attempt:\n" + other.joinToString("\n\n") + "\n\nLatest result:\n" + words)
    }

    /** Called inside the same transaction as the last result and its receipt. */
    internal suspend fun completeOwnIn(dao: TaskDao, id: String, outcome: TaskEntity.Outcome, words: String): Closeout? {
        val task = dao.task(id) ?: return null
        if (task.conversation != task.hand || task.state != TaskEntity.State.RUNNING.name || task.ask != null || task.hold != null || pendingWork(dao, id)) return null
        val closed = closeoutIn(dao, id, outcome, words)
        dao.updateTask(task.copy(state = TaskEntity.State.ENDED.name, ask = null, progress = null,
            outcome = closed.outcome.name, updatedAt = System.currentTimeMillis()))
        log(dao, id, TaskLogEntity.Kind.END, closed.words)
        return closed
    }

    private suspend fun recoverScreens(context: Context) = change(context, recovering = true) { dao, _ ->
        for (task in dao.interruptedScreens()) {
            val words = "The app restarted while the screen was working. Check the current screen and any outward effect before continuing; do not repeat an action whose outcome is unknown."
            val ask = JSONObject().put("kind", "screen_outcome_unknown").put("screen", task.id).put("question", words).toString()
            setAskIn(dao, task, ask)
            log(dao, task.id, TaskLogEntity.Kind.REPORT, words)
            val parent = screenSpec(context, task.id).parent?.let { dao.task(it) }
            val owner = parent?.conversation ?: task.conversation
            if (parent != null && parent.state != TaskEntity.State.ENDED.name) setAskIn(dao, parent, ask)
            Inbox.post(dao, owner, "$REPORT_CUE${parent?.id ?: task.id} · screen · ask] $words", parent?.id ?: task.id)
        }
    }

    /** A local command does not turn an unrelated new message into that command's model turn. */
    suspend fun modelTask(context: Context, hand: String): TaskEntity? {
        awaitRecovery()
        return (context.applicationContext as MinisApp).database.withTransaction {
            val dao = dao(context)
            dao.onHand(hand).firstOrNull { task ->
                task.conversation != hand || (task.ask == null && !pendingWork(dao, task.id))
            }
        }
    }

    internal suspend fun elapsed(context: Context, id: String): Long {
        return elapsedIn(dao(context), id)
    }

    internal suspend fun elapsedIn(dao: TaskDao, id: String, now: Long = System.currentTimeMillis()): Long {
        val task = dao.task(id) ?: return 0
        return TaskClock.elapsed(task, dao.log(id), now)
    }

    suspend fun waitForApproval(context: Context, spec: Approval.Spec): Approval.Spec = change(context) { dao, _ ->
        val source = source(dao, spec.request.sessionId, kotlinx.coroutines.currentCoroutineContext()[ToolCall])
        queueApprovalIn(dao, spec, if (spec.mode == Approval.Mode.NATIVE) null else source?.id,
            if (spec.mode == Approval.Mode.NATIVE) spec.request.summary else source?.words)
    }

    suspend fun findApproval(context: Context, id: String): Approval.Spec? =
        (context.applicationContext as MinisApp).database.withTransaction {
            dao(context).approvalTasks().flatMap { Approval.pending(it.ask) }.firstOrNull { it.request.id == id }
        }

    internal suspend fun queueApprovalIn(dao: TaskDao, spec: Approval.Spec, sourceId: String? = null, sourceWords: String? = null): Approval.Spec {
        if (spec.mode in listOf(Approval.Mode.SHELL, Approval.Mode.NATIVE)) {
            val recipe = requireNotNull(spec.shellArgs) { "Approval needs the exact saved command parameters." }
            com.openminis.app.scheduled.PrefilledToolCall("shell_execute", recipe).validationError()?.let { throw IllegalArgumentException(it) }
        }
        val request = spec.request
        val now = System.currentTimeMillis()
        val task = request.taskId?.let { dao.task(it) ?: error("the task was deleted") } ?: run {
            require(spec.mode != Approval.Mode.SCREEN) { "A screen approval needs a saved screen task; use momoding-hands run." }
            TaskEntity(newId(), request.sessionId, sourceId, (request.summary ?: request.preview).lineSequence().first().take(60),
                request.sessionId, TaskEntity.State.RUNNING.name, createdAt = now, updatedAt = now).also {
                dao.insertTask(it)
                sourceWords?.let { words -> log(dao, it.id, TaskLogEntity.Kind.AUTHORIZED, words) }
            }
        }
        val hand = if (spec.mode == Approval.Mode.SCREEN) TaskEntity.SCREEN else request.sessionId
        TaskGuard.checkIn(dao, TaskGuard.Binding(hand, task.id), outward = true, allowWaiting = true)?.let { error(it) }
        check(task.ask == null || Approval.pending(task.ask).isNotEmpty()) { "Answer the task's current question before requesting this approval." }
        val parent = if (spec.mode == Approval.Mode.SCREEN) dao.screenSpec(task.id)
            ?.let { JSONObject(it).optString("parent").takeIf(String::isNotBlank) }?.let { dao.task(it) } else null
        val captured = spec.copy(request = request.copy(taskId = task.id, conversation = parent?.conversation ?: task.conversation))
        val sequence = log(dao, task.id, TaskLogEntity.Kind.APPROVAL, captured.json().toString())
        val bound = captured.copy(request = captured.request.copy(sequence = sequence))
        setApprovalAsk(dao, task, Approval.pending(task.ask) + bound)
        if (spec.mode == Approval.Mode.SCREEN) {
            parent?.let { owner ->
                check(owner.ask == null || Approval.pending(owner.ask).isNotEmpty()) { "The parent task is waiting on another question." }
                setApprovalAsk(dao, owner, Approval.pending(owner.ask) + bound)
            }
            log(dao, task.id, TaskLogEntity.Kind.REPORT, "Waiting for approval; the proposed action has not run.")
        }
        return bound
    }

    private suspend fun setApprovalAsk(dao: TaskDao, task: TaskEntity, requests: List<Approval.Spec>, ignoreModel: Boolean = false) {
        setAskIn(dao, task, Approval.ask(requests), ignoreModel)
    }

    internal suspend fun setAskIn(dao: TaskDao, task: TaskEntity, ask: String?, ignoreModel: Boolean = false): TaskEntity {
        val working = working(dao, task, ignoreModel)
        clock(dao, task, working, "ask")
        return task.copy(state = (if (ask == null || working) TaskEntity.State.RUNNING else TaskEntity.State.WAITING).name,
            ask = ask, updatedAt = System.currentTimeMillis()).also { dao.updateTask(it) }
    }

    private suspend fun working(dao: TaskDao, task: TaskEntity, ignoreModel: Boolean): Boolean {
        val model = ChatViewModelStore.existing(task.hand)
        val history = dao.log(task.id)
        return TaskReceipt.pending(history, TaskLogEntity.Kind.COMMAND, TaskLogEntity.Kind.COMMAND_RESULT).isNotEmpty() || TaskAction.pending(history).isNotEmpty() ||
            dao.children(task.id).any { it.state == TaskEntity.State.RUNNING.name } ||
            (!ignoreModel && model?.activeTaskId == task.id && model.turnIsRunning)
    }

    private suspend fun clock(dao: TaskDao, task: TaskEntity, running: Boolean, reason: String, reset: Boolean = false) {
        val history = dao.log(task.id)
        val mark = TaskClock.latest(history)
        if (!reset && mark != null && (mark.kind == TaskLogEntity.Kind.STARTED.name) == running &&
            (!running || task.state == TaskEntity.State.RUNNING.name)) return
        val elapsed = if (reset) 0 else TaskClock.elapsed(task, history)
        log(dao, task.id, if (running) TaskLogEntity.Kind.STARTED else TaskLogEntity.Kind.PAUSED,
            JSONObject().put("elapsed_ms", elapsed).put("reason", reason).toString())
    }

    private suspend fun refreshBudget(dao: TaskDao, id: String, ignoreModel: Boolean = false) {
        val task = dao.task(id)?.takeIf { it.state != TaskEntity.State.ENDED.name } ?: return
        if (task.ask != null) setAskIn(dao, task, task.ask, ignoreModel)
        else clock(dao, task, working(dao, task, ignoreModel), "work changed")
    }

    internal suspend fun commandResumed(context: Context, id: String, command: ShellWork.Command) = change(context) { dao, _ ->
        dao.task(id)?.takeIf { TaskGuard.checkIn(dao, TaskGuard.Binding(it.hand, it.id)) == null }
            ?.takeIf { command in ShellWork.pending(dao.log(id)) }
            ?.let { clock(dao, it, true, "read resumed") }
    }

    suspend fun turnStarted(context: Context, taskId: String?) = change(context) { dao, _ ->
        taskId?.let { refreshBudget(dao, it) }
    }

    suspend fun decideApproval(context: Context, spec: Approval.Spec, answer: app.momoding.guard.RiskDecision): Approval.Decision? = change(context) { dao, after ->
        val live = app.momoding.guard.RiskGate.isWaiting(spec.request.id)
        val decided = decideApprovalIn(dao, spec, answer, live) ?: return@change null
        val task = dao.task(requireNotNull(spec.request.taskId)) ?: return@change decided
        if (spec.mode == Approval.Mode.SCREEN) {
            if (!ScreenWork.isRunning(task.id)) {
                if (decided.allowed && Approval.pending(task.ask).isEmpty()) {
                    dao.updateTask(task.copy(state = TaskEntity.State.QUEUED.name))
                    dao.screenSpec(task.id)?.let { JSONObject(it).optString("parent").takeIf(String::isNotBlank) }
                        ?.let { refreshBudget(dao, it) }
                    after += { ScreenWork.wake() }
                } else if (!decided.allowed) after += { screenFinished(context, task.id, app.momoding.hands.HandsOperator.Result(
                    app.momoding.hands.HandsOperator.Outcome.Infeasible("The person did not allow this action."),
                    0, spec.request.id, null, null, emptyList(), null)) }
            }
        } else if (!live) after += { tellChat(context, task.hand) }
        decided
    }

    internal suspend fun decideApprovalIn(dao: TaskDao, spec: Approval.Spec, answer: app.momoding.guard.RiskDecision, live: Boolean): Approval.Decision? {
        val task = spec.request.taskId?.let { dao.task(it) } ?: return null
        if (task.state == TaskEntity.State.ENDED.name) return null
        val stored = Approval.pending(task.ask).firstOrNull { it.request.id == spec.request.id } ?: return null
        val decision = Approval.Decision(stored, answer)
        log(dao, task.id, TaskLogEntity.Kind.APPROVAL_DECISION, decision.json())
        setApprovalAsk(dao, task, Approval.pending(task.ask).filterNot { it.request.id == stored.request.id })
        if (stored.mode == Approval.Mode.SCREEN) {
            dao.screenSpec(task.id)?.let { JSONObject(it).optString("parent").takeIf(String::isNotBlank) }?.let { id ->
                dao.task(id)?.takeIf { it.state != TaskEntity.State.ENDED.name }?.let { parent ->
                    setApprovalAsk(dao, parent, Approval.pending(parent.ask).filterNot { it.request.id == stored.request.id })
                }
            }
        } else if (decision.allowed && !live) {
            postApprovedShell(dao, decision)
        } else if (!decision.allowed) {
            val words = "The person did not allow this (" + stored.request.assessment.reason + "). It was not run. Do not retry or work around it."
            if (task.conversation == task.hand && !pendingWork(dao, task.id)) {
                dao.updateTask(dao.task(task.id)!!.copy(state = TaskEntity.State.ENDED.name, outcome = TaskEntity.Outcome.FAILED.name, ask = null))
                log(dao, task.id, TaskLogEntity.Kind.END, words)
            }
            if (!live) Inbox.post(dao, task.hand, REPORT_CUE + task.id + " · approval · command_result] " + words, task.id)
        }
        return decision
    }

    private suspend fun postApprovedShell(dao: TaskDao, decision: Approval.Decision) {
        val spec = decision.spec
        val task = dao.task(requireNotNull(spec.request.taskId)) ?: return
        if (task.state == TaskEntity.State.ENDED.name || Approval.unclaimed(dao.log(task.id)).none { it.spec.request.id == spec.request.id }) return
        val args = spec.shellArgs?.let(::JSONObject) ?: return
        val message = "approval:" + spec.request.id
        val row = dao.inbox(message)
        if (row == null) Inbox.post(dao, task.hand, REPORT_CUE + task.id + " · approval · command_result] The person approved the exact prefilled command. Execute it once and use its result; do not repeat it.",
            task.id, sender = InboxEntity.Sender.COMMAND,
            prefill = listOf(com.openminis.app.scheduled.PrefilledToolCall("shell_execute", args.put(Approval.TICKET_ARG, spec.ticket).toString())), id = message)
        else if (row.readAt != null) dao.restoreApprovalEnvelope(message, task.id, task.hand)
    }

    suspend fun resumeApproved(context: Context, taskId: String) = change(context) { dao, after ->
        for (decision in Approval.unclaimed(dao.log(taskId))) {
            if (decision.spec.mode in listOf(Approval.Mode.SHELL, Approval.Mode.NATIVE)) postApprovedShell(dao, decision)
        }
        dao.task(taskId)?.takeIf { dao.unread(it.hand).isNotEmpty() }?.let { after += { tellChat(context, it.hand) } }
    }

    suspend fun claimApproval(context: Context, session: String, ticket: String, args: String? = null,
        actual: app.momoding.guard.RiskRequest? = null,
    ): Approval.Claim = change(context) { dao, _ ->
        claimApprovalIn(dao, session, ticket, args, actual)
    }

    internal suspend fun claimApprovalIn(dao: TaskDao, session: String, ticket: String, args: String? = null,
        actual: app.momoding.guard.RiskRequest? = null,
    ): Approval.Claim {
        val taskId = ticket.substringBefore(':')
        val id = ticket.substringAfter(':', "")
        val task = dao.task(taskId) ?: return Approval.Claim.Denied("The approval's task was deleted.")
        val decision = dao.log(taskId).filter { it.kind == TaskLogEntity.Kind.APPROVAL_DECISION.name }.map { Approval.Decision.parse(it.body) }
            .lastOrNull { it.spec.request.id == id } ?: return Approval.Claim.Denied("That approval is no longer available.")
        if (!decision.allowed) return Approval.Claim.Denied("The person did not allow this (" + decision.spec.request.assessment.reason + "). Do not retry it.")
        if (Approval.unclaimed(dao.log(taskId)).none { it.spec.request.id == id }) return Approval.Claim.Denied("That approval was already used or withdrawn. Do not repeat the command.")
        val spec = decision.spec
        if (spec.request.sessionId != session) return Approval.Claim.Denied("That approval belongs to another conversation.")
        val hand = if (spec.mode == Approval.Mode.SCREEN) TaskEntity.SCREEN else session
        val denied = when {
            spec.mode == Approval.Mode.SHELL && args == null -> "An approved shell call needs its saved parameters."
            args != null && (spec.shellArgs == null || Approval.shellSignature(args) != Approval.shellSignature(spec.shellArgs)) -> "The command or its parameters changed; that approval does not apply."
            actual != null && !spec.sameAction(actual) -> "The target changed; that approval does not apply."
            else -> TaskGuard.checkIn(dao, TaskGuard.Binding(hand, taskId), outward = true, allowWaiting = Approval.pending(task.ask).isNotEmpty())
        }
        if (denied != null) {
            log(dao, taskId, TaskLogEntity.Kind.APPROVAL_CLOSED, JSONObject().put("id", id).put("reason", denied).toString())
            if (task.state != TaskEntity.State.ENDED.name && task.ask == null) {
                setAskIn(dao, task, JSONObject().put("kind", "approval_invalid").put("question", denied).toString())
            }
            return Approval.Claim.Denied(denied)
        }
        log(dao, taskId, TaskLogEntity.Kind.APPROVAL_USED, JSONObject().put("id", id).toString())
        val command = if (spec.mode in listOf(Approval.Mode.SHELL, Approval.Mode.NATIVE)) {
            ShellWork.Command(id, session + "~command~" + id, JSONObject(requireNotNull(spec.shellArgs)).getString("command"))
                .also { log(dao, taskId, TaskLogEntity.Kind.COMMAND, it.json()) }
        } else null
        refreshBudget(dao, taskId)
        return Approval.Claim.Granted(Approval.Permit(spec, decision.answer, command))
    }

    suspend fun screenApproval(context: Context, actual: app.momoding.guard.RiskRequest): Approval.Claim? = change(context) { dao, _ ->
        val id = actual.taskId ?: return@change null
        val unused = Approval.unclaimed(dao.log(id)).filter { it.spec.mode == Approval.Mode.SCREEN }
        unused.firstOrNull { it.spec.sameAction(actual) }?.let { return@change claimApprovalIn(dao, actual.sessionId, it.spec.ticket, actual = actual) }
        unused.forEach { log(dao, id, TaskLogEntity.Kind.APPROVAL_CLOSED, JSONObject().put("id", it.spec.request.id).put("reason", "screen target changed").toString()) }
        null
    }

    internal suspend fun invalidateApprovals(dao: TaskDao, id: String) {
        for (decision in Approval.unclaimed(dao.log(id))) {
            log(dao, id, TaskLogEntity.Kind.APPROVAL_CLOSED, JSONObject().put("id", decision.spec.request.id).put("reason", "new instructions").toString())
            dao.deleteInbox(listOf("approval:" + decision.spec.request.id))
        }
    }

    private suspend fun recoverApprovals(context: Context): Set<String> = change(context, recovering = true) { dao, _ ->
        recoverApprovalsIn(dao)
    }

    internal suspend fun recoverApprovalsIn(dao: TaskDao): Set<String> {
        val wake = mutableSetOf<String>()
        for (task in dao.approvalTasks()) {
            for (decision in Approval.unclaimed(dao.log(task.id))) {
                if (decision.spec.mode == Approval.Mode.SCREEN) {
                    if (Approval.pending(task.ask).isEmpty()) dao.updateTask(task.copy(state = TaskEntity.State.QUEUED.name))
                } else postApprovedShell(dao, decision)
            }
            if (dao.unread(task.hand).isNotEmpty()) wake += task.hand
        }
        return wake
    }

    internal suspend fun commandStarted(context: Context, chat: String, taskAtStart: String?, title: String,
        command: ShellWork.Command,
    ): TaskEntity = change(context) { dao, _ ->
        val existing = taskAtStart?.let { id ->
            dao.task(id)?.also { check(it.state != TaskEntity.State.ENDED.name) { "the task ended before the command was handed on" } }
                ?: error("the task was deleted")
        }
        val source = source(dao, chat, command.call ?: kotlinx.coroutines.currentCoroutineContext()[ToolCall] ?: ShellWork.toolCall(command.process))
        val now = System.currentTimeMillis()
        val task = existing ?: TaskEntity(newId(), chat, source?.id, title, chat, TaskEntity.State.RUNNING.name,
            createdAt = now, updatedAt = now).also {
            dao.insertTask(it)
            source?.words?.let { words -> log(dao, it.id, TaskLogEntity.Kind.AUTHORIZED, words) }
            log(dao, it.id, TaskLogEntity.Kind.PASSED_ON, command.text)
            clock(dao, it, true, "command handed on")
        }
        recordCommandIn(dao, task, command)
        task
    }

    /** The command and its original return path commit together, including a helper's own calls. */
    internal suspend fun recordCommandIn(dao: TaskDao, task: TaskEntity, command: ShellWork.Command) {
        if (command.call != null) check(command.savedArgs(dao, task.hand) != null) {
            "The original command invocation is missing or no longer matches."
        }
        log(dao, task.id, TaskLogEntity.Kind.COMMAND, command.json())
        handoffIn(dao, task, command.call, session = task.hand, command = command.id)
    }

    internal suspend fun commandFinished(context: Context, id: String, command: ShellWork.Command,
        result: com.openminis.app.tools.ToolExecutionResult, budgetExpired: Boolean = false, announce: Boolean = true,
        unknown: Boolean = false, onlyIfNotStarted: Boolean = false,
    ) = change(context) { dao, after -> commandFinishedIn(context, dao, after, id, command, result, budgetExpired, announce, unknown, onlyIfNotStarted) }

    internal suspend fun commandFinishedIn(context: Context, dao: TaskDao, after: MutableList<suspend () -> Unit>, id: String,
        command: ShellWork.Command, result: com.openminis.app.tools.ToolExecutionResult, budgetExpired: Boolean = false,
        announce: Boolean = true, unknown: Boolean = false, onlyIfNotStarted: Boolean = false,
    ) {
        val task = dao.task(id) ?: return
        val history = dao.log(id)
        val previous = TaskReceipt.result(history, TaskLogEntity.Kind.COMMAND_RESULT, command.id)
        if (history.none { it.kind == TaskLogEntity.Kind.COMMAND.name && ShellWork.Command.parse(it.body) == command }) return
        if (onlyIfNotStarted && history.any { it.kind == TaskLogEntity.Kind.ABOUT_TO.name && JSONObject(it.body).getString("id") == command.id }) return
        if (previous != null && (!previous.optBoolean("unknown") || unknown)) return
        val row = JSONObject().put("id", command.id).put("output", result.output)
        if (unknown) row.put("unknown", true) else row.put("success", result.success)
        log(dao, id, TaskLogEntity.Kind.COMMAND_RESULT, row.toString())
        actionResultIn(dao, id, command.id, result.output, result.success, unknown)
        val late = task.state == TaskEntity.State.ENDED.name || previous?.optBoolean("unknown") == true ||
            TaskReceipt.belongsToEarlierAttempt(history, TaskLogEntity.Kind.COMMAND, command.id)
        if (late) {
            if (!unknown) lateReceiptIn(dao, task, "command", command.id, result.output, result.success)?.let { owner -> after += { tellChat(context, owner) } }
            return
        }
        if (unknown) {
            val words = "The command was interrupted; its outward effect is unknown. Verify what happened before repeating it.\n" + result.output
            actionUnknownIn(dao, task, words)
            Inbox.post(dao, task.conversation, "$REPORT_CUE$id · command · ask] $words", id)
            after += { tellChat(context, task.conversation) }
            return
        }
        if (budgetExpired) {
            reportIn(context, dao, after, task.hand, Report.FAILED, result.output, helper = true)
            after += { Helpers.ended(context, task.hand, task.conversation) }
            after += { handIdle(context, task.hand) }
            return
        }
        refreshBudget(dao, id)
        val closed = completeOwnIn(dao, id, if (result.success) TaskEntity.Outcome.DONE else TaskEntity.Outcome.FAILED, result.output)
        val words = "$REPORT_CUE$id · command · ${closed?.outcome?.name?.lowercase() ?: "command_result"}] ${closed?.words ?: result.output}"
        if (announce) {
            Inbox.post(dao, task.hand, words, id, sender = InboxEntity.Sender.COMMAND)
            after += { tellChat(context, task.hand) }
        }
    }

    /** Known reads can resume; a killed process cannot prove an outward effect. */
    internal suspend fun recoverCommandsIn(dao: TaskDao): List<ReadRecovery.Pending> {
        val reads = mutableListOf<ReadRecovery.Pending>()
        for (task in dao.commandTasks()) {
            val commands = TaskReceipt.pending(dao.log(task.id), TaskLogEntity.Kind.COMMAND, TaskLogEntity.Kind.COMMAND_RESULT).filter { entry ->
                val read = ShellWork.Command.parse(entry.row.body)?.let { ReadRecovery.pendingIn(dao, task, it) }
                if (read != null) reads += read
                read == null
            }
            if (commands.isEmpty()) continue
            val fallback = "The app restarted while a command was running; its outcome is unknown. Verify what happened before running it again."
            commands.forEach { log(dao, task.id, TaskLogEntity.Kind.COMMAND_RESULT, JSONObject().put("id", it.id).put("unknown", true).toString()) }
            val words = recoverActionsIn(dao, task) ?: fallback.also {
                setAskIn(dao, task, JSONObject().put("question", it).put("kind", "command_outcome_unknown").toString())
            }
            log(dao, task.id, TaskLogEntity.Kind.REPORT, words)
            Inbox.post(dao, task.conversation, "$REPORT_CUE${task.id} · command · ask] $words", task.id)
        }
        return reads
    }

    /** A routine's history/notification consumer acknowledged this persisted completion. */
    suspend fun routineRecorded(context: Context, id: String) = change(context) { dao, _ ->
        if (dao.task(id) != null && dao.log(id).none { it.kind == TaskLogEntity.Kind.ROUTINE_RECORDED.name }) {
            log(dao, id, TaskLogEntity.Kind.ROUTINE_RECORDED, "recorded")
        }
    }

    /** The name a hand goes by: its Momoding's, else it is a helper. */
    fun handName(context: Context, hand: String): String = if (hand == TaskEntity.SCREEN) "screen" else MoApps.ofSession(context, hand)?.title ?: "helper"

    /**
     * The loop that actually ran hands back its result, tied to the task it started on. An
     * explicit report already settled it; unread follow-ups are still owed another loop.
     * Never infer success from a session becoming idle or from an older transcript message.
     */
    suspend fun turnFinished(context: Context, hand: String, taskId: String?, completion: TaskCompletion, hasFollowUp: Boolean) {
        val helper = Helpers.isHelper(context, hand)
        change(context) { dao, after -> finishTurnIn(context, dao, after, hand, taskId, completion, hasFollowUp, helper) }
    }

    /** The final reply, read input and original hand's closeout commit together. */
    suspend fun <T> reply(context: Context, hand: String, taskId: String?, completion: TaskCompletion, persist: suspend () -> T): T {
        val helper = Helpers.isHelper(context, hand)
        return change(context) { dao, after -> replyIn(context, dao, after, hand, taskId, completion, helper, persist) }
    }

    internal suspend fun <T> replyIn(context: Context, dao: TaskDao, after: MutableList<suspend () -> Unit>,
        hand: String, taskId: String?, completion: TaskCompletion, helper: Boolean, persist: suspend () -> T,
    ): T {
        val value = persist()
        dao.completeRead(hand)
        finishTurnIn(context, dao, after, hand, taskId, completion, dao.unread(hand).isNotEmpty(), helper)
        return value
    }

    private suspend fun finishTurnIn(context: Context, dao: TaskDao, after: MutableList<suspend () -> Unit>,
        hand: String, taskId: String?, completion: TaskCompletion, hasFollowUp: Boolean, helper: Boolean,
    ) {
        val current = taskId?.let { dao.task(it) }
        if (current?.hand == hand && current.state == TaskEntity.State.RUNNING.name && !hasFollowUp && !pendingWork(dao, current.id)) {
            reportIn(context, dao, after, hand, if (completion.done) Report.DONE else Report.FAILED, completion.words, helper, current.id)
        }
        if (helper && current != null && dao.task(current.id)?.state == TaskEntity.State.ENDED.name) {
            after += { Helpers.ended(context, hand, current.conversation) }
        }
        current?.let { refreshBudget(dao, it.id, ignoreModel = true) }
        after += {
            val deferred = kotlinx.coroutines.withContext(Dispatchers.Main) {
                ChatViewModelStore.existing(hand)?.afterTurn { scope.launch { handIdle(context, hand) } } == true
            }
            if (!deferred) handIdle(context, hand)
        }
    }

    /** A busy hand may receive queued work just as its loop exits. Only dispatch is inferred
     * from idleness; outcomes always come from the loop's explicit closeout. */
    suspend fun handIdle(context: Context, hand: String) {
        if (hand == TaskEntity.SCREEN) { ScreenWork.wake(); return }
        val helper = Helpers.isHelper(context, hand)
        change(context) { dao, after -> advance(context, dao, after, hand, helper) }
    }

    private suspend fun advance(context: Context, dao: TaskDao, after: MutableList<suspend () -> Unit>, hand: String, helper: Boolean) {
        if (dao.heldBy(hand) != null || ChatViewModelStore.existing(hand)?.turnIsRunning == true) return
        val next = if (helper && dao.helpersAtWork() < Helpers.SLOTS) dao.queuedHelpers().firstOrNull {
            dao.heldBy(it.hand) == null && ChatViewModelStore.existing(it.hand)?.turnIsRunning != true
        } else if (!helper) dao.nextFor(hand) else null
        next?.let { start(dao, it, after, context) }
    }

    /** Whether [hand] can start a task now: it holds none, and a helper has a slot. */
    private suspend fun free(dao: TaskDao, hand: String, helper: Boolean) =
        dao.heldBy(hand) == null && ChatViewModelStore.existing(hand)?.turnIsRunning != true &&
            (!helper || dao.helpersAtWork() < Helpers.SLOTS)

    /** A task that waited its turn starts once this change commits, with everything passed on for it. */
    private suspend fun start(dao: TaskDao, task: TaskEntity, after: MutableList<suspend () -> Unit>, context: Context) {
        clock(dao, task, true, "started")
        val next = task.copy(state = TaskEntity.State.RUNNING.name, updatedAt = System.currentTimeMillis())
        dao.updateTask(next)
        val brief = dao.log(task.id).filter { it.kind == TaskLogEntity.Kind.PASSED_ON.name }.joinToString("\n\n") { it.body }
        dao.retryTaskInbox(task.id, task.hand)
        if (dao.unread(task.hand).none { it.taskId == task.id }) postForHand(context, dao, next, brief)
        after += { deliver(context, next) }
    }

    /**
     * Words for a task's hand: into its turn at its next step when it is at work, else a new turn.
     * A Bot reads them as from Momoding ([AppBots.WAKE_CUE]); a helper is set up as one first.
     */
    private suspend fun postForHand(context: Context, dao: TaskDao, task: TaskEntity, text: String,
        prefill: List<com.openminis.app.scheduled.PrefilledToolCall> = emptyList(),
    ) {
        val words = if (MoApps.ofSession(context, task.hand) != null) "${AppBots.WAKE_CUE} $text" else text
        Inbox.post(dao, task.hand, words, task.id, prefill = prefill)
    }

    private suspend fun deliver(context: Context, task: TaskEntity) {
        runCatching {
            Inbox.wake(context, task.hand)
        }.onFailure {
            if (it is kotlinx.coroutines.CancellationException) throw it
            AppLogger.warning(TAG, "deliver to ${task.hand.take(8)}: ${it.message}")
            dao(context).rejectTaskInbox(task.id, task.hand, System.currentTimeMillis())
            turnFinished(context, task.hand, task.id, TaskCompletion(false, "The hand could not start: ${it.message}"), hasFollowUp = false)
        }
    }

    /** A hand's report for the chat it works for: read when that chat's turn ends, or a turn of its own. */
    private suspend fun tellChat(context: Context, chat: String) {
        runCatching { Inbox.wake(context, chat) }
            .onFailure { AppLogger.warning(TAG, "report to ${chat.take(8)}: ${it.message}") }
    }

    /** One change: [block] in a transaction, then what it set off. */
    private suspend fun <T> change(context: Context, recovering: Boolean = false, block: suspend (TaskDao, MutableList<suspend () -> Unit>) -> T): T {
        if (!recovering) awaitRecovery()
        val db = (context.applicationContext as MinisApp).database
        val after = mutableListOf<suspend () -> Unit>()
        val result = db.withTransaction { block(db.taskDao(), after) }
        after.forEach { runCatching { it() }.onFailure { e -> AppLogger.warning(TAG, "after commit: ${e.message}") } }
        return result
    }

    private suspend fun log(dao: TaskDao, task: String, kind: TaskLogEntity.Kind, body: String) =
        dao.insertLog(TaskLogEntity(taskId = task, at = System.currentTimeMillis(), kind = kind.name, body = body))

    private suspend fun handoffIn(dao: TaskDao, task: TaskEntity, call: ToolCall?, session: String = task.conversation, command: String? = null) {
        if (call != null) log(dao, task.id, TaskLogEntity.Kind.HANDOFF,
            JSONObject().put("tool", call.json()).put("session", session).put("task", task.id).apply {
                command?.let { put("command", it) }
            }.toString())
    }

    private fun draftNeedle(draft: String): String = "\"id\":" + JSONObject.quote(JSONObject(draft).getString("id"))

    /** Short enough for a model to repeat back. */
    private fun newId(): String = java.util.UUID.randomUUID().toString().replace("-", "").take(6)

    /** Everything recent, for the debug RPC `task.dump` (walkthroughs and evals read it). */
    suspend fun dump(context: Context, limit: Int = 50): JSONObject {
        val dao = dao(context)
        val tasks = JSONArray()
        for (t in dao.recentTasks(limit)) {
            val log = JSONArray()
            val history = dao.log(t.id)
            history.forEach { log.put(JSONObject().put("at", it.at).put("kind", it.kind).put("body", it.body)) }
            tasks.put(
                JSONObject().put("id", t.id).put("conversation", t.conversation).put("source", t.sourceMessageId)
                    .put("title", t.title).put("hand", t.hand).put("state", t.state).put("progress", t.progress)
                    .put("ask", t.ask).put("hold", t.hold).put("outcome", t.outcome).put("log", log)
                    .put("elapsed_ms", TaskClock.elapsed(t, history)),
            )
        }
        val inbox = JSONArray()
        dao.recentInbox(limit).forEach {
            inbox.put(
                JSONObject().put("id", it.id).put("to", it.toSession).put("task", it.taskId).put("from", it.sender)
                    .put("text", it.text).put("arrived", it.arrivedAt).put("read", it.readAt)
                    .put("tries", it.tries).put("failed", it.failedAt),
            )
        }
        return JSONObject().put("tasks", tasks).put("inbox", inbox)
            .put("active_ids", JSONArray(active.value.map { it.id }))
            .put("approval_ids", JSONArray(app.momoding.guard.RiskGate.open.value.map { it.id }))
    }
}
