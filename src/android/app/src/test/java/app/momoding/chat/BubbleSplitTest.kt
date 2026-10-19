package app.momoding.chat

import app.momoding.ui.chat.BubbleSplit
import com.openminis.app.ui.chat.splitMarkdownIntoBlockTexts
import org.junit.Assert.assertEquals
import org.junit.Test

class BubbleSplitTest {
    private fun bubbles(text: String) = BubbleSplit.group(splitMarkdownIntoBlockTexts(text))

    @Test fun oneParagraphIsOneBubble() {
        assertEquals(listOf("在呢，有什么事？"), bubbles("在呢，有什么事？"))
    }

    @Test fun blankLineStartsANewBubble() {
        assertEquals(listOf("更正一下：Notion 不是聊天渠道。", "你想存到哪个页面？"),
            bubbles("更正一下：Notion 不是聊天渠道。\n\n你想存到哪个页面？"))
    }

    @Test fun listAndLinksStayWithTheirBubble() {
        val b = bubbles("明天有两件事：\n\n- 10:00 开会\n- 15:00 回电话\n\n[在日历里看](minis://views/calendar)\n\n要我提前提醒吗？")
        assertEquals(2, b.size)
        assertEquals("明天有两件事：\n\n- 10:00 开会\n- 15:00 回电话\n\n[在日历里看](minis://views/calendar)", b[0])
    }

    @Test fun cardStandsAlone() {
        val b = bubbles("写好了，发给张阿姨。\n\n```momoding-draft\n{\"to\":\"张阿姨\"}\n```\n\n- not attached to a card")
        assertEquals(3, b.size)
        assertEquals(true, b[1].startsWith("```momoding-draft"))
    }
}
