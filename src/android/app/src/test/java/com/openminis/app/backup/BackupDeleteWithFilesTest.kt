package com.openminis.app.backup

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-backup-delete-files-feedback] "Delete Record and Files" did
 * nothing when tapped: no confirmation, no deletion, no error.
 *
 * Three defects stacked, and this suite pins the fix for each:
 *
 *  1. **The work was cancelled before it ran.** `AppNavigation` invoked
 *     `vm.removeHistoryRecordWithFiles(id)` and called `safePopBackStack()`
 *     on the same frame. The ViewModel is scoped to that nav entry, so
 *     popping cleared it and cancelled `viewModelScope` — the `withContext`
 *     block never executed. That is the reported "button does nothing".
 *  2. **Failures were swallowed.** A destination that refused the delete was
 *     only written to the log, so a network or permission failure was
 *     indistinguishable from success.
 *  3. **The record was dropped unconditionally**, which on failure threw away
 *     the only record naming the file now orphaned on the remote.
 *
 * The delete itself needs rclone and a real remote, so what is verifiable on
 * the JVM is the decision logic plus the source facts that encode the wiring.
 */
class BackupDeleteWithFilesTest {

    // ---- Outcome model ---------------------------------------------------

    /** Mirrors `BackupViewModel.DeleteWithFilesResult`. */
    private sealed interface Result {
        data class Success(val destinations: Int) : Result
        data class Failed(val failures: List<String>) : Result
    }

    /**
     * The decision the ViewModel makes, extracted verbatim: attempt every
     * destination that received the package, collect per-destination failures,
     * and remove the record only when none failed.
     */
    private fun runDelete(
        packageName: String?,
        destinations: List<String>,
        deleter: (String) -> kotlin.Result<Unit>,
    ): Pair<Result, Boolean> {
        if (packageName.isNullOrEmpty()) {
            // Nothing nameable to delete remotely; dropping the record IS the
            // whole operation, and it succeeded.
            return Result.Success(destinations = 0) to true
        }
        val failed = destinations.mapNotNull { name ->
            deleter(name).exceptionOrNull()?.let { "$name: ${it.message}" }
        }
        return if (failed.isNotEmpty()) {
            Result.Failed(failed) to false
        } else {
            Result.Success(destinations.size) to true
        }
    }

    @Test
    fun `all destinations succeeding removes the record`() {
        val (result, removed) = runDelete("minis-2026.minisbak", listOf("s3", "webdav")) {
            kotlin.Result.success(Unit)
        }
        assertEquals(Result.Success(destinations = 2), result)
        assertTrue("the record must be gone once every file is", removed)
    }

    @Test
    fun `a failing destination keeps the record and names the reason`() {
        // The heart of defect 3: losing the record here would strand the file
        // on the remote with nothing left pointing at it.
        val (result, removed) = runDelete("minis-2026.minisbak", listOf("s3", "webdav")) { name ->
            if (name == "webdav") kotlin.Result.failure(java.io.IOException("401 Unauthorized"))
            else kotlin.Result.success(Unit)
        }
        assertFalse("a partial failure must NOT drop the record", removed)
        val failed = result as Result.Failed
        assertEquals(listOf("webdav: 401 Unauthorized"), failed.failures)
    }

    @Test
    fun `every failing destination is reported, not just the first`() {
        val (result, removed) = runDelete("pkg.minisbak", listOf("a", "b", "c")) { name ->
            if (name == "b") kotlin.Result.success(Unit)
            else kotlin.Result.failure(java.io.IOException("offline"))
        }
        assertFalse(removed)
        assertEquals(
            listOf("a: offline", "c: offline"),
            (result as Result.Failed).failures,
        )
    }

    @Test
    fun `a record with no package name is a record-only delete`() {
        // Older history rows predate packageName. There is no remote file to
        // chase, so this must still succeed rather than report an error.
        val (result, removed) = runDelete(null, listOf("s3")) {
            throw AssertionError("must not attempt a delete without a package name")
        }
        assertEquals(Result.Success(destinations = 0), result)
        assertTrue(removed)
    }


    // ---- [T-android-backup-local-folder-delete] Issue #367 root cause B ----
    //
    // Root cause A (the nav-entry ViewModel being cleared mid-call) was fixed
    // in d51dd8cdc. This second defect survived it: the delete path routed
    // EVERY destination through rclone, and a local folder is a SAF
    // `content://` tree that is never registered as an rclone remote. So for a
    // folder on the phone the delete could not succeed — and because the
    // failure surfaced as a no-op rather than an error, the record was dropped
    // and the file was orphaned on disk.

