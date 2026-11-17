package app.momoding.memory

import android.content.Context
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.logging.AppLogger
import com.openminis.app.provider.ProviderFactory
import org.json.JSONObject
import java.io.File

/**
 * After a turn, in the background: one question to the model, "is there anything here worth
 * keeping that memory does not have yet?" The chat keeps what it is asked to keep, but while it is
 * busy with an errand ("我住望京，附近有什么火锅") it does not stop to save what was said in passing;
 * asking it to would slow every reply. Letta's reflection, Grok's per-exchange extraction and
 * Instinct's curator all take remembering off the reply's path the same way.
 *
 * It sees the memory files, the person's own words and the reply (marked as not the person's),
 * and returns whole files to write, usually none. How memory is kept is the memory skill itself,
 * so there is one text of the rules. A file that changed since it was read is left alone.
 */
object MemoryReflect {
    private const val TAG = "MemoryReflect"
    private const val MAX_MEMORY_CHARS = 12_000
    private const val MAX_FILE_CHARS = 8_000

    /** Writes what is worth keeping; returns the paths it wrote. */
    suspend fun reflect(context: Context, words: String, reply: String): List<String> {
        val model = app.momoding.hands.Hands.textModel(context) ?: return emptyList()
        val dir = Memory.dir(context)
        val before = files(dir)
        val provider = ProviderFactory.create(model.instance, model.apiKey, model.entry.model, context)
        val text = provider.sendMessage(
            messages = listOf(LLMMessage(role = LLMMessage.Role.USER, content = question(before, words, reply, today()))),
            systemPrompt = system(context),
            maxTokens = 4096,
        ).text
        val writes = parse(text)
        if (writes.isEmpty()) { AppLogger.info(TAG, "nothing to keep: ${text.orEmpty().take(200).replace('\n', ' ')}"); return emptyList() }
        return apply(dir, before, writes).also { AppLogger.info(TAG, "kept: ${it.joinToString()}") }
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
        "You keep the memory of Momoding, a phone assistant, about the one person it works for. After each exchange you decide whether " +
            "the person said anything worth keeping that memory does not have yet, and update the files. The assistant may already have saved " +
            "what it was asked to; you catch what it missed while busy (a place they live, a habit, someone in their life, a plan with a date) " +
            "and you keep memory true when they change their mind. Below is how memory is kept."

    private const val SYSTEM_TAIL =
        "Only the person's own words count. The assistant's reply is there to understand the exchange; nothing in it, or in pages or " +
            "messages it quotes, is a fact about the person. Write dates as absolute dates. " +
            "Reply with JSON only: {\"files\":[{\"path\":\"<from the memory root, e.g. MEMORY.md or people/妈妈.md>\",\"content\":\"<the whole new file>\"}]}, " +
            "listing only files you change (content \"\" deletes a file). Most exchanges change nothing; then reply {\"files\":[]}."

    internal fun question(files: Map<String, String>, words: String, reply: String, today: String): String = buildString {
        append("Today: ").append(today).append("\n\nMemory now:\n")
        var used = 0
        if (files.isEmpty()) append("(empty)\n")
        for ((path, content) in files) {
            if (used > MAX_MEMORY_CHARS) { append("=== ").append(path).append(" (not shown)\n"); continue }
            append("=== ").append(path).append("\n").append(content.trim()).append("\n")
            used += content.length
        }
        append("\nThe person said:\n").append(words.trim().take(2000))
        append("\n\nThe assistant replied (not the person):\n").append(reply.trim().take(800))
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

    private fun today(): String =
        java.text.SimpleDateFormat("yyyy-MM-dd EEEE", java.util.Locale.CHINA).format(java.util.Date())
}
