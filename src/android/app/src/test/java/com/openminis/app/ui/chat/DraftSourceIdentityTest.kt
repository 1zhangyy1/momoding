package com.openminis.app.ui.chat

import org.junit.Assert.*
import org.junit.Test

class DraftSourceIdentityTest {
    private val text = "```momoding-draft\n{\"to\":\"Fixture\",\"text\":\"exact text\"}\n```"
    private fun rows(message: ChatMessage) = buildFlatChatItems(listOf(message)).filterIsInstance<FlatChatItem.AssistantMarkdownBlock>()

    @Test fun persistingALiveReplyMakesItsCardActionableWithoutReplacingItsBubble() {
        val live = ChatMessage("assistant_live", "assistant", text, toolBlocks = listOf(AssistantBlock("text", "text", text)))
        val before = rows(live).single()
        val after = rows(live.copy(sourceDbIds = listOf("row"), blockSourceIds = mapOf("text" to "row"))).single()
        assertEquals(before.key, after.key)
        assertNull(before.sourceMessageId)
        assertEquals("row", after.sourceMessageId)
        assertNotEquals(before, after)
    }

    @Test fun identicalCardsInDifferentTurnsOfAMergedBubbleKeepTheirOwnPersistedSource() {
        val merged = ChatMessage("assistant_live", "assistant", text + text,
            toolBlocks = listOf(AssistantBlock("one", "text", text), AssistantBlock("two", "text", text)),
            sourceDbIds = listOf("first row", "second row"), blockSourceIds = mapOf("one" to "first row", "two" to "second row"))
        assertEquals(listOf("first row", "second row"), rows(merged).map { it.sourceMessageId })
    }
}
