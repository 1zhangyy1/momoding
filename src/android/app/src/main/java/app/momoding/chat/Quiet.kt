package app.momoding.chat

/**
 * A turn the app started (a task's result came back, a routine fired) may have nothing worth
 * saying (调研/M50 T2, Grok Bot's silent wake). The model then replies with only this block:
 *
 * ```momoding-quiet
 * ```
 *
 * Nothing shows in the chat and nobody is notified. The reply stays in the history, so the
 * model knows it chose to stay quiet.
 */
object Quiet {
    const val BLOCK = "momoding-quiet"

    /** How to stay quiet, for the instructions of a turn the app started. */
    const val HOW = "reply with only this block and nothing else, so nothing shows and nobody is notified:\n```$BLOCK\n```"

    private val FENCE = Regex("```$BLOCK[\\s\\S]*?```")

    /** It asked for silence: the quiet block is there, whatever else was written with it (nobody is woken or notified). */
    fun asked(reply: String?): Boolean = reply != null && FENCE.containsMatchIn(reply)

    /** The reply chose silence: the quiet block (on one line or two), and nothing for the eye (nothing is drawn). */
    fun chose(reply: String?): Boolean =
        reply != null && FENCE.containsMatchIn(reply) && NotifyDecision.visible(FENCE.replace(reply, "")).isEmpty()
}
