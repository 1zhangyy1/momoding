package app.momoding.files

import android.content.Context
import app.momoding.memory.Memory
import app.momoding.memory.MemoryGit
import com.openminis.app.sandbox.PRootKernel
import com.openminis.app.sandbox.ShellExecutor
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Reads the history of a folder Momoding keeps in git (its memory, each app) for the person to
 * see, and the two things they can do to it: save an edit, undo a change. Each is a commit, so
 * the chat finds them in `git log` too. Repos are sandbox paths; git runs in the sandbox.
 */
object RepoGit {
    data class FileChange(val path: String, val added: Int, val removed: Int)
    data class Commit(val sha: String, val at: Long, val subject: String, val files: List<FileChange>) {
        val label: Label get() = label(subject)
    }

    /** Who a change came from: the person's words in a chat, Momoding tidying up, or the person here. */
    enum class Kind { SAID, SELF, YOU, OTHER }
    data class Label(val kind: Kind, val text: String)

    enum class LineKind { ADD, DEL, CTX, GAP }
    data class Line(val kind: LineKind, val text: String)
    data class FileDiff(val path: String, val lines: List<Line>)

    private const val RS = '\u001e'
    private const val US = '\u001f'
    private const val GIT = "git -c core.quotepath=false -c user.name=Momoding -c user.email=memory@momoding.local"
    private val locks = mutableMapOf<String, Mutex>()

    private fun lockFor(repo: String): Mutex =
        if (repo == Memory.SANDBOX_DIR) MemoryGit.lock else synchronized(locks) { locks.getOrPut(repo) { Mutex() } }

    /** Newest first; [path] narrows it to one file, [from] starts at a commit (one commit: limit 1). */
    suspend fun log(context: Context, repo: String, path: String? = null, limit: Int = 200, from: String? = null): List<Commit> {
        val only = path?.let { " -- ${q(it)}" }.orEmpty()
        val rev = from?.let { " " + q(it) }.orEmpty()
        val out = run(context, "cd ${q(repo)} && $GIT log -n $limit --format=$RS%H$US%at$US%s --numstat$rev$only 2>/dev/null") ?: return emptyList()
        return parseLog(out)
    }

    suspend fun count(context: Context, repo: String): Int =
        run(context, "cd ${q(repo)} && git rev-list --count HEAD 2>/dev/null")?.trim()?.toIntOrNull() ?: 0

    suspend fun show(context: Context, repo: String, sha: String): List<FileDiff> {
        val out = run(context, "cd ${q(repo)} && $GIT show --format= --no-color -U2 ${q(sha)} 2>/dev/null") ?: return emptyList()
        return parseDiff(out)
    }

    /** Undo one change with a new commit; a conflict leaves everything as it was. */
    suspend fun revert(context: Context, repo: String, commit: Commit): Boolean = lockFor(repo).withLock {
        val msg = "you: undo ${commit.label.text}"
        run(context, "cd ${q(repo)} && ($GIT revert --no-commit ${q(commit.sha)} && $GIT commit -q -m ${q(msg)}) || { $GIT revert --abort; exit 1; }") != null
    }

    /** Write a file the person edited and commit it as theirs. */
    suspend fun saveEdit(context: Context, repo: String, file: java.io.File, text: String, name: String): Boolean = lockFor(repo).withLock {
        file.writeText(text)
        val msg = "you: edit $name"
        run(context, "cd ${q(repo)} && $GIT add -A && ($GIT diff --cached --quiet || $GIT commit -q -m ${q(msg)})") != null
    }

    private suspend fun run(context: Context, script: String): String? {
        if (!PRootKernel.isBooted) PRootKernel.boot(context)
        val r = ShellExecutor.execute(context, script, timeout = 60_000)
        return r.output.takeIf { r.exitCode == 0 }
    }

    private fun q(s: String) = "'" + s.replace("'", "'\\''") + "'"

    internal fun label(subject: String): Label = when {
        subject.startsWith("you: ") -> Label(Kind.YOU, subject.removePrefix("you: "))
        subject.startsWith("reflect:") -> Label(Kind.SELF, "")
        subject.startsWith("chat ") -> subject.substringAfter(": ", "").let { if (it.isEmpty()) Label(Kind.SELF, "") else Label(Kind.SAID, it) }
        else -> Label(Kind.OTHER, subject)
    }

    internal fun parseLog(out: String): List<Commit> = out.split(RS).mapNotNull { rec ->
        val lines = rec.trim('\n').lines()
        val head = lines.firstOrNull()?.split(US)?.takeIf { it.size == 3 } ?: return@mapNotNull null
        val files = lines.drop(1).mapNotNull { l ->
            val p = l.split('\t').takeIf { it.size == 3 } ?: return@mapNotNull null
            FileChange(p[2], p[0].toIntOrNull() ?: 0, p[1].toIntOrNull() ?: 0)
        }
        Commit(head[0], (head[1].toLongOrNull() ?: 0) * 1000, head[2], files)
    }

    internal fun parseDiff(out: String): List<FileDiff> {
        val diffs = mutableListOf<FileDiff>()
        var path: String? = null
        var lines = mutableListOf<Line>()
        var inHunk = false
        fun flush() { path?.let { diffs += FileDiff(it, lines) }; lines = mutableListOf(); inHunk = false }
        for (l in out.lines()) when {
            l.startsWith("diff --git ") -> { flush(); path = l.substringAfter(" b/") }
            l.startsWith("@@") -> { if (lines.isNotEmpty()) lines += Line(LineKind.GAP, ""); inHunk = true }
            !inHunk -> Unit
            l.startsWith("+") -> lines += Line(LineKind.ADD, l.drop(1))
            l.startsWith("-") -> lines += Line(LineKind.DEL, l.drop(1))
            l.startsWith(" ") -> lines += Line(LineKind.CTX, l.drop(1))
        }
        flush()
        return diffs
    }
}
