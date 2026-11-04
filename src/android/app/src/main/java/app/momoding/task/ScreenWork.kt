package app.momoding.task

import android.content.Context
import app.momoding.hands.Hands
import app.momoding.hands.HandsOperator
import app.momoding.hands.HandsPrompt
import app.momoding.hands.HandsDisplay
import com.openminis.app.logging.AppLogger
import com.openminis.app.service.SessionActivityTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** The one physical screen drains the persisted task queue. Only live operators are cached. */
object ScreenWork {
    data class Spec(val options: HandsOperator.Options, val parent: String? = null) {
        fun json(): String = with(options) {
            JSONObject().put("task", task).put("app", appHint).put("steps", maxSteps)
                .put("session", sessionId).put("agent", agentName).put("language", language)
                .put("mode", mode.name).put("background", background).put("textSteps", textSteps)
                .put("modelEntry", modelEntryId)
                .put("parent", parent).toString()
        }
        companion object {
            fun parse(json: String): Spec {
                val o = JSONObject(json)
                return Spec(HandsOperator.Options(task = o.getString("task"),
                    appHint = o.optString("app").ifBlank { null }, maxSteps = o.getInt("steps"),
                    sessionId = o.getString("session"), agentName = o.getString("agent"), language = o.getString("language"),
                    mode = HandsPrompt.Mode.valueOf(o.getString("mode")), background = o.getBoolean("background"),
                    textSteps = o.getBoolean("textSteps"), modelEntryId = o.optString("modelEntry").ifBlank { null }), o.optString("parent").ifBlank { null })
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val started = AtomicBoolean(false)
    private val wakes = Channel<Unit>(Channel.CONFLATED)
    private val operators = ConcurrentHashMap<String, HandsOperator>()
    private val finished = ConcurrentHashMap<String, CompletableDeferred<Unit>>()

    fun start(context: Context) {
        if (!started.compareAndSet(false, true)) return
        val app = context.applicationContext
        scope.launch { Hands.active.collect { if (!it) wake() } }
        scope.launch {
            for (signal in wakes) {
                while (true) {
                    val task = runCatching { TaskEngine.takeScreen(app) }
                        .onFailure { AppLogger.warning("ScreenWork", "claim: ${it.message}") }.getOrNull() ?: break
                    var operator: HandsOperator? = null
                    var budget: Job? = null
                    val done = CompletableDeferred<Unit>()
                    finished[task.id] = done
                    try {
                        val spec = TaskEngine.screenSpec(app, task.id)
                        if (!Hands.usable(app)) {
                            TaskEngine.screenFinished(app, task.id, HandsOperator.Result(
                                HandsOperator.Outcome.NeedsUser("Operating the screen is not ready. Check Settings → Hands, then continue this task."),
                                0, task.id, null, null, emptyList(), null))
                            continue
                        }
                        operator = HandsOperator(app)
                        operators[task.id] = operator
                        if (TaskEngine.dao(app).task(task.id)?.state != TaskEntity.State.RUNNING.name) continue
                        SessionActivityTracker.keepBackgroundWork("screen:${task.id}", task.conversation) {
                            requestStop(app, task.id, "stopped from the notification")
                        }
                        budget = watchParentBudget(app, task.id, spec.parent)
                        val history = TaskEngine.dao(app).log(task.id)
                        val log = history.filter { it.kind == TaskLogEntity.Kind.PASSED_ON.name }
                        val previous = history.lastOrNull { it.kind == TaskLogEntity.Kind.REPORT.name || it.kind == TaskLogEntity.Kind.SCREEN_RESULT.name }
                        if (previous != null) HandsDisplay.release()
                        val options = spec.options.copy(task = log.joinToString("\n\n") { it.body } +
                            (previous?.let { "\nPrevious run: ${it.body}\nInspect the current screen; do not repeat completed outward actions." } ?: ""), taskId = task.id,
                            appHint = if (previous == null) spec.options.appHint else null,
                            background = previous == null && spec.options.background, instructionCursor = log.lastOrNull()?.id ?: 0)
                        val result = operator.run(options)
                        operators.remove(task.id, operator)
                        TaskEngine.screenFinished(app, task.id, result)
                    } catch (e: Throwable) {
                        AppLogger.warning("ScreenWork", "run ${task.id}: ${e.message}")
                        runCatching { TaskEngine.screenFinished(app, task.id, HandsOperator.Result(
                            HandsOperator.Outcome.Failed(e.message ?: e.javaClass.simpleName), 0, task.id, null, null, emptyList(), null)) }
                            .onFailure { AppLogger.warning("ScreenWork", "closeout ${task.id}: ${it.message}") }
                    } finally {
                        budget?.cancel()
                        if (operator != null) operators.remove(task.id, operator)
                        Hands.setActive(false)
                        SessionActivityTracker.finishBackgroundWork("screen:${task.id}")
                        done.complete(Unit)
                        finished.remove(task.id, done)
                    }
                }
            }
        }
        wake()
    }

    fun wake() { wakes.trySend(Unit) }

    /** Used only after the task's stop committed. The lease stays held until run() returns. */
    fun stop(id: String, reason: String) { operators[id]?.requestStop(reason) }
    fun isRunning(id: String): Boolean = operators.containsKey(id)
    suspend fun stopAndJoin(id: String, reason: String) {
        val done = finished[id]
        stop(id, reason)
        done?.await()
    }

    fun requestStop(context: Context, id: String, reason: String) {
        scope.launch {
            val parent = runCatching { TaskEngine.screenSpec(context, id).parent }.getOrNull()
            TaskEngine.stop(context, parent ?: id, reason)
        }
    }

    fun answer(context: Context, id: String) {
        scope.launch { TaskEngine.tell(context, id, "The person says to continue; inspect the screen as it is now.") }
    }

    fun continueIfRunning(id: String): Boolean = operators[id]?.let { it.continueAfterUser(); true } ?: false

    private fun watchParentBudget(context: Context, screen: String, parentId: String?): Job = scope.launch {
        val dao = TaskEngine.dao(context)
        val parent = parentId?.let { dao.task(it) } ?: return@launch
        if (!Helpers.isHelper(context, parent.hand)) return@launch
        fun paused(task: TaskEntity?): Boolean = task?.state == TaskEntity.State.WAITING.name &&
            task.ask?.let { runCatching { JSONObject(it).optString("screen") }.getOrNull() } == screen
        while (true) {
            val current = dao.task(parent.id) ?: return@launch
            if (current.state == TaskEntity.State.ENDED.name) return@launch
            if (paused(current)) {
                dao.taskFlow(parent.id).first { !paused(it) }
                continue
            }
            val remaining = app.momoding.prompt.MomodingHelper.MAX_MINUTES * 60_000L +
                com.openminis.app.agent.jobs.HelperRunner.WRAP_UP_GRACE_MS - TaskEngine.elapsed(context, parent.id)
            val changed = if (remaining <= 0) null else withTimeoutOrNull(remaining) {
                dao.taskFlow(parent.id).first { it == null || it.state == TaskEntity.State.ENDED.name || paused(it) }
            }
            if (changed == null) {
                TaskEngine.stop(context, parent.id, "The helper's time budget was exhausted while using the screen.", TaskEntity.Outcome.FAILED)
                return@launch
            }
        }
    }

    fun resultJson(result: HandsOperator.Result): JSONObject {
        val out = JSONObject().put("ok", result.outcome is HandsOperator.Outcome.Done).put("outcome", result.outcome.code)
            .put("steps", result.steps).put("run_id", result.runId).put("model", result.model)
            .put("last_screen", result.lastScreen).put("trace", result.tracePath)
            .put("log", org.json.JSONArray(result.log)).put("done", org.json.JSONArray(result.done))
            .put("instructions_through", result.instructionsThrough)
        when (val outcome = result.outcome) {
            is HandsOperator.Outcome.Done -> out.put("answer", outcome.answer)
            is HandsOperator.Outcome.NeedsUser -> out.put("question", outcome.question)
            is HandsOperator.Outcome.Failed -> out.put("message", outcome.message)
            is HandsOperator.Outcome.Infeasible -> out.put("message", outcome.message)
            is HandsOperator.Outcome.Stopped -> out.put("message", outcome.message).put("note", "Stopped; do not restart it.")
        }
        return out
    }
}
