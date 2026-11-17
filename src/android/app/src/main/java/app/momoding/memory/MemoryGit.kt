package app.momoding.memory

import android.content.Context
import android.util.Log
import com.openminis.app.sandbox.PRootKernel
import com.openminis.app.sandbox.ShellExecutor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Commits the memory folder after a turn that changed it, with the chat and the person's words as
 * the message: that is where a line came from, and `git log -p` is what it said before. The agent
 * never runs git to remember; it edits files, and this keeps their history. Git comes from Alpine
 * the first time (`apk add git`); offline, memory still works and the next turn's commit takes it all.
 */
object MemoryGit {
    private const val TAG = "MemoryGit"
    private const val RETRY_INSTALL_MS = 10 * 60_000L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    @Volatile private var installFailedAt = 0L

    fun commitAfterTurn(context: Context, sessionId: String, words: String?) {
        val app = context.applicationContext
        scope.launch { runCatching { commit(app, message(sessionId, words)) }.onFailure { Log.w(TAG, "commit failed: ${it.message}") } }
    }

    /** One line: which chat, and what the person said there. */
    internal fun message(sessionId: String, words: String?): String {
        val said = words.orEmpty().lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }.orEmpty()
        val head = "chat $sessionId"
        return if (said.isEmpty()) head else "$head: ${said.take(80)}"
    }

    internal fun script(message: String): String {
        val quoted = "'" + message.replace("'", "'\\''") + "'"
        return listOf(
            "cd ${Memory.SANDBOX_DIR} || exit 1",
            "command -v git >/dev/null 2>&1 || apk add -q git >/dev/null 2>&1 || exit 3",
            "[ -d .git ] || git init -q || exit 1",
            "git add -A || exit 1",
            "git diff --cached --quiet && exit 0",
            "git -c user.name=Momoding -c user.email=memory@momoding.local commit -q -m $quoted",
        ).joinToString("\n")
    }

    private suspend fun commit(context: Context, message: String) = lock.withLock {
        if (System.currentTimeMillis() - installFailedAt < RETRY_INSTALL_MS) return@withLock
        // a turn that saved with file_write never started the sandbox
        if (!PRootKernel.isBooted) PRootKernel.boot(context)
        val r = ShellExecutor.execute(context, script(message), timeout = 90_000)
        when (r.exitCode) {
            0 -> Unit
            3 -> { installFailedAt = System.currentTimeMillis(); Log.w(TAG, "git not installed (offline?)") }
            else -> Log.w(TAG, "commit rc=${r.exitCode}: ${r.output.take(300)}")
        }
    }
}
