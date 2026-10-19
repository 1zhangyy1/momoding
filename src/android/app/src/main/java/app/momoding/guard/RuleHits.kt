package app.momoding.guard

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * Every approval decision leaves one line under the reply it belongs to, as in Grok Bot: a
 * standing rule let it through ("approved by your rule · edit rules"), or the person answered the
 * card (allowed once, from now on, denied, no answer in time). [RiskGate] records each; the chat
 * matches them to replies by time (one belongs to the first reply finished after it).
 */
object RuleHits {
    data class Hit(val sessionId: String, val label: String, val at: Long, val kind: String = RULE)

    const val RULE = "rule"; const val ONCE = "once"; const val SESSION = "session"; const val ALWAYS = "always"
    const val DENIED = "denied"; const val TIMEOUT = "timeout"

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
            List(a.length()) { a.getJSONObject(it).let { o -> Hit(o.getString("s"), o.getString("l"), o.getLong("t"), o.optString("k", RULE)) } }
        }.getOrDefault(emptyList())
    }

    fun record(sessionId: String, label: String, kind: String = RULE, at: Long = System.currentTimeMillis()) {
        val next = (_hits.value + Hit(sessionId, label, at, kind)).takeLast(KEEP)
        _hits.value = next
        app?.let { c ->
            val a = JSONArray().apply { next.forEach { put(JSONObject().put("s", it.sessionId).put("l", it.label).put("t", it.at).put("k", it.kind)) } }
            c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, a.toString()).apply()
        }
    }

    fun clear() {
        _hits.value = emptyList()
        app?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.edit()?.remove(KEY)?.apply()
    }

    /** (time, kind + label) for one chat, oldest first, for the chat's row builder; [decode] splits it. */
    fun forSession(all: List<Hit>, sessionId: String?): List<Pair<Long, String>> =
        if (sessionId == null) emptyList() else all.filter { it.sessionId == sessionId }.map { it.at to it.kind + SEP + it.label }

    fun decode(entry: String): Pair<String, String> =
        if (SEP in entry) entry.substringBefore(SEP) to entry.substringAfter(SEP) else RULE to entry

    private const val SEP = '\u001f'
}
