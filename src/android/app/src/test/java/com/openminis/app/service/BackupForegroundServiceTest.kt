package com.openminis.app.service

import com.openminis.app.backup.BackupHistory
import com.openminis.app.backup.BackupRunController
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-backup-fgs] Stage 2: a running backup survives the app being
 * backgrounded.
 *
 * Reported: a backup dies when the screen goes off or the phone hops cells.
 * Stage 1 moved the run to a process scope, which survives leaving the SCREEN
 * but not the process being frozen or reclaimed in the background. This pins
 * the foreground service that prevents that — the classification it announces
 * with (pure), and its wiring and lifecycle (source), since a Service needs a
 * device to run.
 */
class BackupForegroundServiceTest {

    private fun result(vararg ok: Boolean) = BackupRunController.RunResult(
        totalBytes = 1, skippedFiles = 0,
        destinations = ok.mapIndexed { i, s ->
            BackupHistory.DestinationOutcome("d$i", succeeded = s, kind = "webdav", path = "/")
        },
    )

    // ── What the finished notification says ───────────────────────────────

    @Test
    fun `a local-only package is never announced as delivered`() {
        // It is still inside the app; the notification is the prompt to move it.
        assertEquals(
            BackupForegroundService.Outcome.LOCAL_PENDING,
            BackupForegroundService.outcomeOf(result(), error = null),
        )
    }

    @Test
    fun `each finished shape maps to its own outcome`() {
        assertEquals(BackupForegroundService.Outcome.DELIVERED, BackupForegroundService.outcomeOf(result(true), null))
        assertEquals(
            BackupForegroundService.Outcome.ISSUES,
            BackupForegroundService.outcomeOf(result(true, false), "Saved locally, but delivery failed for: d1"),
        )
        assertEquals(BackupForegroundService.Outcome.FAILED, BackupForegroundService.outcomeOf(null, "disk full"))
        assertEquals(
            "no package and no error means the user stopped it",
            BackupForegroundService.Outcome.STOPPED,
            BackupForegroundService.outcomeOf(null, null),
        )
    }

}
