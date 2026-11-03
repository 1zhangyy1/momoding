package app.momoding.task

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage

/** What a completed tool loop proves when its hand did not explicitly report. */
data class TaskCompletion(val done: Boolean, val words: String) {
    companion object {
        fun fromHistory(helper: Boolean, previousTail: LLMMessage?, tail: LLMMessage?, error: String?): TaskCompletion {
            val finalText = tail?.takeIf {
                it !== previousTail && it.role == LLMMessage.Role.ASSISTANT &&
                    it.contentParts.none { part -> part is AgentContentPart.ToolUse }
            }?.content
            return afterTurn(helper, finalText, error)
        }

        fun afterTurn(helper: Boolean, finalText: String?, error: String?): TaskCompletion {
            val text = finalText?.trim()?.takeIf { it.isNotEmpty() }
            return when {
                !error.isNullOrBlank() -> TaskCompletion(false, "The hand could not finish: $error")
                helper && text != null -> TaskCompletion(true, text)
                helper -> TaskCompletion(false, "The helper ended without a final answer.")
                text != null -> TaskCompletion(false, "The Bot ended without reporting a result. Its final words: $text")
                else -> TaskCompletion(false, "The Bot ended without reporting a result.")
            }
        }
    }
}
