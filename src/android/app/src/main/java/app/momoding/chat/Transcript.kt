package app.momoding.chat

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage

/**
 * A conversation as plain text, for the two things compaction needs (Grok Bot does both):
 *
 * - the summary's input: everything that was said and done, fitted into [fit]'s budget fairly —
 *   the person's own words whole, everything else cut to one shared length, so short lines stay
 *   whole and one long page cannot crowd out the rest. Before, every line was cut to its first
 *   500 characters, so the end of anything long never reached the summary.
 * - the record kept beside the summary ([render] of the whole history), which the model greps when a
 *   detail did not make it into the summary: details do not depend on the summary, they can be
 *   looked up.
 */
object Transcript {
    /** One line of the conversation; [person] when it is the person's own words, kept whole. */
    data class Line(val who: String, val text: String, val person: Boolean = false)

    /** The person's message is never cut below this, so a pasted page cannot take everything. */
    const val MAX_PERSON_CHARS = 4_000

    fun lines(history: List<LLMMessage>): List<Line> = history.flatMap { msg ->
        val role = msg.role.name.lowercase()
        val user = msg.role == LLMMessage.Role.USER
        buildList {
            if (msg.content.isNotBlank()) add(Line(role, msg.content, user && isPerson(msg.content)))
            for (part in msg.contentParts) when (part) {
                is AgentContentPart.Text -> if (part.text.isNotBlank()) add(Line(role, part.text, user && isPerson(part.text)))
                is AgentContentPart.ToolUse -> add(Line("$role [tool:${part.name}]", part.input.toString()))
                is AgentContentPart.ToolResult -> add(Line("[result:${part.name}]", part.content))
                is AgentContentPart.ImageData -> add(Line(role, "[image: ${part.mimeType}]"))
            }
        }
    }

    /** The app's own notes and a helper's report ride in user messages; they are not the person. */
    private fun isPerson(text: String) = !text.startsWith("<system-reminder>") && !text.startsWith(app.momoding.task.TaskEngine.REPORT_CUE)

    fun render(lines: List<Line>): String = lines.joinToString("") { "${it.who}: ${it.text}\n" }

    /**
     * [lines] within about [maxChars]: the person's words whole (up to [MAX_PERSON_CHARS]), the
     * rest cut to the one length that fills what is left, each cut marked with what was left out.
     */
    fun fit(lines: List<Line>, maxChars: Int): List<Line> {
        val person = lines.filter { it.person }.sumOf { minOf(it.text.length, MAX_PERSON_CHARS) }
        val cap = waterLevel(lines.filterNot { it.person }.map { it.text.length }, maxOf(0, maxChars - person))
        return lines.map { line ->
            val limit = if (line.person) MAX_PERSON_CHARS else cap
            if (line.text.length <= limit) line
            else line.copy(text = line.text.take(limit) + " …[${line.text.length - limit} chars left out]")
        }
    }

    /** The largest length c with sum(min(len, c)) <= budget; no cut at all when everything fits. */
    internal fun waterLevel(lengths: List<Int>, budget: Int): Int {
        if (lengths.sum() <= budget) return Int.MAX_VALUE
        var left = budget
        val sorted = lengths.sorted()
        for ((i, len) in sorted.withIndex()) {
            val share = left / (sorted.size - i)
            if (len > share) return share
            left -= len
        }
        return Int.MAX_VALUE
    }
}
