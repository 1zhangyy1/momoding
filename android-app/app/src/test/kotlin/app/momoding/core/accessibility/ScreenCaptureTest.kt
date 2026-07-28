package app.momoding.core.accessibility

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ScreenCaptureTest {
    @Test
    fun encoderBoundsDimensionsBytesAndDigest() {
        val bitmap = Bitmap.createBitmap(2_400, 1_200, Bitmap.Config.ARGB_8888)
        for (row in 0 until bitmap.height step 40) {
            for (column in 0 until bitmap.width step 40) {
                bitmap.setPixel(
                    column,
                    row,
                    Color.rgb(column % 255, row % 255, (column + row) % 255),
                )
            }
        }

        val result = ScreenCaptureEncoder.encode(
            RawScreenFrame(
                bitmap = bitmap,
                source = ScreenCaptureSource.ACCESSIBILITY,
                foregroundPackage = "com.example",
            ),
        ) as ScreenCaptureResult.Ready

        assertEquals(1_440, result.capture.width)
        assertEquals(720, result.capture.height)
        assertTrue(result.capture.bytes.size <= ScreenCaptureEncoder.MAX_ENCODED_BYTES)
        assertEquals("image/jpeg", result.capture.mimeType)
        assertEquals(
            MessageDigest.getInstance("SHA-256")
                .digest(result.capture.bytes)
                .joinToString("") { "%02x".format(it) },
            result.capture.contentSha256,
        )
        assertEquals("com.example", result.capture.foregroundPackage)
    }

    @Test
    fun platformScreenshotFailuresRemainTypedAndSafe() {
        assertEquals(
            "SCREEN_CAPTURE_SECURE_WINDOW",
            accessibilityScreenshotFailure(
                AccessibilityService.ERROR_TAKE_SCREENSHOT_SECURE_WINDOW,
            ).code,
        )
        assertEquals(
            "SCREEN_CAPTURE_RATE_LIMITED",
            accessibilityScreenshotFailure(
                AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT,
            ).code,
        )
        assertEquals(
            "SCREEN_CAPTURE_ACCESS_REVOKED",
            accessibilityScreenshotFailure(
                AccessibilityService.ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS,
            ).code,
        )
    }

    @Test
    fun lockedDeviceFailsBeforeAnyCaptureBackend() {
        val coordinator = ScreenCaptureCoordinator(
            context = ApplicationProvider.getApplicationContext(),
            isDeviceLocked = { true },
        )

        val result = runBlocking { coordinator.capture(targetPackage = null) }

        assertTrue(result is ScreenCaptureResult.Failed)
        assertEquals(
            "SCREEN_CAPTURE_DEVICE_LOCKED",
            (result as ScreenCaptureResult.Failed).code,
        )
    }

    @Test
    fun deviceLockedDuringCaptureRecyclesFrameAndFailsClosed() {
        var lockChecks = 0
        val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        val coordinator = ScreenCaptureCoordinator(
            context = ApplicationProvider.getApplicationContext(),
            isDeviceLocked = { lockChecks++ > 0 },
            captureBackend = RawScreenCaptureBackend {
                RawScreenFrameResult.Ready(
                    RawScreenFrame(
                        bitmap = bitmap,
                        source = ScreenCaptureSource.MEDIA_PROJECTION,
                        foregroundPackage = null,
                    ),
                )
            },
        )

        val result = runBlocking { coordinator.capture(targetPackage = null) }

        assertTrue(result is ScreenCaptureResult.Failed)
        assertEquals(
            "SCREEN_CAPTURE_DEVICE_LOCKED",
            (result as ScreenCaptureResult.Failed).code,
        )
        assertEquals(2, lockChecks)
        assertTrue(bitmap.isRecycled)
    }
}
