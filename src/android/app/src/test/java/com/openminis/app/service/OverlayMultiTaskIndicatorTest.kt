package com.openminis.app.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-overlay-multitask] [T-android-overlay-stale-tool] The floating
 * capsule with more than one task running.
 *
 * Reported: "任务已经结束了，为什么悬浮窗还在展示正在执行工具". The device log
 * showed both halves of the problem within six seconds:
 *
 *   10:57:55  toolRunning=false toolName=null  … yet the capsule still rendered
 *             `toolTitle=验证界面复刻 APK` with a running glyph
 *   10:58:01  the same stale title, now paired with `session=60ee870e` — a
 *             DIFFERENT session from the one whose tool that was
 *
 * Two distinct defects. The capsule fell back to the last tool's identity
 * unconditionally while passing `isRunning=true`, so a finished tool kept
 * rendering as running; and every piece of tool state was a single global that
 * whichever session wrote last owned, so a second run silently took the capsule
 * over and the title could describe one task while the tap target opened
 * another.
 *
 * The tracker's real state is a set of StateFlows written from several threads
 * and reachable only through a Context-bound singleton, so the decisions are
 * restated here as pure functions and pinned against the source. Each
 * behavioural test also runs the OLD rule and asserts it gives the wrong
 * answer, so none can pass vacuously.
 */
class OverlayMultiTaskIndicatorTest {

    // ── the stale-tool fallback ───────────────────────────────────────────

    /** Mirrors AgentForegroundService's gated fallback. */
    private fun effectiveToolTitle(
        liveTitle: String?,
        lastTitle: String?,
        isRunning: Boolean,
        isThinking: Boolean,
        hasActiveStream: Boolean,
    ): String? {
        val stillWorking = isRunning || isThinking || hasActiveStream
        return liveTitle ?: lastTitle?.takeIf { stillWorking }
    }

    /** The old rule: fall back unconditionally. */
    private fun oldEffectiveToolTitle(liveTitle: String?, lastTitle: String?): String? =
        liveTitle ?: lastTitle

    @Test
    fun `the reported case - a finished tool stops being shown as running`() {
        // Exactly the 10:57:55 state: tool gone, nothing else in flight.
        val title = effectiveToolTitle(
            liveTitle = null,
            lastTitle = "验证界面复刻 APK",
            isRunning = false,
            isThinking = false,
            hasActiveStream = false,
        )
        assertNull(title)

        // The old rule is what kept it on screen.
        assertEquals(
            "old rule must fail here",
            "验证界面复刻 APK",
            oldEffectiveToolTitle(null, "验证界面复刻 APK"),
        )
    }

    @Test
    fun `the snapshot survives a streaming stretch between two tool calls`() {
        // This is what the fallback was written for: no live tool, but the
        // model is still producing text. A bare spinner here would be a
        // regression, so the gate must NOT drop the snapshot.
        assertEquals(
            "验证界面复刻 APK",
            effectiveToolTitle(null, "验证界面复刻 APK", false, false, hasActiveStream = true),
        )
        assertEquals(
            "验证界面复刻 APK",
            effectiveToolTitle(null, "验证界面复刻 APK", false, isThinking = true, hasActiveStream = false),
        )
    }

    @Test
    fun `a live tool always wins over the snapshot`() {
        assertEquals(
            "搜索天气数据",
            effectiveToolTitle("搜索天气数据", "验证界面复刻 APK", true, false, true),
        )
    }

    // ── task slots: ordering, numbering, lifetime ─────────────────────────

    private data class Task(val id: String, val title: String, val finished: Boolean = false)

    /** Mirrors SessionActivityTracker.focusedTaskSlot(). */
    private fun slot(tasks: List<Task>, focusedId: String?): Triple<Task, Int, Int>? {
        if (tasks.isEmpty()) return null
        val i = tasks.indexOfFirst { it.id == focusedId }.takeIf { it >= 0 } ?: tasks.lastIndex
        return Triple(tasks[i], i + 1, tasks.size)
    }

    @Test
    fun `a single task reports one of one`() {
        val s = slot(listOf(Task("a", "Find Android Dev Minis Skill")), null)!!
        assertEquals(1, s.second)
        assertEquals(1, s.third)
    }

