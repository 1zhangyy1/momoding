package app.momoding.core.accessibility

import android.content.Context
import android.content.Intent
import java.lang.ref.WeakReference
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class MediaProjectionSessionState(
    val active: Boolean = false,
    val visible: Boolean = false,
)

class MediaProjectionSessionController internal constructor() {
    private val lock = Any()
    private var serviceReference = WeakReference<ScreenCaptureSessionService>(null)
    private val _state = MutableStateFlow(MediaProjectionSessionState())
    val state: StateFlow<MediaProjectionSessionState> = _state.asStateFlow()

    internal fun onSessionReady(service: ScreenCaptureSessionService) {
        synchronized(lock) {
            serviceReference = WeakReference(service)
            _state.value = MediaProjectionSessionState(active = true, visible = true)
        }
        MomodingAccessibilityRuntime.notifyCapabilityChanged()
    }

    internal fun onVisibilityChanged(
        service: ScreenCaptureSessionService,
        visible: Boolean,
    ) {
        synchronized(lock) {
            if (serviceReference.get() !== service) return
            _state.value = _state.value.copy(visible = visible)
        }
    }

    internal fun onSessionStopped(service: ScreenCaptureSessionService) {
        synchronized(lock) {
            if (serviceReference.get() !== service) return
            serviceReference.clear()
            _state.value = MediaProjectionSessionState()
        }
        MomodingAccessibilityRuntime.notifyCapabilityChanged()
    }

    internal suspend fun capture(): RawScreenFrameResult {
        val service = synchronized(lock) {
            serviceReference.get()
        } ?: return RawScreenFrameResult.Failed(
            "SCREEN_CAPTURE_SESSION_REQUIRED",
            "Enable Accessibility control or start a screen-capture session.",
        )
        if (!state.value.visible) {
            return RawScreenFrameResult.Failed(
                "SCREEN_CAPTURE_NOT_VISIBLE",
                "The selected screen content is not currently visible.",
            )
        }
        return service.captureFrame()
    }

    fun stop(context: Context) {
        if (!state.value.active) return
        context.stopService(Intent(context, ScreenCaptureSessionService::class.java))
    }
}

object MomodingScreenCaptureRuntime {
    val mediaProjection = MediaProjectionSessionController()
}
