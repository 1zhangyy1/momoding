package app.momoding.apps

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.openminis.app.R
import com.openminis.app.i18n.LocaleWrap
import com.openminis.app.sandbox.NativeOffloadHandler
import com.openminis.app.sandbox.NativeOffloadRequest
import com.openminis.app.sandbox.NativeOffloadResult
import com.openminis.app.sandbox.offload.OffloadArgs
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * `momoding-app` — Momoding Apps from the shell (调研/M27):
 *
 *     momoding-app new <name> --title "<title>" --brief "<what the person wants>"
 *     momoding-app install <name>        put it on the home screen
 *     momoding-app open <name>           show it on the screen
 *     momoding-app tell <name> "<text>"  a message for that app's Momoding
 *     momoding-app done "<what changed>" (from an app's Momoding) one line in the chat that asked for it
 *     momoding-app list
 */
class AppsOffloadHandler(private val context: Context) : NativeOffloadHandler {

    override fun handle(request: NativeOffloadRequest): NativeOffloadResult {
        val args = OffloadArgs(request.argv.drop(1))
        return runCatching {
            when (val sub = args.positional.firstOrNull()) {
                null, "help", "--help", "-h" -> NativeOffloadResult(0, HELP)
                "new" -> new(args, request)
                "install" -> install(app(args))
                "open" -> open(app(args))
                "tell" -> tell(app(args), args.positional.drop(2).joinToString(" "))
                "done" -> done(args, request)
                "list" -> list()
                else -> NativeOffloadResult(2, "momoding-app: unknown subcommand '$sub'\n$HELP")
            }
        }.getOrElse { NativeOffloadResult(2, "momoding-app: ${it.message}\n") }
    }

    private fun app(args: OffloadArgs): MoApps.App {
        val name = args.positional.getOrNull(1) ?: throw IllegalArgumentException("which app? momoding-app list shows them")
        return MoApps.get(context, name) ?: throw IllegalArgumentException("no app called $name (momoding-app list)")
    }

    private fun new(args: OffloadArgs, request: NativeOffloadRequest): NativeOffloadResult {
        val name = args.positional.getOrNull(1) ?: throw IllegalArgumentException("momoding-app new <name> --title \"…\" --brief \"…\"")
        val title = args.get("title")?.trim().orEmpty().ifEmpty { name }
        val brief = args.get("brief")?.trim().orEmpty()
        require(brief.isNotEmpty()) { "--brief: what the person wants, in their words, with anything you know that helps" }
        val app = MoApps.create(context, name, title)
        runBlocking { AppBots.create(context, app, request.sessionId, brief) }
        return ok(JSONObject().put("app", name).put("folder", app.linuxDir)
            .put("tell_user", "Its own Momoding is building it now and reports back here when it is ready. Say so in one line; do not build it yourself."))
    }

    private fun install(app: MoApps.App): NativeOffloadResult {
        if (!app.index.isFile) throw IllegalStateException("${app.linuxDir}/index.html does not exist yet")
        val asked = onMain { MoAppActivity.pin(context, app) }
        return ok(JSONObject().put("app", app.name).put("home_screen", if (asked) "asked" else "unsupported")
            .put("note", if (asked) "The launcher shows its own confirmation; once the person adds it, the icon opens the app." else "This launcher cannot add icons; it opens with momoding-app open."))
    }

    private fun open(app: MoApps.App): NativeOffloadResult {
        if (!app.index.isFile) throw IllegalStateException("${app.linuxDir}/index.html does not exist yet")
        onMain { context.startActivity(MoAppActivity.intent(context, app.name)); true }
        return ok(JSONObject().put("opened", app.name))
    }

    private fun tell(app: MoApps.App, text: String): NativeOffloadResult {
        require(text.isNotBlank()) { "momoding-app tell <name> \"<what to do>\"" }
        if (app.botSession == null) throw IllegalStateException("${app.name} has no Momoding of its own")
        AppBots.tell(context, app, text)
        return ok(JSONObject().put("told", app.name).put("note", "It works on its own and reports back here; no need to wait or check."))
    }

    private fun done(args: OffloadArgs, request: NativeOffloadRequest): NativeOffloadResult {
        val app = request.sessionId?.let { MoApps.ofSession(context, it) }
            ?: throw IllegalStateException("done is for an app's own Momoding")
        val text = args.positional.drop(1).joinToString(" ").trim()
        require(text.isNotEmpty()) { "momoding-app done \"<what changed, in one line>\"" }
        val ui = LocaleWrap.uiContext(context)
        AppBots.receipt(context, app, text, ui.getString(R.string.mo_app_open, app.title))
        return ok(JSONObject().put("reported", app.name))
    }

    private fun list(): NativeOffloadResult = ok(JSONObject().put("apps", JSONArray().apply {
        MoApps.list(context).forEach { put(JSONObject().put("name", it.name).put("title", it.title).put("folder", it.linuxDir).put("ready", it.index.isFile)) }
    }))

    private fun <T> onMain(block: () -> T): T {
        var result: T? = null
        var error: Throwable? = null
        val latch = CountDownLatch(1)
        Handler(Looper.getMainLooper()).post { runCatching(block).onSuccess { result = it }.onFailure { error = it }; latch.countDown() }
        latch.await(10, TimeUnit.SECONDS)
        error?.let { throw it }
        @Suppress("UNCHECKED_CAST") return result as T
    }

    private fun ok(o: JSONObject) = NativeOffloadResult(0, o.toString(2) + "\n")

    companion object {
        private val HELP = """
            momoding-app — Momoding Apps: small apps on the home screen, each with its own Momoding.

              new <name> --title "<title>" --brief "<what the person wants>"
                                   a new app folder and its Momoding, who builds it (name: a short
                                   lowercase word like ledger)
              install <name>       put it on the home screen
              open <name>          show it on the screen
              tell <name> "<text>" a message for that app's Momoding
              done "<what changed>" from an app's own Momoding: one line in the chat that asked for it
              list                 the apps
        """.trimIndent() + "\n"
    }
}
