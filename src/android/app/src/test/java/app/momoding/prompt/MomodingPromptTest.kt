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

    /** momoding: an English app with Chinese examples in the prompt replied in Chinese; the line names the app's language. */
    @Test fun `now names the app language and asks to follow the person's`() {
        val line = MomodingPrompt.now(LocalDateTime.of(2026, 10, 20, 9, 0), language = "English")
        assertTrue(line, line.contains("The app's screens are in English"))
        assertTrue(line, line.contains("language of the person's latest message"))
        assertTrue(line, line.contains("link labels"))
    }
}
