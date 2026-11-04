// Ported from nanoMuse (GPL-3.0): io/github/nanomuse/hands/HandsOffloadHandler.kt
package app.momoding.hands

import android.content.Context
import com.openminis.app.logging.AppLogger
import com.openminis.app.sandbox.NativeOffloadHandler
import com.openminis.app.sandbox.NativeOffloadRequest
import com.openminis.app.sandbox.NativeOffloadResult
import app.momoding.Identity
import app.momoding.task.TaskEngine
import app.momoding.task.ScreenWork
import app.momoding.task.TaskEntity
import kotlinx.coroutines.runBlocking
import com.openminis.app.sandbox.offload.OffloadArgs
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * `momoding-hands` — the phone's screen as a hand, from the sandbox shell:
 *
 *     momoding-hands status
 *     momoding-hands apps
 *     momoding-hands run --task "<one clear task>" [--app "<app name>"] [--max-steps N]
 *
 * `run` persists a task and returns its id immediately; the result comes through its inbox. Off by default;
 * with the switch off or a prerequisite missing it exits 3 with the reason and the settings
 * link, so the agent tells the user instead of guessing. Registered in `MinisApp` next to
 * `nanomuse-media`.
 */
class HandsOffloadHandler(private val context: Context) : NativeOffloadHandler {

    override fun handle(request: NativeOffloadRequest): NativeOffloadResult {
        val args = OffloadArgs(request.argv.drop(1))
        return when (args.positional.firstOrNull()) {
            null, "help", "--help", "-h" -> NativeOffloadResult(0, HELP)
            "status" -> ok(status())
            "apps" -> ok(apps())
            "run" -> runCatching { run(args, request.sessionId, process = request.env["MINIS_EXECUTION_ID"]) }
                .getOrElse { NativeOffloadResult(2, "momoding-hands: ${it.message}\n") }
            "stop" -> runCatching {
                val sid = requireNotNull(request.sessionId) { "a screen task needs its conversation" }
                val task = runBlocking { args.positional.getOrNull(1)?.let { TaskEngine.dao(context).task(it) }
                    ?: TaskEngine.dao(context).heldBy(TaskEntity.SCREEN) } ?: error("there is no active screen task")
                require(task.hand == TaskEntity.SCREEN && task.conversation == sid) { "that screen task belongs to another conversation" }
                val parent = runBlocking { TaskEngine.screenSpec(context, task.id).parent }
                runBlocking { TaskEngine.stop(context, parent ?: task.id) }
                ok(JSONObject().put("task", task.id).put("state", "stopped"))
            }.getOrElse { NativeOffloadResult(2, "momoding-hands: ${it.message}\n") }
            else -> NativeOffloadResult(2, "momoding-hands: unknown subcommand '${args.positional.first()}'\n$HELP")
        }
    }

    private fun status(): JSONObject {
        val r = Hands.readiness(context)
        return JSONObject()
            .put("enabled", Hands.enabled(context))
            .put("ready", r.ready)
            .put("android_11_or_newer", r.androidOk)
            .put("accessibility_service", r.serviceOn)
            .put("draw_over_apps", r.overlayOk)
            .put("screen_model", r.model?.let { JSONObject().put("model", it.modelId).put("provider", it.instance.label) } ?: JSONObject.NULL)
            .put("running", Hands.active.value)
            .put("settings", Hands.DEEP_LINK)
    }

    private fun apps(): JSONObject {
        val list = HandsApps.launchable(context)
        return JSONObject()
            .put("count", list.size)
            .put("apps", JSONArray().apply { list.forEach { put(JSONObject().put("name", it.label).put("package", it.packageName)) } })
    }

    /**
     * Debug builds (`momoding.hands.run`): run the hands on [task] the way the agent would, with an
     * optional screen model for this run only, and return the result plus the wall time.
     */
    fun runForDebug(task: String, app: String?, maxSteps: Int?, modelEntryId: String?, mode: String? = null, textSteps: Boolean = false, background: Boolean = true): JSONObject {
        val started = System.currentTimeMillis()
        run {
            val argv = buildList {
                add("momoding-hands"); add("run"); add("--task"); add(task)
                app?.let { add("--app"); add(it) }
                maxSteps?.let { add("--max-steps"); add(it.toString()) }
                mode?.let { add("--mode"); add(it) }
                if (textSteps) { add("--text-steps"); add("true") }
                if (!background) { add("--background"); add("false") }
            }
            val r = run(OffloadArgs(argv.drop(1)), sessionId = "debug-hands", persistent = false, modelEntry = modelEntryId)
            val body = runCatching { JSONObject(r.output) }.getOrElse { JSONObject().put("raw", r.output) }
            return body.put("exit", r.exitCode).put("elapsed_ms", System.currentTimeMillis() - started)
        }
    }

