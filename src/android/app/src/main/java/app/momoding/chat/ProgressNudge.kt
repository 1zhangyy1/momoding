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
        "a slow step coming, a change of plan — say it in one short line before your next step; otherwise carry on.</system-reminder>"

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

    /** A note is due: the last thing in the history is a tool's result and the Bot has been quiet for [EVERY] steps. */
    fun due(history: List<LLMMessage>): Boolean =
        history.lastOrNull()?.contentParts?.any { it is AgentContentPart.ToolResult } == true && quietSteps(history) >= EVERY
}
