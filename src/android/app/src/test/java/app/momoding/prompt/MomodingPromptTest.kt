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
}
