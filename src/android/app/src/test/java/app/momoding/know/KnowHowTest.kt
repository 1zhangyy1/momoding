package app.momoding.know

import app.momoding.know.SiteLedger.Outcome
import app.momoding.know.SiteLedger.Step
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class KnowHowTest {
    private val day = LocalDate.of(2026, 10, 22)

    @Test
    fun `lines are cleaned, short, and keep nothing secret`() {
        assertEquals("Trains: m.ctrip.com/webapp/train reads fine", KnowHow.sanitize("- Trains: m.ctrip.com/webapp/train reads fine"))
        assertEquals("Read https://a.com/list first", KnowHow.sanitize("Read https://a.com/list?uid=42&t=x first"))
        assertNull(KnowHow.sanitize("记下他的支付密码"))
        assertNull(KnowHow.sanitize("ok"))
        assertEquals(KnowHow.MAX_CHARS, KnowHow.sanitize("x".repeat(500))!!.length)
    }

    @Test
    fun `newest first, replacing the line it supersedes, capped`() {
        val old = listOf("- Flights: try Ctrip first (2026-10-01)", "- Hotels: use Trip.com (2026-10-01)")
        val merged = KnowHow.merge(old, "Flights: skip Ctrip (walled), use the airline sites", "Flights: try Ctrip", day)
        assertEquals(listOf("- Flights: skip Ctrip (walled), use the airline sites (2026-10-22)", "- Hotels: use Trip.com (2026-10-01)"), merged)
        val many = (1..30).map { "- lesson $it (2026-10-01)" }
        assertEquals(KnowHow.MAX_LINES, KnowHow.merge(many, "new lesson", null, day).size)
    }

    @Test
    fun `the learner wakes only for hard web work`() {
        val read = Step("a.com", "get_readable", Outcome.READ, null)
        assertFalse(KnowHowLearner.worthLearning(List(3) { read }))
        assertTrue(KnowHowLearner.worthLearning(List(8) { read }))
        assertTrue(KnowHowLearner.worthLearning(listOf(Step("b.com", "navigate", Outcome.WALL, "滑块"))))
    }

    @Test
    fun `the question summarises sites, never page text`() {
        val steps = listOf(
            Step("flights.ctrip.com", "navigate", Outcome.VISIT, null),
            Step("flights.ctrip.com", "get_readable", Outcome.WALL, "滑块"),
            Step("www.airchina.com.cn", "get_readable", Outcome.READ, null),
        )
        val q = KnowHowLearner.question("对比京沪高铁和飞机", steps, "高铁 G7…", "[travel]\n(nothing yet)", "")
        assertTrue(q, q.contains("- flights.ctrip.com: 2 actions, 0 read content, 1 walled (\"滑块\")"))
        assertTrue(q, q.contains("- www.airchina.com.cn: 1 actions, 1 read content, 0 walled"))
    }

    @Test
    fun `lessons are parsed from json with prose around it, at most three of each`() {
        val l = KnowHowLearner.parse("好的：\n{\"sites\":[{\"domain\":\"flights.ctrip.com\",\"note\":\"滑块，别来\"}],\"know_how\":[{\"area\":\"travel\",\"line\":\"机票先查航司官网\",\"replaces\":\"\"}]}")
        assertEquals(listOf("flights.ctrip.com" to "滑块，别来"), l.sites)
        assertEquals(listOf(Triple("travel", "机票先查航司官网", null)), l.knowHow)
        assertTrue(KnowHowLearner.parse("nothing").sites.isEmpty())
    }
}
