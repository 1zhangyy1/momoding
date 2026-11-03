package app.momoding.task

import android.content.Context
import androidx.room.withTransaction
import app.momoding.apps.AppBots
import app.momoding.apps.MoApps
import com.openminis.app.MinisApp
import com.openminis.app.debug.HeadlessChatRunner
import com.openminis.app.logging.AppLogger
import com.openminis.app.service.SessionActivityTracker
import com.openminis.app.ui.chat.ChatViewModelStore
import com.openminis.app.ui.chat.QueuedPromptOrigin
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

    /** At startup: keep [recent] current, and close a task whose hand stopped without a report. */
    fun start(context: Context) {
        val app = context.applicationContext
        scope.launch { dao(app).recentFlow(200).collect { _recent.value = it } }
        scope.launch {
            var before = emptySet<String>()
            SessionActivityTracker.activeSessions.collect { now ->
                (before - now).forEach { settle(app, it) }
                before = now
            }
        }
    }

    /** The task the person's words started: a new one for [hand], or in line behind the one it is on. */
    suspend fun open(context: Context, conversation: String, title: String, hand: String, brief: String): TaskEntity {
        val source = ChatViewModelStore.existing(conversation)?.messages?.value
            ?.lastOrNull { it.rendersAsUserBubble() && !it.isQueued }
        return change(context) { dao, after ->
            val busy = dao.heldBy(hand) != null
            val now = System.currentTimeMillis()
            val task = TaskEntity(
                id = newId(), conversation = conversation, sourceMessageId = source?.id, title = title.trim(), hand = hand,
                state = (if (busy) TaskEntity.State.QUEUED else TaskEntity.State.RUNNING).name, createdAt = now, updatedAt = now,
            )
            dao.insertTask(task)
            source?.content?.let { log(dao, task.id, TaskLogEntity.Kind.AUTHORIZED, it) }
            log(dao, task.id, TaskLogEntity.Kind.PASSED_ON, brief)
            if (!busy) after += { deliver(context, hand, brief) }
            task
        }
    }

    /**
     * More words for a task's hand. It reads them before its next step when it is at work; they
     * answer what it waits on; a task that is done or did not work picks up again with the same hand.
     */
    suspend fun tell(context: Context, id: String, text: String): TaskEntity = change(context) { dao, after ->
        val task = dao.task(id) ?: throw IllegalArgumentException("no task $id (the table of tasks lists them)")
        if (task.outcome == TaskEntity.Outcome.STOPPED.name) throw IllegalStateException("task $id was stopped: open a new one")
        log(dao, id, TaskLogEntity.Kind.PASSED_ON, text)
        val state = when (TaskEntity.State.valueOf(task.state)) {
            TaskEntity.State.QUEUED -> TaskEntity.State.QUEUED // read when it starts, with the brief
            TaskEntity.State.ENDED -> if (dao.heldBy(task.hand) != null) TaskEntity.State.QUEUED else TaskEntity.State.RUNNING
            else -> TaskEntity.State.RUNNING
        }
        if (state == TaskEntity.State.RUNNING) after += { deliver(context, task.hand, text) }
        task.copy(state = state.name, ask = null, outcome = null, updatedAt = System.currentTimeMillis()).also { dao.updateTask(it) }
    }

    /** What [hand] says of the task it is on. Progress only changes the task's row; the rest wake its conversation. */
    suspend fun report(context: Context, hand: String, kind: Report, text: String): TaskEntity = change(context) { dao, after ->
        val task = dao.heldBy(hand) ?: throw IllegalStateException("you are on no task: just answer in your chat")
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
            after += { deliver(context, task.conversation, words, QueuedPromptOrigin.PROGRAMMATIC) }
            if (next.state == TaskEntity.State.ENDED.name) {
                after += { MoApps.ofSession(context, hand)?.let { AppBots.reported(context, it, task.conversation, text) } }
                startNext(context, dao, hand, after)
            }
        }
        next
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
            append("## Tasks\nWork handed on from this chat (`momoding-task tell <id> \"…\"` passes words to its hand):\n")
            (open + ended).forEach { t ->
                append("- ").append(t.id).append(" · ").append(handName(context, t.hand)).append(" · ").append(t.title).append(" · ")
                append(t.outcome?.lowercase() ?: t.state.lowercase())
                t.progress?.takeIf { t.state == TaskEntity.State.RUNNING.name }?.let { append(" (").append(it).append(")") }
                t.ask?.let { runCatching { JSONObject(it).optString("question") }.getOrNull() }?.let { append(" — asks: ").append(it) }
                append("\n")
            }
        }.trimEnd()
    }

    /** The name the person knows a hand by. */
    fun handName(context: Context, hand: String): String = MoApps.ofSession(context, hand)?.title ?: hand

    /** A hand's turn ended while its task is still on: what it said last is its report, so no task hangs. */
    private suspend fun settle(context: Context, hand: String) {
        val task = dao(context).heldBy(hand)?.takeIf { it.state == TaskEntity.State.RUNNING.name } ?: return
        if (ChatViewModelStore.existing(hand)?.isStreaming?.value == true) return
        val last = lastWords(context, hand)
        AppLogger.info(TAG, "${task.id}: ${hand.take(8)} stopped without a report — its last words stand in")
        // not a report: the chat's Momoding judges from the words what came of it
        val words = last?.let { "it stopped without a report; what it said last: $it" } ?: "it stopped without a word"
        runCatching { report(context, hand, if (last != null) Report.DONE else Report.FAILED, words) }
            .onFailure { AppLogger.warning(TAG, "settle ${task.id}: ${it.message}") }
    }

    private suspend fun lastWords(context: Context, session: String): String? {
        val repo = (context.applicationContext as MinisApp).chatRepository
        val total = repo.messageCount(session)
        return repo.loadMessagePage(session, offset = maxOf(0, total - 6), limit = 6, maxChars = 2000, includeTools = true)
            .lastOrNull { it.role == "assistant" && it.text.isNotBlank() }?.text?.trim()
    }

    /** The next task in line for [hand], started once this change commits. */
    private suspend fun startNext(context: Context, dao: TaskDao, hand: String, after: MutableList<suspend () -> Unit>) {
        val next = dao.nextFor(hand) ?: return
        dao.updateTask(next.copy(state = TaskEntity.State.RUNNING.name, updatedAt = System.currentTimeMillis()))
        val brief = dao.log(next.id).filter { it.kind == TaskLogEntity.Kind.PASSED_ON.name }.joinToString("\n\n") { it.body }
        after += { deliver(context, hand, brief) }
    }

    /**
     * Words for a session: into its turn when it is at work, else a new turn. A hand's own work
     * comes from Momoding ([AppBots.WAKE_CUE]) and goes in at its next step, like the person's words.
     */
    private suspend fun deliver(context: Context, to: String, text: String, origin: QueuedPromptOrigin = QueuedPromptOrigin.USER) {
        val words = if (origin == QueuedPromptOrigin.USER && MoApps.ofSession(context, to) != null) "${AppBots.WAKE_CUE} $text" else text
        runCatching { HeadlessChatRunner.prompt(context.applicationContext, to, words, wait = false, timeoutMs = 0, origin = origin) }
            .onFailure { AppLogger.warning(TAG, "deliver to ${to.take(8)}: ${it.message}") }
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
                    .put("text", it.text).put("arrived", it.arrivedAt).put("read", it.readAt),
            )
        }
        return JSONObject().put("tasks", tasks).put("inbox", inbox)
    }
}
