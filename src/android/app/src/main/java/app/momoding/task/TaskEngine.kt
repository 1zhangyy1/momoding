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
    /** How a hand's report starts, in the conversation it goes to: `[task <id> · <hand> · <kind>] <words>`. */
    const val REPORT_CUE = "[task "

    enum class Report { PROGRESS, ASK, DONE, FAILED }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _active = MutableStateFlow<List<TaskEntity>>(emptyList())
    val active: StateFlow<List<TaskEntity>> = _active.asStateFlow()

    fun dao(context: Context): TaskDao = (context.applicationContext as MinisApp).database.taskDao()

    private val _parentsWithWork = MutableStateFlow<Set<String>>(emptySet())
    val parentsWithWork: StateFlow<Set<String>> = _parentsWithWork.asStateFlow()

    /** Keep the UI's view of persisted work current; a loop explicitly reports its end. */
    fun start(context: Context) {
        val app = context.applicationContext
        scope.launch { dao(app).activeFlow().collect {
            _active.value = it
            _parentsWithWork.value = it.map { task -> task.conversation }.toSet()
        } }
        scope.launch {
            recoverCommands(app)
            recoverScreens(app)
            ScreenWork.start(app)
            kotlinx.coroutines.delay(3000)
            dao(app).queuedHands().forEach { hand -> handIdle(app, hand) }
        }
    }

    /** The task the person's words started: a new one for [hand], or in line behind the one it is on. */
    suspend fun open(context: Context, conversation: String, title: String, hand: String, brief: String,
        prefill: List<com.openminis.app.scheduled.PrefilledToolCall> = emptyList(), routine: RoutineFire? = null,
    ): TaskEntity {
        val source = if (routine != null) null else ChatViewModelStore.existing(conversation)?.messages?.value
            ?.lastOrNull { it.rendersAsUserBubble() && !it.unread }
        val helper = Helpers.isHelper(context, hand)
        return change(context) { dao, after ->
            if (helper) check(dao.heldBy(hand) == null) { "a helper keeps one task; use tell to add instructions" }
            val busy = !free(dao, hand, helper)
            val now = System.currentTimeMillis()
            val task = TaskEntity(
                id = newId(), conversation = conversation, sourceMessageId = source?.id, title = title.trim(), hand = hand,
                state = (if (busy) TaskEntity.State.QUEUED else TaskEntity.State.RUNNING).name, createdAt = now, updatedAt = now,
            )
            dao.insertTask(task)
            source?.content?.let { log(dao, task.id, TaskLogEntity.Kind.AUTHORIZED, it) }
            log(dao, task.id, TaskLogEntity.Kind.PASSED_ON, brief)
            if (!busy) log(dao, task.id, TaskLogEntity.Kind.STARTED, "started")
            routine?.let { log(dao, task.id, TaskLogEntity.Kind.ROUTINE, it.json()) }
            // Each helper has its own chat: its queued payload can safely be stored now.
            // A busy Bot must not see another task's instructions until that task starts.
            if (helper || !busy) postForHand(context, dao, task, brief, prefill)
            if (!busy) after += { deliver(context, task) }
            task
        }
    }

    /**
     * More words for a task's hand. It reads them before its next step when it is at work; they
     * answer what it waits on; a task that is done or did not work picks up again with the same hand.
     */
    suspend fun tell(context: Context, id: String, text: String): TaskEntity {
        val helper = dao(context).task(id)?.let { Helpers.isHelper(context, it.hand) } ?: false
        return change(context) { dao, after -> tellIn(context, dao, after, id, text, helper) }
    }

    private suspend fun tellIn(context: Context, dao: TaskDao, after: MutableList<suspend () -> Unit>, id: String, text: String, helper: Boolean): TaskEntity {
        val task = dao.task(id) ?: throw IllegalArgumentException("no task $id (the table of tasks lists them)")
        if (task.outcome == TaskEntity.Outcome.STOPPED.name) throw IllegalStateException("task $id was stopped: open a new one")
        log(dao, id, TaskLogEntity.Kind.PASSED_ON, text)
        val waitingScreen = task.ask?.let { runCatching { JSONObject(it).optString("screen") }.getOrNull() }?.takeIf { it.isNotBlank() }
        if (waitingScreen != null && dao.children(id).any { it.id == waitingScreen && it.state == TaskEntity.State.WAITING.name }) {
            tellIn(context, dao, after, waitingScreen, text, helper = false)
            return dao.task(id) ?: task
        }
        if (task.hand == TaskEntity.SCREEN) {
            val live = ScreenWork.isRunning(id)
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
        dao.updateTask(next)
        if (helper || state == TaskEntity.State.RUNNING) postForHand(context, dao, next, text)
        if (state == TaskEntity.State.RUNNING) {
            if (task.state == TaskEntity.State.ENDED.name || task.state == TaskEntity.State.WAITING.name) log(dao, id, TaskLogEntity.Kind.STARTED, "resumed")
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

    private suspend fun reportIn(context: Context, dao: TaskDao, after: MutableList<suspend () -> Unit>, hand: String, kind: Report, text: String, helper: Boolean): TaskEntity {
        val task = dao.heldBy(hand) ?: throw IllegalStateException("you are on no task: just answer in your chat")
        if (kind == Report.DONE && pendingWork(dao, task.id)) {
            throw IllegalStateException("Work for this task is still running or waiting. Its result will arrive automatically; do not report done yet.")
        }
        if (kind == Report.DONE && dao.unread(hand).isNotEmpty()) {
            throw IllegalStateException("There are unread instructions for this hand. Read them at the next step before reporting done.")
        }
        val now = System.currentTimeMillis()
        val next = when (kind) {
            Report.PROGRESS -> task.copy(progress = text.lineSequence().first().take(120))
            Report.ASK -> task.copy(state = TaskEntity.State.WAITING.name, ask = JSONObject().put("question", text)
                .put("screen", task.ask?.let { runCatching { JSONObject(it).optString("screen") }.getOrNull() }?.takeIf { it.isNotBlank() }).toString())
            Report.DONE, Report.FAILED -> task.copy(
                state = TaskEntity.State.ENDED.name, ask = null,
                outcome = (if (kind == Report.DONE) TaskEntity.Outcome.DONE else TaskEntity.Outcome.FAILED).name,
            )
        }.copy(updatedAt = now)
        dao.updateTask(next)
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

    suspend fun pendingWork(dao: TaskDao, id: String): Boolean =
        ShellWork.pending(dao.log(id)).isNotEmpty() || dao.children(id).any { it.state != TaskEntity.State.ENDED.name }

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
    suspend fun stop(context: Context, id: String, reason: String = "Stopped by the person.", outcome: TaskEntity.Outcome = TaskEntity.Outcome.STOPPED): TaskEntity? = change(context) { dao, after ->
        val root = dao.task(id) ?: return@change null
        val unread = dao.unread(root.hand).filter { it.taskId == id }.map { it.text }
        stopTree(context, dao, after, id, reason)
        if (root.state != TaskEntity.State.ENDED.name) {
            if (outcome != TaskEntity.Outcome.STOPPED) dao.task(id)?.let { dao.updateTask(it.copy(outcome = outcome.name)) }
            val words = reason + if (unread.isEmpty()) "" else "\nInstructions the hand had not read:\n" + unread.joinToString("\n\n")
            Inbox.post(dao, root.conversation, "$REPORT_CUE$id · ${handName(context, root.hand)} · ${outcome.name.lowercase()}] $words", id)
            after += { tellChat(context, root.conversation) }
        }
        dao.task(id)
    }

    private suspend fun stopTree(context: Context, dao: TaskDao, after: MutableList<suspend () -> Unit>, id: String, reason: String) {
        val root = dao.task(id) ?: return
        for (task in descendants(dao, listOf(root))) {
            if (task.state == TaskEntity.State.ENDED.name) continue
            val commands = ShellWork.pending(dao.log(task.id))
            dao.updateTask(task.copy(state = TaskEntity.State.ENDED.name, outcome = TaskEntity.Outcome.STOPPED.name,
                ask = null, updatedAt = System.currentTimeMillis()))
            log(dao, task.id, TaskLogEntity.Kind.END, reason)
            dao.deleteTaskInbox(task.id)
            commands.forEach { command -> after += { ShellWork.stop(command.process) } }
            if (task.hand == TaskEntity.SCREEN) after += { ScreenWork.stop(task.id, reason) }
            else after += { kotlinx.coroutines.withContext(Dispatchers.Main) { ChatViewModelStore.existing(task.hand)?.cancelTaskIfRunning(task.id) } }
            after += { handIdle(context, task.hand) }
        }
    }

    suspend fun openScreen(context: Context, options: app.momoding.hands.HandsOperator.Options, parentId: String? = null): TaskEntity = change(context) { dao, after ->
        val chat = requireNotNull(options.sessionId) { "a screen task needs its conversation" }
        val parent = parentId?.let { dao.task(it) ?: error("the parent task was deleted") }
        require(parent == null || parent.hand == chat) { "the parent task belongs to another hand" }
        check(parent == null || parent.state != TaskEntity.State.ENDED.name) { "the parent task ended" }
        val source = ChatViewModelStore.existing(chat)?.messages?.value?.lastOrNull { it.rendersAsUserBubble() && !it.unread }
        val sourceId = if (parent?.conversation == chat) parent.sourceMessageId else source?.id
        val now = System.currentTimeMillis()
        val task = TaskEntity(newId(), chat, sourceId, options.task.lineSequence().first().take(60), TaskEntity.SCREEN,
            TaskEntity.State.QUEUED.name, createdAt = now, updatedAt = now)
        dao.insertTask(task)
        source?.content?.let { log(dao, task.id, TaskLogEntity.Kind.AUTHORIZED, it) }
        log(dao, task.id, TaskLogEntity.Kind.SCREEN, ScreenWork.Spec(options, parent?.id).json())
        log(dao, task.id, TaskLogEntity.Kind.PASSED_ON, options.task)
        parent?.let { log(dao, it.id, TaskLogEntity.Kind.CHILD, task.id) }
        after += { ScreenWork.wake() }
        task
    }

    internal suspend fun acquireScreen(dao: TaskDao, claim: () -> Boolean): TaskEntity? {
        if (dao.heldBy(TaskEntity.SCREEN) != null) return null
        val task = dao.nextFor(TaskEntity.SCREEN) ?: return null
        if (!claim()) return null
        val next = task.copy(state = TaskEntity.State.RUNNING.name, updatedAt = System.currentTimeMillis())
        dao.updateTask(next)
        log(dao, task.id, TaskLogEntity.Kind.STARTED, "screen lease acquired")
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
        ScreenWork.Spec.parse(dao(context).log(id).first { it.kind == TaskLogEntity.Kind.SCREEN.name }.body)

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
        val task = dao.task(id)?.takeIf { it.state != TaskEntity.State.ENDED.name } ?: return@change
        val ask = JSONObject().put("kind", "screen_takeover").put("screen", id).put("question", question).toString()
        dao.updateTask(task.copy(state = TaskEntity.State.WAITING.name, ask = ask, updatedAt = System.currentTimeMillis()))
        log(dao, id, TaskLogEntity.Kind.REPORT, question)
        screenSpec(context, id).parent?.let { parent -> dao.task(parent)?.takeIf { it.state != TaskEntity.State.ENDED.name }?.let {
            dao.updateTask(it.copy(state = TaskEntity.State.WAITING.name, ask = ask, updatedAt = System.currentTimeMillis()))
        } }
    }

    private suspend fun screenParentReady(dao: TaskDao, id: String) {
        val spec = dao.log(id).firstOrNull { it.kind == TaskLogEntity.Kind.SCREEN.name }?.body?.let { ScreenWork.Spec.parse(it) } ?: return
        spec.parent?.let { parent -> dao.task(parent)?.takeIf {
            it.state == TaskEntity.State.WAITING.name && it.ask?.let { ask -> JSONObject(ask).optString("screen") } == id
        }?.let {
            dao.updateTask(it.copy(state = TaskEntity.State.RUNNING.name, ask = null, updatedAt = System.currentTimeMillis()))
            log(dao, it.id, TaskLogEntity.Kind.STARTED, "screen continued")
        } }
    }

    /** An old run's result cannot settle words that arrived after its last checkpoint. */
    internal suspend fun requeueScreenFollowUp(dao: TaskDao, id: String, through: Long?): Boolean {
        val task = dao.task(id) ?: return false
        if (through == null || task.state == TaskEntity.State.ENDED.name ||
            dao.log(id).none { it.kind == TaskLogEntity.Kind.PASSED_ON.name && it.id > through }) return false
        dao.updateTask(task.copy(state = TaskEntity.State.QUEUED.name, ask = null, outcome = null,
            progress = null, updatedAt = System.currentTimeMillis()))
        screenParentReady(dao, id)
        return true
    }

    suspend fun screenFinished(context: Context, id: String, result: app.momoding.hands.HandsOperator.Result) = change(context) { dao, after ->
        val task = dao.task(id) ?: return@change
        val body = ScreenWork.resultJson(result).toString()
        if (dao.log(id).none { it.kind == TaskLogEntity.Kind.SCREEN_RESULT.name && runCatching { JSONObject(it.body).optString("run_id") == result.runId }.getOrDefault(false) }) {
            log(dao, id, TaskLogEntity.Kind.SCREEN_RESULT, body)
        }
        if (task.state == TaskEntity.State.ENDED.name) return@change
        if (requeueScreenFollowUp(dao, id, result.instructionsThrough)) {
            after += { ScreenWork.wake() }
            return@change
        }
        val question = (result.outcome as? app.momoding.hands.HandsOperator.Outcome.NeedsUser)?.question
        val outcome = when (result.outcome) {
            is app.momoding.hands.HandsOperator.Outcome.Done -> TaskEntity.Outcome.DONE
            is app.momoding.hands.HandsOperator.Outcome.Stopped -> TaskEntity.Outcome.STOPPED
            else -> TaskEntity.Outcome.FAILED
        }
        dao.updateTask(task.copy(state = (if (question != null) TaskEntity.State.WAITING else TaskEntity.State.ENDED).name,
            ask = question?.let { JSONObject().put("kind", "screen_question").put("screen", id).put("question", it).toString() },
            outcome = if (question == null) outcome.name else null, progress = null, updatedAt = System.currentTimeMillis()))
        log(dao, id, if (question == null) TaskLogEntity.Kind.END else TaskLogEntity.Kind.REPORT, body)
        val parent = screenSpec(context, id).parent
        if (parent != null && dao.task(parent)?.state !in listOf(TaskEntity.State.RUNNING.name, TaskEntity.State.WAITING.name)) return@change
        if (question != null && parent != null) dao.task(parent)?.let {
            dao.updateTask(it.copy(state = TaskEntity.State.WAITING.name,
                ask = JSONObject().put("kind", "screen_question").put("screen", id).put("question", question).toString(), updatedAt = System.currentTimeMillis()))
        }
        if (question == null) screenParentReady(dao, id)
        val ownParent = parent?.let { dao.task(it) }?.takeIf { it.conversation == it.hand }
        var reportedOutcome = outcome
        if (question == null && ownParent != null && !pendingWork(dao, ownParent.id)) {
            val history = dao.log(ownParent.id)
            val since = history.lastOrNull { it.kind == TaskLogEntity.Kind.STARTED.name }?.id ?: 0
            val commandFailed = history.any { it.id > since && it.kind == TaskLogEntity.Kind.COMMAND_RESULT.name &&
                runCatching { !JSONObject(it.body).optBoolean("success", true) }.getOrDefault(false) }
            reportedOutcome = if (commandFailed) TaskEntity.Outcome.FAILED else outcome
            dao.updateTask(ownParent.copy(state = TaskEntity.State.ENDED.name, ask = null, progress = null,
                outcome = reportedOutcome.name, updatedAt = System.currentTimeMillis()))
            log(dao, ownParent.id, TaskLogEntity.Kind.END, body)
        }
        val receipt = ownParent?.id ?: id
        Inbox.post(dao, task.conversation, "$REPORT_CUE$receipt · screen · ${if (question != null) "ask" else reportedOutcome.name.lowercase()}] $body", receipt)
        after += { tellChat(context, task.conversation) }
    }

    private suspend fun recoverScreens(context: Context) = change(context) { dao, after ->
        for (task in dao.interruptedScreens()) {
            val words = "The app restarted while the screen was working. Check the current screen and any outward effect before continuing; do not repeat an action whose outcome is unknown."
            val ask = JSONObject().put("kind", "screen_outcome_unknown").put("screen", task.id).put("question", words).toString()
            dao.updateTask(task.copy(state = TaskEntity.State.WAITING.name, ask = ask, updatedAt = System.currentTimeMillis()))
            log(dao, task.id, TaskLogEntity.Kind.REPORT, words)
            val parent = screenSpec(context, task.id).parent?.let { dao.task(it) }
            val owner = parent?.conversation ?: task.conversation
            if (parent != null && parent.state != TaskEntity.State.ENDED.name) dao.updateTask(parent.copy(state = TaskEntity.State.WAITING.name, ask = ask, updatedAt = System.currentTimeMillis()))
            Inbox.post(dao, owner, "$REPORT_CUE${parent?.id ?: task.id} · screen · ask] $words", parent?.id ?: task.id)
            after += { tellChat(context, owner) }
        }
    }

    /** A local command does not turn an unrelated new message into that command's model turn. */
    suspend fun modelTask(context: Context, hand: String): TaskEntity? =
        (context.applicationContext as MinisApp).database.withTransaction {
            val dao = dao(context)
            dao.onHand(hand).firstOrNull { task ->
                task.conversation != hand || (task.ask == null && !pendingWork(dao, task.id))
            }
        }

    internal suspend fun elapsed(context: Context, id: String): Long {
        val dao = dao(context)
        val at = dao.log(id).lastOrNull { it.kind == TaskLogEntity.Kind.STARTED.name }?.at ?: dao.task(id)?.createdAt
        return at?.let { (System.currentTimeMillis() - it).coerceAtLeast(0) } ?: 0
    }

    internal suspend fun commandStarted(context: Context, chat: String, taskAtStart: String?, title: String,
        command: ShellWork.Command,
    ): TaskEntity = change(context) { dao, _ ->
        val existing = taskAtStart?.let { id ->
            dao.task(id)?.also { check(it.state != TaskEntity.State.ENDED.name) { "the task ended before the command was handed on" } }
                ?: error("the task was deleted")
        }
        val source = ChatViewModelStore.existing(chat)?.messages?.value?.lastOrNull { it.rendersAsUserBubble() && !it.unread }
        val now = System.currentTimeMillis()
        val task = existing ?: TaskEntity(newId(), chat, source?.id, title, chat, TaskEntity.State.RUNNING.name,
            createdAt = now, updatedAt = now).also {
            dao.insertTask(it)
            source?.content?.let { words -> log(dao, it.id, TaskLogEntity.Kind.AUTHORIZED, words) }
            log(dao, it.id, TaskLogEntity.Kind.PASSED_ON, command.text)
        }
        log(dao, task.id, TaskLogEntity.Kind.COMMAND, command.json())
        task
    }

    internal suspend fun commandFinished(context: Context, id: String, command: ShellWork.Command,
        result: com.openminis.app.tools.ToolExecutionResult, budgetExpired: Boolean = false,
    ) = change(context) { dao, after ->
        val task = dao.task(id) ?: return@change
        if (task.state == TaskEntity.State.ENDED.name || ShellWork.pending(dao.log(id)).none { it.id == command.id }) return@change
        log(dao, id, TaskLogEntity.Kind.COMMAND_RESULT, JSONObject().put("id", command.id).put("success", result.success).put("output", result.output).toString())
        if (budgetExpired) {
            reportIn(context, dao, after, task.hand, Report.FAILED, result.output, helper = true)
            after += { Helpers.ended(context, task.hand, task.conversation) }
            after += { handIdle(context, task.hand) }
            return@change
        }
        val ownCommand = task.conversation == task.hand
        val completeOwn = ownCommand && !pendingWork(dao, id)
        val kind = if (result.success) "done" else "failed"
        if (completeOwn) {
            dao.updateTask(task.copy(state = TaskEntity.State.ENDED.name,
                outcome = (if (result.success) TaskEntity.Outcome.DONE else TaskEntity.Outcome.FAILED).name,
                updatedAt = System.currentTimeMillis()))
            log(dao, id, TaskLogEntity.Kind.END, result.output)
        }
        val words = "$REPORT_CUE$id · command · ${if (completeOwn) kind else "command_result"}] ${result.output}"
        Inbox.post(dao, task.hand, words, id, sender = InboxEntity.Sender.COMMAND)
        after += { tellChat(context, task.hand) }
    }

    /** A killed process cannot prove a command's effect. Keep it waiting for verification. */
    private suspend fun recoverCommands(context: Context) = change(context) { dao, after ->
        for (task in dao.commandTasks()) {
            val commands = ShellWork.pending(dao.log(task.id))
            if (commands.isEmpty()) continue
            val words = "The app restarted while a command was running; its outcome is unknown. Verify what happened before running it again."
            commands.forEach { log(dao, task.id, TaskLogEntity.Kind.COMMAND_RESULT, JSONObject().put("id", it.id).put("unknown", true).toString()) }
            dao.updateTask(task.copy(state = TaskEntity.State.WAITING.name,
                ask = JSONObject().put("question", words).put("kind", "command_outcome_unknown").toString(),
                updatedAt = System.currentTimeMillis()))
            log(dao, task.id, TaskLogEntity.Kind.REPORT, words)
            Inbox.post(dao, task.conversation, "$REPORT_CUE${task.id} · command · ask] $words", task.id)
            after += { tellChat(context, task.conversation) }
        }
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
        change(context) { dao, after ->
            val current = taskId?.let { dao.task(it) }
            if (current?.hand == hand && current.state == TaskEntity.State.RUNNING.name && !hasFollowUp && !pendingWork(dao, current.id)) {
                reportIn(context, dao, after, hand, if (completion.done) Report.DONE else Report.FAILED, completion.words, helper)
            }
            if (helper && current != null && dao.task(current.id)?.state == TaskEntity.State.ENDED.name) {
                after += { Helpers.ended(context, hand, current.conversation) }
            }
            after += {
                val deferred = kotlinx.coroutines.withContext(Dispatchers.Main) {
                    ChatViewModelStore.existing(hand)?.afterTurn { scope.launch { handIdle(context, hand) } } == true
                }
                if (!deferred) handIdle(context, hand)
            }
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
        val next = task.copy(state = TaskEntity.State.RUNNING.name, updatedAt = System.currentTimeMillis())
        dao.updateTask(next)
        log(dao, task.id, TaskLogEntity.Kind.STARTED, "started")
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
    private suspend fun <T> change(context: Context, block: suspend (TaskDao, MutableList<suspend () -> Unit>) -> T): T {
        val db = (context.applicationContext as MinisApp).database
        val after = mutableListOf<suspend () -> Unit>()
        val result = db.withTransaction { block(db.taskDao(), after) }
        after.forEach { runCatching { it() }.onFailure { e -> AppLogger.warning(TAG, "after commit: ${e.message}") } }
        return result
    }

    private suspend fun log(dao: TaskDao, task: String, kind: TaskLogEntity.Kind, body: String) =
        dao.insertLog(TaskLogEntity(taskId = task, at = System.currentTimeMillis(), kind = kind.name, body = body))

    /** Short enough for a model to repeat back. */
    private fun newId(): String = java.util.UUID.randomUUID().toString().replace("-", "").take(6)

    /** Everything recent, for the debug RPC `task.dump` (walkthroughs and evals read it). */
    suspend fun dump(context: Context, limit: Int = 50): JSONObject {
        val dao = dao(context)
        val tasks = JSONArray()
        for (t in dao.recentTasks(limit)) {
            val log = JSONArray()
            dao.log(t.id).forEach { log.put(JSONObject().put("at", it.at).put("kind", it.kind).put("body", it.body)) }
            tasks.put(
                JSONObject().put("id", t.id).put("conversation", t.conversation).put("source", t.sourceMessageId)
                    .put("title", t.title).put("hand", t.hand).put("state", t.state).put("progress", t.progress)
                    .put("ask", t.ask).put("hold", t.hold).put("outcome", t.outcome).put("log", log),
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
    }
}
