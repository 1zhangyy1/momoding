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
 *     momoding-app open <name>           show it on the person's screen (when they ask to see it)
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
                "open" -> open(app(args), request)
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

    /**
     * Shows [app] on the person's screen — when they ask to see it. An app's own Momoding never takes
     * their screen: its open readies the stage no one sees, where look and tap work (AppStage).
     */
    private fun open(app: MoApps.App, request: NativeOffloadRequest): NativeOffloadResult {
        if (!app.index.isFile) throw IllegalStateException("${app.linuxDir}/index.html does not exist yet")
        if (request.sessionId?.let { MoApps.ofSession(context, it) } != null) {
            runBlocking { stage(app) }
            return ok(JSONObject().put("ready", app.name).put("note", "Not shown on the person's screen: they are not interrupted while you check. look, tap, swipe and type work as it is; they open it from your line's card."))
        }
        onMain { context.startActivity(MoAppActivity.intent(context, app.name)); true }
        return ok(JSONObject().put("opened", app.name))
    }

    private fun tell(app: MoApps.App, text: String, request: NativeOffloadRequest): NativeOffloadResult {
        require(text.isNotBlank()) { "momoding-app tell <name> \"<what to do>\"" }
        if (app.botSession == null) throw IllegalStateException("${app.name} has no Momoding of its own")
        AppBots.tell(context, app, text, by = request.sessionId)
        return ok(JSONObject().put("told", app.name).put("note", "It works on its own and reports back here, with what there is to see, when it is done; no need to wait or check. The chat already shows the hand-over."))
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
            val stage = stage(app)
            when (sub) {
                "look" -> {
                    val file = java.io.File(app.platform, "look.png")
                    val picture = withContext(Dispatchers.Main) { AppScreen.picture(stage) }
                    file.outputStream().use { picture.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                    ok(JSONObject().put("picture", "${app.linuxDir}/.momoding/look.png").put("width", picture.width).put("height", picture.height)
                        .put("note", "Look at it with read_image. tap / swipe take its pixels. What the system shows (its permission prompt, a picker, the camera app) is not in it: the log says when the app waits on one."))
                }
                "tap", "swipe" -> {
                    val to = if (sub == "tap") listOf(n(0), n(1)) else listOf(n(2), n(3))
                    val hit = withContext(Dispatchers.Main) { AppScreen.touch(stage, n(0), n(1), to[0], to[1], args.get("ms")?.toLongOrNull() ?: if (sub == "tap") 60 else 300) }
                    MoApps.log(app, "log", "$sub ${rest.joinToString(" ")} → ${hit.ifEmpty { "nothing" }}")
                    settled(hit)
                }
                else -> {
                    val text = rest.joinToString(" ")
                    require(text.isNotEmpty()) { "momoding-app type \"<text>\"" }
                    val typed = withContext(Dispatchers.Main) { AppScreen.type(stage, text) }
                    if (!typed) throw IllegalStateException("nothing on the page has the focus: tap the field first")
                    settled()
                }
            }
        }
    }

    /**
     * Where to check [app]: the person's screen when they have it open, else a stage no one sees
     * (AppStage) — checking the work never takes their screen.
     */
    private suspend fun stage(app: MoApps.App): AppScreen.Stage {
        AppScreen.of(app.name)?.let { return it }
        if (!app.index.isFile) throw IllegalStateException("${app.linuxDir}/index.html does not exist yet")
        val stage = withContext(Dispatchers.Main) { AppStage.of(context, app) }
        withContext(Dispatchers.Main) { stage.ready() }
        delay(SETTLE)
        return stage
    }

    /** What the touch started has had a moment to show. */
    private suspend fun settled(hit: String = ""): NativeOffloadResult {
        delay(SETTLE)
        return ok(JSONObject().put("done", true).apply { if (hit.isNotEmpty()) put("landed_on", hit) }.put("note", "look shows what it did"))
    }

    private fun list(): NativeOffloadResult = ok(JSONObject().put("apps", JSONArray().apply {
        MoApps.list(context).forEach { put(JSONObject().put("name", it.name).put("title", it.title).put("what", it.what).put("folder", it.linuxDir).put("app", it.hasApp)
            .put("home_screen", AppHome.there(context, it, AppHome.What.ICON)).put("widget", AppHome.there(context, it, AppHome.What.WIDGET))) }
    }).put("this_phone", JSONObject() // what the person can put on this phone's home screen at all
        .put("home_screen", AppHome.possible(context, AppHome.What.ICON)).put("widget", AppHome.possible(context, AppHome.What.WIDGET))))

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
              open <name>          show it on the person's screen (when they ask to see it)
              tell <name> "<text>" a message for that Momoding
              progress "<what is there now>"
                                   from an app's own Momoding while it builds: the same line, early,
                                   so the person can watch it grow
              done "<what changed>" from an app's own Momoding: one line in the chat that asked for it,
                                   with the app's card under it (open it, put it on the home screen)
              suggest "<improvement>" from an app's own Momoding after a review: a dot on the app
              list                 the Momodings, which have an app, and what of it is on the home
                                   screen (home_screen, widget: what the launcher says); this_phone:
                                   what this phone lets the person put there at all

            An app's own Momoding, checking its work on the phone as the person sees it: on their
            screen if they have it open, else where no one sees it, so they are never interrupted
            (Momoding itself names the app: look <name>):
              look                 a picture of it now, with what Momoding shows over it
              tap <x> <y> [--ms N] a finger on the page, in the picture's pixels (--ms 800: press and
                                   hold); what Momoding shows over it is the person's to answer
              swipe <x1> <y1> <x2> <y2> [--ms N]
              type "<text>"        typed where the page's focus is (tap the field first)
        """.trimIndent() + "\n"
    }
}
