package app.momoding.chat

import app.momoding.ui.chat.LinkLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** momoding: only a line made of links becomes buttons; links in a sentence stay text. */
class LinkLineTest {
    @Test fun `a line of links`() =
        assertEquals(listOf("在日历里看" to "minis://views/calendar", "打开设置" to "minis://settings"),
            LinkLine.parse("[在日历里看](minis://views/calendar) · [打开设置](minis://settings)"))
    @Test fun `a sentence with a link`() = assertNull(LinkLine.parse("已存入时钟（[查看](minis://views/alarm)）。"))
    @Test fun `an image is not a button`() = assertNull(LinkLine.parse("![图](minis://attachments/a.png)"))

    @Test fun `trailing links split off a merged block`() {
        val (text, links) = LinkLine.splitTrailing("已记上：周三 16:00 取快递。\n\n[在日历里看](minis://views/calendar?at=2026-10-21T16:00)")!!
        assertEquals("已记上：周三 16:00 取快递。", text)
        assertEquals(listOf("在日历里看" to "minis://views/calendar?at=2026-10-21T16:00"), links)
        assertNull(LinkLine.splitTrailing("没有链接。"))
    }

    @Test fun `a links line followed by a question still becomes buttons`() {
        val (text, links) = LinkLine.splitTrailing("定了：明天 14:00 交房租。\n\n[在日历里看](minis://views/calendar?at=x)\n\n对了，我该怎么称呼你？")!!
        assertEquals("定了：明天 14:00 交房租。\n\n对了，我该怎么称呼你？", text)
        assertEquals(1, links.size)
    }
}
