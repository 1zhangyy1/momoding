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
    @Test fun `a retried original fire updates its outcome without counting twice`() {
        val failed = ScheduledRun(100, "chat", "failed", false, scheduled = true)
        val first = ScheduledTaskManager.recordRun(routine(), failed)
        val recovered = failed.copy(preview = "actual answer", ok = true, resultMessageId = "reply")
        val next = ScheduledTaskManager.recordRun(first, recovered)
        assertEquals(listOf(recovered), next.runHistory); assertEquals(1, next.fireCount)
        assertEquals("actual answer", next.lastResultPreview)
    }
    @Test fun `the fire and its result pointers survive settings serialization`() {
        val run = ScheduledRun(100, "chat", "preview", true, scheduled = true, workId = "work", resultMessageId = "reply", dispatchId = "dispatch")
        assertEquals(run, ScheduledRun.fromJson(run.toJson()))
        val task = routine(); val pending = PendingScheduledFire.create(task.copy(prompt = "substituted result"), 100)
        val restored = ScheduledTask.fromJson(task.copy(enabled = false, triggeredCount = 1, pendingDispatch = pending).toJson())
        assertEquals(pending, restored.pendingDispatch)
        assertEquals("substituted result", restored.pendingDispatch!!.task.prompt)
        assertNull(restored.pendingDispatch!!.task.pendingDispatch)
    }
    @Test fun `two fires in the same millisecond keep their own identities`() {
        val first = ScheduledRun(100, "chat", "one", true, dispatchId = "one")
        val second = first.copy(preview = "two", dispatchId = "two")
        val both = ScheduledTaskManager.recordRun(ScheduledTaskManager.recordRun(routine(), first), second)
        assertEquals(2, both.fireCount); assertEquals(setOf("one", "two"), both.runHistory.map { it.dispatchId }.toSet())
        assertEquals(both, ScheduledTaskManager.recordRun(both, second))
    }
}
