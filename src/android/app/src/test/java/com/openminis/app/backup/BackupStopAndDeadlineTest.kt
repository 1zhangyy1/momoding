package com.openminis.app.backup

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * [T-android-backup-webdav-deadline] Two field problems, both fixed on iOS
 * first (ceac9df0b, c82fe8eb4) and reproduced here on a Pixel 4a:
 *  - backups to a slow WebDAV server always failed: rclone's `Timeout` is a
 *    flat response deadline counted from the end of the upload, and 45 s
 *    failed every server that needs longer to answer;
 *  - Stop could not interrupt an upload in flight: the upload was one
 *    blocking call and the run's cancellation never reached it.
 */
class BackupStopAndDeadlineTest {

    private val tmp: File = Files.createTempDirectory("backup-stop").toFile()

    @After
    fun tearDown() {
        tmp.deleteRecursively()
    }

    private fun src(path: String): String {
        val f = File(path)
        assertTrue("missing ${f.absolutePath}", f.exists())
        return f.readText()
    }

    // ── Stop during the file-tree export (behaviour) ──────────────────────

    private fun treeExporter(isCancelled: () -> Boolean): BackupFileTreeExporter {
        val staging = File(tmp, "staging").apply { mkdirs() }
        return BackupFileTreeExporter(
            BackupBlobStore(staging, null),
            BackupFileIndexWriter(File(staging, "files.index.jsonl")),
            isCancelled = isCancelled,
        )
    }

    private fun sourceTree(files: Int): File {
        val root = File(tmp, "source").apply { mkdirs() }
        repeat(files) { File(root, "f$it.txt").writeText("x$it") }
        return root
    }

    // ── Wiring (source) ───────────────────────────────────────────────────

    @Test
    fun `retry pause and tree checkpoint match iOS`() {
        assertEquals(3_000L, com.openminis.app.backup.remote.RcloneChunkedUpload.RETRY_BACKOFF_MS)
        assertEquals(32, BackupFileTreeExporter.CANCEL_CHECK_EVERY)
    }
}
