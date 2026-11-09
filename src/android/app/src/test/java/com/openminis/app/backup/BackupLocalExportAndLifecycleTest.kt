package com.openminis.app.backup

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-backup-local-export] [T-android-backup-run-controller] Stage 1 of
 * the Android backup rework.
 *
 * Two user reports drove it:
 *  - "No way to back up locally on Android": Start refused outright unless an
 *    rclone / folder destination was configured, and there was no Save or
 *    Share exit for the package.
 *  - "Can't leave the backup screen": the run lived on the Backup screen's
 *    `viewModelScope`, so leaving the screen cancelled it mid-package.
 *
 * The run semantics live on [BackupRunController.RunResult] (pure, tested
 * directly). The wiring — which scope runs the job, whether Start is gated,
 * when Save / Share appear — needs Compose and a Context, so it is pinned
 * against the source, as the neighbouring backup tests do.
 */
class BackupLocalExportAndLifecycleTest {

    private fun outcome(name: String, ok: Boolean) =
        BackupHistory.DestinationOutcome(name, succeeded = ok, kind = "webdav", path = "/")

    // ── Run semantics ─────────────────────────────────────────────────────

    @Test
    fun `a run with no destination is local-only and not settled`() {
        val r = BackupRunController.RunResult(totalBytes = 10, skippedFiles = 0, destinations = emptyList())
        assertTrue(r.localOnly)
        assertFalse("nothing delivered it anywhere", r.allDelivered)
        assertFalse(
            "a local-only run nobody has saved must keep its card — it is the only prompt " +
                "to get the package out of the app",
            r.settled,
        )
    }

    @Test
    fun `saving a local-only run to the device settles it`() {
        val r = BackupRunController.RunResult(10, 0, emptyList(), savedAs = "backup-x.minisbak")
        assertTrue(r.settled)
    }

    @Test
    fun `a fully delivered run is settled and a partial one is not`() {
        assertTrue(BackupRunController.RunResult(1, 0, listOf(outcome("a", true))).settled)
        val partial = BackupRunController.RunResult(1, 0, listOf(outcome("a", true), outcome("b", false)))
        assertFalse("a failed destination is what the user must come back to", partial.settled)
        assertFalse("and it is not local-only — Save/Share are not offered", partial.localOnly)
    }
}
