package app.momoding.chat

import com.openminis.app.sandbox.offload.OffloadArgs
import com.openminis.app.scheduled.ScheduledRepeatMode
import com.openminis.app.scheduled.ScheduledTask
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class FireIfTest {
    @Test
    fun `run and skip are read at the start, anything else is unclear`() {
        assertEquals(true, FireIf.parse("RUN"))
        assertEquals(true, FireIf.parse(" run, still unpaid"))
        assertEquals(false, FireIf.parse("SKIP"))
        assertEquals(false, FireIf.parse("**Skip**"))
        assertNull(FireIf.parse("maybe"))
        assertNull(FireIf.parse(""))
        assertNull(FireIf.parse(null))
    }

    @Test
    fun `the question carries now, the condition and the messages`() {
        val q = FireIf.question("unless I said it is paid", "user: 水费交了", LocalDateTime.of(2026, 10, 23, 8, 0))
        assertTrue(q.startsWith("Now: 2026-10-23 Friday 08:00\nCondition: unless I said it is paid\n\nLatest messages:\nuser: 水费交了"))
        assertTrue(FireIf.question("x", "", LocalDateTime.of(2026, 10, 23, 8, 0)).endsWith("(none)"))
    }

    @Test
    fun `fireIf survives a round trip and is absent when unset`() {
        val t = ScheduledTask(label = "bill", timeOfDayHour = 8, timeOfDayMinute = 0, repeatMode = ScheduledRepeatMode.DAILY,
            prompt = "remind", fireIf = "unless I said it is paid")
        assertEquals(t, ScheduledTask.fromJson(t.toJson()))
        assertTrue(!t.copy(fireIf = null).toJson().has("fireIf"))
    }
}
