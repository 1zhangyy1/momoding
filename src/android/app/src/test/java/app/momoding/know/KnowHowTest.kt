package app.momoding.know

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class KnowHowTest {
    private val day = LocalDate.of(2026, 10, 22)

    @Test
    fun `lessons are cleaned, short, and keep nothing secret`() {
        assertEquals("Trains: m.ctrip.com/webapp/train reads fine", KnowHow.sanitize("- Trains: m.ctrip.com/webapp/train reads fine"))
        assertEquals("Read https://a.com/list first", KnowHow.sanitize("Read https://a.com/list?uid=42&t=x first"))
        assertNull(KnowHow.sanitize("记下他的支付密码"))
        assertNull(KnowHow.sanitize("ok"))
        assertEquals(KnowHow.MAX_CHARS, KnowHow.sanitize("x".repeat(500))!!.length)
    }

    @Test
    fun `topics are the agent's own names, safe as file names`() {
        assertEquals("travel", KnowHow.slug(" Travel "))
        assertEquals("北京-政务", KnowHow.slug("北京/政务"))
        assertNull(KnowHow.slug("/"))
    }

    @Test
    fun `newest first, replacing the line it supersedes, capped`() {
        val old = listOf("- Flights: try Ctrip first (2026-10-01)", "- Hotels: use Trip.com (2026-10-01)")
        val merged = KnowHow.merge(old, "Flights: skip Ctrip (walled), use the airline sites", "Flights: try Ctrip", day)
        assertEquals(listOf("- Flights: skip Ctrip (walled), use the airline sites (2026-10-22)", "- Hotels: use Trip.com (2026-10-01)"), merged)
        assertEquals(KnowHow.MAX_LINES, KnowHow.merge((1..30).map { "- lesson $it (2026-10-01)" }, "new lesson", null, day).size)
    }

    @Test
    fun `the learner wakes only after hard web work, and sees sites, not pages`() {
        val step = SiteLedger.Step("kayak.com", "get_readable")
        assertFalse(KnowHowLearner.worthLearning(List(3) { step }))
        assertTrue(KnowHowLearner.worthLearning(List(8) { step }))
        val q = KnowHowLearner.question("对比京沪高铁和飞机", listOf(SiteLedger.Step("flights.ctrip.com", "navigate"), SiteLedger.Step("flights.ctrip.com", "get_readable")), "高铁 G7…", "(none yet)", "")
        assertTrue(q, q.contains("- flights.ctrip.com: 2 (navigate×1, get_readable×1)"))
    }

    @Test
    fun `lessons parse from json with prose around it`() {
        val l = KnowHowLearner.parse("好的：{\"sites\":[{\"domain\":\"flights.ctrip.com\",\"flow\":\"查机票\",\"outcome\":\"walled\",\"how\":\"滑块验证\"}],\"lessons\":[{\"topic\":\"travel\",\"line\":\"机票先查航司官网\",\"replaces\":\"\"}]}")
        assertEquals(listOf(KnowHowLearner.SiteLesson("flights.ctrip.com", "查机票", SiteLedger.Outcome.WALLED, "滑块验证")), l.sites)
        assertEquals(listOf(Triple("travel", "机票先查航司官网", null)), l.knowHow)
        assertTrue(KnowHowLearner.parse("nothing").sites.isEmpty())
    }
}
