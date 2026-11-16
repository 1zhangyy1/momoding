package app.momoding.prompt

import org.junit.Assert.assertTrue
import org.junit.Test

/** momoding: the prompt's "now" line carries the weekday and the time (today/yesterday came out wrong without them). */
class MomodingPromptTest {
    /** The time opens each message, with the weekday (today/yesterday came out wrong without it), from the row's own time. */
    @Test fun `a message's time has weekday and minute and never changes`() {
        val at = java.time.LocalDateTime.of(2026, 10, 17, 23, 12).atZone(java.time.ZoneId.of("Asia/Shanghai"))
        val stamp = app.momoding.chat.Clock.stamp(at.toInstant().toEpochMilli(), at.zone)
        assertTrue(stamp, stamp == "<time>2026-10-17 Saturday 23:12</time>\n")
    }

    /** The instructions carry the zone, not the minute, so they stay the same from request to request. */
    @Test fun `the instructions name the zone, not the time`() {
        val section = app.momoding.chat.Clock.promptSection(java.time.ZoneId.of("Asia/Shanghai"))
        assertTrue(section, section.contains("Asia/Shanghai") && section.contains("<time>") && !Regex("\\d\\d:\\d\\d").containsMatchIn(section))
    }

    /** momoding: Chinese examples and notes pulled English questions back to Chinese; the person's script decides. */
    @Test fun `language line follows the person's latest message`() {
        val en = MomodingPrompt.languageLine("Every morning at 8, tell me what to watch out for today")!!
        assertTrue(en, en.contains("not in Chinese") && en.contains("remembered line"))
        val zh = MomodingPrompt.languageLine("每天早上 8 点告诉我今天要注意的事")!!
        assertTrue(zh, zh.contains("is in Chinese"))
        val mixed = MomodingPrompt.languageLine("帮我查一下 Z175 的时刻")!!
        assertTrue(mixed, mixed.contains("is in Chinese"))
        assertTrue(MomodingPrompt.languageLine("ok") == null)
        assertTrue(MomodingPrompt.languageLine("") == null)
    }

    @Test fun `the coming days are listed, so this Saturday is read off, not worked out`() {
        val d = app.momoding.chat.Clock.days(java.time.LocalDate.of(2026, 11, 16))
        assertTrue(d, d.contains("Today is Monday 2026-11-16") && d.contains("Sat 11-21") && d.contains("Mon 11-23"))
    }
}
