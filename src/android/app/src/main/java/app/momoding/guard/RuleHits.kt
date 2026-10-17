package app.momoding.guard

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * When a standing rule ("Always allow for calendar") lets something run without a card, the chat
 * says so in one line under that reply — Grok Bot's "approved by your rule · edit rules" — so the
 * person knows why they were not asked and where to change it. [RiskGate] records each hit; the
 * chat matches hits to replies by time (a hit belongs to the first reply finished after it).
 */
object RuleHits {
    data class Hit(val sessionId: String, val label: String, val at: Long)

    private const val PREFS = "momoding"
    private const val KEY = "rules.hits"
    private const val KEEP = 200
    private val _hits = MutableStateFlow<List<Hit>>(emptyList())
    val hits: StateFlow<List<Hit>> = _hits
    private var app: Context? = null

    fun init(context: Context) {
        app = context.applicationContext
        _hits.value = runCatching {
            val a = JSONArray(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "[]"))
            List(a.length()) { a.getJSONObject(it).let { o -> Hit(o.getString("s"), o.getString("l"), o.getLong("t")) } }
        }.getOrDefault(emptyList())
    }

    fun record(sessionId: String, label: String, at: Long = System.currentTimeMillis()) {
        val next = (_hits.value + Hit(sessionId, label, at)).takeLast(KEEP)
        _hits.value = next
        app?.let { c ->
            val a = JSONArray().apply { next.forEach { put(JSONObject().put("s", it.sessionId).put("l", it.label).put("t", it.at)) } }
            c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, a.toString()).apply()
        }
    }

    /** (time, label) for one chat, oldest first, for the chat's row builder. */
    fun forSession(all: List<Hit>, sessionId: String?): List<Pair<Long, String>> =
        if (sessionId == null) emptyList() else all.filter { it.sessionId == sessionId }.map { it.at to it.label }
}
