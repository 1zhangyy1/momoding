package app.momoding.core.diagnostics

import android.content.Intent
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import app.momoding.core.transport.SecureTransportUiPhase
import app.momoding.core.transport.SecureTransportUiStatus
import app.momoding.BuildConfig
import java.io.File
import java.util.zip.ZipFile
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DiagnosticsExporterTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private var now = 1_000L

    @Before
    fun cleanBefore() {
        File(context.cacheDir, "diagnostics-export").deleteRecursively()
    }

    @After
    fun cleanAfter() {
        File(context.cacheDir, "diagnostics-export").deleteRecursively()
    }

    @Test
    fun exportContainsOnlyAllowlistedSanitizedMetadata() {
        val exporter = DiagnosticsExporter(context) { now }
        val archive = exporter.export(
            SecureTransportUiStatus(
                phase = SecureTransportUiPhase.OFFLINE,
                hostAlias = "Synthetic Host",
                configRevision = 42,
            ),
        )
        val file = File(File(context.cacheDir, "diagnostics-export"), archive.alias)
        val text = ZipFile(file).use { zip ->
            assertEquals(listOf("diagnostics.json"), zip.entries().asSequence().map { it.name }.toList())
            zip.getInputStream(zip.getEntry("diagnostics.json")).bufferedReader().readText()
        }

        listOf(
            "deviceCredential",
            "pairingCode",
            "spkiPin",
            "authorization",
            "endpoint",
            "clientInstanceId",
            "deviceId",
            "credentialId",
            "prompt",
            "fileName",
            "content://",
        ).forEach { forbidden -> assertFalse("found $forbidden", text.contains(forbidden, ignoreCase = true)) }
        assertTrue(text.contains("Synthetic Host"))
        assertTrue(text.contains("OFFLINE"))
        assertTrue(BuildConfig.SOURCE_REVISION.matches(Regex("^[0-9a-f]{40}$")))
        assertTrue(text.contains(BuildConfig.SOURCE_REVISION))
        assertEquals(now + 15 * 60 * 1000L, archive.expiresAtMillis)
    }

    @Test
    fun shareIsReadOnlyAndExpiresOrDeletesClosed() {
        val exporter = DiagnosticsExporter(context) { now }
        val archive = exporter.export(SecureTransportUiStatus(SecureTransportUiPhase.READY))
        val intent = exporter.shareIntent(archive)

        assertEquals(Intent.ACTION_SEND, intent.action)
        assertEquals("application/zip", intent.type)
        assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertEquals(0, intent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        assertEquals(0, intent.flags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        assertEquals(0, intent.flags and Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
        assertTrue(intent.clipData?.getItemAt(0)?.uri.toString().startsWith("content://${context.packageName}.diagnostics-fileprovider/"))

        assertTrue(exporter.delete(archive))
        assertThrows(IllegalArgumentException::class.java) { exporter.shareIntent(archive) }

        val expired = exporter.export(SecureTransportUiStatus())
        now = expired.expiresAtMillis
        assertThrows(IllegalArgumentException::class.java) { exporter.shareIntent(expired) }
    }

    @Test
    fun fileProviderRejectsEveryFileOutsideDiagnosticsDirectory() {
        val outside = File(context.cacheDir, "outside.zip").apply { writeText("synthetic") }
        try {
            assertThrows(IllegalArgumentException::class.java) {
                FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.diagnostics-fileprovider",
                    outside,
                )
            }
        } finally {
            outside.delete()
        }
    }
}
