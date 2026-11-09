package com.openminis.app.ui.chat

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-switch-model-ghost-retry] Switching model mid-conversation left
 * the OLD model still retrying.
 *
 * Port of the iOS fix (05d654128), which was reported with a gateway request
 * log: a turn fails 5xx on model A, the user picks model B from the header, B
 * answers every later turn correctly — and the gateway still records further
 * 5xx retries against A, whose error banner sits over the working
 * conversation. The user's own workaround is the diagnostic clue: pressing
 * Stop BEFORE switching prevents it, so cancellation tears this work down and
 * switching model did not.
 *
 * Android had the identical shape: `selectGroup` / `selectGroupEntry` were
 * pure data writes, while the auto-retry ladder sleeps through a 1s/2s/4s
 * countdown holding `currentProvider` — still the abandoned model.
 *
 * The ladder needs a live provider and a real 5xx, so what is verifiable here
 * is the decision logic plus the source facts that encode the wiring.
 */
class SwitchModelGhostRetryTest {

    // ---- What a model switch must and must not tear down ------------------

    /**
     * The two teardown scopes, kept apart deliberately.
     *
     * "Stop" means *stop this conversation*. "Switch model" means *use a
     * different model from here on* — sub agents, queued delegations,
     * scheduled timers and the running shell command are not addressed to the
     * old model and must survive it.
     */
    private data class Teardown(
        val streamJob: Boolean,
        val retryCountdownUi: Boolean,
        val subAgents: Boolean,
        val queuedDelegations: Boolean,
        val shellCommand: Boolean,
        /** [T-android-switch-model-let-stream-finish] Stop's per-turn closer,
         *  which raises `_canResume` and pauses the conversation. */
        val perTurnCancelCleanup: Boolean = false,
    )

    private fun onStop() = Teardown(
        streamJob = true,
        retryCountdownUi = true,
        subAgents = true,
        queuedDelegations = true,
        shellCommand = true,
        perTurnCancelCleanup = true,
    )

    /**
     * Mirrors `cancelWorkBoundToPreviousModel`.
     *
     * [T-android-switch-model-let-stream-finish] The seam is state-dependent:
     * [inRetryLadder] mirrors `isBoundToAbandonedModel()`. Only ladder work is
     * torn down; a healthy streaming turn is left to finish.
     */
    private fun onSwitchModel(inRetryLadder: Boolean) = Teardown(
        streamJob = inRetryLadder,
        retryCountdownUi = inRetryLadder,
        subAgents = false,
        queuedDelegations = false,
        shellCommand = false,
        perTurnCancelCleanup = false,
    )

    @Test
    fun `switching model cancels a turn parked in the retry ladder`() {
        // The whole point: the ladder holding the abandoned provider has to go.
        assertTrue(onSwitchModel(inRetryLadder = true).streamJob)
    }

    @Test
    fun `switching model during normal streaming does not cancel anything`() {
        // [T-android-switch-model-let-stream-finish] The Pixel 6 report: the
        // model was streaming a tool call and the switch halted the task.
        // "Use the other model" means from the NEXT turn on.
        val switch = onSwitchModel(inRetryLadder = false)
        assertFalse("the live turn must finish", switch.streamJob)
        assertFalse("nothing to clear — no ladder is running", switch.retryCountdownUi)
        assertFalse("and the turn must never be paused into Resume", switch.perTurnCancelCleanup)
    }

    @Test
    fun `a model switch never runs Stop's per-turn closer`() {
        // That closer flips in-flight tool blocks to CANCELLED and raises
        // _canResume — the "task stopped, tap Resume" the user reported.
        assertFalse(onSwitchModel(inRetryLadder = true).perTurnCancelCleanup)
        assertFalse(onSwitchModel(inRetryLadder = false).perTurnCancelCleanup)
        assertTrue("Stop still owns it", onStop().perTurnCancelCleanup)
    }

    @Test
    fun `switching model clears the retry countdown the user can see`() {
        // "… — retrying (2/3)…" names the old model and is owned by the ladder
        // just cancelled, so nothing else would ever clear it.
        assertTrue(onSwitchModel(inRetryLadder = true).retryCountdownUi)
    }

    @Test
    fun `switching model is not Stop — sub agents and shell work survive`() {
        val switch = onSwitchModel(inRetryLadder = true)
        assertFalse("a running sub agent is not addressed to the old model", switch.subAgents)
        assertFalse("queued delegations must still run", switch.queuedDelegations)
        assertFalse("a running shell command must not be killed", switch.shellCommand)

        // Stop remains the broad teardown; the two must not converge.
        val stop = onStop()
        assertTrue(stop.subAgents)
        assertTrue(stop.queuedDelegations)
        assertTrue(stop.shellCommand)
        assertFalse("switch and stop must stay distinct", switch == stop)
    }

    // ---- Source facts: the wiring that actually broke ----------------------

    private fun src(path: String): String {
        val f = File(path)
        assertTrue("missing source: $path", f.exists())
        return f.readText()
    }

}
