package app.momoding.memory

import android.content.Context
import app.momoding.hands.Hands
import com.openminis.app.MinisApp
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.logging.AppLogger
import com.openminis.app.provider.ProviderFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.File

/**
 * Remembers in the background what the person said in passing. The chat keeps what it is asked to
 * keep, but while it is busy with an errand ("我住望京，附近有什么火锅") it does not stop to save
 * the rest; asking it to would slow every reply. So the person's words are set aside as they come,
 * and once they have been quiet for [QUIET_MS], one question goes to the model over all of them:
 * "is there anything here worth keeping that memory does not have yet?" Within a chat the model
 * still sees everything said, so memory only has to be ready for the next one (Letta's reflection,
 * Instinct's ingestion and Grok Build's idle flush take remembering off the reply's path the same way).
 *
 * The model sees the memory files and the person's own words, nothing else, and returns whole files
 * to write, usually none. How memory is kept is the memory skill itself, so there is one text of
 * the rules. A file that changed while it was asking is left alone.
 */
object MemoryReflect {
    private const val TAG = "MemoryReflect"
    /** How long the person is quiet before what they said is looked at. */
    const val QUIET_MS = 30 * 60_000L
    private const val MAX_MEMORY_CHARS = 12_000
    private const val MAX_FILE_CHARS = 8_000
    private const val MAX_SAID = 40
    private const val MAX_SAID_CHARS = 1_000

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var waiting: Job? = null
    /** One look at a time, so two never take the same words. */
    private val looking = Mutex()

    /** The person said [words] in [sessionId]: set them aside, and look once they have been quiet. */
    fun heard(context: Context, sessionId: String, words: String) {
        val app = context.applicationContext
        Heard.add(heardFile(app), Said(System.currentTimeMillis(), sessionId, words.trim()))
        lookAfter(app, QUIET_MS)
    }

    /** At start: whatever was set aside before the app was closed. */
    fun catchUp(context: Context) {
        val app = context.applicationContext
        if (Heard.read(heardFile(app)).isNotEmpty()) lookAfter(app, 0)
    }

    @Synchronized private fun lookAfter(app: Context, delayMs: Long) {
        waiting?.cancel()
        // a new word cancels the wait, never a look already under way
        waiting = scope.launch { delay(delayMs); scope.launch { look(app) } }
    }

    private suspend fun look(app: Context) = looking.withLock { runCatching {
        val file = heardFile(app)
        val said = Heard.read(file).take(MAX_SAID)
        if (said.isEmpty()) return@runCatching
        // at start the models are still loading
        (app as? MinisApp)?.providerRepositoryOrNull?.configLoaded?.let { loaded -> withTimeoutOrNull(30_000) { loaded.first { it } } }
        val model = Hands.textModel(app)
            ?: return@runCatching AppLogger.warning(TAG, "no model yet; ${said.size} said kept for later")
        val dir = Memory.dir(app)
        val before = files(dir)
        val text = ProviderFactory.create(model.instance, model.apiKey, model.entry.model, app).sendMessage(
            messages = listOf(LLMMessage(role = LLMMessage.Role.USER, content = question(before, said))),
            systemPrompt = system(app),
            maxTokens = 4096,
        ).text
        MemoryGit.lock.withLock {
            val wrote = apply(dir, before, parse(text))
            AppLogger.info(TAG, "looked at ${said.size} said: " + if (wrote.isEmpty()) "nothing to keep" else "kept ${wrote.joinToString()}")
            if (wrote.isNotEmpty()) MemoryGit.commit(app, commitMessage(said))
            Heard.drop(file, said.size)
        }
        if (Heard.read(file).isNotEmpty() && waiting?.isActive != true) lookAfter(app, 0)
    }.onFailure { AppLogger.warning(TAG, "look failed, kept for later: ${it.message}") } } // offline: the next word or start tries again

    /** "reflect: what the person said in N chats", then each thing said with its chat: where every kept line came from. */
    internal fun commitMessage(said: List<Said>): String =
        "reflect: what the person said in ${said.map { it.session }.distinct().size} chats\n\n" +
            said.joinToString("\n") { MemoryGit.message(it.session, it.words) }

    private fun heardFile(context: Context) = File(context.filesDir, "memory-heard.jsonl")

    internal data class Said(val at: Long, val session: String, val words: String)

    /** What the person said and memory has not looked at yet, one JSON line each, oldest first. */
    internal object Heard {
        @Synchronized fun add(file: File, s: Said) =
            file.appendText(JSONObject().put("at", s.at).put("session", s.session).put("words", s.words).toString() + "\n")

        @Synchronized fun read(file: File): List<Said> =
            if (!file.isFile) emptyList()
            else file.readLines().mapNotNull { line ->
                runCatching { JSONObject(line).let { Said(it.getLong("at"), it.getString("session"), it.getString("words")) } }.getOrNull()
            }

