package app.momoding.core.attachments

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.core.content.edit
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID

internal class CameraCaptureStore(
    context: Context,
    private val idFactory: () -> String = { UUID.randomUUID().toString() },
    private val uriFactory: (File) -> Uri = { output ->
        FileProvider.getUriForFile(
            context.applicationContext,
            "${context.applicationContext.packageName}.camera-fileprovider",
            output,
        )
    },
) {
    private val appContext = context.applicationContext
    private val root = appContext.cacheDir.resolve("camera-capture/v1")
    private val preferences = appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    init {
        check(root.exists() || root.mkdirs()) { "CAMERA_CAPTURE_ROOT_CREATE_FAILED" }
        check(root.isDirectory) { "CAMERA_CAPTURE_ROOT_INVALID" }
    }

    fun prepare(draftId: String): CameraCaptureRequest {
        discardCurrent(draftId)
        val captureId = idFactory().also(::requireCaptureId)
        val output = captureFile(captureId)
        check(output.createNewFile()) { "CAMERA_CAPTURE_FILE_CREATE_FAILED" }
        val saved = preferences.edit().putString(preferenceKey(draftId), captureId).commit()
        if (!saved) {
            output.delete()
            error("CAMERA_CAPTURE_STATE_SAVE_FAILED")
        }
        return try {
            CameraCaptureRequest(
                captureId = captureId,
                outputUri = uriFactory(output),
            )
        } catch (error: Exception) {
            preferences.edit { remove(preferenceKey(draftId)) }
            output.delete()
            throw error
        }
    }

    fun pendingFile(draftId: String, captureId: String): File? {
        requireCaptureId(captureId)
        if (preferences.getString(preferenceKey(draftId), null) != captureId) return null
        return captureFile(captureId).takeIf(File::isFile)
    }

    fun discard(draftId: String, captureId: String) {
        requireCaptureId(captureId)
        if (preferences.getString(preferenceKey(draftId), null) != captureId) return
        preferences.edit { remove(preferenceKey(draftId)) }
        captureFile(captureId).delete()
    }

    fun pruneStale(nowMillis: Long = System.currentTimeMillis()): Int {
        val cutoff = nowMillis - MAX_PENDING_AGE_MILLIS
        var deleted = 0
        root.listFiles().orEmpty().forEach { candidate ->
            val validName = candidate.isFile && CAPTURE_FILE.matches(candidate.name)
            if ((!validName || candidate.lastModified() <= cutoff) && candidate.delete()) deleted += 1
        }
        val liveCaptureIds = root.listFiles().orEmpty().mapNotNullTo(mutableSetOf()) { candidate ->
            CAPTURE_FILE.matchEntire(candidate.name)?.groupValues?.get(1)
        }
        preferences.edit {
            preferences.all.forEach { (key, value) ->
                if (value !is String || value !in liveCaptureIds) remove(key)
            }
        }
        return deleted
    }

    private fun discardCurrent(draftId: String) {
        val captureId = preferences.getString(preferenceKey(draftId), null) ?: return
        preferences.edit { remove(preferenceKey(draftId)) }
        if (CAPTURE_ID.matches(captureId)) captureFile(captureId).delete()
    }

    private fun captureFile(captureId: String): File =
        root.resolve("capture-$captureId.jpg")

    private fun preferenceKey(draftId: String): String = "draft-" + MessageDigest
        .getInstance("SHA-256")
        .digest(draftId.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }

    private fun requireCaptureId(captureId: String) {
        require(CAPTURE_ID.matches(captureId)) { "captureId is invalid" }
    }

    private companion object {
        const val PREFERENCES_NAME = "camera-capture-v1"
        const val MAX_PENDING_AGE_MILLIS = 24L * 60L * 60L * 1_000L
        val CAPTURE_ID =
            Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")
        val CAPTURE_FILE = Regex("^capture-(${CAPTURE_ID.pattern.removePrefix("^").removeSuffix("$")})\\.jpg$")
    }
}
