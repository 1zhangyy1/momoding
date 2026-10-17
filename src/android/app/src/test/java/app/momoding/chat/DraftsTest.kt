package app.momoding.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** momoding: the draft fence parses, ids are stable per content, and the hands get the exact words. */
class DraftsTest {
    @Test fun `parses a draft`() {
        val d = Drafts.parse("""{"to": "张阿姨", "app": "微信", "text": "房租下周一转给您"}""")!!
        assertEquals("张阿姨", d.to); assertEquals("微信", d.app); assertEquals("房租下周一转给您", d.text)
    }

    @Test fun `incomplete or empty is not a draft`() {
        assertNull(Drafts.parse("""{"to": "张阿姨", "text": "房租"""))
        assertNull(Drafts.parse("""{"to": "张阿姨", "text": "  "}"""))
    }

    @Test fun `id is stable and content-bound`() {
        val a = Drafts.Draft("张阿姨", "微信", "hi")
        assertEquals(a.id, Drafts.Draft("张阿姨", "微信", "hi").id)
        assertNotEquals(a.id, Drafts.Draft("张阿姨", "微信", "hi!").id)
    }

    @Test fun `task carries the edited text and says stop rather than guess`() {
        val t = Drafts.task(Drafts.Draft("张阿姨", "微信", "old"), "new words")
        assertTrue(t.contains("new words")); assertTrue(!t.contains("old"))
        assertTrue(t.contains("in 微信")); assertTrue(t.contains("to 张阿姨")); assertTrue(t.contains("instead of guessing")); assertTrue(t.contains("start a new message"))
    }

    @Test fun `an sms to a number goes the prefilled way`() {
        assertTrue(Drafts.isSmsToNumber(Drafts.Draft("+15551234567", "短信", "hi")))
        assertTrue(!Drafts.isSmsToNumber(Drafts.Draft("张阿姨", "短信", "hi")))
        assertTrue(!Drafts.isSmsToNumber(Drafts.Draft("+15551234567", "微信", "hi")))
        assertTrue(Drafts.prefilledSmsTask(Drafts.Draft("+15551234567", "短信", "x"), "new words").contains("new words"))
    }
}
