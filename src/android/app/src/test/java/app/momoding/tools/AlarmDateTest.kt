package app.momoding.tools

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDateTime

/** momoding: a one-off alarm on a day other than the next occurrence is refused (it used to ring a day early). */
class AlarmDateTest {
    private val justAfterMidnight = LocalDateTime.of(2026, 10, 18, 0, 2)

    @Test fun `tomorrow 15h just after midnight is refused`() =
        assertNotNull(AlarmDate.refusal("2026-10-19T15:00", null, justAfterMidnight))
    @Test fun `today 15h is fine`() = assertNull(AlarmDate.refusal("2026-10-18T15:00", "ONCE", justAfterMidnight))
    @Test fun `plain HH-MM is fine`() = assertNull(AlarmDate.refusal("15:00", null, justAfterMidnight))
    @Test fun `daily ignores the date`() = assertNull(AlarmDate.refusal("2026-10-25T07:00", "DAILY", justAfterMidnight))
    @Test fun `tomorrow 7h at night is the next occurrence`() =
        assertNull(AlarmDate.refusal("2026-10-19T07:00", null, LocalDateTime.of(2026, 10, 18, 23, 0)))
}

class CalendarLinkTimeTest {
    @Test fun `iso and epoch both work`() {
        org.junit.Assert.assertEquals(1792393200000L, AlarmDate.epochMs("1792393200000"))
        assertNotNull(AlarmDate.epochMs("2026-10-19T15:00"))
        assertNull(AlarmDate.epochMs("tomorrow"))
    }
}
