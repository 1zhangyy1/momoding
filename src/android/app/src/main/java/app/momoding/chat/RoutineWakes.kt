// Ported from nanoMuse (GPL-3.0): io/github/nanomuse/chat/RoutineWakes.kt
package app.momoding.chat

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A routine's turn is not the person's words. When a scheduled task fires (the feed's daily
 * routine, a goal check-in, a routine the person set up), its prompt goes into the conversation
 * as a user turn, since that is how a turn starts; the chat shows that row as one quiet line
 * ("Routine · Morning brief · 08:00") instead of a bubble in the person's voice, and the model
 * is told, for that one turn, that nobody typed it, so it opens the way an assistant does who
 * comes to you with the thing done.
 *
 * `ScheduledAgentRunner` calls [arm] just before the prompt is sent; the chat pipeline calls
 * [claim] for the user row it persists, which binds the label to that row's id for good (a small
 * JSON map in SharedPreferences, bounded). The chat screen reads [wakes] to draw the row. An
 * armed wake nobody claimed within a minute is dropped, so a line the person types later in the
 * same conversation is never mistaken for a routine's.
 */
object RoutineWakes {
    data class Wake(val label: String, val at: Long) {
        /** "08:00", in the phone's clock. */
        val time: String get() = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(at))
    }

    private const val PREFS = "momoding"
    private const val KEY = "routines.wakes"
    private const val KEEP = 400
    private const val CLAIM_WINDOW_MS = 60_000L
    private const val ADDENDUM_TAG = "nm-routine-wake"

    private val lock = Any()
    /** Session id → the wake armed there and not yet bound to a row. */
    private val pending = HashMap<String, Wake>()
    private val _wakes = MutableStateFlow<Map<String, Wake>>(emptyMap())
    /** Persisted user row id → the routine that sent it. */
    val wakes: StateFlow<Map<String, Wake>> = _wakes
    private var app: Context? = null

    fun init(context: Context) {
        app = context.applicationContext
        _wakes.value = load(context.applicationContext)
    }

    /** The runner is about to send a routine's prompt into [sessionId]: the next user row there is the routine's. */
    fun arm(context: Context, sessionId: String, label: String) {
        val wake = Wake(label.trim().take(60), System.currentTimeMillis())
        synchronized(lock) { pending[sessionId] = wake }
        SessionAddenda.add(sessionId, ADDENDUM_TAG, framing(wake), turns = 1)
        if (app == null) app = context.applicationContext
    }

    /** The prompt was not sent after all (no model, the chat gone): nothing to bind, nothing to tell the model. */
    fun disarm(sessionId: String) {
        val had = synchronized(lock) { pending.remove(sessionId) } != null
        if (had) SessionAddenda.remove(sessionId, ADDENDUM_TAG)
    }

    /** The chat pipeline persisted user row [messageId] in [sessionId]: it is the armed wake, if one is waiting. */
    fun claim(sessionId: String, messageId: String) {
        val wake = synchronized(lock) { pending.remove(sessionId) } ?: return
        if (System.currentTimeMillis() - wake.at > CLAIM_WINDOW_MS) return
        val next = LinkedHashMap(_wakes.value)
        next[messageId] = wake
        while (next.size > KEEP) next.remove(next.keys.first())
        _wakes.value = next
        app?.let { save(it, next) }
    }

    fun wakeFor(messageId: String): Wake? = _wakes.value[messageId]

    /** What the model is told for this one turn (not shown to the person; English like the rest of the system prompt). */
    private fun framing(wake: Wake): String = buildString {
        appendLine("## This turn is a routine's, not a message the person typed (Momoding)")
        append("The routine \"${wake.label}\" they set up fired at ${wake.time} and sent the next message on their behalf. ")
        append("Do what it asks, then write to them as the one who brings it up: open with the result or the point itself, ")
        append("do not thank them for asking or say \"as requested\", and keep it as short as the task allows. ")
        append("If something needs their say before you can act, ask plainly.")
    }

    private fun load(context: Context): Map<String, Wake> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: return emptyMap()
        return runCatching {
            val o = JSONObject(raw)
            val out = LinkedHashMap<String, Wake>()
            for (id in o.keys()) {
                val w = o.optJSONObject(id) ?: continue
                out[id] = Wake(w.optString("label"), w.optLong("at"))
            }
            out
        }.getOrDefault(emptyMap())
    }

    private fun save(context: Context, map: Map<String, Wake>) {
        val o = JSONObject()
        for ((id, w) in map) o.put(id, JSONObject().put("label", w.label).put("at", w.at))
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, o.toString()).apply()
    }
}
