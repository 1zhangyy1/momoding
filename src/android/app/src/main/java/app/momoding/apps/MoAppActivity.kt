package app.momoding.apps

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import app.momoding.guard.RiskDecision
import app.momoding.guard.RiskGate
import com.openminis.app.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

/**
 * A Momoding App on the screen: full screen, its own task with its own name and icon. The page is
 * its folder (AppPage). When the folder changes the page reloads, so a change its Momoding makes
 * shows while the person watches; what the app keeps is in momoding.store and survives the reload.
 * The pen opens the app's own Momoding. An error right after opening asks that Momoding to fix it.
 */
class MoAppActivity : ComponentActivity() {

    private lateinit var app: MoApps.App
    private var web: WebView? = null
    private var bridge: AppBridge? = null
    private var loadedAt = 0L
    private var pen: TextView? = null
    private var deliver: ((List<org.json.JSONObject>) -> Unit)? = null
    private var troubleOffered = false
    /** What Momoding shows over the page (asks, approvals), newest last. */
    private val dialogs = mutableListOf<AlertDialog>()

    /** Momoding's windows over the page now, bottom to top (AppScreen). */
    internal fun over(): List<AlertDialog> = dialogs.filter { it.isShowing }

    internal fun page(): WebView? = web

    private fun AlertDialog.Builder.showOver(): AlertDialog = show().also { d -> dialogs.removeAll { !it.isShowing }; dialogs += d }

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        app = intent.getStringExtra(EXTRA_NAME)?.let { MoApps.get(this, it) } ?: run { finish(); return }
        setTaskDescription(ActivityManager.TaskDescription(app.title, AppIcon.bitmap(app, 192, adaptive = false)))

