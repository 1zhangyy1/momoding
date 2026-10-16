package app.momoding.chat

import org.json.JSONObject

/**
 * Whether a routine's result is worth a notification, as the model judged it. A routine's turn
 * ends with a fence the person never sees (the chat hides `momoding-*` fences):
 *
 * ```momoding-notify
 * {"notify": false, "text": "Nothing new in your inbox."}
 * ```
 *
 * `ScheduledAgentRunner` reads it: `notify: false` means stay quiet (the run is still recorded
 * and the reply is still in the chat); otherwise `text` is the notification's one line. A reply
 * without the fence, or with one that does not parse, notifies as before with the reply's start,
 * so a model that forgets the protocol never silences a routine.
 */
object NotifyDecision {
    const val BLOCK = "momoding-notify"

    data class Decision(val notify: Boolean, val body: String)

    private val FENCE = Regex("```" + BLOCK + "[^\\n]*\\n(.*?)\\n?```", RegexOption.DOT_MATCHES_ALL)
    private val ANY_MOMODING_FENCE = Regex("```momoding-[^\\n]*\\n.*?```", RegexOption.DOT_MATCHES_ALL)

    /** The reply with app-protocol fences removed: what the person would read. */
    fun visible(reply: String): String = ANY_MOMODING_FENCE.replace(reply, "").trim()

    /** [failed] runs always notify: an error is the one result nobody should have to go looking for. */
    fun decide(reply: String?, failed: Boolean, previewChars: Int = 200): Decision {
        val raw = reply.orEmpty()
        val fallback = visible(raw).take(previewChars).ifBlank { "(no response)" }
        if (failed) return Decision(true, fallback)
        val json = FENCE.findAll(raw).lastOrNull()?.groupValues?.get(1)
            ?.let { runCatching { JSONObject(it.trim()) }.getOrNull() }
            ?: return Decision(true, fallback)
        val text = json.optString("text").trim().take(previewChars)
        return Decision(json.optBoolean("notify", true), text.ifBlank { fallback })
    }

    /** Appended to a routine's framing (English like the rest of the system prompt). */
    const val INSTRUCTION =
        "End your reply with a fenced block the person never sees, telling the app whether this result is worth " +
            "interrupting them for:\n```" + BLOCK + "\n{\"notify\": true, \"text\": \"<one short line: the result itself>\"}\n```\n" +
            "Use \"notify\": false when nothing changed or nothing needs them (no new mail, no price move, all as usual); " +
            "the reply still stays in the chat. Use true when there is news, something due, or something needs their say."
}
