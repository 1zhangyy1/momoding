package app.momoding.core.update

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdateFileStoreTest {
    @Test
    fun `keeps only the active release download files`() {
        val directory = Files.createTempDirectory("momoding-update-store-").toFile()
        val staleNames = listOf(
            "momoding-0.1.0-alpha.1.apk",
            "momoding-0.1.0-alpha.1.apk.part",
            "momoding-0.1.0-alpha.1.apk.part.sha256",
            "momoding-0.1.0-alpha.1.apk.part.sha256.tmp",
        )
        staleNames.forEach { File(directory, it).writeText("stale") }
        val currentPartial = File(directory, "momoding-0.1.0-alpha.2.apk.part").apply {
            writeText("keep-progress")
        }
        val currentBinding = File(directory, "momoding-0.1.0-alpha.2.apk.part.sha256").apply {
            writeText("keep-binding")
        }
        val unrelated = File(directory, "user-note.txt").apply { writeText("keep") }

        val files = AppUpdateFileStore(directory).prepareFor(release("0.1.0-alpha.2"))

        staleNames.forEach { assertFalse(File(directory, it).exists()) }
        assertEquals(currentPartial, files.partial)
        assertEquals(currentBinding, files.binding)
        assertEquals("keep-progress", currentPartial.readText())
        assertEquals("keep-binding", currentBinding.readText())
        assertTrue(unrelated.isFile)
    }

    @Test
    fun `rejects release asset names that could escape the update slot`() {
        val directory = Files.createTempDirectory("momoding-update-store-").toFile()
        val invalid = release("0.1.0-alpha.2").copy(
            apk = AppReleaseAsset("../momoding.apk", "https://github.com/apk", 10),
        )

        assertThrows(IllegalArgumentException::class.java) {
            AppUpdateFileStore(directory).prepareFor(invalid)
        }
    }

    @Test
    fun `checksum change resets a same-name partial before binding the new release`() {
        val directory = Files.createTempDirectory("momoding-update-store-").toFile()
        val partial = File(directory, "momoding.apk.part").apply { writeText("old-release") }
        val binding = File(directory, "momoding.apk.part.sha256").apply { writeText("a".repeat(64)) }
        val expectedChecksum = "b".repeat(64)

        prepareBoundPartialDownload(partial, binding, expectedChecksum, expectedSize = 100)

        assertFalse(partial.exists())
        assertEquals(expectedChecksum, binding.readText().trim())
    }

    private fun release(version: String) = AppRelease(
        versionName = version,
        tagName = "v$version",
        title = "Momoding $version",
        notes = "Notes",
        publishedAt = null,
        apk = AppReleaseAsset("momoding-$version.apk", "https://github.com/apk", 10),
        checksum = AppReleaseAsset(
            "momoding-$version.apk.sha256",
            "https://github.com/checksum",
            93,
        ),
    )
}
