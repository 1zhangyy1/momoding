package app.momoding.core.accessibility

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.graphics.createBitmap
import app.momoding.R
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

class ScreenCaptureSessionService : Service() {
    private lateinit var captureThread: HandlerThread
    private lateinit var captureHandler: Handler
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var pendingCapture: PendingCapture? = null
    private var projectionCallback: MediaProjection.Callback? = null
    private val expireSession = Runnable { stopSession() }
    private var released = AtomicBoolean(false)

    override fun onCreate() {
        super.onCreate()
        captureThread = HandlerThread("momoding-media-projection").apply { start() }
        captureHandler = Handler(captureThread.looper)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startSession(intent)
            ACTION_STOP -> stopSession()
            else -> stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        releaseSession(stopProjection = true)
        captureThread.quitSafely()
        super.onDestroy()
    }

    internal suspend fun captureFrame(): RawScreenFrameResult =
        suspendCancellableCoroutine { continuation ->
            val delivered = AtomicBoolean(false)
            val pending = PendingCapture(
                deliver = { result ->
                    if (delivered.compareAndSet(false, true)) continuation.resume(result)
                    else if (result is RawScreenFrameResult.Ready) result.frame.bitmap.recycle()
                },
            )
            continuation.invokeOnCancellation {
                if (delivered.compareAndSet(false, true)) {
                    captureHandler.post {
                        if (pendingCapture === pending) pendingCapture = null
                    }
                }
            }
            captureHandler.post {
                when {
                    projection == null || imageReader == null -> pending.deliver(
                        RawScreenFrameResult.Failed(
                            "SCREEN_CAPTURE_SESSION_REQUIRED",
                            "Start a new Android screen-capture session.",
                        ),
                    )
                    pendingCapture != null -> pending.deliver(
                        RawScreenFrameResult.Failed(
                            "SCREEN_CAPTURE_BUSY",
                            "Another screen capture is already in progress.",
                        ),
                    )
                    else -> {
                        pendingCapture = pending
                        acquireLatestFrame()
                        captureHandler.postDelayed(
                            {
                                if (pendingCapture === pending) {
                                    pendingCapture = null
                                    pending.deliver(
                                        RawScreenFrameResult.Failed(
                                            "SCREEN_CAPTURE_TIMEOUT",
                                            "Android did not provide a screen image in time.",
                                        ),
                                    )
                                }
                            },
                            CAPTURE_TIMEOUT_MILLIS,
                        )
                    }
                }
            }
        }

    private fun startSession(intent: Intent) {
        startForeground(
            NOTIFICATION_ID,
            notification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
        )
        if (projection != null) return
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
        @Suppress("DEPRECATION")
        val resultData = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
        if (resultCode != Activity.RESULT_OK || resultData == null) {
            stopSession()
            return
        }
        runCatching {
            val manager = getSystemService(MediaProjectionManager::class.java)
            val activeProjection = requireNotNull(
                manager.getMediaProjection(resultCode, resultData),
            )
            val callback = object : MediaProjection.Callback() {
                override fun onStop() {
                    releaseSession(stopProjection = false)
                    stopSelf()
                }

                override fun onCapturedContentVisibilityChanged(isVisible: Boolean) {
                    MomodingScreenCaptureRuntime.mediaProjection.onVisibilityChanged(
                        this@ScreenCaptureSessionService,
                        isVisible,
                    )
                }

                override fun onCapturedContentResize(width: Int, height: Int) {
                    resizeCaptureSurface(width, height)
                }
            }
            activeProjection.registerCallback(callback, captureHandler)
            projectionCallback = callback
            projection = activeProjection
            val bounds = getSystemService(WindowManager::class.java)
                .maximumWindowMetrics
                .bounds
            val width = bounds.width().coerceAtLeast(1)
            val height = bounds.height().coerceAtLeast(1)
            val densityDpi = resources.configuration.densityDpi.coerceAtLeast(1)
            val reader = createImageReader(width, height)
            imageReader = reader
            virtualDisplay = requireNotNull(
                activeProjection.createVirtualDisplay(
                    "Momoding live screen",
                    width,
                    height,
                    densityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    reader.surface,
                    null,
                    captureHandler,
                ),
            )
            released.set(false)
            MomodingScreenCaptureRuntime.mediaProjection.onSessionReady(this)
            captureHandler.removeCallbacks(expireSession)
            captureHandler.postDelayed(expireSession, SESSION_TTL_MILLIS)
        }.onFailure {
            stopSession()
        }
    }