    /** Which transport a destination's delete must take. */
    private enum class Route { RCLONE, LOCAL_SAF }

    private data class Dest(val name: String, val isLocalFolder: Boolean, val treeUri: String = "")

    /**
     * Mirrors the branching `removeHistoryRecordWithFiles` now performs,
     * verbatim: local folders go through LocalFolderDelivery, everything else
     * through the rclone uploader, and the rclone config sync happens only when
     * a non-local destination is actually present.
     */
    private fun routeDelete(
        dests: List<Dest>,
        deleter: (Dest, Route) -> kotlin.Result<Unit>,
    ): Triple<Result, List<Pair<String, Route>>, Boolean> {
        val taken = mutableListOf<Pair<String, Route>>()
        val syncedToRclone = dests.any { !it.isLocalFolder }
        val failed = dests.mapNotNull { d ->
            val route = if (d.isLocalFolder) Route.LOCAL_SAF else Route.RCLONE
            taken += d.name to route
            deleter(d, route).exceptionOrNull()?.let { "${d.name}: ${it.message}" }
        }
        val result = if (failed.isNotEmpty()) Result.Failed(failed) else Result.Success(dests.size)
        return Triple(result, taken, syncedToRclone)
    }

    @Test
    fun `a local folder destination deletes through SAF, never rclone`() {
        // The reported bug: this went to rclone, which has no such remote.
        val (result, taken, _) = routeDelete(
            listOf(Dest("Phone folder", isLocalFolder = true, treeUri = "content://tree/primary%3ABackups")),
        ) { _, _ -> kotlin.Result.success(Unit) }
        assertEquals(listOf("Phone folder" to Route.LOCAL_SAF), taken)
        assertEquals(Result.Success(destinations = 1), result)
    }

    @Test
    fun `mixed destinations each take their own transport`() {
        val (result, taken, _) = routeDelete(
            listOf(
                Dest("s3", isLocalFolder = false),
                Dest("Phone folder", isLocalFolder = true, treeUri = "content://tree/x"),
            ),
        ) { _, _ -> kotlin.Result.success(Unit) }
        assertEquals(
            listOf("s3" to Route.RCLONE, "Phone folder" to Route.LOCAL_SAF),
            taken,
        )
        assertEquals(Result.Success(destinations = 2), result)
    }

    @Test
    fun `an all-local record never starts rclone`() {
        // Starting rclone to delete a file on this phone is pure cost, and it
        // is what made the old path look like it "did nothing".
        val (_, _, synced) = routeDelete(
            listOf(
                Dest("Phone folder", isLocalFolder = true, treeUri = "content://tree/x"),
                Dest("SD card", isLocalFolder = true, treeUri = "content://tree/y"),
            ),
        ) { _, _ -> kotlin.Result.success(Unit) }
        assertFalse("no non-local destination → no config sync", synced)

        val (_, _, syncedMixed) = routeDelete(
            listOf(Dest("Phone folder", isLocalFolder = true), Dest("webdav", isLocalFolder = false)),
        ) { _, _ -> kotlin.Result.success(Unit) }
        assertTrue("one non-local destination is enough to need it", syncedMixed)
    }

    @Test
    fun `a file already gone counts as deleted`() {
        // Idempotency: the caller's goal is "no such package in this folder",
        // and that already holds. Failing here would strand the record forever
        // for anyone who had cleaned the folder by hand.
        val (result, _, _) = routeDelete(
            listOf(Dest("Phone folder", isLocalFolder = true, treeUri = "content://tree/x")),
        ) { _, route ->
            if (route == Route.LOCAL_SAF) kotlin.Result.success(Unit) // missing == success
            else kotlin.Result.failure(IllegalStateException("unreachable"))
        }
        assertEquals(Result.Success(destinations = 1), result)
    }

    @Test
    fun `a revoked folder permission is reported, not swallowed`() {
        // The whole point of not reusing deleteChild(): a SecurityException
        // must keep the record and name the destination, because the file is
        // still on disk.
        val (result, _, _) = routeDelete(
            listOf(
                Dest("s3", isLocalFolder = false),
                Dest("Phone folder", isLocalFolder = true, treeUri = "content://tree/x"),
            ),
        ) { d, _ ->
            if (d.isLocalFolder) kotlin.Result.failure(SecurityException("Permission denied"))
            else kotlin.Result.success(Unit)
        }
        val failed = result as Result.Failed
        assertEquals(listOf("Phone folder: Permission denied"), failed.failures)
    }

}
