package app.momoding.apps

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.PixelCopy
import android.view.Window
import android.webkit.WebView
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * An app as the person sees it on the phone, for its own Momoding to check its work: what is on
 * its stage (the page and what Momoding shows over it), and a finger on the page. The stage is the
 * person's screen when they have the app open, else one no one sees (AppStage). Only Momoding's own
 * windows: what the system shows (its permission prompt, a picker, the camera app) is not ours to
 * see; the app's log says when it is waiting on one.
 *
 * Pixels are the screen's, top-left of the app's window: the same in a picture and in a touch.
 */
object AppScreen {
    /** Where an app's page is: on the person's screen (MoAppActivity) or where no one sees it (AppStage). */
    interface Stage {
        fun window(): Window
        fun page(): WebView?
        /** The person is being asked something over the page: only they answer it. */
        fun asking(): Boolean
        /** ([x],[y]) falls on something of Momoding's over the page, the person's to tap. */
        fun ownAt(x: Float, y: Float): Boolean
    }

    private val shown = ConcurrentHashMap<String, WeakReference<MoAppActivity>>()

    /** [activity] is the app on the screen now (null: it left). */
    fun showing(name: String, activity: MoAppActivity?) {
        if (activity == null) shown.remove(name) else shown[name] = WeakReference(activity)
    }

    fun of(name: String): MoAppActivity? = shown[name]?.get()?.takeIf { !it.isFinishing && !it.isDestroyed }

    /** The page as the person sees it, with what Momoding shows over it (a question, the pen). Main thread. */
    suspend fun picture(stage: Stage): Bitmap = copy(stage.window())

    private suspend fun copy(w: Window): Bitmap = suspendCancellableCoroutine { c ->
        val v = w.decorView
        val b = Bitmap.createBitmap(v.width.coerceAtLeast(1), v.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        PixelCopy.request(w, b, { r ->
            if (r == PixelCopy.SUCCESS) c.resume(b) else c.resumeWithException(IllegalStateException("the screen could not be copied ($r)"))
        }, Handler(Looper.getMainLooper()))
    }

    /**
     * A finger from ([x1],[y1]) to ([x2],[y2]) over [ms]: a tap, a press and hold, or a swipe. On the
     * page only: what Momoding shows over it (a question, a line to put the app on the home screen,
     * the pen) is the person's to answer, so it is not touched; while a question is up, nothing is.
     * Main thread.
     */
    suspend fun touch(stage: Stage, x1: Float, y1: Float, x2: Float, y2: Float, ms: Long): String {
        if (stage.asking()) throw IllegalStateException("Momoding is asking the person something over the app (look shows it); only they can answer it. Wait, or tell them in a line.")
        if (stage.ownAt(x1, y1)) throw IllegalStateException("that is Momoding's own, over the page (the pen, or a line at the top for the person); only they tap it. Say in a line what it is for.")
        val page = stage.page() ?: throw IllegalStateException("the app's page is not there")
        val origin = IntArray(2).also { stage.window().decorView.getLocationOnScreen(it) }
        val at = IntArray(2).also { page.getLocationOnScreen(it) }
        val dx = (origin[0] - at[0]).toFloat()
        val dy = (origin[1] - at[1]).toFloat()
        val down = SystemClock.uptimeMillis()
        fun send(action: Int, x: Float, y: Float) {
            val e = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x + dx, y + dy, 0)
            e.source = InputDevice.SOURCE_TOUCHSCREEN
            page.dispatchTouchEvent(e)
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
        return hit(page, x1 + dx, y1 + dy)
    }

    /** What is under ([x],[y]) on [page] (its own pixels), in a few words: what the finger landed on. */
    private suspend fun hit(page: WebView, x: Float, y: Float): String = suspendCancellableCoroutine { c ->
        val script = "(()=>{const r=devicePixelRatio,e=document.elementFromPoint($x/r,$y/r);if(!e)return '';" +
            "const t=(e.getAttribute('aria-label')||e.value||e.innerText||'').trim().replace(/\\s+/g,' ').slice(0,40);" +
            "return e.tagName.toLowerCase()+(t?' \u201c'+t+'\u201d':'')})()"
        page.evaluateJavascript(script) { c.resume(runCatching { org.json.JSONTokener(it).nextValue() as? String }.getOrNull().orEmpty()) }
    }

    /** [text] typed where the page's focus is, as a keyboard would. Main thread. */
    suspend fun type(stage: Stage, text: String): Boolean = suspendCancellableCoroutine { c ->
        val script = "(()=>{const e=document.activeElement;if(!e||e===document.body)return false;" +
            "return document.execCommand('insertText',false,${JSONObject.quote(text)})})()"
        stage.page()?.evaluateJavascript(script) { c.resume(it == "true") } ?: c.resume(false)
    }

    private const val FRAME = 16L
}
