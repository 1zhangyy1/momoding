package app.momoding.task

import android.content.Context
import app.momoding.apps.MoApps
import com.openminis.app.sandbox.NativeOffloadHandler
import com.openminis.app.sandbox.NativeOffloadRequest
import com.openminis.app.sandbox.NativeOffloadResult
import com.openminis.app.sandbox.offload.OffloadArgs
import kotlinx.coroutines.runBlocking
import org.json.JSONObject

/**
 * `momoding-task` — work that takes more than a step, from the shell (调研/M39):
 *
 *     momoding-task new --to helper|bot:<name> --title "<a few words>" "<what to do>"   (a chat) hand work on
 *     momoding-task tell <id> "<words>"                                          (a chat) more for that hand
 *     momoding-task report progress|ask|done|failed "<words>"                    (a hand) on the task it is on
 *
 * Who may use which is decided by the calling session: a chat hands work on, a hand reports.
 */
class TaskOffloadHandler(private val context: Context) : NativeOffloadHandler {

    override fun handle(request: NativeOffloadRequest): NativeOffloadResult {
        val args = OffloadArgs(request.argv.drop(1))
        val sid = request.sessionId ?: return NativeOffloadResult(2, "momoding-task: no chat to act for\n")
        return runCatching {
            when (val verb = args.positional.firstOrNull()) {
                null, "help", "--help", "-h" -> NativeOffloadResult(0, HELP)
                "new" -> new(args, sid, ShellWork.toolCall(request.env["MINIS_EXECUTION_ID"]))
                "tell" -> tell(args, sid)
                "stop" -> stop(args, sid)
                "report" -> report(args, sid)
                else -> NativeOffloadResult(2, "momoding-task: unknown verb '$verb'\n$HELP")
            }
        }.getOrElse { NativeOffloadResult(2, "momoding-task: ${it.message}\n") }
    }

    private fun new(args: OffloadArgs, sid: String, call: ToolCall?): NativeOffloadResult {
        if (MoApps.ofSession(context, sid) != null) throw IllegalStateException("an app's Momoding hands no work on: do it yourself, or say what is needed")
        if (runBlocking { Helpers.isHelper(context, sid) }) throw IllegalStateException("a helper hands no work on: do it yourself")
        val to = args.get("to")?.trim().orEmpty()
        val brief = args.positional.drop(1).joinToString(" ").trim()
        require(brief.isNotEmpty()) { "momoding-task new --to helper|bot:<name> --title \"…\" \"<what to do>\"" }
        val title = args.get("title")?.trim().orEmpty().ifEmpty { brief.lineSequence().first().take(30) }
        val task = when {
            to == "helper" -> {
                check(com.openminis.app.tools.AgentToolSwitch.AGENTS.isEnabled(context)) { "helpers are turned off in settings" }
                runBlocking { TaskEngine.openHelper(context, sid, title, brief, call) }
            }
            to.startsWith("bot:") -> {
                val app = MoApps.get(context, to.removePrefix("bot:")) ?: throw IllegalArgumentException("no Momoding called ${to.removePrefix("bot:")} (momoding-app list)")
                val hand = app.botSession ?: throw IllegalStateException("${app.name} has no chat of its own")
                runBlocking { TaskEngine.open(context, sid, title, hand, brief, call = call) }
            }
            else -> throw IllegalArgumentException("--to helper | bot:<name>")
        }
        return ok(JSONObject().put("task", task.id).put("state", task.state.lowercase())
            .put("note", "It has its own line in this chat and reports back here when there is a result, a question or a problem; no need to wait or check."))
    }

    private fun tell(args: OffloadArgs, sid: String): NativeOffloadResult {
        val id = args.positional.getOrNull(1) ?: throw IllegalArgumentException("momoding-task tell <id> \"<words>\"")
        val text = args.positional.drop(2).joinToString(" ").trim()
        require(text.isNotEmpty()) { "momoding-task tell $id \"<words>\"" }
        val owner = runBlocking { TaskEngine.dao(context).task(id) }?.conversation
        if (owner != null && owner != sid) throw IllegalArgumentException("task $id belongs to another chat")
        val task = runBlocking { TaskEngine.tell(context, id, text) }
        return ok(JSONObject().put("task", task.id).put("state", task.state.lowercase()))
    }

    private fun report(args: OffloadArgs, sid: String): NativeOffloadResult {
        val kind = args.positional.getOrNull(1)?.let { k -> TaskEngine.Report.entries.firstOrNull { it.name.equals(k, true) } }
            ?: throw IllegalArgumentException("momoding-task report progress|ask|done|failed \"<words>\"")
        val text = args.positional.drop(2).joinToString(" ").trim()
        require(text.isNotEmpty()) { "momoding-task report ${kind.name.lowercase()} \"<words>\"" }
        val task = runBlocking { TaskEngine.report(context, sid, kind, text) }
        return ok(JSONObject().put("task", task.id).put("state", task.state.lowercase()))
    }

    private fun stop(args: OffloadArgs, sid: String): NativeOffloadResult {
        val id = args.positional.getOrNull(1) ?: throw IllegalArgumentException("momoding-task stop <id>")
        val task = runBlocking { TaskEngine.dao(context).task(id) } ?: throw IllegalArgumentException("no task $id")
        require(task.conversation == sid) { "task $id belongs to another chat" }
        val stopped = runBlocking { TaskEngine.stop(context, id) } ?: error("the task was deleted")
        return ok(JSONObject().put("task", id).put("state", stopped.state.lowercase()).put("outcome", stopped.outcome?.lowercase()))
    }

    private fun ok(o: JSONObject) = NativeOffloadResult(0, o.toString(2) + "\n")

    companion object {
        private val HELP = """
            momoding-task — work that takes more than a step: each task has its own line in the chat
            that handed it on, and its hand works on one task at a time (the next waits its turn).

            From a chat:
              stop <id>          stop this task and its work; a stopped task does not resume
              new --to helper --title "<a few words>" "<a complete brief>"
                                   a helper: its own chat, no memory, nothing of this one; it works
                                   on its own and its final answer comes back here. At most 3 at
                                   once, the rest wait their turn
              new --to bot:<name> --title "<a few words>" "<what to do>"
                                   hand work to that Momoding; it reports back to this chat
              tell <id> "<words>"  more for the task's hand (the person's change of mind, their answer
                                   to its question); a task that is done picks up again

            From a hand, on the task it is on:
              report progress "<where it stands>"   updates the task's line; sends no message
              report ask "<question>"               the chat asks the person
              report done "<what came of it>"
              report failed "<why>"
        """.trimIndent() + "\n"
    }
}
