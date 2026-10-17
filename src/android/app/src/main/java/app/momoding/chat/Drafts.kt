package app.momoding.chat

import android.content.Context
import app.momoding.Identity
import app.momoding.guard.Grants
import app.momoding.guard.RiskClass
import app.momoding.hands.Hands
import app.momoding.hands.HandsOperator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.Locale

/**
 * Messages the Bot writes for the person to send (Grok Bot's draft bubble: pending → Send / Discard).
 * Neither OpenMinis nor nanoMuse has drafts, so this is new; the sending itself is nanoMuse's hands.
 *
 * The model ends its reply with
 *
 * ```momoding-draft
 * {"to": "张阿姨", "app": "微信", "text": "张阿姨好，房租下周一转给您～"}
 * ```
 *
 * and the chat draws it as a card. Send runs the hands with the (possibly edited) text and a
 * grant for outbound taps scoped to that one run, so the person, who just read the exact words
 * and tapped Send, is not asked a second time; the grant is dropped when the run ends.
 */
object Drafts {
    const val BLOCK = "momoding-draft"

    data class Draft(val to: String, val app: String, val text: String) {
        /** Stable per draft content, so the card's state survives recomposition and restarts. */
        val id: String get() = Integer.toHexString("$app\u0000$to\u0000$text".hashCode())
    }

    enum class Status { PENDING, SENDING, SENT, FAILED, DISCARDED }

    data class State(val status: Status, val text: String? = null, val note: String? = null)

    fun parse(code: String): Draft? = runCatching {
        val o = JSONObject(code.trim())
        val text = o.optString("text").trim()
        if (text.isEmpty()) null else Draft(o.optString("to").trim(), o.optString("app").trim(), text)
    }.getOrNull()

    private const val PREFS = "momoding"
    private const val KEY = "drafts.state."
    private val states = MutableStateFlow<Map<String, State>>(emptyMap())
    val all: StateFlow<Map<String, State>> = states

    fun state(context: Context, id: String): State = states.value[id] ?: run {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY + id, null)
        val s = raw?.let { runCatching { JSONObject(it) }.getOrNull() }?.let {
            State(Status.valueOf(it.optString("status", "PENDING")), it.optString("text").ifEmpty { null }, it.optString("note").ifEmpty { null })
        } ?: State(Status.PENDING)
        // A send cut off by the process dying did not finish; show it as not sent.
        (if (s.status == Status.SENDING) s.copy(status = Status.FAILED, note = null) else s).also { set(context, id, it, persist = false) }
    }

    fun set(context: Context, id: String, s: State, persist: Boolean = true) {
        states.value = states.value + (id to s)
        if (!persist) return
        val o = JSONObject().put("status", s.status.name).put("text", s.text ?: "").put("note", s.note ?: "")
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY + id, o.toString()).apply()
    }

    /** The task the hands get: exact words, the right chat, stop rather than guess. */
    internal fun task(d: Draft, text: String): String = buildString {
        append("Send a message for the user")
        if (d.app.isNotBlank()) append(" in ${d.app}")
        if (d.to.isNotBlank()) append(" to ${d.to}")
        append(". The user has read and approved exactly this text; type it unchanged, then tap Send once:\n")
        append(text)
        append("\nOpen the chat with that person first and check its title is them. If there is no such chat, or more than one ")
        append("person matches, stop and say so instead of guessing. After sending, check the message shows in the chat.")
    }

    /** Outlives the card: the hands are in another app while this runs, and the chat may be rebuilt. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** The person tapped Send on [d] with [text] (possibly edited). */
    fun start(context: Context, d: Draft, text: String) {
        val app = context.applicationContext
        set(app, d.id, State(Status.SENDING, text))
        scope.launch { set(app, d.id, send(app, d, text)) }
    }

    /** Runs the send. Returns the state to show; never throws. */
    private suspend fun send(context: Context, d: Draft, text: String): State = withContext(Dispatchers.IO) {
        if (!Hands.enabled(context) || !Hands.readiness(context).ready) {
            return@withContext State(Status.FAILED, text, "hands_off")
        }
        if (!Hands.claim()) return@withContext State(Status.FAILED, text, "busy")
        val runSession = "draft-${d.id}"
        Grants.grantSession(RiskClass.OUTBOUND, runSession, label = d.to.ifBlank { d.app })
        try {
            val r = HandsOperator(context).run(
                HandsOperator.Options(
                    task = task(d, text),
                    appHint = d.app.ifBlank { null },
                    sessionId = runSession,
                    agentName = Identity.name(context),
                    language = Locale.getDefault().getDisplayLanguage(Locale.ENGLISH).ifBlank { "the user's language" },
                )
            )
            when (val o = r.outcome) {
                is HandsOperator.Outcome.Done -> State(Status.SENT, text)
                is HandsOperator.Outcome.Stopped -> State(Status.FAILED, text, "stopped")
                is HandsOperator.Outcome.NeedsUser -> State(Status.FAILED, text, o.question)
                is HandsOperator.Outcome.Infeasible -> State(Status.FAILED, text, o.message)
                is HandsOperator.Outcome.Failed -> State(Status.FAILED, text, o.message)
            }
        } catch (t: Throwable) {
            State(Status.FAILED, text, t.message)
        } finally {
            Grants.clearSession(runSession)
            Hands.setActive(false)
        }
    }
}
