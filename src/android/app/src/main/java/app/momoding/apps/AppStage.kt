package app.momoding.apps

import android.app.Presentation
import android.content.Context
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.webkit.ConsoleMessage
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.view.Window
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * An app's page where no one sees it, for its own Momoding to check its work while the person gets
 * on with theirs (调研/M40): handing work over means they can walk away, so checking it never takes
 * their screen. A display of Momoding's own that no screen shows — a private VirtualDisplay the
 * phone's size, which needs no permission — holds the same page the person would see.
 *
 * Nobody is looking, so it is the background's page (AppBridge.Mode.BACKGROUND): it uses what the
 * person already allowed and asks them nothing. What needs the person (a permission, a key, a file,
 * the camera) waits until they open the app, and the log says so. Unused for a while, it closes.
 */
class AppStage private constructor(context: Context, private val app: MoApps.App) : AppScreen.Stage {
    private val reader: ImageReader
    private val display: VirtualDisplay
    private val shown: Presentation
    private val web: WebView
    private val bridge: AppBridge
    private val deliver: (List<JSONObject>) -> Unit
    private var loaded = -1L

    init {
        val m = context.resources.displayMetrics
        reader = ImageReader.newInstance(m.widthPixels, m.heightPixels, PixelFormat.RGBA_8888, 2)
        display = context.getSystemService(DisplayManager::class.java)
            .createVirtualDisplay("momoding-${app.name}", m.widthPixels, m.heightPixels, m.densityDpi, reader.surface, 0)
        shown = Presentation(context, display.display)
        val (wv, b) = AppPage.unattended(shown.context, app)
        web = wv
        bridge = b
        wv.webChromeClient = Unattended(app)
        shown.setContentView(wv)
        shown.show()
        deliver = { events -> wv.evaluateJavascript(AppEvents.script(events), null) }
        AppEvents.attach(app, deliver)
    }

    override fun window(): Window = shown.window!!
    override fun page(): WebView = web
    override fun asking() = false
    override fun ownAt(x: Float, y: Float) = false

    /** The page as the app's files are now, drawn. */
    suspend fun ready() {
        val now = MoAppActivity.stamp(app.dir)
        if (now != loaded) {
            loaded = now
            web.loadUrl(MoAppActivity.ORIGIN + "index.html")
            delay(100)
            withTimeoutOrNull(8000) { while (web.progress < 100) delay(100) }
        }
        later()
    }

    private val closeIt = Runnable { close() }

    /** Closes once it has gone [IDLE_MS] unused. */
    private fun later() {
        main.removeCallbacks(closeIt)
        main.postDelayed(closeIt, IDLE_MS)
    }

    private fun close() {
        stages.remove(app.name, this)
        AppEvents.detach(app, deliver)
        bridge.close()
        runCatching { shown.dismiss() }
        web.destroy()
        display.release()
        reader.close()
    }

    /** The browser side with nobody to ask: errors go to the log; what needs the person waits for them. */
    private class Unattended(private val app: MoApps.App) : WebChromeClient() {
        private fun later(what: String) = MoApps.log(app, "wait", "needs the person: $what — asked when they open the app, not while it is checked")

        override fun onConsoleMessage(m: ConsoleMessage): Boolean {
            if (m.messageLevel() == ConsoleMessage.MessageLevel.ERROR) MoApps.log(app, "ERROR", m.message())
            return false
        }

        override fun onShowFileChooser(view: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
            later("a file or the camera (<input type=file>)")
            callback.onReceiveValue(null)
            return true
        }

        override fun onPermissionRequest(request: PermissionRequest) {
            later("the camera or microphone")
            request.deny()
        }

        override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) {
            later("where the phone is")
            callback.invoke(origin, false, false)
        }
    }

    companion object {
        private const val IDLE_MS = 2 * 60_000L
        private val main = Handler(Looper.getMainLooper())
        private val stages = ConcurrentHashMap<String, AppStage>()

        /** [app]'s stage, opened if it is not. Main thread. */
        fun of(context: Context, app: MoApps.App): AppStage =
            stages.getOrPut(app.name) { AppStage(context.applicationContext, app) }
    }
}
