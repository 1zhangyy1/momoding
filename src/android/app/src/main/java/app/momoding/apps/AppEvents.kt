package app.momoding.apps

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.openminis.app.logging.AppLogger
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Apps that wake when something arrives (plan step 3): a text, a notification. An app says what
 * it listens to in its manifest, the way routines do —
 *
 *     "momoding": {"listen": [{"app": "sms", "match": "消费|支付|收款"}]}
 *
 * — and handles it in its own code with `momoding.on('notification', async n => …)`; no model
 * turn unless the app asks for one. Each matching arrival goes into the app's inbox (outside the
 * sandbox) and is delivered: at once to the app's page if one is open, otherwise to the app run
 * in the background; whatever is not handled (the phone killed it, the person has not allowed
 * it yet) is delivered the next time the app opens. Nothing is lost on the way.
 */
object AppEvents {
    private const val TAG = "AppEvents"
    private const val KEEP = 100
    private const val KEEP_MS = 7L * 24 * 60 * 60 * 1000
    private const val BACKGROUND_MS = 30_000L
    private val main = Handler(Looper.getMainLooper())

    /** Open pages of each app (its screen, or a background run), by name: how a new arrival reaches them. */
    private val live = ConcurrentHashMap<String, (List<JSONObject>) -> Unit>()

    /** The ability an arrival needs the person's OK for, per app (AppPermissions). */
    fun ability(event: JSONObject): String? = when {
        event.optString("type") == "share" -> null // the person shared it themselves
        event.optBoolean("sms") -> "android-sms"
        else -> "android-notification"
    }

    private suspend fun allowed(app: MoApps.App, event: JSONObject, ask: Boolean): Boolean {
        val ability = ability(event) ?: return true
        return if (ask) AppPermissions.check(app, ability) else AppPermissions.allowedAlready(app, ability)
    }

    /** Something the person shared to [app]: in its inbox, and straight to its page when one is open (the app is coming to the front). */
    fun share(app: MoApps.App, event: JSONObject) {
        add(app, event)
        live[app.name]?.let { deliver -> main.post { deliver(listOf(event)) } }
    }

    // ── matching ──────────────────────────────────────────────────────────────

    /** What [app] listens to, from its manifest. */
    internal fun listens(manifest: JSONObject): List<Pair<String?, String?>> {
        val list = manifest.optJSONObject("momoding")?.optJSONArray("listen") ?: return emptyList()
        return (0 until list.length()).mapNotNull { list.optJSONObject(it) }.map {
            it.optString("app").trim().ifEmpty { null } to it.optString("match").trim().ifEmpty { null }
        }.filter { it.first != null || it.second != null }
    }

    /** The same rule as a routine's `--app/--match`: the app's name or package, and any of `a|b` in the title or text. */
    internal fun matches(rule: Pair<String?, String?>, appName: String, pkg: String, isSms: Boolean, body: String): Boolean {
        val (app, match) = rule
        if (app != null) {
            val ok = (app.equals("sms", true) && isSms) || appName.contains(app, true) || pkg.contains(app, true)
            if (!ok) return false
        }
        if (match != null && match.split('|').map { it.trim() }.filter { it.isNotEmpty() }.none { body.contains(it, true) }) return false
        return true
    }

    // ── the inbox ─────────────────────────────────────────────────────────────

    private fun inbox(app: MoApps.App) = File(app.vault, "${app.name}.inbox.json")

    @Synchronized private fun read(app: MoApps.App): MutableList<JSONObject> {
        val arr = runCatching { JSONArray(inbox(app).readText()) }.getOrDefault(JSONArray())
        val now = System.currentTimeMillis()
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.filter { now - it.optLong("at") < KEEP_MS }.toMutableList()
    }

    @Synchronized private fun write(app: MoApps.App, events: List<JSONObject>) {
        inbox(app).writeText(JSONArray(events.takeLast(KEEP)).toString())
    }

    @Synchronized private fun add(app: MoApps.App, event: JSONObject) = write(app, read(app) + event)

    /** The page handled these: out of the inbox. */
    @Synchronized fun handled(app: MoApps.App, ids: Set<String>) = write(app, read(app).filterNot { it.optString("id") in ids })

    /** What [app] may be given now: arrivals it is allowed to receive ([ask]: ask the person, on its screen). */
    suspend fun pending(app: MoApps.App, ask: Boolean): List<JSONObject> = read(app).filter { allowed(app, it, ask) }

    // ── arrivals ──────────────────────────────────────────────────────────────

    /** A notification or a text arrived (ScheduledNotificationTriggers, SmsTriggerReceiver). */
    fun arrived(context: Context, pkg: String, appName: String, title: String, text: String, isSms: Boolean) {
        val body = "$title\n$text"
        for (app in MoApps.list(context)) {
            val rules = listens(app.manifest())
            if (rules.none { matches(it, appName, pkg, isSms, body) }) continue
            val event = JSONObject().put("id", java.util.UUID.randomUUID().toString()).put("type", "notification")
                .put("app", appName).put("title", title).put("text", text).put("sms", isSms).put("at", System.currentTimeMillis())
            add(app, event)
            AppLogger.info(TAG, "${app.name}: ${if (isSms) "text" else "notification"} from $appName")
            val deliver = live[app.name]
            val ok = ability(event)?.let { AppPermissions.allowedAlready(app, it) } ?: true
            if (deliver != null) {
                if (ok) main.post { deliver(listOf(event)) }
            } else if (ok) {
                runInBackground(context.applicationContext, app)
            }
            // not allowed yet: it waits in the inbox, and the person is asked when they open the app
        }
    }

    /** An open page of [app] takes new arrivals through [deliver] while it lives. */
    fun attach(app: MoApps.App, deliver: (List<JSONObject>) -> Unit) { live[app.name] = deliver }
    fun detach(app: MoApps.App, deliver: (List<JSONObject>) -> Unit) { live.remove(app.name, deliver) }

    /** The JavaScript that hands [events] to the page's `momoding.on` handlers. */
    fun script(events: List<JSONObject>) = "window.__momodingDeliver && window.__momodingDeliver(${JSONArray(events)})"

    /** [app]'s page, loaded with nobody looking, for as long as its handlers need (at most [BACKGROUND_MS]). */
    private fun runInBackground(context: Context, app: MoApps.App) = main.post {
        if (live.containsKey(app.name)) return@post // a page is already open; the new arrival went to it
        val (wv, bridge) = AppPage.unattended(context, app)
        val deliver: (List<JSONObject>) -> Unit = { events -> wv.evaluateJavascript(script(events), null) }
        attach(app, deliver)
        MoApps.log(app, "log", "woke in the background for what arrived")
        wv.loadUrl(MoAppActivity.ORIGIN + "index.html")
        main.postDelayed({
            detach(app, deliver)
            bridge.close()
            wv.destroy()
        }, BACKGROUND_MS)
    }
}
