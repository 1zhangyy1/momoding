package app.momoding.task

import android.content.Context
import androidx.room.withTransaction
import com.openminis.app.logging.AppLogger
import com.openminis.app.sandbox.ExecutionCoordinator
import com.openminis.app.tools.ToolExecutionResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** A shell keeps its existing process; after ten seconds its task owns the wait and result.
 * Live process handles retain their original invocation; task state and receipts live in Room. */
object ShellWork {
    const val FOREGROUND_MS = 10_000L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private data class Run(val execution: Deferred<ToolExecutionResult>, val call: ToolCall?)
    private val runs = ConcurrentHashMap<String, Run>()

    internal fun toolCall(process: String?): ToolCall? = process?.let { runs[it]?.call }

    data class Command(val id: String, val process: String, val text: String, val call: ToolCall? = null) {
        fun json(): String = JSONObject().put("id", id).put("process", process).put("text", text)
            .apply { call?.let { put("tool", it.json()) } }.toString()

        /** The exact saved arguments, only while this command still matches its original invocation. */
        internal suspend fun savedArgs(dao: TaskDao, session: String): String? = runCatching {
            val source = call?.takeIf { it.name == "shell_execute" } ?: return null
            val parts = org.json.JSONArray(dao.toolMessage(session, source.message) ?: return null)
            val value = (0 until parts.length()).map { parts.getJSONObject(it) }
                .filter { it.optString("type") == "toolUse" }.map { it.getJSONObject("value") }
                .singleOrNull { it.optString("name") == source.name &&
                    com.openminis.app.provider.ToolPairing.key(it.optString("toolUseId")) == com.openminis.app.provider.ToolPairing.key(source.id) }
                ?: return null
            value.getString("input").takeIf { JSONObject(it).optString("command") == text }
        }.getOrNull()

        companion object {
            fun parse(text: String): Command? = runCatching {
                val row = JSONObject(text)
                Command(row.get("id") as String, row.get("process") as String, row.get("text") as String,
                    row.optJSONObject("tool")?.let { runCatching { ToolCall.parse(it) }.getOrNull() })
                    .also { require(it.id.isNotBlank() && it.process.isNotBlank() && it.text.isNotBlank()) }
            }.getOrNull()
        }
    }

    internal fun pending(log: List<TaskLogEntity>): List<Command> =
        TaskReceipt.pending(log, TaskLogEntity.Kind.COMMAND, TaskLogEntity.Kind.COMMAND_RESULT).mapNotNull { Command.parse(it.row.body) }

    suspend fun run(context: Context, chat: String, taskAtStart: String?, title: String, text: String,
        permit: Approval.Permit? = null,
        action: TaskAction? = null,
        execute: suspend (process: String, foreground: () -> Boolean) -> ToolExecutionResult,
    ): ToolExecutionResult {
        val id = java.util.UUID.randomUUID().toString()
        val command = action?.command ?: permit?.command ?: Command(id, "$chat~command~$id", text,
            kotlinx.coroutines.currentCoroutineContext()[ToolCall])
        val registered = action?.taskId ?: permit?.spec?.request?.taskId
        val outward = action != null || permit != null
        val foreground = AtomicBoolean(true)
        val budgetExpired = AtomicBoolean(false)
        val remaining = if (taskAtStart != null && Helpers.isHelper(context, chat)) {
            app.momoding.prompt.MomodingHelper.MAX_MINUTES * 60_000L +
                com.openminis.app.agent.jobs.HelperRunner.WRAP_UP_GRACE_MS - TaskEngine.elapsed(context, taskAtStart)
        } else null
        val execution = scope.async(start = CoroutineStart.LAZY) {
            if (registered != null) {
                val allowedWaiting = Approval.pending(TaskEngine.dao(context).task(registered)?.ask).isNotEmpty()
                TaskGuard.check(context, TaskGuard.Binding(chat, registered), outward = true, allowWaiting = allowedWaiting)
                    ?.let { return@async ToolExecutionResult(it, false) }
            }
            if (remaining == null) execute(command.process, foreground::get)
            else (if (remaining <= 0) null else withTimeoutOrNull(remaining) { execute(command.process, foreground::get) }) ?: run {
                budgetExpired.set(true)
                ExecutionCoordinator.stopCurrentCommand(command.process)
                ToolExecutionResult("The helper's time budget was exhausted while its command was running.", false, timedOut = true)
            }
        }
        val run = Run(execution, kotlinx.coroutines.currentCoroutineContext()[ToolCall])
        runs[command.process] = run
        execution.invokeOnCompletion { runs.remove(command.process, run) }
        execution.start()
        var handedOn = false
        var settled = false
        try {
            withTimeoutOrNull(FOREGROUND_MS) { execution.await() }?.let {
                if (registered != null) withContext(NonCancellable) {
                    TaskEngine.commandFinished(context, registered, command, it, announce = false, unknown = outward && it.timedOut)
                }
                settled = true
                return it
            }
            foreground.set(false)
            return withContext(NonCancellable) {
                val task = if (registered == null) TaskEngine.commandStarted(context, chat, taskAtStart, title, command)
                    else requireNotNull(TaskEngine.dao(context).task(registered)).also { check(it.state != TaskEntity.State.ENDED.name) { "The task ended before its command was handed on." } }
                com.openminis.app.service.SessionActivityTracker.keepBackgroundWork(command.process, chat)
                scope.launch {
                    val observed = runCatching { execution.await() }
                    val result = observed.getOrElse {
                        ToolExecutionResult("The command was interrupted: ${it.message}", false)
                    }
                    try {
                        runCatching { TaskEngine.commandFinished(context, task.id, command, result, budgetExpired.get(),
                            unknown = outward && (observed.isFailure || result.timedOut)) }
                            .onFailure { AppLogger.warning("ShellWork", "completion ${task.id}: ${it.message}") }
                    } finally {
                        com.openminis.app.service.SessionActivityTracker.finishBackgroundWork(command.process)
                        ExecutionCoordinator.sessionDidTerminate(command.process)
                    }
                }
                handedOn = true
                ToolExecutionResult(
                    "The command is still running for task ${task.id}. Its result will arrive here automatically. " +
                        "Do not run it again or wait/poll for it; you can answer other messages meanwhile.",
                    true, toolTitle = title,
                )
            }
        } finally {
            foreground.set(false)
            if (!handedOn && !execution.isCompleted) withContext(NonCancellable) { stop(command.process) }
            if (!handedOn && !settled && registered != null) withContext(NonCancellable) {
                TaskEngine.commandFinished(context, registered, command,
                    ToolExecutionResult("No command result was observed before its caller stopped.", false), announce = false, unknown = outward)
            }
            if (!handedOn) ExecutionCoordinator.sessionDidTerminate(command.process)
        }
    }

    suspend fun stop(process: String) {
        val execution = runs[process]?.execution
        execution?.cancel()
        ExecutionCoordinator.stopCurrentCommand(process)
        execution?.join()
        ExecutionCoordinator.sessionDidTerminate(process)
    }
}
