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
        if (kind == Report.DONE && ShellWork.pending(dao.log(task.id)).isNotEmpty()) {
            throw IllegalStateException("A command for this task is still running. Its result will arrive automatically; do not report done yet.")
        }
        if (kind == Report.DONE && dao.unread(hand).isNotEmpty()) {
            throw IllegalStateException("There are unread instructions for this hand. Read them at the next step before reporting done.")
        }
        val now = System.currentTimeMillis()
        val next = when (kind) {
            Report.PROGRESS -> task.copy(progress = text.lineSequence().first().take(120))
            Report.ASK -> task.copy(state = TaskEntity.State.WAITING.name, ask = JSONObject().put("question", text).toString())
            Report.DONE, Report.FAILED -> task.copy(
                state = TaskEntity.State.ENDED.name, ask = null,
                outcome = (if (kind == Report.DONE) TaskEntity.Outcome.DONE else TaskEntity.Outcome.FAILED).name,
            )
        }.copy(updatedAt = now)
        dao.updateTask(next)
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
        val tasks = dao.relatedTo(ids)
        val unfinished = tasks.filter { it.state != TaskEntity.State.ENDED.name }
        for (task in unfinished) {
            ShellWork.pending(dao.log(task.id)).forEach { command -> after += { ShellWork.stop(command.process) } }
            val words = "Stopped because the conversation or its hand was deleted."
            dao.updateTask(task.copy(state = TaskEntity.State.ENDED.name, outcome = TaskEntity.Outcome.STOPPED.name,
                ask = null, updatedAt = System.currentTimeMillis()))
            log(dao, task.id, TaskLogEntity.Kind.END, words)
            dao.deleteTaskInbox(task.id)
            if (task.conversation !in ids) {
                Inbox.post(dao, task.conversation, "$REPORT_CUE${task.id} · ${handName(context, task.hand)} · stopped] $words", task.id)
                after += { tellChat(context, task.conversation) }
            }
        }
        // The FK cascades remove messages, inbox and tasks owned by these sessions.
        for (id in ids) app.database.chatDao().deleteSession(id)
        DeletedSessions(ids, tasks, unfinished.size)
    }

    /** A local command does not turn an unrelated new message into that command's model turn. */
    suspend fun modelTask(context: Context, hand: String): TaskEntity? =
        (context.applicationContext as MinisApp).database.withTransaction {
            val dao = dao(context)
            dao.onHand(hand).firstOrNull { task ->
                task.conversation != hand || (task.ask == null && ShellWork.pending(dao.log(task.id)).isEmpty())
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
        val kind = if (result.success) "done" else "failed"
        if (ownCommand && ShellWork.pending(dao.log(id)).isEmpty()) {
            dao.updateTask(task.copy(state = TaskEntity.State.ENDED.name,
                outcome = (if (result.success) TaskEntity.Outcome.DONE else TaskEntity.Outcome.FAILED).name,
                updatedAt = System.currentTimeMillis()))
            log(dao, id, TaskLogEntity.Kind.END, result.output)
        }
        val words = "$REPORT_CUE$id · command · ${if (ownCommand) kind else "command_result"}] ${result.output}"
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
    fun handName(context: Context, hand: String): String = MoApps.ofSession(context, hand)?.title ?: "helper"

    /**
     * The loop that actually ran hands back its result, tied to the task it started on. An
     * explicit report already settled it; unread follow-ups are still owed another loop.
     * Never infer success from a session becoming idle or from an older transcript message.
     */
    suspend fun turnFinished(context: Context, hand: String, taskId: String?, completion: TaskCompletion, hasFollowUp: Boolean) {
        val helper = Helpers.isHelper(context, hand)
        change(context) { dao, after ->
            val current = taskId?.let { dao.task(it) }
            if (current?.hand == hand && current.state == TaskEntity.State.RUNNING.name && !hasFollowUp && ShellWork.pending(dao.log(current.id)).isEmpty()) {
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