    private fun run(args: OffloadArgs, sessionId: String?, persistent: Boolean = true, process: String? = null, modelEntry: String? = null): NativeOffloadResult {
        val task = args.get("task")?.trim().orEmpty()
        if (task.isEmpty()) return NativeOffloadResult(2, "momoding-hands run: --task is required\n")
        if (!Hands.enabled(context)) return refused("off", "Operating the screen is switched off in Settings → Hands.")
        val r = Hands.readiness(context).copy(model = Hands.screenModel(context, entryOverride = modelEntry))
        if (!r.ready) {
            val missing = buildList {
                if (!r.androidOk) add("Android 11 or newer is needed for screenshots")
                if (!r.serviceOn) add("the accessibility service is off")
                if (!r.overlayOk) add("${Identity.name(context)} may not draw over other apps")
                if (r.model == null) add("no model that can see pictures is configured")
            }
            return refused("not_ready", missing.joinToString("; ") + ".")
        }
        // No cap unless the caller asks for one: the run ends when the task is done, the person
        // stops it, or the time limit is hit.
        val maxSteps = args.get("max-steps")?.toIntOrNull()?.coerceAtLeast(0) ?: HandsOperator.DEFAULT_MAX_STEPS
        val opts = HandsOperator.Options(
            task = task,
            appHint = args.get("app")?.trim()?.takeIf { it.isNotEmpty() },
            maxSteps = maxSteps,
            sessionId = sessionId,
            agentName = Identity.name(context),
            language = context.resources.configuration.locales[0].getDisplayLanguage(Locale.ENGLISH).ifBlank { "the user's language" }, // momoding: the app's language, not the system's
            mode = args.get("mode")?.let { m -> HandsPrompt.Mode.entries.firstOrNull { it.name.equals(m, ignoreCase = true) } } ?: HandsPrompt.Mode.TREE,
            textSteps = args.get("text-steps") == "true",
            background = args.get("background") != "false",
            modelEntryId = modelEntry,
        )
        if (persistent) {
            val sid = requireNotNull(sessionId) { "a screen task needs its conversation" }
            val parent = runBlocking { TaskEngine.executionTask(context, sid, process) }
            val work = runBlocking { TaskEngine.openScreen(context, opts, parent?.id) }
            return ok(JSONObject().put("task", work.id).put("state", work.state.lowercase())
                .put("note", "The screen task is saved and will run when the screen is free. Its result arrives here automatically. Do not repeat it or poll."))
        }
        // Taken atomically, so two runs arriving together cannot both start; given back when the run ends.
        if (!runBlocking { TaskEngine.claimDiagnosticScreen(context) }) return refused("busy", "A screen task is running, queued or waiting; the diagnostic cannot borrow its screen.")
        AppLogger.info(TAG, "run: ${task.take(80)} app=${opts.appHint} maxSteps=$maxSteps")
        val result = try { HandsOperator(context).run(opts) } finally { Hands.setActive(false) }
        val body = JSONObject()
            .put("ok", result.outcome is HandsOperator.Outcome.Done)
            .put("outcome", result.outcome.code)
            .put("steps", result.steps)
            .put("run_id", result.runId)
            .put("model", result.model ?: JSONObject.NULL)
            .put("last_screen", result.lastScreen ?: JSONObject.NULL)
            .put("trace", result.tracePath ?: JSONObject.NULL)
            .put("log", JSONArray(result.log))
            .put("done", JSONArray(result.done))
        when (val o = result.outcome) {
            is HandsOperator.Outcome.Done -> body.put("answer", o.answer)
            is HandsOperator.Outcome.Infeasible -> body.put("message", o.message)
                .put("tell_user", "Say plainly what could not be done and why; do not retry on your own.")
            is HandsOperator.Outcome.Stopped -> body.put("message", o.message)
                .put("tell_user", "The user stopped it. Say so in one line and do not start it again.")
            is HandsOperator.Outcome.NeedsUser -> body.put("question", o.question)
                .put("tell_user", "Ask the user this question in their language and wait; then run again with the answer in the task.")
            is HandsOperator.Outcome.Failed -> body.put("message", o.message)
                .put("tell_user", "Tell the user what went wrong in their language; the settings are at ${Hands.DEEP_LINK}.")
        }
        val code = when (result.outcome) {
            is HandsOperator.Outcome.Done -> 0
            is HandsOperator.Outcome.Failed -> 1
            else -> 4
        }
        return NativeOffloadResult(code, body.toString(2) + "\n")
    }

    private fun refused(error: String, message: String): NativeOffloadResult = NativeOffloadResult(
        3,
        JSONObject().put("ok", false).put("error", error).put("message", message)
            .put("tell_user", "Tell the user in one line, in their language, and give the link ${Hands.DEEP_LINK} so they can switch it on or fix the setting. Do not use android-a11y-cli instead.")
            .toString(2) + "\n",
    )

    private fun ok(body: JSONObject): NativeOffloadResult = NativeOffloadResult(0, body.toString(2) + "\n")

    companion object {
        private const val TAG = "momoding-hands"
        const val HELP = """momoding-hands — the phone's screen as a hand: taps, types and swipes in apps that have no API

Usage:
  momoding-hands status
  momoding-hands apps
  momoding-hands run --task "<one clear task, with every detail the hands need>" [--app "<app name>"] [--max-steps N]
  momoding-hands stop

The screen model sees screenshots only. It never types passwords or codes (the user takes over), and taps that
pay, send, post or delete wait for the same approval card as the shell and the browser. Off by default:
Settings → Hands. run returns a saved task id immediately, queues when the screen is busy and reports back here.
Exit codes: 0 accepted · 2 usage · 3 off or not ready. Use momoding-task tell <id> to update it or stop <id> to stop it.
"""
    }
}
