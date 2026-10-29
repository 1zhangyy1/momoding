package app.momoding.apps

import android.app.Dialog
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.PixelCopy
import android.view.Window
import android.view.WindowManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * An app as the person sees it on the phone, for its own Momoding to check its work: what is on
 * the screen (the page and what Momoding shows over it), and a finger on it. Only Momoding's own
 * windows: what the system shows (its permission prompt, a picker, the camera app) is not ours to
 * see; the app's log says when it is waiting on one.
 *
 * Pixels are the screen's, top-left of the app's window: the same in a picture and in a touch.
 */
object AppScreen {
    private val shown = ConcurrentHashMap<String, WeakReference<MoAppActivity>>()

    /** [activity] is the app on the screen now (null: it left). */
    fun showing(name: String, activity: MoAppActivity?) {
        if (activity == null) shown.remove(name) else shown[name] = WeakReference(activity)
    }

    fun of(name: String): MoAppActivity? = shown[name]?.get()?.takeIf { !it.isFinishing && !it.isDestroyed }

    /** The page, and each of Momoding's own windows over it as the person sees them (dimmed behind). Main thread. */
    suspend fun picture(activity: MoAppActivity): Bitmap {
        val out = copy(activity.window)
        val canvas = Canvas(out)
        val origin = IntArray(2).also { activity.window.decorView.getLocationOnScreen(it) }
        for (w in activity.over().mapNotNull { it.window }) {
            if (w.attributes.flags and WindowManager.LayoutParams.FLAG_DIM_BEHIND != 0)
                canvas.drawColor(Color.argb((w.attributes.dimAmount * 255).toInt(), 0, 0, 0))
            val at = IntArray(2).also { w.decorView.getLocationOnScreen(it) }
            canvas.drawBitmap(copy(w), (at[0] - origin[0]).toFloat(), (at[1] - origin[1]).toFloat(), null)
        }
        return out
    }

    private suspend fun copy(w: Window): Bitmap = suspendCancellableCoroutine { c ->
        val v = w.decorView
        val b = Bitmap.createBitmap(v.width.coerceAtLeast(1), v.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        PixelCopy.request(w, b, { r ->
            if (r == PixelCopy.SUCCESS) c.resume(b) else c.resumeWithException(IllegalStateException("the screen could not be copied ($r)"))
        }, Handler(Looper.getMainLooper()))
    }

    /** A finger from ([x1],[y1]) to ([x2],[y2]) over [ms]: a tap, a press and hold, or a swipe. To what is on top. Main thread. */
    suspend fun touch(activity: MoAppActivity, x1: Float, y1: Float, x2: Float, y2: Float, ms: Long) {
        // a window Momoding shows over the page takes the touch, as it would the person's
        val top: Dialog? = activity.over().lastOrNull()
        val origin = IntArray(2).also { activity.window.decorView.getLocationOnScreen(it) }
        val at = IntArray(2).also { (top?.window?.decorView ?: activity.window.decorView).getLocationOnScreen(it) }
        val dx = (origin[0] - at[0]).toFloat()
        val dy = (origin[1] - at[1]).toFloat()
        val down = SystemClock.uptimeMillis()
        fun send(action: Int, x: Float, y: Float) {
            val e = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x + dx, y + dy, 0)
            e.source = InputDevice.SOURCE_TOUCHSCREEN
            if (top != null) top.dispatchTouchEvent(e) else activity.dispatchTouchEvent(e)
            e.recycle()
        }
        send(MotionEvent.ACTION_DOWN, x1, y1)
        val steps = (ms / FRAME).toInt().coerceAtLeast(1)
        for (i in 1..steps) {
            delay(FRAME)
            val f = i.toFloat() / steps
            send(MotionEvent.ACTION_MOVE, x1 + (x2 - x1) * f, y1 + (y2 - y1) * f)
        }
        send(MotionEvent.ACTION_UP, x2, y2)
    }

    /** [text] typed where the page's focus is, as a keyboard would. Main thread. */
    suspend fun type(activity: MoAppActivity, text: String): Boolean = suspendCancellableCoroutine { c ->
        val script = "(()=>{const e=document.activeElement;if(!e||e===document.body)return false;" +
            "return document.execCommand('insertText',false,${JSONObject.quote(text)})})()"
        activity.page()?.evaluateJavascript(script) { c.resume(it == "true") } ?: c.resume(false)
    }

    private const val FRAME = 16L
}
