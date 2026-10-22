package app.momoding.chat

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage

/**
 * One tool result never floods the conversation. A web page read by hand-written JS, a long
 * listing or a log can be tens of thousands of characters; past [LIMIT_CHARS] the whole result
 * goes to a file and the model gets its start plus where the rest is (Instinct spills results
 * over ~10k tokens the same way, 调研/M18). Nothing is lost: it reads the file when it needs more.
 *
 * Applied to every outbound request rather than once when the result arrives, so a reloaded
 * session is capped too, and the stand-in is the same text each time, so the provider's prompt
 * cache still holds. The stored conversation keeps the full result.
 */
object LargeResults {
    const val LIMIT_CHARS = 20_000
    const val PREVIEW_CHARS = 6_000

    /**
     * [messages] with each tool result over [LIMIT_CHARS] cut to its start plus a pointer to
     * [save]'s file (a sandbox path, or "" when it could not be written: then it is left whole).
     * The same list instance when nothing is over.
     */
    fun cap(messages: List<LLMMessage>, save: (AgentContentPart.ToolResult) -> String): List<LLMMessage> {
        if (messages.none { m -> m.contentParts.any { it is AgentContentPart.ToolResult && it.content.length > LIMIT_CHARS } }) return messages
        return messages.map { m ->
            if (m.contentParts.none { it is AgentContentPart.ToolResult && it.content.length > LIMIT_CHARS }) m
            else m.copy(contentParts = m.contentParts.map { part ->
                if (part !is AgentContentPart.ToolResult || part.content.length <= LIMIT_CHARS) part
                else save(part).takeIf { it.isNotEmpty() }?.let { path -> part.copy(content = preview(part.content, path)) } ?: part
            })
        }
    }

    internal fun preview(content: String, path: String): String =
        content.take(PREVIEW_CHARS) +
            "\n\n[Long result: the first $PREVIEW_CHARS of ${content.length} characters are above. " +
            "The whole result is in $path — read it with file_read (offset / lines) only if you need more.]"
}
