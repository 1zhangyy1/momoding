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
        assertNull(SiteLedger.domainOf(null))
    }

    @Test
    fun `a newer record of the same errand replaces the older, capped`() {
        var e = SiteLedger.Entry("kayak.com")
        e = SiteLedger.add(e, SiteLedger.Record("查机票", Outcome.WALLED, "首页验证", now - 2 * day))
        e = SiteLedger.add(e, SiteLedger.Record("查机票", Outcome.WORKED, "flights 页 get_readable 可读", now - day))
        assertEquals(1, e.records.size)
        assertEquals(Outcome.WORKED, e.records.single().outcome)
        repeat(8) { e = SiteLedger.add(e, SiteLedger.Record("errand $it", Outcome.PARTIAL, "x", now)) }
        assertEquals(SiteLedger.MAX_RECORDS, e.records.size)
    }

    @Test
    fun `the record reads back as one line, nothing when empty`() {
        val e = SiteLedger.Entry("kyfw.12306.cn", listOf(SiteLedger.Record("查余票", Outcome.WORKED, "余票查询页免登录可读", now - 3 * day)), now)
        val line = SiteLedger.render(e, now)!!
        assertTrue(line, line.contains("查余票: worked 3 days ago — 余票查询页免登录可读"))
        assertNull(SiteLedger.render(SiteLedger.Entry("a.com"), now))
    }

    @Test
    fun `outcomes parse loosely and entries survive a round trip`() {
        assertEquals(Outcome.WALLED, Outcome.parse(" Walled "))
        assertNull(Outcome.parse("blocked?"))
        val e = SiteLedger.Entry("a.com", listOf(SiteLedger.Record("f", Outcome.PARTIAL, "h", now)), now)
        assertEquals(e, SiteLedger.fromJson(SiteLedger.toJson(e)))
    }
}
