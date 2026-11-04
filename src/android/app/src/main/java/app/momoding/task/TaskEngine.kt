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
    private val _recent = MutableStateFlow<List<TaskEntity>>(emptyList())
    /** The newest tasks of every conversation, newest change first; read by the rows and the main chat's table. */
    val recent: StateFlow<List<TaskEntity>> = _recent.asStateFlow()

    fun dao(context: Context): TaskDao = (context.applicationContext as MinisApp).database.taskDao()

    /** Keep the UI's view of the persisted tasks current. A loop reports its own end explicitly. */
    fun start(context: Context) {
        val app = context.applicationContext
        scope.launch { dao(app).recentFlow(200).collect { _recent.value = it } }
    }

    /** The task the person's words started: a new one for [hand], or in line behind the one it is on. */
    suspend fun open(context: Context, conversation: String, title: String, hand: String, brief: String): TaskEntity {
        val source = ChatViewModelStore.existing(conversation)?.messages?.value
            ?.lastOrNull { it.rendersAsUserBubble() && !it.unread }
        val helper = Helpers.isHelper(context, hand)
        return change(context) { dao, after ->
            val busy = !free(dao, hand, helper)
            val now = System.currentTimeMillis()
            val task = TaskEntity(
                id = newId(), conversation = conversation, sourceMessageId = source?.id, title = title.trim(), hand = hand,
                state = (if (busy) TaskEntity.State.QUEUED else TaskEntity.State.RUNNING).name, createdAt = now, updatedAt = now,
            )
            dao.insertTask(task)
            source?.content?.let { log(dao, task.id, TaskLogEntity.Kind.AUTHORIZED, it) }
            log(dao, task.id, TaskLogEntity.Kind.PASSED_ON, brief)
            if (!busy) {
                postForHand(context, dao, task, brief)
                after += { deliver(context, task) }
            }
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
        if (state == TaskEntity.State.RUNNING) {
            if (task.state == TaskEntity.State.ENDED.name) dao.retryTaskInbox(task.id, task.hand)
            postForHand(context, dao, next, text)
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
        _recent.value.firstOrNull { it.hand == hand && it.state != TaskEntity.State.ENDED.name && it.state != TaskEntity.State.QUEUED.name }?.conversation

    /**
     * The main chat's table of tasks, at the end of every request: what is open, and what ended
     * lately (it can be picked up again with tell), so the model never has to look them up.
     */
    fun table(context: Context, conversation: String): String? {
        val mine = _recent.value.filter { it.conversation == conversation }
        val open = mine.filter { it.state != TaskEntity.State.ENDED.name }
        val ended = mine.filter { it.state == TaskEntity.State.ENDED.name }.take(3)
        if (open.isEmpty() && ended.isEmpty()) return null
        return buildString {
            append("## Tasks\nWork handed on from this chat. A message starting $REPORT_CUE<id> · <hand> · done|ask|failed] is that task's report, not the person: ")
            append("tell the person what came of it in your own words, in a line or two (with a link to open a Momoding's app when there is something to see); for an ask, ask the person. ")
            append("Their answer, or anything more they want of a task, goes to its hand with `momoding-task tell <id> \"<their words>\"`.\n")
            (open + ended).forEach { t ->
                append("- ").append(t.id).append(" · ").append(handName(context, t.hand)).append(" · ").append(t.title).append(" · ")
                append(t.outcome?.lowercase() ?: t.state.lowercase())
                t.progress?.takeIf { t.state == TaskEntity.State.RUNNING.name }?.let { append(" (").append(it).append(")") }
                t.ask?.let { runCatching { JSONObject(it).optString("question") }.getOrNull() }?.let { append(" — asks: ").append(it) }
                append("\n")
            }
        }.trimEnd()
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
            if (current?.hand == hand && current.state == TaskEntity.State.RUNNING.name && !hasFollowUp) {
                reportIn(context, dao, after, hand, if (completion.done) Report.DONE else Report.FAILED, completion.words, helper)
            }
            if (helper && current != null && dao.task(current.id)?.state == TaskEntity.State.ENDED.name) {
                after += { Helpers.ended(context, hand, current.conversation) }
            }
            advance(context, dao, after, hand, helper, released = true)
        }
    }

    /** A busy hand may receive queued work just as its loop exits. Only dispatch is inferred
     * from idleness; outcomes always come from the loop's explicit closeout. */
    suspend fun handIdle(context: Context, hand: String) {
        val helper = Helpers.isHelper(context, hand)
        change(context) { dao, after -> advance(context, dao, after, hand, helper, released = false) }
    }

    private suspend fun advance(context: Context, dao: TaskDao, after: MutableList<suspend () -> Unit>, hand: String, helper: Boolean, released: Boolean) {
        if (dao.heldBy(hand) != null || (!released && ChatViewModelStore.existing(hand)?.isStreaming?.value == true)) return
        val next = if (helper && dao.helpersAtWork() < Helpers.SLOTS) dao.queuedHelpers().firstOrNull {
            dao.heldBy(it.hand) == null && (it.hand == hand && released || ChatViewModelStore.existing(it.hand)?.isStreaming?.value != true)
        } else if (!helper) dao.nextFor(hand) else null
        next?.let { start(dao, it, after, context) }
    }

    /** Whether [hand] can start a task now: it holds none, and a helper has a slot. */
    private suspend fun free(dao: TaskDao, hand: String, helper: Boolean) =
        dao.heldBy(hand) == null && ChatViewModelStore.existing(hand)?.isStreaming?.value != true &&
            (!helper || dao.helpersAtWork() < Helpers.SLOTS)

    /** A task that waited its turn starts once this change commits, with everything passed on for it. */
    private suspend fun start(dao: TaskDao, task: TaskEntity, after: MutableList<suspend () -> Unit>, context: Context) {
        val next = task.copy(state = TaskEntity.State.RUNNING.name, updatedAt = System.currentTimeMillis())
        dao.updateTask(next)
        val brief = dao.log(task.id).filter { it.kind == TaskLogEntity.Kind.PASSED_ON.name }.joinToString("\n\n") { it.body }
        dao.retryTaskInbox(task.id, task.hand)
        postForHand(context, dao, next, brief)
        after += { deliver(context, next) }
    }

    /**
     * Words for a task's hand: into its turn at its next step when it is at work, else a new turn.
     * A Bot reads them as from Momoding ([AppBots.WAKE_CUE]); a helper is set up as one first.
     */
    private suspend fun postForHand(context: Context, dao: TaskDao, task: TaskEntity, text: String) {
        val words = if (MoApps.ofSession(context, task.hand) != null) "${AppBots.WAKE_CUE} $text" else text
        Inbox.post(dao, task.hand, words, task.id)
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
