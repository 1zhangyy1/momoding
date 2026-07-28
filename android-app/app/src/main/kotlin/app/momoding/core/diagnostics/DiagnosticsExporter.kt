package app.momoding.core.diagnostics

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import app.momoding.wire.P1aProtocol
import app.momoding.BuildConfig
import app.momoding.core.transport.SecureTransportUiStatus
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.Serializable

data class DiagnosticsArchive(
    val alias: String,
    val expiresAtMillis: Long,
)

@Serializable
private data class SanitizedDiagnostics(
    val appVersion: String,
    val sourceRevision: String,
    val sourceDirty: Boolean,
    val androidApi: Int,
    val abi: String,
    val wireVersion: Int,
    val piVersion: String,
    val connectionPhase: String,
    val hostAlias: String?,
    val configRevision: Long?,
)

class DiagnosticsExporter(
    private val context: Context,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private val directory: File = File(context.cacheDir, DIRECTORY_NAME)

    fun export(status: SecureTransportUiStatus): DiagnosticsArchive {
        directory.mkdirs()
        require(directory.canonicalFile.parentFile == context.cacheDir.canonicalFile) {
            "Diagnostics directory escaped cache"
        }
        deleteExpired()
        val alias = "diagnostics-${UUID.randomUUID()}.zip"
        val file = resolve(alias)
        val payload = SanitizedDiagnostics(
            appVersion = BuildConfig.VERSION_NAME,
            sourceRevision = BuildConfig.SOURCE_REVISION,
            sourceDirty = BuildConfig.SOURCE_DIRTY,
            androidApi = Build.VERSION.SDK_INT,
            abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown",
            wireVersion = P1aProtocol.PROTOCOL_VERSION,
            piVersion = P1aProtocol.PI_VERSION,
            connectionPhase = status.phase.name,
            hostAlias = status.hostAlias,
            configRevision = status.configRevision,
        )
        ZipOutputStream(file.outputStream().buffered()).use { zip ->
            zip.putNextEntry(ZipEntry("diagnostics.json"))
            zip.write(Json.encodeToString(payload).toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
        return DiagnosticsArchive(alias, nowMillis() + ARCHIVE_TTL_MILLIS)
    }

    fun shareIntent(archive: DiagnosticsArchive): Intent {
        require(nowMillis() < archive.expiresAtMillis) { "Diagnostics archive expired" }
        val file = resolve(archive.alias)
        require(file.isFile) { "Diagnostics archive is unavailable" }
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.diagnostics-fileprovider",
            file,
        )
        return Intent(Intent.ACTION_SEND).apply {
            type = ARCHIVE_MIME
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri("Sanitized diagnostics", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    fun delete(archive: DiagnosticsArchive): Boolean = resolve(archive.alias).delete()

    fun deleteExpired() {
        val cutoff = nowMillis() - ARCHIVE_TTL_MILLIS
        directory.listFiles().orEmpty().filter { it.lastModified() < cutoff }.forEach(File::delete)
    }

    private fun resolve(alias: String): File {
        require(ALIAS.matches(alias)) { "Diagnostics alias is invalid" }
        val file = File(directory, alias).canonicalFile
        require(file.parentFile == directory.canonicalFile) { "Diagnostics alias escaped directory" }
        return file
    }

    private companion object {
        const val DIRECTORY_NAME = "diagnostics-export"
        const val ARCHIVE_MIME = "application/zip"
        const val ARCHIVE_TTL_MILLIS = 15 * 60 * 1000L
        val ALIAS = Regex("^diagnostics-[0-9a-f-]{36}\\.zip$")
    }
}
