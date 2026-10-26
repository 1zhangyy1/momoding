package app.momoding.apps

import android.webkit.MimeTypeMap
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream
import java.io.File
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

/**
 * Serves an app's files, the same way to its home-screen host and to the agent's own browser
 * (`minis://apps/<name>/…`), so what the agent checks is what the person gets. Every HTML page
 * gets `window.momoding` as its first script, bound by a token minted for that load: the native
 * side answers only calls carrying a live token, so no other page in the browser can use it.
 */
object AppPage {
    private val tokens = ConcurrentHashMap<String, String>() // token -> app name
    private val random = SecureRandom()

    fun appFor(token: String): String? = tokens[token]

    /** [path] is inside the app folder ("" or "/" for index.html). Null when there is no such file. */
    fun respond(app: MoApps.App, path: String): WebResourceResponse? {
        val rel = path.trimStart('/').ifEmpty { "index.html" }
        // the platform's files and git's are not the app's to serve
        if (rel.split('/').any { it.startsWith(".") || it == ".." }) return notFound()
        val file = File(app.dir, rel).let { if (it.isDirectory) File(it, "index.html") else it }
        if (!file.isFile || !file.canonicalPath.startsWith(app.dir.canonicalPath + File.separator)) return notFound()
        val mime = mimeOf(file.name)
        val bytes = if (mime == "text/html") withBridge(app, file.readText()).toByteArray() else file.readBytes()
        return WebResourceResponse(mime, if (mime.startsWith("text/") || mime.endsWith("json") || mime.endsWith("javascript")) "utf-8" else null,
            200, "OK", mapOf("Cache-Control" to "no-store"), ByteArrayInputStream(bytes))
    }

    /** `minis://apps/<name>/<path>` in the agent's browser. */
    fun forBrowser(context: android.content.Context, path: String): WebResourceResponse {
        val name = path.trimStart('/').substringBefore('/')
        val app = MoApps.get(context, name) ?: return notFound()
        return respond(app, path.trimStart('/').removePrefix(name)) ?: notFound()
    }

    /** The page with the bridge in front of its own scripts. */
    internal fun withBridge(app: MoApps.App, html: String): String {
        val token = ByteArray(16).also(random::nextBytes).joinToString("") { "%02x".format(it) }
        tokens[token] = app.name
        val script = "<script>${AppBridge.shim(token)}</script>"
        val head = Regex("<head[^>]*>", RegexOption.IGNORE_CASE).find(html)
        return if (head != null) html.replaceRange(head.range.last + 1, head.range.last + 1, script) else script + html
    }

    private fun notFound() = WebResourceResponse("text/plain", "utf-8", 404, "Not found", null, ByteArrayInputStream(ByteArray(0)))

    private fun mimeOf(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "html", "htm" -> "text/html"
        "js", "mjs" -> "text/javascript"
        "css" -> "text/css"
        "json", "webmanifest" -> "application/json"
        "svg" -> "image/svg+xml"
        "wasm" -> "application/wasm"
        else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase()) ?: "application/octet-stream"
    }
}
