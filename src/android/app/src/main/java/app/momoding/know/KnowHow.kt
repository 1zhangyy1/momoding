package app.momoding.know

import android.content.Context
import java.io.File
import java.time.LocalDate

/**
 * What this phone learned about getting errands done (调研/M21, layer 2): one file per area at
 * /var/minis/memory/know-how/<area>.md, one line per lesson, dated. The bundled know-how skills
 * (the seed, replaced on upgrade) tell the model to read it first and to let it win, so each
 * person's copy drifts toward what works for them. Written by the learner after long jobs and by
 * the model when the person says how they want something done.
 */
object KnowHow {
    val AREAS = listOf("travel", "shopping", "procedures")
    const val MAX_LINES = 20
    const val MAX_CHARS = 120

    fun file(context: Context, area: String): File = File(File(context.filesDir, "minis-global/memory/know-how"), "$area.md")

    fun lines(context: Context, area: String): List<String> =
        runCatching { file(context, area).readLines() }.getOrDefault(emptyList()).filter { it.startsWith("- ") }

    /** Add [line] to [area], replacing the line that starts like [replaces] when given; newest first, capped. */
    fun add(context: Context, area: String, line: String, replaces: String? = null, today: LocalDate = LocalDate.now()): Boolean {
        if (area !in AREAS) return false
        val clean = sanitize(line) ?: return false
        synchronized(this) {
            val f = file(context, area)
            val updated = merge(lines(context, area), clean, replaces, today)
            f.parentFile?.mkdirs()
            f.writeText(header(area) + updated.joinToString("\n") + "\n")
        }
        return true
    }

    // ── pure parts (tests) ─────────────────────────────────────────────────

    internal fun header(area: String) =
        "# What this phone learned: $area\nNewest first. Learned automatically after long jobs, or told by the person. Wins over the know-how-$area skill.\n\n"

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
