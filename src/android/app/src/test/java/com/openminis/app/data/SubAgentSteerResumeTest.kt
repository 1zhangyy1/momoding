package com.openminis.app.data

import com.openminis.app.agent.jobs.HelperRunner
import org.junit.Assert.*
import org.junit.Test

/** Pure history/steer policies retained after the persistent task migration. */
class SubAgentSteerResumeTest {
    // ── Steer at loop end [T-subagent-steer-continues-loop] ────────────────

    @Test
    fun `a steer pending when the final answer ends buys one more turn`() {
        // The reported case: the correction arrived while the child was
        // writing its answer, the turn had no tool calls, and the loop broke —
        // leaving the steer to be reported as "never seen".
        assertTrue(HelperRunner.shouldContinueForSteer(isHelper = true, isEmptyTurn = false, turn = 3, turnCap = 20, pendingSteers = 1))
    }

    @Test
    fun `no pending steer means the child finishes as before`() {
        assertFalse(HelperRunner.shouldContinueForSteer(isHelper = true, isEmptyTurn = false, turn = 3, turnCap = 20, pendingSteers = 0))
    }

    @Test
    fun `a main conversation never continues for a steer`() {
        assertFalse(HelperRunner.shouldContinueForSteer(isHelper = false, isEmptyTurn = false, turn = 3, turnCap = 20, pendingSteers = 1))
    }

    @Test
    fun `the last permitted round does not continue into the turn-limit error`() {
        // turn is 0-based: turn 19 of a 20-turn cap is the last one. Another
        // iteration does not exist; the steer is reported as missed instead.
        assertFalse(HelperRunner.shouldContinueForSteer(isHelper = true, isEmptyTurn = false, turn = 19, turnCap = 20, pendingSteers = 1))
        assertTrue(HelperRunner.shouldContinueForSteer(isHelper = true, isEmptyTurn = false, turn = 18, turnCap = 20, pendingSteers = 1))
    }

    @Test
    fun `an empty turn that already gave up does not continue`() {
        assertFalse(HelperRunner.shouldContinueForSteer(isHelper = true, isEmptyTurn = true, turn = 3, turnCap = 20, pendingSteers = 1))
    }

    // ── Resume ──────────────────────────────────────────────────────────────

    @Test
    fun `the resume notice says what survived the restart and what did not`() {
        // The child's transcript still shows tool results whose side effects
        // are gone; without this it would keep referring to a closed tab.
        // Wording is iOS's verbatim, so the child reads the same brief on both
        // platforms.
        val n = HelperRunner.resumeNotice()
        assertTrue(n.contains("interrupted and has been resumed"))
        assertTrue("files survive", n.contains("Files in the workspace are still there"))
        assertTrue("tabs do not", n.contains("browser tabs are closed"))
        assertTrue("shell processes do not", n.contains("shell processes have ended"))
        assertTrue("tool results remain usable", n.contains("tool results above are still valid"))
    }

}
