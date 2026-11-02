package app.momoding.apps

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.openminis.app.R
import com.openminis.app.sandbox.NativeOffloadHandler
import com.openminis.app.sandbox.NativeOffloadRequest
import com.openminis.app.sandbox.NativeOffloadResult
import com.openminis.app.sandbox.offload.OffloadArgs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * `momoding-app` — the person's other Momodings and their apps, from the shell (调研/M27, M34):
 *
 *     momoding-app new <name> --title "<title>" --what "<what it takes care of>" --brief "<what the person wants>"
 *     momoding-app open <name>           show it on the screen
 *     momoding-app tell <name> "<text>"  a message for that app's Momoding
 *     momoding-app progress "<what is there now>"  (from an app's Momoding, while building) the same, early
 *     momoding-app done "<what changed>" (from an app's Momoding) one line in the chat that asked for it
 *     momoding-app list
 *     momoding-app look | tap <x> <y> | swipe <x1> <y1> <x2> <y2> | type "<text>"   (an app's Momoding) its app on the phone (AppScreen)
 */
class AppsOffloadHandler(private val context: Context) : NativeOffloadHandler {

    override fun handle(request: NativeOffloadRequest): NativeOffloadResult {
        val args = OffloadArgs(request.argv.drop(1))
        return runCatching {
            when (val sub = args.positional.firstOrNull()) {
                null, "help", "--help", "-h" -> NativeOffloadResult(0, HELP)
                "new" -> new(args, request)
                "open" -> open(app(args))
                "tell" -> tell(app(args), args.positional.drop(2).joinToString(" "), request)
                "done", "progress" -> done(args, request)
                "suggest" -> suggest(args, request)
                "list" -> list()
                "look", "tap", "swipe", "type" -> screen(sub, args, request)
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
        val app = MoApps.create(context, name, title, args.get("what")?.trim().orEmpty())
        runBlocking { AppBots.create(context, app, request.sessionId, brief) }
        return ok(JSONObject().put("app", name).put("folder", app.linuxDir)
            .put("tell_user", "It is starting now and reports back here. Say so in one line; do not do its work yourself."))
    }

    private fun open(app: MoApps.App): NativeOffloadResult {
        if (!app.index.isFile) throw IllegalStateException("${app.linuxDir}/index.html does not exist yet")
        onMain { context.startActivity(MoAppActivity.intent(context, app.name)); true }
        return ok(JSONObject().put("opened", app.name))
    }

    private fun tell(app: MoApps.App, text: String, request: NativeOffloadRequest): NativeOffloadResult {
        require(text.isNotBlank()) { "momoding-app tell <name> \"<what to do>\"" }
        if (app.botSession == null) throw IllegalStateException("${app.name} has no Momoding of its own")
        AppBots.tell(context, app, text, by = request.sessionId)
        return ok(JSONObject().put("told", app.name).put("note", "It works on its own and reports back here; no need to wait or check. Tell the person in a line that ${app.title} has it."))
    }

    private fun done(args: OffloadArgs, request: NativeOffloadRequest): NativeOffloadResult {
        val app = request.sessionId?.let { MoApps.ofSession(context, it) }
            ?: throw IllegalStateException("done and progress are for an app's own Momoding")
        val text = args.positional.drop(1).joinToString(" ").trim()
        require(text.isNotEmpty()) { "momoding-app done \"<what changed, in one line>\"" }
        AppBots.receipt(context, app, text, finished = args.positional.firstOrNull() == "done")
        return ok(JSONObject().put("reported", app.name))
    }

    private fun suggest(args: OffloadArgs, request: NativeOffloadRequest): NativeOffloadResult {
        val app = request.sessionId?.let { MoApps.ofSession(context, it) }
            ?: throw IllegalStateException("suggest is for an app's own Momoding")
        val text = args.positional.drop(1).joinToString(" ").trim()
        require(text.isNotEmpty()) { "momoding-app suggest \"<the improvement, in one line>\"" }
        AppBots.suggest(app, text)
        return ok(JSONObject().put("suggested", app.name).put("note", "A dot shows on the app until the person opens your chat."))
    }

    /** Its app on the phone as the person sees it, and a finger on it: for checking the work where it is used. */
    private fun screen(sub: String, args: OffloadArgs, request: NativeOffloadRequest): NativeOffloadResult {
        val mine = request.sessionId?.let { MoApps.ofSession(context, it) }
        // an app's Momoding sees its own app; Momoding itself names the one it means
        val first = args.positional.getOrNull(1)
        val other = first?.let { MoApps.get(context, it) }
        if (mine != null && other != null && other.name != mine.name) throw IllegalArgumentException("an app's Momoding sees its own app only (${mine.name})")
        val app = mine ?: other ?: throw IllegalArgumentException("which app? momoding-app $sub <name> … (momoding-app list)")
        val rest = args.positional.drop(if (first == app.name) 2 else 1)
        fun n(i: Int) = rest.getOrNull(i)?.toFloatOrNull() ?: throw IllegalArgumentException("momoding-app $sub: pixel numbers as in the picture from look\n$HELP")
        return runBlocking {
            val activity = shown(app)
            when (sub) {
                "look" -> {
                    val file = java.io.File(app.platform, "look.png")
                    val picture = withContext(Dispatchers.Main) { AppScreen.picture(activity) }
                    file.outputStream().use { picture.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                    ok(JSONObject().put("picture", "${app.linuxDir}/.momoding/look.png").put("width", picture.width).put("height", picture.height)
                        .put("note", "Look at it with read_image. tap / swipe take its pixels. What the system shows (its permission prompt, a picker, the camera app) is not in it: the log says when the app waits on one."))
                }
                "tap" -> { withContext(Dispatchers.Main) { AppScreen.touch(activity, n(0), n(1), n(0), n(1), args.get("ms")?.toLongOrNull() ?: 60) }; settled() }
                "swipe" -> { withContext(Dispatchers.Main) { AppScreen.touch(activity, n(0), n(1), n(2), n(3), args.get("ms")?.toLongOrNull() ?: 300) }; settled() }
                else -> {
                    val text = rest.joinToString(" ")
                    require(text.isNotEmpty()) { "momoding-app type \"<text>\"" }
                    val typed = withContext(Dispatchers.Main) { AppScreen.type(activity, text) }
                    if (!typed) throw IllegalStateException("nothing on the page has the focus: tap the field first")
                    settled()
                }
            }
        }
    }

    /** The app on the screen, opened (and given time to draw) if it is not. */
    private suspend fun shown(app: MoApps.App): MoAppActivity {
        AppScreen.of(app.name)?.let { return it }
        if (!app.index.isFile) throw IllegalStateException("${app.linuxDir}/index.html does not exist yet")
        withContext(Dispatchers.Main) { context.startActivity(MoAppActivity.intent(context, app.name)) }
        val activity = withTimeoutOrNull(8000) { while (AppScreen.of(app.name) == null) delay(100); AppScreen.of(app.name) }
            ?: throw IllegalStateException("${app.name} did not come up on the screen")
        withTimeoutOrNull(8000) { while (withContext(Dispatchers.Main) { activity.page()?.progress ?: 100 } < 100) delay(100) }
        delay(SETTLE)
        return activity
    }

    /** What the touch started has had a moment to show. */
    private suspend fun settled(): NativeOffloadResult { delay(SETTLE); return ok(JSONObject().put("done", true).put("note", "look shows what it did")) }

    private fun list(): NativeOffloadResult = ok(JSONObject().put("apps", JSONArray().apply {
        MoApps.list(context).forEach { put(JSONObject().put("name", it.name).put("title", it.title).put("what", it.what).put("folder", it.linuxDir).put("app", it.hasApp)
            .put("home_screen", AppHome.there(context, it, AppHome.What.ICON)).put("widget", AppHome.there(context, it, AppHome.What.WIDGET))) }
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
        private const val SETTLE = 700L
        private val HELP = """
            momoding-app — the person's other Momodings, each looking after one thing, and their apps
            (at most one each, on the home screen).

              new <name> --title "<title>" --what "<what it takes care of>" --brief "<what the person wants>"
                                   a new Momoding with its own chat and folder; it builds an app when
                                   one is wanted (name: a short lowercase word like ledger)
              open <name>          show it on the screen
              tell <name> "<text>" a message for that Momoding
              progress "<what is there now>"
                                   from an app's own Momoding while it builds: the same line, early,
                                   so the person can watch it grow
              done "<what changed>" from an app's own Momoding: one line in the chat that asked for it.
                                   A link to the app on its own last line ([打开记账](minis://apps/<name>/))
                                   shows as the app's card: open it, put it on the home screen
              suggest "<improvement>" from an app's own Momoding after a review: a dot on the app
              list                 the Momodings, which have an app, and what of it is on the home
                                   screen (home_screen, widget: what the launcher says)

            An app's own Momoding, checking its work on the phone as the person sees it (opens it if
            it is not on the screen; Momoding itself names the app: look <name>):
              look                 a picture of it now, with what Momoding shows over it
              tap <x> <y> [--ms N] a finger on the page, in the picture's pixels (--ms 800: press and
                                   hold); what Momoding shows over it is the person's to answer
              swipe <x1> <y1> <x2> <y2> [--ms N]
              type "<text>"        typed where the page's focus is (tap the field first)
        """.trimIndent() + "\n"
    }
}
