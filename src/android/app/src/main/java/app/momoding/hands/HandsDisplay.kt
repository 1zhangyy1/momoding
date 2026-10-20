package app.momoding.hands

import android.content.Context
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import com.openminis.app.logging.AppLogger

/**
 * The hands' own screen: a display nobody sees, the size of the phone's, where an app can be opened
 * and worked on while the person keeps using their phone (RikkaHub's virtual display tools: the
 * display is created here, in-process; apps are started on it and input sent to it through
 * Shizuku with `am start --display` and `input -d`, which need the shell's rights).
 */
object HandsDisplay {
    private const val TAG = "HandsDisplay"
    private const val NAME = "momoding-hands"

    @Volatile private var display: VirtualDisplay? = null
    @Volatile private var reader: ImageReader? = null

    /** The display's id, created on first use; null when the system refuses one. */
    @Synchronized
    fun ensure(context: Context): Int? {
        display?.display?.displayId?.let { return it }
        val dm = context.getSystemService(DisplayManager::class.java) ?: return null
        val m = context.resources.displayMetrics
        val bounds = (context.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager).maximumWindowMetrics.bounds
        val r = ImageReader.newInstance(bounds.width(), bounds.height(), PixelFormat.RGBA_8888, 2)
        // PUBLIC so the shell can start other apps on it; OWN_CONTENT_ONLY so nothing of the phone's own screen is mirrored there
        val flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
        val vd = runCatching { dm.createVirtualDisplay(NAME, bounds.width(), bounds.height(), m.densityDpi, r.surface, flags) }
            .onFailure { AppLogger.warning(TAG, "create failed: ${it.message}") }.getOrNull()
        if (vd == null) { r.close(); return null }
        display = vd; reader = r
        AppLogger.info(TAG, "created display ${vd.display.displayId} ${bounds.width()}x${bounds.height()}")
        return vd.display.displayId
    }

    val id: Int? get() = display?.display?.displayId

    @Synchronized
    fun release() {
        display?.release(); display = null
        reader?.close(); reader = null
    }
}
