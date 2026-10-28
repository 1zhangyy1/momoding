package app.momoding.apps

import android.Manifest
import android.app.Activity
import android.app.DownloadManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.view.View
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.GeolocationPermissions
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.openminis.app.R
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/**
 * What a page can do in any browser, an app's page can do here: pick or take a picture or a file
 * (`<input type=file>`, with `capture`), the camera and microphone (getUserMedia), where the phone
 * is (geolocation), saving a file (a download, `<a download>` included), full screen. These are the
 * web's own interfaces, done once: an app is web code, so whatever the web offers works in every
 * app and nothing is opened app by app. What the web does not offer goes through momoding.run.
 *
 * The camera, microphone and location are asked per app, as a browser asks per site
 * (AppPermissions), then by the system's own dialog if Momoding itself lacks them. Picking a file
 * or saving one is the person's own act, so nothing is asked.
 *
 * Made in the activity's onCreate (its results are registered then); [attach] it to the page.
 */
class AppWeb(private val activity: ComponentActivity, private val app: MoApps.App, private val onError: (String) -> Unit) {

    private var permissionsAnswer: CompletableDeferred<Map<String, Boolean>>? = null
    private val permissions = activity.registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        permissionsAnswer?.complete(it)
    }

    private var filesAnswer: CompletableDeferred<androidx.activity.result.ActivityResult>? = null
    private val files = activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        filesAnswer?.complete(it)
    }

    /** The page's browser side; an error in its console goes to [onError]. */
    val chrome: WebChromeClient = object : WebChromeClient() {
        override fun onConsoleMessage(m: ConsoleMessage): Boolean {
            if (m.messageLevel() == ConsoleMessage.MessageLevel.ERROR) onError(m.message())
            return false
        }

        override fun onShowFileChooser(view: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
            activity.lifecycleScope.launch { callback.onReceiveValue(choose(params)) }
            return true
        }

        override fun onPermissionRequest(request: PermissionRequest) {
            // only the app's own page, not something it frames from elsewhere
            if (request.origin.host != MoAppActivity.HOST) { request.deny(); return }
            activity.lifecycleScope.launch {
                val granted = request.resources.filter { r ->
                    when (r) {
                        PermissionRequest.RESOURCE_VIDEO_CAPTURE -> may(CAMERA, Manifest.permission.CAMERA)
                        PermissionRequest.RESOURCE_AUDIO_CAPTURE -> may(MICROPHONE, Manifest.permission.RECORD_AUDIO)
                        else -> false
                    }
                }
                if (granted.isEmpty()) request.deny() else request.grant(granted.toTypedArray())
            }
        }

        override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) {
            if (Uri.parse(origin).host != MoAppActivity.HOST) { callback.invoke(origin, false, false); return }
            activity.lifecycleScope.launch {
                callback.invoke(origin, may(LOCATION, Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), false)
            }
        }

        private var fullscreen: View? = null
        private var fullscreenGone: CustomViewCallback? = null

        override fun onShowCustomView(view: View, callback: CustomViewCallback) {
            onHideCustomView()
            fullscreen = view
            fullscreenGone = callback
            (activity.window.decorView as ViewGroup).addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }

        override fun onHideCustomView() {
            fullscreen?.let { (activity.window.decorView as ViewGroup).removeView(it) }
            fullscreen = null
            fullscreenGone?.onCustomViewHidden()
            fullscreenGone = null
        }
    }

    /** The browser side, geolocation on, downloads saved, and the page's way to hand over a file it made (`blob:`). */
    fun attach(wv: WebView) {
        wv.webChromeClient = chrome
        wv.settings.setGeolocationEnabled(true)
        wv.addJavascriptInterface(Saver(), SAVER)
        // the name `<a download="…">` gives is not passed to a download: the page notes it as the link is followed
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT))
            WebViewCompat.addDocumentStartJavaScript(wv, NAMES_SCRIPT, setOf(MoAppActivity.ORIGIN.trimEnd('/')))
        wv.setDownloadListener { url, _, disposition, mime, _ ->
            val name = URLUtil.guessFileName(url, disposition, mime)
            when {
                url.startsWith("blob:") || url.startsWith("data:") ->
                    // only the page can read its own blob: it hands the bytes over as a data URL
                    wv.evaluateJavascript(
                        "fetch(${JSONObject.quote(url)}).then(r=>r.blob()).then(b=>{const f=new FileReader();" +
                            "f.onload=()=>$SAVER.save(f.result,(window.$NAMES||{})[${JSONObject.quote(url)}]||${JSONObject.quote(name)});f.readAsDataURL(b)})", null,
                    )
                url.startsWith("http") -> runCatching {
                    val dm = activity.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                    dm.enqueue(DownloadManager.Request(Uri.parse(url)).setMimeType(mime)
                        .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                        .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name))
                    saved(name)
                }.onFailure { MoApps.log(app, "ERROR", "download of $url: ${it.message}") }
            }
        }
    }

    private inner class Saver {
        /** A file the page made, as a data URL (`data:<type>;base64,…`), saved to Downloads under [name]. */
        @JavascriptInterface
        fun save(dataUrl: String, name: String) {
            activity.lifecycleScope.launch(Dispatchers.IO) {
                runCatching {
                    val m = Regex("^data:([^;,]*)(;base64)?,(.*)$", RegexOption.DOT_MATCHES_ALL).find(dataUrl) ?: error("not a data URL")
                    val mime = m.groupValues[1].ifBlank { "application/octet-stream" }
                    val bytes = if (m.groupValues[2].isNotEmpty()) android.util.Base64.decode(m.groupValues[3], android.util.Base64.DEFAULT)
                        else Uri.decode(m.groupValues[3]).toByteArray()
                    write(name.ifBlank { "download" }, mime, bytes)
                    withContext(Dispatchers.Main) { saved(name) }
                }.onFailure { MoApps.log(app, "ERROR", "saving $name: ${it.message}") }
            }
        }
    }

    private fun write(name: String, mime: String, bytes: ByteArray) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, mime)
            }
            val uri = activity.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: error("Downloads refused the file")
            activity.contentResolver.openOutputStream(uri)!!.use { it.write(bytes) }
        } else {
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).apply { mkdirs() }
            File(dir, name).writeBytes(bytes)
        }
    }

    private fun saved(name: String) {
        MoApps.log(app, "log", "saved $name to Downloads")
        Toast.makeText(activity, activity.getString(R.string.mo_app_saved, name), Toast.LENGTH_SHORT).show()
    }

    /** What `<input type=file>` gets: a picture taken, files picked, or nothing (always answered, or the input stops working). */
    private suspend fun choose(params: WebChromeClient.FileChooserParams): Array<Uri>? {
        val accepts = params.acceptTypes.flatMap { it.split(',') }.map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        val any = accepts.isEmpty() || accepts.any { it == "*/*" }
        val images = any || accepts.any { it.startsWith("image/") || it in IMAGE_EXTS }
        val videos = any || accepts.any { it.startsWith("video/") || it in VIDEO_EXTS }

        // taking one needs the camera: Momoding declares it, so the system camera refuses without it
        val shot = if ((images || videos) && system(Manifest.permission.CAMERA)) {
            File(activity.filesDir, "camera-photos").apply { mkdirs() }
                .let { File(it, "app-${System.currentTimeMillis()}" + if (images) ".jpg" else ".mp4") }
        } else null
        val takes = shot?.let { file ->
            val out = FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", file)
            listOfNotNull(
                if (images) Intent(MediaStore.ACTION_IMAGE_CAPTURE) else null,
                if (videos && !images) Intent(MediaStore.ACTION_VIDEO_CAPTURE) else null,
            ).map { it.putExtra(MediaStore.EXTRA_OUTPUT, out).addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        }.orEmpty()

        val pick = params.createIntent().apply {
            if (params.mode == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE) putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        val intent = when {
            // `capture` asks for the camera straight away, as a phone browser does
            params.isCaptureEnabled && takes.isNotEmpty() -> takes.first()
            takes.isEmpty() -> pick
            else -> Intent.createChooser(pick, null).putExtra(Intent.EXTRA_INITIAL_INTENTS, takes.toTypedArray())
        }
        val answer = CompletableDeferred<androidx.activity.result.ActivityResult>()
        filesAnswer = answer
        if (runCatching { files.launch(intent) }.isFailure) return null
        val result = answer.await()
        if (result.resultCode != Activity.RESULT_OK) { shot?.delete(); return null }
        val data = result.data
        val picked = buildList {
            data?.clipData?.let { c -> for (i in 0 until c.itemCount) add(c.getItemAt(i).uri) }
            if (isEmpty()) data?.data?.let { add(it) }
        }
        if (picked.isNotEmpty()) { shot?.delete(); return picked.toTypedArray() }
        return shot?.takeIf { it.length() > 0 }?.let { arrayOf(FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", it)) }
    }

    /** This app may use [what] (the person's answer for this app), and Momoding has one of [system] from the system. */
    private suspend fun may(what: String, vararg system: String): Boolean =
        AppPermissions.check(app, what) && system(*system)

    /** Momoding has one of [names] from the system, asked for now if not. */
    private suspend fun system(vararg names: String): Boolean {
        fun has() = names.any { activity.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
        if (has()) return true
        val answer = CompletableDeferred<Map<String, Boolean>>()
        permissionsAnswer = answer
        permissions.launch(arrayOf(*names))
        answer.await()
        return has()
    }

    companion object {
        /** What the person is asked about, in AppPermissions' terms: the same as the phone commands' where it is the same thing. */
        const val CAMERA = "web-camera"
        const val MICROPHONE = "android-speech"
        const val LOCATION = "android-location"

        private const val SAVER = "__momodingSave"
        private const val NAMES = "__momodingNames"
        /** Notes the name of each `<a download>` followed: tapped, or clicked by the page (also off the document). */
        private val NAMES_SCRIPT = """
            (()=>{const n=window.$NAMES={};const note=a=>{if(a&&a.hasAttribute&&a.hasAttribute('download')&&a.download)n[a.href]=a.download};
            addEventListener('click',e=>note(e.target&&e.target.closest&&e.target.closest('a')),true);
            const P=HTMLAnchorElement.prototype,c=P.click,d=EventTarget.prototype.dispatchEvent;
            P.click=function(){note(this);return c.call(this)};
            EventTarget.prototype.dispatchEvent=function(e){if(this instanceof HTMLAnchorElement&&e&&e.type==='click')note(this);return d.call(this,e)}})()
        """.trimIndent()
        private val IMAGE_EXTS = setOf(".jpg", ".jpeg", ".png", ".gif", ".webp", ".heic")
        private val VIDEO_EXTS = setOf(".mp4", ".mov", ".webm")
    }
}