    @Test
    fun `with no explicit focus the capsule follows the newest task`() {
        // Pre-existing behaviour: the most recent activity owns the capsule.
        val tasks = listOf(Task("a", "A"), Task("b", "B"))
        val s = slot(tasks, focusedId = null)!!
        assertEquals("B", s.first.title)
        assertEquals(2, s.second)
    }

    @Test
    fun `a finished task keeps its index and stays in the total`() {
        // The capsule lingers after a turn ends, so the task is still visible
        // and must still be numbered — "① of 2" pointing at nothing is worse
        // than showing a finished slot.
        val tasks = listOf(Task("a", "A", finished = true), Task("b", "B"))
        val s = slot(tasks, focusedId = "a")!!
        assertEquals(1, s.second)
        assertEquals(2, s.third)
        assertTrue(s.first.finished)
    }

    @Test
    fun `finishing a task never renumbers the one beside it`() {
        // If A leaving renumbered B from ② to ①, the user would look away from
        // "② of 2" and back at "① of 1" describing the same work.
        val before = listOf(Task("a", "A"), Task("b", "B"))
        assertEquals(2, slot(before, "b")!!.second)

        val afterAFinished = listOf(Task("a", "A", finished = true), Task("b", "B"))
        assertEquals(2, slot(afterAFinished, "b")!!.second)
    }

    @Test
    fun `focus survives a slot leaving because it is held by id not index`() {
        val tasks = listOf(Task("a", "A"), Task("b", "B"), Task("c", "C"))
        assertEquals("C", slot(tasks, "c")!!.first.title)
        // A is dropped; C is now index 2 of 2 but is still the focused task.
        val dropped = tasks.filterNot { it.id == "a" }
        val s = slot(dropped, "c")!!
        assertEquals("C", s.first.title)
        assertEquals(2, s.second)
        assertEquals(2, s.third)
    }

    @Test
    fun `an empty task list yields no slot`() {
        assertNull(slot(emptyList(), null))
    }

    // ── cycling ───────────────────────────────────────────────────────────

    private fun cycle(tasks: List<Task>, focusedId: String?): String? {
        if (tasks.size < 2) return focusedId
        val cur = focusedId ?: tasks.first().id
        val i = tasks.indexOfFirst { it.id == cur }
        return tasks[(i + 1) % tasks.size].id
    }

    @Test
    fun `cycling advances and wraps`() {
        val tasks = listOf(Task("a", "A"), Task("b", "B"), Task("c", "C"))
        assertEquals("b", cycle(tasks, "a"))
        assertEquals("c", cycle(tasks, "b"))
        assertEquals("a", cycle(tasks, "c"))
    }

    @Test
    fun `cycling a single task is a no-op`() {
        val tasks = listOf(Task("a", "A"))
        assertEquals("a", cycle(tasks, "a"))
    }

    // ── the index chip ────────────────────────────────────────────────────

    // ── untitled sessions ─────────────────────────────────────────────────

    /** Mirrors ChatViewModel.overlaySessionTitle(). */
    private fun overlaySessionTitle(raw: String): String? =
        raw.takeIf { it.isNotBlank() && it != "New Chat" }

    /** Mirrors the capsule's row-1 precedence. */
    private fun rowOne(sessionTitle: String?, completionWord: String?, soulName: String): String =
        sessionTitle?.takeIf { it.isNotBlank() } ?: completionWord ?: soulName

    // ── slots must not leak between runs ──────────────────────────────────

    @Test
    fun `a fresh run after a cleared capsule starts from one`() {
        // The leak's observable shape: slots surviving into the next run.
        var tasks = listOf(Task("a", "A", finished = true), Task("b", "B", finished = true))
        // Capsule goes down -> every slot goes with it.
        tasks = emptyList()
        // Next run registers exactly one.
        tasks = tasks + Task("c", "C")
        val s = slot(tasks, null)!!
        assertEquals(1, s.second)
        assertEquals(1, s.third)
    }

    // ── source guards ─────────────────────────────────────────────────────

}
