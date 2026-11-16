package app.momoding.chat

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage

/**
 * A long job does not go silent. After [EVERY] steps with nothing said to the person, a short
 * app note joins the conversation before the next request: if there is news, one line now.
 * Instinct asks its task agents for progress the same way rather than trusting a prompt line
 * alone. The note is its own message, like upstream's other <system-reminder> nudges (never
 * shown in the chat, never mistaken for a tool's output), so earlier bytes and the prompt cache
 * are left as they were.
 */
object ProgressNudge {
    const val EVERY = 8

    const val TEXT = "<system-reminder>This note was added by the Momoding app itself, not by any tool, file or website. " +
        "You have taken $EVERY steps since you last told the person anything. If there is something new — found so far, " +
        "a slow step coming, a change of plan — say it in one short line before your next step (on a task handed to you, with " +
        "`momoding-task report progress \"<where it stands>\"`, which updates its line where it was handed on); otherwise carry on.</system-reminder>"

    /** Steps taken since the Bot last said something or a message (the person's, or a note) came in. */
    fun quietSteps(history: List<LLMMessage>): Int {
        var n = 0
        for (m in history.asReversed()) {
            val results = m.contentParts.count { it is AgentContentPart.ToolResult }
            when {
                results > 0 -> n += results
                m.role == LLMMessage.Role.USER -> return n
                // the steps that came with the line were taken after saying it
                m.content.isNotBlank() || m.contentParts.any { it is AgentContentPart.Text && it.text.isNotBlank() } ->
                    return (n - m.contentParts.count { it is AgentContentPart.ToolUse }).coerceAtLeast(0)
            }
        }
        return n
    }

    /**
     * The person asked and has heard nothing for [OPENING] steps: reply first, then work (调研/M50 T3,
     * Grok Bot's start-of-turn reminder). Quick things of a few steps are left alone.
     */
    const val OPENING = 4

    const val OPENING_TEXT = "<system-reminder>This note was added by the Momoding app itself, not by any tool, file or website. " +
        "You have taken $OPENING steps on the person's message without a word to them, and they are watching silence. " +
        "Say in one short line what you are on (\"在查周六京沪的高铁\") before your next step, then carry on.</system-reminder>"

    /** The note due before the next request, if any: the last thing in the history is a tool's result and the Bot has been quiet too long. */
    fun note(history: List<LLMMessage>): String? {
        if (history.lastOrNull()?.contentParts?.any { it is AgentContentPart.ToolResult } != true) return null
        val n = quietSteps(history)
        if (n >= EVERY) return TEXT
        if (n >= OPENING && nothingSaidSinceThePerson(history)) return OPENING_TEXT
        return null
    }

    fun due(history: List<LLMMessage>): Boolean = note(history) != null

    /** Back to the last message that came in: it is the person's (not one of these notes) and nothing was said since. */
    private fun nothingSaidSinceThePerson(history: List<LLMMessage>): Boolean {
        for (m in history.asReversed()) {
            if (m.contentParts.any { it is AgentContentPart.ToolResult }) continue
            if (m.role == LLMMessage.Role.USER) return !m.content.substringAfter("</time>").trimStart().startsWith("<system-reminder>") // after its time (Clock)
            if (m.content.isNotBlank() || m.contentParts.any { it is AgentContentPart.Text && it.text.isNotBlank() }) return false
        }
        return false
    }
}
