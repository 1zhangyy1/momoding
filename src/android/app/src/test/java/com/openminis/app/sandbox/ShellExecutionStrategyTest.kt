package com.openminis.app.sandbox

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-shell-fresh-process] Migration off in-band completion markers.
 *
 * Completion has always been detected by appending
 * `echo "__MINIS_DONE_<uuid>_EXIT_$?__"` to every command and scanning stdout
 * for it. That was forced by the warm-shell design: with a REUSED shell,
 * `Process.waitFor()` waits for the shell rather than the command, stdout has
 * no delimiters, and `$?` lives only in the guest.
 *
 * In-band signalling then failed three times in five months — a marker split
 * across two reads (command hung to its timeout), the hold-back buffer added
 * to fix that (every short output swallowed, measured on a Pixel 6), and a
 * timed-out command's bytes landing in the next command's result (#358). The
 * defect is structural: control and data share a channel the command can
 * write to.
 *
 * Measured on a Pixel 6 inside this project's PRoot Alpine guest, 20 runs:
 *
 *     sh -c "echo hi"          4.2 ms
 *     setsid sh -c "echo hi"   8.5 ms
 *     FIFO control round-trip  24.3 ms
 *
 * against 65-90 ms of per-tool-call host overhead. The cold-start objection
 * that motivated warm shells does not survive measurement.
 */
class ShellExecutionStrategyTest {

    // ── Step 1: the switch exists and changes nothing yet ──────────────────

    @Test
    fun `the fresh process path is what users now get`() {
        // Step 5. Steps 1-4 were deliberately invisible; this is the flip.
        assertEquals(ShellExecutionStrategy.FRESH_PROCESS, ShellExecutionStrategy.DEFAULT)
    }

    @Test
    fun `the warm shell survives as a rollback path`() {
        // Deleting it in the same change that adds the replacement would leave
        // no way back if the new path misbehaves on a device we cannot test.
        assertTrue(ShellExecutionStrategy.entries.contains(ShellExecutionStrategy.WARM_SHELL))
        assertTrue(ShellExecutionStrategy.entries.contains(ShellExecutionStrategy.FRESH_PROCESS))
    }

    // ── The properties that motivate the migration ─────────────────────────

    @Test
    fun `only the warm shell needs an in-band marker`() {
        // The marker exists solely because a reused shell cannot signal a
        // command boundary. A fresh process gets the code from waitFor(), so
        // appending one would be pure added risk.
        assertTrue(ShellExecutionStrategy.needsInBandMarker(ShellExecutionStrategy.WARM_SHELL))
        assertFalse(ShellExecutionStrategy.needsInBandMarker(ShellExecutionStrategy.FRESH_PROCESS))
    }

    @Test
    fun `only the warm shell must be recycled after a timeout`() {
        // A runaway keeps writing into a stdout that later commands read, so
        // even a successful kill leaves bytes that would be misattributed —
        // the #358 mechanism. A fresh process owns its pipes; they die with it.
        assertTrue(ShellExecutionStrategy.recyclesShellOnTimeout(ShellExecutionStrategy.WARM_SHELL))
        assertFalse(ShellExecutionStrategy.recyclesShellOnTimeout(ShellExecutionStrategy.FRESH_PROCESS))
    }

    // ── Step 2: the fresh-process executor ─────────────────────────────────

    private fun src(path: String): String {
        val f = File(path)
        assertTrue("missing ${f.absolutePath}", f.exists())
        return f.readText()
    }

    // ── Step 4: killing the whole guest tree on timeout ────────────────────

    // ── Step 3: the coordinator routes through the strategy ────────────────

    // ── Step 5: the flip, and the Stop button that had to come with it ─────

    // ── Detached daemons: proot outlives the command ──────────────────────

}