        /** Drops the [n] oldest, which were looked at; what came meanwhile stays. */
        @Synchronized fun drop(file: File, n: Int) {
            val rest = if (file.isFile) file.readLines().filter { it.isNotBlank() }.drop(n) else emptyList()
            if (rest.isEmpty()) file.delete() else file.writeText(rest.joinToString("\n", postfix = "\n"))
        }
    }

    /** The person's memory files, path to content, as the model sees them. */
    internal fun files(dir: File): Map<String, String> =
        dir.walkTopDown().onEnter { it == dir || it.name !in Memory.NOT_MEMORY }
            .filter { it.isFile && it.name.endsWith(".md") && it.name !in Memory.NOT_MEMORY }
            .associate { it.relativeTo(dir).path to runCatching { it.readText() }.getOrDefault("") }
            .toSortedMap(compareBy<String> { it != "MEMORY.md" }.thenBy { it })

    private fun system(context: Context): String {
        val skill = runCatching { context.assets.open("momoding-skills-memory.md").bufferedReader().readText() }.getOrDefault("")
            .substringAfter("\n---\n", "")
        return SYSTEM_HEAD + "\n\n" + skill.trim() + "\n\n" + SYSTEM_TAIL
    }

    private const val SYSTEM_HEAD =
        "You keep the memory of Momoding, a phone assistant, about the one person it works for. You are shown what the person " +
            "said to it since you last looked, and you decide whether any of it is worth keeping that memory does not have yet. " +
            "The assistant may already have saved what it was asked to; you catch what it missed while busy (a place they live, " +
            "a habit, someone in their life, a plan with a date) and you keep memory true when they change their mind. " +
            "Below is how memory is kept."

    private const val SYSTEM_TAIL =
        "You see only the person's own words, oldest first, each with when it was said; write dates as absolute dates. " +
            "When they said two things that disagree, the later one is what is true now. " +
            "Reply with JSON only: {\"files\":[{\"path\":\"<from the memory root, e.g. MEMORY.md or people/妈妈.md>\",\"content\":\"<the whole new file>\"}]}, " +
            "listing only files you change (content \"\" deletes a file). Most of what people say changes nothing; then reply {\"files\":[]}."

    internal fun question(files: Map<String, String>, said: List<Said>, today: String = day(System.currentTimeMillis(), "yyyy-MM-dd EEEE")): String = buildString {
        append("Today: ").append(today).append("\n\nMemory now:\n")
        var used = 0
        if (files.isEmpty()) append("(empty)\n")
        for ((path, content) in files) {
            if (used > MAX_MEMORY_CHARS) { append("=== ").append(path).append(" (not shown)\n"); continue }
            append("=== ").append(path).append("\n").append(content.trim()).append("\n")
            used += content.length
        }
        append("\nThe person said:\n")
        for (s in said) append("[").append(day(s.at, "yyyy-MM-dd HH:mm")).append("] ").append(s.words.take(MAX_SAID_CHARS)).append("\n")
    }

    internal fun parse(text: String?): List<Pair<String, String>> {
        val json = text?.let { Regex("""\{[\s\S]*\}""").find(it)?.value }?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return emptyList()
        val a = json.optJSONArray("files") ?: return emptyList()
        return (0 until a.length()).mapNotNull { a.optJSONObject(it) }
            .mapNotNull { o -> safePath(o.optString("path"))?.let { it to o.optString("content") } }
            .filter { it.second.length <= MAX_FILE_CHARS }
            .take(5)
    }

    /** A path inside the person's memory, never the identity, know-how or old notes. */
    internal fun safePath(raw: String): String? {
        val p = raw.trim().removePrefix("./").removePrefix(Memory.SANDBOX_DIR + "/")
        if (p.isEmpty() || p.startsWith("/") || !p.endsWith(".md") || p.split('/').any { it == ".." || it.isEmpty() || it in Memory.NOT_MEMORY }) return null
        return p
    }

    /** Writes each file unless it changed since it was read (the chat got there first). */
    internal fun apply(dir: File, before: Map<String, String>, writes: List<Pair<String, String>>): List<String> =
        writes.mapNotNull { (path, content) ->
            val f = File(dir, path)
            val now = if (f.isFile) f.readText() else null
            if (now != before[path]) return@mapNotNull null
            if (content.isBlank()) { if (f.exists()) f.delete() else return@mapNotNull null }
            else { f.parentFile?.mkdirs(); f.writeText(content.trimEnd() + "\n") }
            path
        }

    private fun day(at: Long, pattern: String): String =
        java.text.SimpleDateFormat(pattern, java.util.Locale.CHINA).format(java.util.Date(at))
}
