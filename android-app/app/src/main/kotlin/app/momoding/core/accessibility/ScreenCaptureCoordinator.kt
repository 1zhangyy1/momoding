package app.momoding.core.accessibility

import android.app.KeyguardManager
import android.content.Context
import android.graphics.Bitmap
import androidx.core.graphics.get
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

fun interface ScreenCapturePerformer {
    suspend fun capture(targetPackage: String?): ScreenCaptureResult

    fun stop() = Unit
}

internal fun interface RawScreenCaptureBackend {
    suspend fun capture(targetPackage: String?): RawScreenFrameResult
}

internal class ScreenCaptureCoordinator(
    context: Context,
    private val accessibility: AccessibilitySessionController =
        MomodingAccessibilityRuntime.controller,
    private val mediaProjection: MediaProjectionSessionController =
        MomodingScreenCaptureRuntime.mediaProjection,
    private val isDeviceLocked: () -> Boolean = {
        context.applicationContext
            .getSystemService(KeyguardManager::class.java)
            .isDeviceLocked
    },
    private val captureBackend: RawScreenCaptureBackend = RawScreenCaptureBackend { targetPackage ->
        if (accessibility.state.value.connected) {
            accessibility.captureScreenshot(targetPackage)
        } else {
            if (targetPackage != null) {
                RawScreenFrameResult.Failed(
                    "SCREEN_CAPTURE_TARGET_UNVERIFIED",
                    "Enable Accessibility control to verify a target app before capture.",
                )
            } else {
                mediaProjection.capture()
            }
        }
    },
) : ScreenCapturePerformer {
    private val applicationContext = context.applicationContext

    override suspend fun capture(targetPackage: String?): ScreenCaptureResult {
        if (isDeviceLocked()) {
            return ScreenCaptureResult.Failed(
                "SCREEN_CAPTURE_DEVICE_LOCKED",
                "Unlock the device before the Agent captures the screen.",
            )
        }
        val raw = captureBackend.capture(targetPackage)
        if (raw is RawScreenFrameResult.Failed) {
            return ScreenCaptureResult.Failed(raw.code, raw.safeMessage)
        }
        val frame = (raw as RawScreenFrameResult.Ready).frame
        if (isDeviceLocked()) {
            frame.bitmap.recycle()
            return ScreenCaptureResult.Failed(
                "SCREEN_CAPTURE_DEVICE_LOCKED",
                "Unlock the device before the Agent captures the screen.",
            )
        }
        if (
            frame.source == ScreenCaptureSource.MEDIA_PROJECTION &&
            frame.bitmap.isUniformlyProtected()
        ) {
            frame.bitmap.recycle()
            return ScreenCaptureResult.Failed(
                "SCREEN_CAPTURE_SECURE_OR_EMPTY",
                "Android returned protected or empty screen content.",
            )
        }
        val encoded = withContext(Dispatchers.Default) {
            ScreenCaptureEncoder.encode(frame)
        }
        return if (isDeviceLocked()) {
            ScreenCaptureResult.Failed(
                "SCREEN_CAPTURE_DEVICE_LOCKED",
                "Unlock the device before the Agent captures the screen.",
            )
        } else {
            encoded
        }
    }

    override fun stop() {
        accessibility.stop()
        mediaProjection.stop(applicationContext)
    }

    private fun Bitmap.isUniformlyProtected(): Boolean {
        var first: Int? = null
        for (row in 1..6) {
            for (column in 1..6) {
                val x = (width * column / 7).coerceIn(0, width - 1)
                val y = (height * row / 7).coerceIn(0, height - 1)
                val pixel = this[x, y]
                if (first == null) first = pixel else if (pixel != first) return false
            }
        }
        val value = first ?: return true
        val red = value shr 16 and 0xff
        val green = value shr 8 and 0xff
        val blue = value and 0xff
        return red <= 4 && green <= 4 && blue <= 4
    }
}
