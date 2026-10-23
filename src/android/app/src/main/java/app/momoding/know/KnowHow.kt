package app.momoding.know

import android.content.Context
import java.io.File
import java.time.LocalDate

/**
 * Reusable lessons the Bot learned about getting errands done on this phone (调研/M21): one file
 * per topic the agent names (travel, shopping, a city's government site…) at
 * /var/minis/memory/know-how/<topic>.md, one dated line per lesson, newest first. Written by the
 * agent (`momoding-know lesson`, when the person says how they want it done, or the learner after
 * a long job); read back with `momoding-know lookup`.
 */
object KnowHow {
    const val MAX_LINES = 20
    const val MAX_CHARS = 120

    fun dir(context: Context): File = File(context.filesDir, "minis-global/memory/know-how")
    fun file(context: Context, area: String): File = File(dir(context), "$area.md")

    /** Topics that have lessons. */
    fun areas(context: Context): List<String> =
        dir(context).listFiles { f -> f.isFile && f.name.endsWith(".md") }.orEmpty().map { it.name.removeSuffix(".md") }.sorted()

    /** Lessons whose topic or text contains [query], as "topic: line". */
    fun lookup(context: Context, query: String): List<String> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return emptyList()
        return areas(context).flatMap { a -> lines(context, a).filter { a.contains(q) || it.lowercase().contains(q) }.map { "$a: ${it.removePrefix("- ")}" } }.take(12)
    }

    /** A topic name the agent chose, made safe as a file name. */
    internal fun slug(area: String): String? =
        area.trim().lowercase().replace(Regex("""[^a-z0-9\u4e00-\u9fff]+"""), "-").trim('-').take(30).takeIf { it.length >= 2 }

    fun lines(context: Context, area: String): List<String> =
        runCatching { file(context, area).readLines() }.getOrDefault(emptyList()).filter { it.startsWith("- ") }

    /** Add [line] to [area], replacing the line that starts like [replaces] when given; newest first, capped. */
    fun add(context: Context, area: String, line: String, replaces: String? = null, today: LocalDate = LocalDate.now()): Boolean {
        val topic = slug(area) ?: return false
        val clean = sanitize(line) ?: return false
        synchronized(this) {
            val f = file(context, topic)
            val updated = merge(lines(context, topic), clean, replaces, today)
            f.parentFile?.mkdirs()
            f.writeText(header(topic) + updated.joinToString("\n") + "\n")
        }
        return true
    }

    // ── pure parts (tests) ─────────────────────────────────────────────────

    internal fun header(area: String) =
        "# What this phone learned: $area\nNewest first. Written by the assistant after its own work, or told by the person.\n\n"

    internal fun merge(existing: List<String>, clean: String, replaces: String?, today: LocalDate): List<String> {
        val entry = "- $clean ($today)"
        val body = { l: String -> l.removePrefix("- ").replace(Regex("""\s*\(\d{4}-\d{2}-\d{2}\)$"""), "") }
        val key = replaces?.trim()?.takeIf { it.length >= 6 }
        val kept = existing.filterNot { l -> body(l) == clean || (key != null && body(l).startsWith(key.take(40))) }
        return (listOf(entry) + kept).take(MAX_LINES)
    }

    private val UNSAFE = Regex("""(?i)password|密码|验证码是|付款|支付|转账|pay\b|send money|信用卡|card number|身份证号""")

    /** One plain line, short, no query strings (they can carry personal data), nothing about secrets or money. */
    internal fun sanitize(line: String): String? {
        var t = line.replace('\n', ' ').trim().removePrefix("- ").trim()
        t = t.replace(Regex("""(https?://[^\s?#]+)[?#]\S*"""), "$1")
        if (t.length < 6 || UNSAFE.containsMatchIn(t)) return null
        return t.take(MAX_CHARS)
    }
}
