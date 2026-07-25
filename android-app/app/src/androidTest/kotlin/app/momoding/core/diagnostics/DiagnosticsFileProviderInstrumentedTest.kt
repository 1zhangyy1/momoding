package app.momoding.core.diagnostics

import android.content.Intent
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.momoding.core.transport.SecureTransportUiPhase
import app.momoding.core.transport.SecureTransportUiStatus
import java.io.File
import java.security.MessageDigest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DiagnosticsFileProviderInstrumentedTest {
    @Test
    fun shellReceiverReadsOnlyDuringTemporaryGrantAndCanNeverWrite() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val targetContext = instrumentation.targetContext
        var now = 1_000L
        val exporter = DiagnosticsExporter(targetContext) { now }
        val archive = exporter.export(SecureTransportUiStatus(SecureTransportUiPhase.OFFLINE, hostAlias = "Synthetic Host"))
        val intent = exporter.shareIntent(archive)
        val uri = requireNotNull(intent.clipData?.getItemAt(0)?.uri)
        val archiveFile = File(File(targetContext.cacheDir, "diagnostics-export"), archive.alias)
        val originalHash = sha256(archiveFile)

        targetContext.revokeUriPermission(SHELL_PACKAGE, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            assertFalse(shellRead(uri.toString()).stdout.startsWith(ZIP_MAGIC))

            targetContext.grantUriPermission(SHELL_PACKAGE, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            val grantedRead = shellRead(uri.toString())
            assertTrue(grantedRead.stderr, grantedRead.stdout.startsWith(ZIP_MAGIC))

            val deniedWrite = shell("content write --uri $uri", "tamper".toByteArray())
            assertTrue(
                "write unexpectedly had no denial: ${deniedWrite.stderr}",
                deniedWrite.stderr.contains("denial", ignoreCase = true) ||
                    deniedWrite.stderr.contains("SecurityException", ignoreCase = true),
            )
            assertArrayEquals(originalHash, sha256(archiveFile))

            targetContext.revokeUriPermission(SHELL_PACKAGE, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            assertFalse(shellRead(uri.toString()).stdout.startsWith(ZIP_MAGIC))

            assertTrue(exporter.delete(archive))
            assertThrows(IllegalArgumentException::class.java) { exporter.shareIntent(archive) }

            val expired = exporter.export(SecureTransportUiStatus(SecureTransportUiPhase.READY))
            now = expired.expiresAtMillis
            assertThrows(IllegalArgumentException::class.java) { exporter.shareIntent(expired) }
            exporter.delete(expired)
        } finally {
            targetContext.revokeUriPermission(SHELL_PACKAGE, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            exporter.delete(archive)
        }
    }

    private fun shellRead(uri: String): ShellResult = shell("content read --uri $uri")

    private fun shell(command: String, stdin: ByteArray = byteArrayOf()): ShellResult {
        val descriptors = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommandRwe(command)
        ParcelFileDescriptor.AutoCloseOutputStream(descriptors[1]).use { output -> output.write(stdin) }
        val stdout = ParcelFileDescriptor.AutoCloseInputStream(descriptors[0]).use { it.readBytes() }
        val stderr = ParcelFileDescriptor.AutoCloseInputStream(descriptors[2]).use { it.bufferedReader().readText() }
        return ShellResult(stdout, stderr)
    }

    private fun sha256(file: File): ByteArray = MessageDigest.getInstance("SHA-256").digest(file.readBytes())

    private data class ShellResult(val stdout: ByteArray, val stderr: String)

    private companion object {
        const val SHELL_PACKAGE = "com.android.shell"
        val ZIP_MAGIC = byteArrayOf(0x50, 0x4b)

        private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
            size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }
    }
}
