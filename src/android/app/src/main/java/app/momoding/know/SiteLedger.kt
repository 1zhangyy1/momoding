package app.momoding.know

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * What the Bot itself recorded about the websites it used (调研/M21): for each site, a few records
 * of an errand there — worked, walled off, or partly — and how. The agent writes them
 * (`momoding-know site …`, or the learner after a long job); judging what happened on a page is the
 * model's job, not a keyword list's. Code only does the reading back: the first time a chat opens
 * a site, its records ride along with the tool result, so the model sees them exactly then.
 *
 * Stored at /var/minis/memory/sites.json next to USER.md: the person can read it, backups carry it.
 * No record means unknown, not blocked.
 */
object SiteLedger {
    enum class Outcome { WORKED, WALLED, PARTIAL;
        companion object { fun parse(s: String?) = entries.firstOrNull { it.name.equals(s?.trim(), ignoreCase = true) } }
    }

    data class Record(val flow: String, val outcome: Outcome, val how: String, val at: Long)
    data class Entry(val domain: String, val records: List<Record> = emptyList(), val lastAt: Long = 0)
    /** One browser action of the current turn (site and action only), for the learner. */
    data class Step(val domain: String, val action: String)

    const val MAX_RECORDS = 5
    private const val KEEP_DOMAINS = 300

    private val lock = Any()
    private var cache: MutableMap<String, Entry>? = null
    private val shown = HashSet<String>()
    private val steps = HashMap<String, MutableList<Step>>()

    fun file(context: Context): File = File(File(context.filesDir, "minis-global/memory"), "sites.json")

    /**
     * After a browser action on [url]: notes the step for the learner, and returns this site's
     * records the first time this chat touches it (null when there are none or already shown).
     */
    fun onBrowse(context: Context, sessionId: String, action: String, url: String?, now: Long = System.currentTimeMillis()): String? {
        val domain = domainOf(url) ?: return null
        synchronized(lock) {
            steps.getOrPut(sessionId) { mutableListOf() }.add(Step(domain, action))
            if (!shown.add("$sessionId|$domain")) return null
            return load(context)[domain]?.let { render(it, now) }
        }
    }

    /** This turn's steps for [sessionId], cleared. */
    fun takeTurn(sessionId: String): List<Step> = synchronized(lock) { steps.remove(sessionId).orEmpty() }

    /** The agent's record of an errand on [site]; the same errand's older record is replaced. */
    fun record(context: Context, site: String, flow: String, outcome: Outcome, how: String, now: Long = System.currentTimeMillis()): Entry? {
        val domain = domainOf(site) ?: return null
        val f = KnowHow.sanitize(flow) ?: return null
        val h = KnowHow.sanitize(how) ?: return null
        synchronized(lock) {
            val map = load(context)
            val e = add(map[domain] ?: Entry(domain), Record(f, outcome, h, now))
            map[domain] = e
            save(context, map)
            return e
        }
    }

    /** Sites whose name or records contain [query]. */
    fun lookup(context: Context, query: String): List<Entry> {
        val q = query.trim().lowercase().removePrefix("www.")
        if (q.isEmpty()) return emptyList()
        return synchronized(lock) {
            load(context).values.filter { e -> e.domain.contains(q) || e.records.any { it.flow.lowercase().contains(q) || it.how.lowercase().contains(q) } }
                .sortedByDescending { it.lastAt }.take(8)
        }
    }

    // ── pure parts (tests) ─────────────────────────────────────────────────

    internal fun domainOf(url: String?): String? {
        val u = url?.trim().orEmpty()
        if (u.isEmpty() || u.startsWith("minis:") || u.startsWith("about:") || u.startsWith("data:") || u.startsWith("file:")) return null
        val host = runCatching { java.net.URI(if (u.contains("://")) u else "https://$u").host }.getOrNull() ?: return null
        return host.lowercase().removePrefix("www.").takeIf { it.contains('.') }
    }

    internal fun add(e: Entry, r: Record): Entry =
        e.copy(records = (listOf(r) + e.records.filterNot { it.flow.equals(r.flow, ignoreCase = true) }).take(MAX_RECORDS), lastAt = r.at)

    internal fun render(e: Entry, now: Long): String? {
        if (e.records.isEmpty()) return null
        val items = e.records.joinToString("; ") { "${it.flow}: ${it.outcome.name.lowercase()} ${ago(it.at, now)} — ${it.how}" }
        return "[Your own past records of ${e.domain}: $items. Records, not rules.]"
    }

    internal fun ago(at: Long, now: Long): String {
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
        if (map.size > KEEP_DOMAINS) map.values.sortedBy { it.lastAt }.take(map.size - KEEP_DOMAINS).forEach { map.remove(it.domain) }
        runCatching {
            val f = file(context); f.parentFile?.mkdirs()
            val arr = JSONArray().apply { map.values.sortedByDescending { it.lastAt }.forEach { put(toJson(it)) } }
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(JSONObject().put("sites", arr).toString(1))
            tmp.renameTo(f)
        }
    }

    internal fun toJson(e: Entry): JSONObject = JSONObject().put("domain", e.domain).put("lastAt", e.lastAt).put("records", JSONArray().apply {
        e.records.forEach { put(JSONObject().put("flow", it.flow).put("outcome", it.outcome.name.lowercase()).put("how", it.how).put("at", it.at)) }
    })

    internal fun fromJson(o: JSONObject): Entry? {
        val d = o.optString("domain").takeIf { it.isNotBlank() } ?: return null
        val recs = o.optJSONArray("records")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } }.orEmpty().mapNotNull { r ->
            Outcome.parse(r.optString("outcome"))?.let { Record(r.optString("flow"), it, r.optString("how"), r.optLong("at")) }
        }
        return Entry(d, recs, o.optLong("lastAt"))
    }
}