        val wv = WebView(this)
        web = wv
        val b = AppBridge(this, wv, AppBridge.Mode.SCREEN)
        bridge = b
        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            mediaPlaybackRequiresUserGesture = false
        }
        wv.addJavascriptInterface(b, AppBridge.JS_NAME)
        // whatever a page can do in a browser (AppWeb)
        AppWeb(this, app) { trouble(it) }.attach(wv)
        wv.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                if (request.url.host != HOST) return null
                return AppPage.respond(app, request.url.path.orEmpty())
            }
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (request.url.host == HOST) return false
                // links out of the app open where they belong (a browser, another app)
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, request.url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                return true
            }
            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                if (request.isForMainFrame) trouble("the page did not load (${response.statusCode})")
            }
            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) trouble("the page did not load (${error.description})")
            }
            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                MoApps.log(app, "ERROR", "the page crashed")
                finish()
                return true
            }
        }

        val pen = TextView(this).apply {
            text = "✎"
            textSize = 15f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            alpha = 0.6f
            background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = 999f; setColor(0x99000000.toInt()) }
            contentDescription = getString(R.string.mo_app_chat, app.title)
            setOnClickListener { AppChat.open(this@MoAppActivity, app, "") }
        }
        this.pen = pen
        val dp = resources.displayMetrics.density
        setContentView(FrameLayout(this).apply {
            fitsSystemWindows = true
            addView(wv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(pen, FrameLayout.LayoutParams((36 * dp).toInt(), (36 * dp).toInt(), Gravity.BOTTOM or Gravity.END)
                .apply { setMargins((14 * dp).toInt(), (14 * dp).toInt(), (14 * dp).toInt(), (14 * dp).toInt()) })
        })

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { if (wv.canGoBack()) wv.goBack() else finish() }
        })

        // the app's own commands may wait on an approval: shown here too, since the chat is not on screen
        lifecycleScope.launch {
            var shown: AlertDialog? = null
            RiskGate.pending.collect { r ->
                // the card goes when its request does (answered in the chat, or no answer in time)
                shown?.dismiss()
                shown = null
                if (r == null || (r.sessionId != app.botSession && r.sessionId != AppBridge.GATE_PREFIX + app.name)) return@collect
                shown = AlertDialog.Builder(this@MoAppActivity)
                    .setTitle(app.title)
                    .setMessage(r.summary ?: r.preview)
                    .setPositiveButton(R.string.mo_risk_allow_once) { _, _ -> RiskGate.decide(r.id, RiskDecision.ALLOW_ONCE) }
                    .setNegativeButton(R.string.mo_risk_deny) { _, _ -> RiskGate.decide(r.id, RiskDecision.DENY) }
                    .setOnCancelListener { RiskGate.decide(r.id, RiskDecision.DENY) }
                    .showOver()
            }
        }

        // the person's answer on what this app may use (AppPermissions)
        lifecycleScope.launch {
            var shown: AlertDialog? = null
            AppPermissions.pending.collect { q ->
                shown?.dismiss()
                shown = null
                if (q == null || q.app.name != app.name) return@collect
                val what = AppPermissions.label(q.command)?.let { getString(it) } ?: q.command
                shown = AlertDialog.Builder(this@MoAppActivity)
                    .setMessage(getString(R.string.mo_app_cap_ask, app.title, what))
                    .setPositiveButton(R.string.mo_app_cap_allow) { _, _ -> q.answer(true) }
                    .setNegativeButton(R.string.mo_app_cap_deny) { _, _ -> q.answer(false) }
                    .setOnCancelListener { q.answer(false) }
                    .showOver()
            }
        }

        AppBots.opened(this, app)
        AppShare.publish(this, app)
        // what arrives while it is open comes straight in (AppEvents)
        val deliver: (List<org.json.JSONObject>) -> Unit = { events -> wv.evaluateJavascript(AppEvents.script(events), null) }
        this.deliver = deliver
        AppEvents.attach(app, deliver)

        // a change to the folder shows at once (also while the app's chat is open over it)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                var seen = stamp(app.dir)
                while (true) {
                    delay(700)
                    val now = stamp(app.dir)
                    if (now != seen) {
                        delay(400) // let a burst of writes finish
                        seen = stamp(app.dir)
                        load(wv, byChange = true)
                    }
                }
            }
        }

        load(wv)
    }

    /** [byChange]: its Momoding is changing it; errors while it works are expected, so none is offered a fix. */
    private fun load(wv: WebView, byChange: Boolean = false) {
        loadedAt = System.currentTimeMillis()
        troubleOffered = byChange
        if (!app.index.isFile) { trouble("index.html is missing"); return }
        wv.loadUrl(ORIGIN + "index.html")
    }

    /** Something broke right after the page loaded: offer the app's Momoding to fix it. */
    private fun trouble(what: String) {
        MoApps.log(app, "ERROR", what)
        if (troubleOffered || System.currentTimeMillis() - loadedAt > 8000) return
        troubleOffered = true
        runOnUiThread {
            if (isFinishing) return@runOnUiThread
            AlertDialog.Builder(this)
                .setTitle(app.title)
                .setMessage(R.string.mo_app_broken)
                .setPositiveButton(R.string.mo_app_fix) { _, _ ->
                    AppBots.tell(this, app, getString(R.string.mo_app_fix_brief, what), AppBots.From.PERSON)
                    AppChat.open(this, app, "")
                }
                .setNegativeButton(android.R.string.cancel, null)
                .showOver()
        }
    }

    override fun onResume() {
        super.onResume()
        AppScreen.showing(app.name, this)
        // a dot on the pen when the app's Momoding has said something the person has not seen
        lifecycleScope.launch {
            val news = runCatching { AppBots.hasNews(this@MoAppActivity, app) }.getOrDefault(false)
            pen?.text = if (news) "✎•" else "✎"
            pen?.alpha = if (news) 0.95f else 0.6f
        }
    }

    override fun onPause() {
        if (AppScreen.of(app.name) === this) AppScreen.showing(app.name, null)
        super.onPause()
    }

    override fun onDestroy() {
        deliver?.let { AppEvents.detach(app, it) }
        bridge?.close()
        web?.destroy()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_NAME = "momoding_app"
        const val HOST = "appassets.androidplatform.net"
        const val ORIGIN = "https://$HOST/"

        /** The newest change to the app's own files (not git's, not the platform's). */
        internal fun stamp(dir: File): Long {
            var newest = 0L
            dir.walkTopDown().onEnter { it == dir || !it.name.startsWith(".") }.forEach { if (it.isFile) newest = maxOf(newest, it.lastModified()) }
            return newest
        }

        fun intent(context: Context, name: String): Intent = Intent(context, MoAppActivity::class.java)
            .setAction(Intent.ACTION_VIEW)
            .setData(Uri.parse("momoding-app://$name")) // one task per app
            .putExtra(EXTRA_NAME, name)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NEW_DOCUMENT)

        /** Asks the launcher to put the app on the home screen; the launcher shows its own confirmation. */
        fun pin(context: Context, app: MoApps.App): Boolean {
            if (!ShortcutManagerCompat.isRequestPinShortcutSupported(context)) return false
            val existing = ShortcutManagerCompat.getShortcuts(context, ShortcutManagerCompat.FLAG_MATCH_PINNED).any { it.id == shortcutId(app) }
            if (existing) { ShortcutManagerCompat.updateShortcuts(context, listOf(shortcut(context, app))); return true }
            return runCatching { ShortcutManagerCompat.requestPinShortcut(context, shortcut(context, app), null) }.getOrDefault(false)
        }

        private fun shortcutId(app: MoApps.App) = "momoding_app_${app.name}"

        private fun shortcut(context: Context, app: MoApps.App) = ShortcutInfoCompat.Builder(context, shortcutId(app))
            .setShortLabel(app.title).setLongLabel(app.title)
            .setIcon(IconCompat.createWithAdaptiveBitmap(AppIcon.bitmap(app, 432, adaptive = true)))
            .setIntent(intent(context, app.name))
            .build()
    }
}

