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
}
