package app.momoding.prompt

import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

/** momoding: the prompt's "now" line carries the weekday and the time (today/yesterday came out wrong without them). */
class MomodingPromptTest {
    @Test fun `now has weekday and time`() {
        val line = MomodingPrompt.now(LocalDateTime.of(2026, 10, 17, 23, 12))
        assertTrue(line, line.contains("2026-10-17 Saturday 23:12"))
    }

    /** momoding: with Chinese examples in the prompt, English questions got Chinese replies; the line says to follow the person. */
    @Test fun `now asks to reply in the person's language`() {
        val line = MomodingPrompt.now(LocalDateTime.of(2026, 10, 20, 9, 0))
        assertTrue(line, line.contains("Reply in the language the person writes in"))
        assertTrue(line, line.contains("link labels"))
        assertTrue(line, line.contains("show the form, not the language"))
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
}