/** An app's icon: the one its manifest names (a PNG), else its first letter on its theme colour. */
object AppIcon {
    fun bitmap(app: MoApps.App, px: Int, adaptive: Boolean): Bitmap {
        val out = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val bg = runCatching { Color.parseColor(app.themeColor) }.getOrDefault(PALETTE[Math.floorMod(app.name.hashCode(), PALETTE.size)])
        c.drawColor(bg)
        // a launcher masks an adaptive icon to its middle (a 66-unit circle of 108): keep what matters inside it
        val inset = if (adaptive) px * 0.2f else 0f
        own(app)?.let { src ->
            c.drawBitmap(src, null, android.graphics.RectF(inset, inset, px - inset, px - inset), Paint(Paint.FILTER_BITMAP_FLAG))
            return out
        }
        val letter = app.title.trim().take(1).ifEmpty { "·" }
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; textAlign = Paint.Align.CENTER; isFakeBoldText = true
            textSize = (px - 2 * inset) * 0.5f
        }
        c.drawText(letter, px / 2f, px / 2f - (p.descent() + p.ascent()) / 2f, p)
        return out
    }

    /** The largest PNG or WebP among the manifest's icons. */
    private fun own(app: MoApps.App): Bitmap? {
        val icons = app.manifest().optJSONArray("icons") ?: return null
        val files = (0 until icons.length()).mapNotNull { icons.optJSONObject(it)?.optString("src")?.takeIf { s -> s.isNotBlank() } }
            .map { File(app.dir, it.trimStart('/')) }
            .filter { it.isFile && it.canonicalPath.startsWith(app.dir.canonicalPath) && it.extension.lowercase() in setOf("png", "webp", "jpg", "jpeg") }
        return files.maxByOrNull { it.length() }?.let { BitmapFactory.decodeFile(it.path) }
    }

    private val PALETTE = intArrayOf(0xFFF4A261.toInt(), 0xFF2A9D8F.toInt(), 0xFFE76F51.toInt(), 0xFF457B9D.toInt(),
        0xFF8E7DBE.toInt(), 0xFF6A994E.toInt(), 0xFFE9C46A.toInt(), 0xFF264653.toInt())
}
