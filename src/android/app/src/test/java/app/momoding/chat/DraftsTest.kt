package app.momoding.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import app.momoding.task.TaskEntity
import app.momoding.task.TaskLogEntity

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

    @Test fun `identical draft cards in different messages or chats do not share send state`() {
        val id = Drafts.Draft("Mom", "SMS", "hi").id
        assertNotEquals(Drafts.key("chat", "one", id), Drafts.key("chat", "two", id))
        assertNotEquals(Drafts.key("chat", "one", id), Drafts.key("other chat", "one", id))
    }

    private fun task(state: String, outcome: String? = null) =
        TaskEntity("draft", "chat", title = "send", hand = "chat", state = state, outcome = outcome, createdAt = 1, updatedAt = 1)
    private fun entry(kind: String, body: String) = TaskLogEntity(taskId = "draft", at = 1, kind = kind, body = body)

    @Test fun `an unconfirmed or stopped send never offers automatic retry`() {
        assertEquals(Drafts.Status.WAITING, Drafts.project(task("WAITING"), emptyList()).status)
        assertEquals(Drafts.Status.WAITING, Drafts.project(task("ENDED", "STOPPED"), emptyList()).status)
        assertEquals(Drafts.Status.WAITING, Drafts.project(task("ENDED", "FAILED"), emptyList()).status)
    }

    @Test fun `a carrier receipt arriving after stop still shows what was actually sent`() {
        val history = listOf(entry("DRAFT", """{"sms":true,"text":"exact edited text"}"""),
            entry("RESULT", """{"id":"send","success":true,"output":"all parts sent"}"""))
        val projected = Drafts.project(task("ENDED", "STOPPED"), history)
        assertEquals(Drafts.Status.SENT, projected.status)
        assertEquals("exact edited text", projected.text)
    }

    @Test fun `running and queued sends still owe their result`() {
        assertEquals(Drafts.Status.SENDING, Drafts.project(task("RUNNING"), emptyList()).status)
        assertEquals(Drafts.Status.SENDING, Drafts.project(task("QUEUED"), emptyList()).status)
    }

    @Test fun `an sms goes straight through the phone, any other app through the screen`() {
        assertTrue(Drafts.isSms(Drafts.Draft("张阿姨", "短信", "hi")))
        assertTrue(Drafts.isSms(Drafts.Draft("Mom", "SMS", "hi")))
        assertTrue(Drafts.isSms(Drafts.Draft("10086", "", "hi")))
        assertTrue(Drafts.isSms(Drafts.Draft("张阿姨", "", "hi", number = "138 0000 0000")))
        assertTrue(!Drafts.isSms(Drafts.Draft("张阿姨", "微信", "hi", number = "13800000000")))
        assertTrue(!Drafts.isSms(Drafts.Draft("张阿姨", "", "hi")))
    }

    @Test fun `the number rides along in the draft block`() {
        val d = Drafts.parse("""{"to":"张阿姨","app":"短信","text":"晚点到","number":"13800000000"}""")!!
        assertEquals("13800000000", d.number)
        assertEquals(Drafts.Draft("张阿姨", "短信", "晚点到").id, d.id)
    }
}
