package com.openminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** momoding: with steps hidden the chat keeps what was said and drops how it was done. */
class MomodingShowStepsTest {
    private fun user(id: String, text: String) = ChatMessage(id = id, role = "user", content = text)
    private fun assistant(id: String, vararg blocks: AssistantBlock) =
        ChatMessage(id = id, role = "assistant", content = "", toolBlocks = blocks.toList())
    private fun tool(id: String) = AssistantBlock(id = id, kind = "tool_use", content = "android-alarm set", toolStatus = ToolBlockStatus.SUCCESS)
    private fun text(id: String, t: String) = AssistantBlock(id = id, kind = "text", content = t)
    private fun info(id: String) = AssistantBlock(id = id, kind = "info", content = "🔄 Switched to GLM")

    private val transcript = listOf(
        user("u1", "明天下午 3 点提醒我"),
        assistant("a1", AssistantBlock(id = "th", kind = "thinking", content = "…"), tool("t1")), // a turn of steps only
        assistant("a2", info("i1"), tool("t2"), text("x2", "已设好明天 15:00 的闹钟")),
    )

    @Test fun `hidden steps leave the words and one header`() {
        val rows = buildFlatChatItems(transcript, showSteps = false)
        assertTrue(rows.none { it is FlatChatItem.AssistantToolUse || it is FlatChatItem.AssistantThinking || it is FlatChatItem.AssistantInfo })
        assertEquals(1, rows.count { it is FlatChatItem.AssistantHeader })
        assertTrue(rows.any { it is FlatChatItem.AssistantMarkdownBlock && it.rawText.contains("闹钟") })
        assertEquals(1, rows.count { it is FlatChatItem.UserBubble })
    }

    @Test fun `shown steps are unchanged`() {
        val rows = buildFlatChatItems(transcript, showSteps = true)
        assertEquals(2, rows.count { it is FlatChatItem.AssistantToolUse })
        assertEquals(1, rows.count { it is FlatChatItem.AssistantInfo })
    }

    @Test fun `a running tool still shows the typing dots when steps are hidden`() {
        val live = listOf(user("u1", "hi"), ChatMessage(id = "a1", role = "assistant", content = "", toolBlocks = listOf(tool("t1")), isStreaming = true))
        assertTrue(buildFlatChatItems(live, showSteps = false).any { it is FlatChatItem.AssistantTyping })
    }
}
