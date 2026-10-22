package app.momoding.know

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * This phone's record of the websites the Bot has used (调研/M21, layer 1). Kept by code, not by
 * the model: after every browser action the site is marked visited, read (real content came back)
 * or walled (a bot check, a login wall, "open in the app"), so it costs no tokens and never
 * depends on the model remembering to take notes. The first time a chat touches a site, the
 * record rides along with the tool result, so the model sees it exactly when it matters:
 * "flights.ctrip.com: walled 5 of 6 visits, last 2 days ago; m.ctrip.com read fine".
 *
 * Stored at /var/minis/memory/sites.json next to USER.md: the person can read it, backups carry
 * it. No record means unknown, not blocked, so nothing is added for a new site.
 */
object SiteLedger {
    enum class Outcome { VISIT, READ, WALL }

    data class Note(val text: String, val at: Long)

    /** One browser action of the current turn, for the learner (no page text kept). */
    data class Step(val domain: String, val action: String, val outcome: Outcome, val sign: String?)

    data class Entry(
        val domain: String,
        val visits: Int = 0,
        val reads: Int = 0,
        val walls: Int = 0,
        val lastAt: Long = 0,
        val lastWallAt: Long? = null,
        val lastWallSign: String? = null,
        val lastReadAt: Long? = null,
        val lastReadUrl: String? = null,
        val notes: List<Note> = emptyList(),
    )

    private const val KEEP_DOMAINS = 300
    const val MAX_NOTES = 3
    /** A read shorter than this says too little to count as the site working. */
    private const val READ_MIN_CHARS = 600
    private val READ_ACTIONS = setOf("get_text", "get_readable", "execute_js", "scroll_and_collect", "get_backbone")

    /** Phrases that mean the page is a wall, not the content (checked on titles and reads). */
    private val WALL_SIGNS = listOf(
        "验证码", "滑块", "拖动", "安全验证", "人机验证", "请完成验证", "访问过于频繁", "访问异常",
        "请先登录", "登录后查看", "请登录后", "扫码登录", "打开App", "打开APP", "下载App", "请在App", "在APP内",
        "captcha", "access denied", "just a moment", "are you a robot", "unusual traffic", "verify you are human",
        "403 forbidden", "enable javascript and cookies",
    )

    private val lock = Any()
    private var cache: MutableMap<String, Entry>? = null
    /** "session|domain" already shown the record in this process. */
    private val shown = HashSet<String>()
    /** Session → this turn's browser steps, taken by [takeTurn] when the turn ends. */
    private val steps = HashMap<String, MutableList<Step>>()

    /** This turn's steps for [sessionId], cleared. */
    fun takeTurn(sessionId: String): List<Step> = synchronized(lock) { steps.remove(sessionId).orEmpty() }

    fun file(context: Context): File = File(File(context.filesDir, "minis-global/memory"), "sites.json")

    /**
     * Record [action] on [url] with what came back ([text]); returns the line to add to the tool
     * result when this chat touches the domain for the first time and a record exists.
     */
    fun observe(context: Context, sessionId: String, action: String, url: String?, text: String, now: Long = System.currentTimeMillis()): String? {
        val domain = domainOf(url) ?: return null
        val outcome = classify(action, text)
        synchronized(lock) {
            val map = load(context)
            val before = map[domain]
            val line = if (before != null && shown.add("$sessionId|$domain")) render(before, now) else {
                shown.add("$sessionId|$domain"); null
            }
            map[domain] = record(before ?: Entry(domain), outcome, url, text, now)
            steps.getOrPut(sessionId) { mutableListOf() }.add(Step(domain, action, outcome, if (outcome == Outcome.WALL) wallSign(text) else null))
            save(context, map)
            return line
        }
    }

    /** Add a learned note to [domain] (layer 3, and when the person says so); newest first, capped. */
    fun note(context: Context, domain: String, text: String, now: Long = System.currentTimeMillis()) {
        val d = domainOf(domain) ?: domain.lowercase().removePrefix("www.").takeIf { it.isNotBlank() } ?: return
        val clean = text.trim().take(120)
        if (clean.isEmpty()) return
        synchronized(lock) {
            val map = load(context)
            val e = map[d] ?: Entry(d)
            map[d] = e.copy(notes = (listOf(Note(clean, now)) + e.notes.filterNot { it.text == clean }).take(MAX_NOTES))
            save(context, map)
        }
    }

    fun entry(context: Context, domain: String): Entry? = synchronized(lock) { load(context)[domain] }

    // ── pure parts (tests) ─────────────────────────────────────────────────

    internal fun domainOf(url: String?): String? {
        val u = url?.trim().orEmpty()
        if (u.isEmpty() || u.startsWith("minis:") || u.startsWith("about:") || u.startsWith("data:") || u.startsWith("file:")) return null
        val host = runCatching { java.net.URI(if (u.contains("://")) u else "https://$u").host }.getOrNull() ?: return null
        return host.lowercase().removePrefix("www.").takeIf { it.contains('.') }
    }

