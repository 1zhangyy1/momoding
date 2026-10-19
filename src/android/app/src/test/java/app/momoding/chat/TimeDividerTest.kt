package app.momoding.chat

import app.momoding.ui.chat.TimeDivider
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale

class TimeDividerTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private fun t(s: String) = LocalDateTime.parse(s).atZone(zone).toInstant().toEpochMilli()

    @Test fun firstMessageAndPausesGetADivider() {
        assertEquals(true, TimeDivider.shows(t("2026-10-19T16:30"), null, zone))
        assertEquals(false, TimeDivider.shows(t("2026-10-19T16:30"), t("2026-10-19T16:10"), zone))
        assertEquals(true, TimeDivider.shows(t("2026-10-19T17:30"), t("2026-10-19T16:10"), zone))
        assertEquals(true, TimeDivider.shows(t("2026-10-20T00:05"), t("2026-10-19T23:55"), zone))
    }

    @Test fun labels() {
        val now = t("2026-10-19T18:00")
        fun l(s: String) = TimeDivider.label(t(s), "今天 %1\$s", "昨天 %1\$s", now, zone, Locale.CHINA)
        assertEquals("今天 16:30", l("2026-10-19T16:30"))
        assertEquals("昨天 21:05", l("2026-10-18T21:05"))
        assertEquals("星期六 09:12", l("2026-10-17T09:12"))
        assertEquals("10月2日 09:12", l("2026-10-02T09:12"))
    }
}