    private fun resizeCaptureSurface(width: Int, height: Int) {
        if (width <= 0 || height <= 0 || projection == null) return
        val display = virtualDisplay ?: return
        val currentReader = imageReader ?: return
        if (currentReader.width == width && currentReader.height == height) return
        pendingCapture?.deliver(
            RawScreenFrameResult.Failed(
                "SCREEN_CAPTURE_RESIZED",
                "The captured screen changed size. Try the capture again.",
            ),
        )
        pendingCapture = null
        val replacement = createImageReader(width, height)
        imageReader = replacement
        display.resize(width, height, resources.configuration.densityDpi.coerceAtLeast(1))
        display.surface = replacement.surface
        currentReader.setOnImageAvailableListener(null, null)
        currentReader.close()
    }

    private fun createImageReader(width: Int, height: Int): ImageReader =
        ImageReader.newInstance(
            width.coerceAtLeast(1),
            height.coerceAtLeast(1),
            PixelFormat.RGBA_8888,
            MAX_IMAGES,
        ).also { reader ->
            reader.setOnImageAvailableListener(
                { acquireLatestFrame() },
                captureHandler,
            )
        }

    private fun acquireLatestFrame() {
        val pending = pendingCapture ?: return
        val image = runCatching { imageReader?.acquireLatestImage() }.getOrNull() ?: return
        pendingCapture = null
        val result = try {
            RawScreenFrameResult.Ready(
                RawScreenFrame(
                    bitmap = image.toBitmap(),
                    source = ScreenCaptureSource.MEDIA_PROJECTION,
                    foregroundPackage = null,
                ),
            )
        } catch (_: Throwable) {
            RawScreenFrameResult.Failed(
                "SCREEN_CAPTURE_COPY_FAILED",
                "Android returned a screen image that could not be read.",
            )
        } finally {
            image.close()
        }
        pending.deliver(result)
    }

    private fun Image.toBitmap(): Bitmap {
        val plane = planes.single()
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * width
        val paddedWidth = width + rowPadding / pixelStride
        val padded = createBitmap(paddedWidth, height)
        plane.buffer.rewind()
        padded.copyPixelsFromBuffer(plane.buffer)
        if (paddedWidth == width) return padded
        return Bitmap.createBitmap(padded, 0, 0, width, height).also { padded.recycle() }
    }

    private fun stopSession() {
        releaseSession(stopProjection = true)
        stopSelf()
    }

    private fun releaseSession(stopProjection: Boolean) {
        if (!released.compareAndSet(false, true)) return
        captureHandler.removeCallbacks(expireSession)
        pendingCapture?.deliver(
            RawScreenFrameResult.Failed(
                "SCREEN_CAPTURE_STOPPED",
                "The Android screen-capture session ended.",
            ),
        )
        pendingCapture = null
        imageReader?.setOnImageAvailableListener(null, null)
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
        val activeProjection = projection
        val callback = projectionCallback
        projection = null
        projectionCallback = null
        if (callback != null) runCatching { activeProjection?.unregisterCallback(callback) }
        if (stopProjection) runCatching { activeProjection?.stop() }
        MomodingScreenCaptureRuntime.mediaProjection.onSessionStopped(this)
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun notification(): Notification = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.momoding_mark_monochrome)
        .setContentTitle("Momoding screen access")
        .setContentText("A time-limited screen-capture session is active")
        .setOngoing(true)
        .setSilent(true)
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .build()

    private fun createNotificationChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Screen access",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Shows when Momoding can capture the selected screen."
            },
        )
    }

    private data class PendingCapture(
        val deliver: (RawScreenFrameResult) -> Unit,
    )

    companion object {
        const val ACTION_START = "app.momoding.action.START_SCREEN_CAPTURE"
        const val ACTION_STOP = "app.momoding.action.STOP_SCREEN_CAPTURE"
        const val EXTRA_RESULT_CODE = "screen_capture_result_code"
        const val EXTRA_RESULT_DATA = "screen_capture_result_data"
        private const val CHANNEL_ID = "momoding-screen-capture"
        private const val NOTIFICATION_ID = 4402
        private const val MAX_IMAGES = 2
        private const val CAPTURE_TIMEOUT_MILLIS = 3_000L
        private const val SESSION_TTL_MILLIS = 10 * 60_000L

        fun startIntent(context: Context, resultCode: Int, resultData: Intent): Intent =
            Intent(context, ScreenCaptureSessionService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_RESULT_DATA, resultData)
    }
}
