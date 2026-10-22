package app.momoding.know

import app.momoding.know.SiteLedger.Outcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SiteLedgerTest {
    private val now = 1_800_000_000_000L
    private val day = 24 * 3600_000L

    @Test
    fun `domains drop www, ignore app and data links`() {
        assertEquals("flights.ctrip.com", SiteLedger.domainOf("https://flights.ctrip.com/online/list?x=1"))
        assertEquals("jd.com", SiteLedger.domainOf("www.jd.com"))
        assertNull(SiteLedger.domainOf("minis://workspace/a.html"))
        assertNull(SiteLedger.domainOf("about:blank"))
        assertNull(SiteLedger.domainOf(null))
    }

    @Test
    fun `walls, reads and plain visits`() {
        assertEquals(Outcome.WALL, SiteLedger.classify("navigate", "Navigated to https://x.com\n  Title: 安全验证"))
        assertEquals(Outcome.VISIT, SiteLedger.classify("navigate", "Navigated to https://x.com\n  Title: 机票查询"))
        assertEquals(Outcome.WALL, SiteLedger.classify("get_readable", "{\"text\":\"请拖动滑块完成验证\"}"))
        assertEquals(Outcome.READ, SiteLedger.classify("get_readable", "G7 北京南 09:00 ".repeat(60)))
        // a long page that merely has a login link is content, not a wall
        assertEquals(Outcome.READ, SiteLedger.classify("get_text", "请先登录 " + "车次 时间 价格 ".repeat(400)))
        assertEquals(Outcome.VISIT, SiteLedger.classify("click", "clicked"))
    }

    @Test
    fun `the record counts and keeps the reading url without its query`() {
        var e = SiteLedger.Entry("m.ctrip.com")
        e = SiteLedger.record(e, Outcome.WALL, "https://m.ctrip.com/a", "滑块", now - 2 * day)
        e = SiteLedger.record(e, Outcome.READ, "https://m.ctrip.com/html5/flight?dcity=bjs#x", "x".repeat(700), now - day)
        assertEquals(2, e.visits); assertEquals(1, e.walls); assertEquals(1, e.reads)
        assertEquals("https://m.ctrip.com/html5/flight", e.lastReadUrl)
        val line = SiteLedger.render(e, now)!!
        assertTrue(line, line.contains("walled 1 of 2 visits (last 2 days ago, \"滑块\")"))
        assertTrue(line, line.contains("content read 1 times (last 24 h ago at https://m.ctrip.com/html5/flight)"))
    }

    @Test
    fun `nothing worth saying, nothing shown`() {
        assertNull(SiteLedger.render(SiteLedger.Entry("a.com", visits = 3, lastAt = now), now))
    }

    @Test
    fun `entries survive a round trip`() {
        val e = SiteLedger.Entry("a.com", 3, 1, 1, now, now, "captcha", now, "https://a.com/p", listOf(SiteLedger.Note("use the mobile page", now)))
        assertEquals(e, SiteLedger.fromJson(SiteLedger.toJson(e)))
    }
}