    internal fun wallSign(text: String): String? {
        val head = text.take(3000).lowercase()
        return WALL_SIGNS.firstOrNull { head.contains(it.lowercase()) }
    }

    internal fun classify(action: String, text: String): Outcome {
        val sign = wallSign(text)
        return when {
            action == "navigate" -> if (sign != null && text.length < 2000) Outcome.WALL else Outcome.VISIT
            action in READ_ACTIONS -> when {
                // a long read that happens to mention a login link is still content
                sign != null && text.length < 2000 -> Outcome.WALL
                text.length >= READ_MIN_CHARS -> Outcome.READ
                else -> Outcome.VISIT
            }
            else -> Outcome.VISIT
        }
    }

    internal fun record(e: Entry, outcome: Outcome, url: String?, text: String, now: Long): Entry = when (outcome) {
        Outcome.VISIT -> e.copy(visits = e.visits + 1, lastAt = now)
        Outcome.READ -> e.copy(visits = e.visits + 1, reads = e.reads + 1, lastAt = now, lastReadAt = now, lastReadUrl = url?.substringBefore('?')?.substringBefore('#')?.take(160))
        Outcome.WALL -> e.copy(visits = e.visits + 1, walls = e.walls + 1, lastAt = now, lastWallAt = now, lastWallSign = wallSign(text))
    }

    /** One line, in the model's working language; no line when the record says nothing useful. */
    internal fun render(e: Entry, now: Long): String? {
        if (e.walls == 0 && e.reads == 0 && e.notes.isEmpty()) return null
        val parts = mutableListOf<String>()
        if (e.walls > 0) parts += "walled ${e.walls} of ${e.visits} visits (last ${ago(e.lastWallAt, now)}${e.lastWallSign?.let { ", \"$it\"" } ?: ""})"
        if (e.reads > 0) parts += "content read ${e.reads} times (last ${ago(e.lastReadAt, now)}${e.lastReadUrl?.let { " at $it" } ?: ""})"
        e.notes.forEach { parts += "note ${ago(it.at, now)}: ${it.text}" }
        return "[This phone's past record of ${e.domain}: ${parts.joinToString("; ")}. A record, not a rule: a wall that kept recurring means try another source.]"
    }

    private fun ago(at: Long?, now: Long): String {
        if (at == null) return "?"
        val m = (now - at) / 60_000
        return when {
            m < 60 -> "${m.coerceAtLeast(1)} min ago"
            m < 48 * 60 -> "${m / 60} h ago"
            else -> "${m / (24 * 60)} days ago"
        }
    }

    // ── storage ────────────────────────────────────────────────────────────

    private fun load(context: Context): MutableMap<String, Entry> {
        cache?.let { return it }
        val map = LinkedHashMap<String, Entry>()
        runCatching {
            val arr = JSONObject(file(context).readText()).optJSONArray("sites") ?: JSONArray()
            for (i in 0 until arr.length()) fromJson(arr.getJSONObject(i))?.let { map[it.domain] = it }
        }
        cache = map
        return map
    }

    private fun save(context: Context, map: MutableMap<String, Entry>) {
        if (map.size > KEEP_DOMAINS) {
            map.values.sortedBy { it.lastAt }.take(map.size - KEEP_DOMAINS).forEach { map.remove(it.domain) }
        }
        runCatching {
            val f = file(context); f.parentFile?.mkdirs()
            val arr = JSONArray()
            map.values.sortedByDescending { it.lastAt }.forEach { arr.put(toJson(it)) }
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(JSONObject().put("sites", arr).toString(1))
            tmp.renameTo(f)
        }
    }

    internal fun toJson(e: Entry): JSONObject = JSONObject().apply {
        put("domain", e.domain); put("visits", e.visits); put("reads", e.reads); put("walls", e.walls); put("lastAt", e.lastAt)
        e.lastWallAt?.let { put("lastWallAt", it) }; e.lastWallSign?.let { put("lastWallSign", it) }
        e.lastReadAt?.let { put("lastReadAt", it) }; e.lastReadUrl?.let { put("lastReadUrl", it) }
        if (e.notes.isNotEmpty()) put("notes", JSONArray().apply { e.notes.forEach { put(JSONObject().put("text", it.text).put("at", it.at)) } })
    }

    internal fun fromJson(o: JSONObject): Entry? {
        val d = o.optString("domain").takeIf { it.isNotBlank() } ?: return null
        val notes = o.optJSONArray("notes")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { Note(it.optString("text"), it.optLong("at")) } }.orEmpty()
        return Entry(
            d, o.optInt("visits"), o.optInt("reads"), o.optInt("walls"), o.optLong("lastAt"),
            if (o.has("lastWallAt")) o.optLong("lastWallAt") else null, o.optString("lastWallSign").ifBlank { null },
            if (o.has("lastReadAt")) o.optLong("lastReadAt") else null, o.optString("lastReadUrl").ifBlank { null }, notes,
        )
    }
}
