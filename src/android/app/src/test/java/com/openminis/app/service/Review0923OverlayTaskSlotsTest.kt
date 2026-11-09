package com.openminis.app.service

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Review 2026-09-23 — overlay capsule task slots, driven through the REAL
 * [SessionActivityTracker] (not a re-statement of it).
 *
 * Guards commits 07762f32e (T-android-overlay-multitask, stale tool),
 * c9e859dca (slots cleared whenever the capsule goes down) and a3a871fe2
 * (untitled session title).
 *
 * The overlay area was fixed three times in one day, and each fix moved the
 * same invariant: the slot list must describe exactly the tasks the capsule
 * can show — no finished slots leaking into the NEXT run (c9e859dca), but also
 * no RUNNING task losing its slot while it is still running. c9e859dca solved
 * the first half by clearing every slot on every hide, including the
 * foreground transition, the camera suppression and the dynamic-island branch.
 * A task still running across one of those hides is never re-registered
 * (setActive runs once per send), so when the capsule comes back it has no
 * slot: no session title on row 1, and no "n of m" even with two tasks live.
 *
 * `SessionActivityTracker` runs fine on the JVM here: `appContext` is null,
 * so service start/stop are logged no-ops, and android.* stubs return
 * defaults (unitTests.isReturnDefaultValues = true).
 */
class Review0923OverlayTaskSlotsTest {

    private val ids = listOf("review0923-a", "review0923-b", "review0923-c")

    @Before
    fun reset() = cleanup()

    @After
    fun cleanup() {
        ids.forEach { SessionActivityTracker.setInactive(it) }
        SessionActivityTracker.clearOverlayTasks()
    }

    @Test
    fun `two concurrent runs are numbered by start order and focus follows the newest`() {
        SessionActivityTracker.setActive(ids[0], sessionTitle = "Task A")
        SessionActivityTracker.setActive(ids[1], sessionTitle = "Task B")
        val slot = SessionActivityTracker.focusedTaskSlot()
        assertNotNull(slot)
        assertEquals(ids[1], slot!!.first.sessionId)
        assertEquals(2, slot.second)
        assertEquals(2, slot.third)
    }

    @Test
    fun `a finished run keeps its number while the capsule lingers`() {
        SessionActivityTracker.setActive(ids[0], sessionTitle = "Task A")
        SessionActivityTracker.setActive(ids[1], sessionTitle = "Task B")
        SessionActivityTracker.setInactive(ids[0])
        val tasks = SessionActivityTracker.overlayTasks.value
        assertEquals(listOf(ids[0], ids[1]), tasks.map { it.sessionId })
        assertTrue(tasks[0].finished)
        assertFalse(tasks[1].finished)
    }

    @Test
    fun `a re-run keeps its slot position and a null or blank title does not erase the known one`() {
        SessionActivityTracker.setActive(ids[0], sessionTitle = "Task A")
        SessionActivityTracker.setActive(ids[1], sessionTitle = "Task B")
        SessionActivityTracker.setInactive(ids[0])
        // Untitled re-run (overlaySessionTitle() maps the sentinel to null).
        SessionActivityTracker.setActive(ids[0], sessionTitle = null)
        val tasks = SessionActivityTracker.overlayTasks.value
        assertEquals(listOf(ids[0], ids[1]), tasks.map { it.sessionId })
        assertEquals("Task A", tasks[0].title)
        assertFalse(tasks[0].finished)
        SessionActivityTracker.setActive(ids[0], sessionTitle = "  ")
        assertEquals("Task A", SessionActivityTracker.overlayTasks.value[0].title)
    }

    /**
     * [BUG, latent — no gesture calls cycleFocusedTask() yet] With no explicit
     * focus, focusedTaskSlot() shows the NEWEST slot, but cycleFocusedTask()
     * starts from the FIRST (`_focusedTaskId.value ?: tasks.first()`), so the
     * first tap on "3 of 3" lands on "2 of 3" instead of wrapping to "1 of 3".
     * Fix: default `cur` to `tasks.last().sessionId`.
     */
    @Test
    fun `the first cycle advances from the slot actually being shown`() {
        SessionActivityTracker.setActive(ids[0], sessionTitle = "A")
        SessionActivityTracker.setActive(ids[1], sessionTitle = "B")
        SessionActivityTracker.setActive(ids[2], sessionTitle = "C")
        assertEquals(ids[2], SessionActivityTracker.focusedTaskSlot()!!.first.sessionId)
        SessionActivityTracker.cycleFocusedTask()
        assertEquals(
            "showing 3 of 3, one cycle must wrap to 1 of 3",
            ids[0],
            SessionActivityTracker.focusedTaskSlot()!!.first.sessionId,
        )
    }

    @Test
    fun `focus held on a removed slot falls back to the newest`() {
        SessionActivityTracker.setActive(ids[0], sessionTitle = "A")
        SessionActivityTracker.setActive(ids[1], sessionTitle = "B")
        SessionActivityTracker.setActive(ids[2], sessionTitle = "C")
        // Focus A explicitly (cycle twice from B/C wraps to A regardless of start).
        while (SessionActivityTracker.focusedTaskSlot()!!.first.sessionId != ids[0]) {
            SessionActivityTracker.cycleFocusedTask()
        }
        SessionActivityTracker.removeOverlayTask(ids[0])
        val slot = SessionActivityTracker.focusedTaskSlot()!!
        assertEquals(ids[2], slot.first.sessionId)
        assertEquals(2, slot.second)
        assertEquals(2, slot.third)
    }

}
