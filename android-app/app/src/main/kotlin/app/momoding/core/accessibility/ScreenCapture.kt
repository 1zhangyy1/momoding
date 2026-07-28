package app.momoding.core.accessibility

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import kotlin.math.max
import kotlin.math.roundToInt

enum class ScreenCaptureSource {
    ACCESSIBILITY,
    MEDIA_PROJECTION,
}

data class EncodedScreenCapture(
    val bytes: ByteArray,
    val mimeType: String,
    val width: Int,
    val height: Int,
    val contentSha256: String,
    val source: ScreenCaptureSource,
    val foregroundPackage: String?,
)

sealed interface ScreenCaptureResult {
    data class Ready(val capture: EncodedScreenCapture) : ScreenCaptureResult
    data class Failed(
        val code: String,
        val safeMessage: String,
    ) : ScreenCaptureResult
}

internal data class RawScreenFrame(
    val bitmap: Bitmap,
    val source: ScreenCaptureSource,
    val foregroundPackage: String?,
)

internal sealed interface RawScreenFrameResult {
    data class Ready(val frame: RawScreenFrame) : RawScreenFrameResult
    data class Failed(
        val code: String,
        val safeMessage: String,
    ) : RawScreenFrameResult
}

internal object ScreenCaptureEncoder {
    fun encode(frame: RawScreenFrame): ScreenCaptureResult {
        val source = frame.bitmap
        return try {
            val scaled = scale(source)
            try {
                val encoded = encodeJpeg(scaled)
                    ?: return ScreenCaptureResult.Failed(
                        code = "SCREEN_CAPTURE_TOO_LARGE",
                        safeMessage = "The current screen could not be reduced to the live image limit.",
                    )
                ScreenCaptureResult.Ready(
                    EncodedScreenCapture(
                        bytes = encoded,
                        mimeType = MIME_JPEG,
                        width = scaled.width,
                        height = scaled.height,
                        contentSha256 = encoded.sha256(),
                        source = frame.source,
                        foregroundPackage = frame.foregroundPackage,
                    ),
                )
            } finally {
                if (scaled !== source) scaled.recycle()
            }
        } catch (_: Throwable) {
            ScreenCaptureResult.Failed(
                code = "SCREEN_CAPTURE_ENCODE_FAILED",
                safeMessage = "The current screen could not be prepared for the Agent.",
            )
        } finally {
            source.recycle()
        }
    }

    private fun scale(source: Bitmap): Bitmap {
        val longest = max(source.width, source.height)
        if (longest <= MAX_LONG_EDGE) return source
        val ratio = MAX_LONG_EDGE.toDouble() / longest.toDouble()
        return Bitmap.createScaledBitmap(
            source,
            (source.width * ratio).roundToInt().coerceAtLeast(1),
            (source.height * ratio).roundToInt().coerceAtLeast(1),
            true,
        )
    }

    private fun encodeJpeg(bitmap: Bitmap): ByteArray? {
        JPEG_QUALITIES.forEach { quality ->
            val output = ByteArrayOutputStream()
            if (
                bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output) &&
                output.size() <= MAX_ENCODED_BYTES
            ) {
                return output.toByteArray()
            }
        }
        return null
    }

    private fun ByteArray.sha256(): String = MessageDigest.getInstance("SHA-256")
        .digest(this)
        .joinToString("") { byte -> "%02x".format(byte) }

    const val MAX_LONG_EDGE = 1_440
    const val MAX_ENCODED_BYTES = 2 * 1_024 * 1_024
    private const val MIME_JPEG = "image/jpeg"
    private val JPEG_QUALITIES = intArrayOf(90, 82, 74, 66, 58, 50, 42)
}
