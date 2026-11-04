package app.momoding.chat

import android.content.Context
import app.momoding.task.Helpers
import app.momoding.task.TaskEngine
import com.openminis.app.MinisApp
import com.openminis.app.debug.HeadlessChatRunner
import com.openminis.app.logging.AppLogger
import com.openminis.app.service.SessionActivityTracker
import com.openminis.app.ui.chat.ChatViewModelStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Work the phone cut short goes on by itself. A local app is killed in the background all the
 * time; a chat that was working then (the main chat on an errand, an app's own Momoding building
 * it) used to sit half done until someone said "go on".
 *
 * While a chat works it is written down; when it stops — done, or stopped by the person — it is
 * crossed out. What is still written down at the next cold start was cut off by the phone, and
 * picks up where it was: a loop stopped between tool calls resumes, a message never answered is
 * answered. Only recent work (within [WINDOW_MS]) and at most [MAX_TRIES] times, so a chat that
 * keeps getting killed does not keep restarting.
 */
object WorkResume {
    private const val TAG = "WorkResume"
    private const val PREFS = "momoding"
    private const val KEY = "work.running"
    private const val WINDOW_MS = 30L * 60 * 1000
    private const val MAX_TRIES = 2
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Called once at startup, before any chat runs: resume what was cut off, then keep the list. */
    fun start(context: Context) {
        val app = context.applicationContext
        val cutOff = read(app)
        write(app, JSONObject())
        scope.launch {
            delay(3000) // let the repositories and the sandbox come up
            val now = System.currentTimeMillis()
            for (sid in cutOff.keys()) {
                val entry = cutOff.optJSONObject(sid) ?: continue
                val tries = entry.optInt("tries")
                if (now - entry.optLong("at") > WINDOW_MS || tries >= MAX_TRIES) continue
                runCatching { resume(app, sid, tries + 1) }.onFailure { AppLogger.warning(TAG, "resume $sid: ${it.message}") }
            }
            track(app)
        }
    }

    /** Write down every chat that starts working; cross out every one that stops. */
    private suspend fun track(context: Context) {
        var before = emptySet<String>()
        SessionActivityTracker.activeSessions.collect { now ->
            val list = read(context)
            (now - before).filterNot { it.startsWith("__new__") }.forEach { sid ->
                val tries = list.optJSONObject(sid)?.optInt("tries") ?: pendingTries.remove(sid) ?: 0
                list.put(sid, JSONObject().put("at", System.currentTimeMillis()).put("tries", tries))
            }
            (before - now).forEach { list.remove(it) }
            write(context, list)
            before = now
        }
    }

    /** A resumed chat keeps its count until it finishes, so a third kill in a row lets it rest. */
    private val pendingTries = java.util.concurrent.ConcurrentHashMap<String, Int>()

    private suspend fun resume(context: Context, sid: String, attempt: Int) {
        val repo = (context as MinisApp).chatRepository
        if (repo.getSession(sid) == null) return
        val held = TaskEngine.dao(context).heldBy(sid)
        // A detached command is not a model loop to replay, and an unanswered ask stays an ask.
        if (held != null && (held.state == app.momoding.task.TaskEntity.State.WAITING.name ||
                app.momoding.task.ShellWork.pending(TaskEngine.dao(context).log(held.id)).isNotEmpty())) return
        // a helper comes back as one, and only while its task is on
        if (Helpers.isHelper(context, sid)) Helpers.ready(context, sid, TaskEngine.dao(context).heldBy(sid) ?: return)
        pendingTries[sid] = attempt
        val vm = withContext(Dispatchers.Main) { HeadlessChatRunner.viewModelFor(context, sid) }
        // the chat loads its history and tells whether a loop was left between tool calls
        for (i in 0 until 20) { if (vm.canResume.value) break; delay(250) }
        if (vm.canResume.value) {
            AppLogger.info(TAG, "$sid: resuming the loop the phone cut off (attempt $attempt)")
            withContext(Dispatchers.Main) { vm.resume() }
            return
        }
        val total = repo.messageCount(sid)
        val last = repo.loadMessagePage(sid, offset = maxOf(0, total - 1), limit = 1).lastOrNull()
        if (last?.role == "user") {
            AppLogger.info(TAG, "$sid: answering the message the phone cut off (attempt $attempt)")
            HeadlessChatRunner.retry(context, sid, null, wait = false, timeoutMs = 30 * 60_000L)
        } else {
            pendingTries.remove(sid)
        }
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    @Synchronized private fun read(context: Context): JSONObject =
        runCatching { JSONObject(prefs(context).getString(KEY, "{}")!!) }.getOrDefault(JSONObject())
    // commit, not apply: the point is to survive the process being killed right after
    @Synchronized private fun write(context: Context, list: JSONObject) { prefs(context).edit().putString(KEY, list.toString()).commit() }
}
