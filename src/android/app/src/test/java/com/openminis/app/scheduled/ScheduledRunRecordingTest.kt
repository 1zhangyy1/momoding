package com.openminis.app.scheduled

import org.junit.Assert.*
import org.junit.Test

class ScheduledRunRecordingTest {
    private fun routine() = ScheduledTask(label = "fixture", timeOfDayHour = 9, timeOfDayMinute = 0,
        repeatMode = ScheduledRepeatMode.ONCE, prompt = "fixture")

    @Test fun `replaying the same completion does not increment history or count`() {
        val run = ScheduledRun(100, "hand", "full result", true)
        val once = ScheduledTaskManager.recordRun(routine(), run)
        assertEquals(once, ScheduledTaskManager.recordRun(once, run))
        assertEquals(listOf(run), once.runHistory)
        assertEquals(1, once.fireCount)
    }

    @Test fun `an older fire finishing last does not replace the latest result`() {
        val latest = ScheduledTaskManager.recordRun(routine(), ScheduledRun(200, "new", "new result", true))
        val both = ScheduledTaskManager.recordRun(latest, ScheduledRun(100, "old", "old result", false))
        assertEquals(listOf(200L, 100L), both.runHistory.map { it.firedAt })
        assertEquals(200L, both.lastFiredAt)
        assertEquals("new result", both.lastResultPreview)
        assertEquals("new", both.lastResultSessionId)
        assertEquals(2, both.fireCount)
    }
}
