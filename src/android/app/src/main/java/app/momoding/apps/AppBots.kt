package app.momoding.apps

import android.content.Context
import com.openminis.app.MinisApp
import com.openminis.app.debug.HeadlessChatRunner
import com.openminis.app.logging.AppLogger
import com.openminis.app.ui.chat.ChatViewModelStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Each app's own Momoding (Grok Bot's many Bots, 调研/M27): an ordinary chat, marked
 * `source = "app:<name>"`, with the same Momoding and the same rules, plus a standing note on
 * which app it is. Bots talk asynchronously: [tell] wakes one with a message; [receipt] puts the
 * app Bot's one line into the chat that asked for the app, without a model turn there.
 */
object AppBots {
    private const val TAG = "AppBots"
    const val SOURCE_PREFIX = "app:"
    const val WAKE_CUE = "[from Momoding]"
    /** A request the app itself sent (momoding.tell). */
    const val APP_CUE = "[from the app]"
    /** The weekly look at how the app is used, asked by its host. */
    const val REVIEW_CUE = "[app review]"

    /** Who a message for an app's Momoding is from. */
    enum class From(val cue: String?) { MOMODING(WAKE_CUE), PERSON(null), APP(APP_CUE), REVIEW(REVIEW_CUE) }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun repo(context: Context) = (context.applicationContext as MinisApp).chatRepository

    /** The Bot's chat, on the model the asking chat uses; then it is handed the brief and starts. */
    suspend fun create(context: Context, app: MoApps.App, askedIn: String?, brief: String): String {
        val repo = repo(context)
        val parent = askedIn?.let { repo.getSession(it) }
        val session = repo.createSession(modelId = parent?.modelId.orEmpty(), title = app.title)
        repo.dao.updateSource(session.id, SOURCE_PREFIX + app.name)
        parent?.modelBinding?.let { repo.dao.updateSessionBinding(session.id, it, parent.modelId) }
        app.setBot(session.id, askedIn)
        tell(context, app, brief)
        return session.id
    }

    /** A message for the app's Bot; it answers on its own turn, in its own chat. */
    fun tell(context: Context, app: MoApps.App, text: String, from: From = From.MOMODING) {
        val sid = app.botSession ?: return
        scope.launch {
            runCatching {
                HeadlessChatRunner.prompt(context.applicationContext, sid, from.cue?.let { "$it $text" } ?: text, wait = false, timeoutMs = 30 * 60_000L)
            }.onFailure { AppLogger.warning(TAG, "tell ${app.name}: ${it.message}") }
        }
    }

    /**
     * The app Bot's line in the chat that asked for the app ("记账本：加了月度图"), with a button to
     * open it. Written when that chat is not mid-turn, so it never lands between a tool call and
     * its result; the chat's Momoding reads it next time like anything else said there.
     */
    fun receipt(context: Context, app: MoApps.App, text: String, openLabel: String) {
        val target = app.askedIn ?: return
        val line = text.trim().let { if (it.startsWith(app.title)) it else "${app.title}：$it" }
        val body = "$line\n[$openLabel](minis://apps/${app.name})"
        scope.launch {
            repeat(600) {
                val vm = ChatViewModelStore.existing(target)
                if (vm == null || !vm.isStreaming.value) {
                    repo(context).appendMessage(target, "assistant", """[{"type":"text","value":${JSONObject.quote(body)}}]""")
                    vm?.let { withContext(Dispatchers.Main) { it.reloadFromDisk() } }
                    return@launch
                }
                delay(2000)
            }
            AppLogger.warning(TAG, "receipt for ${app.name} dropped: the chat stayed busy")
        }
    }

    // ── how the app is used ───────────────────────────────────────────────────

    private const val WEEK_MS = 7L * 24 * 60 * 60 * 1000
    private const val REVIEW_AFTER_OPENS = 5

    private fun usageFile(app: MoApps.App) = java.io.File(app.platform, "usage.json")
    @Synchronized fun usage(app: MoApps.App): JSONObject =
        runCatching { JSONObject(usageFile(app).readText()) }.getOrDefault(JSONObject())
    @Synchronized private fun saveUsage(app: MoApps.App, u: JSONObject) { usageFile(app).writeText(u.toString(2)) }

    /**
     * The person opened the app: counted by day, for its Momoding to read. After a week of use and a
     * few opens since the last look, its Momoding is asked to look once (it says something only when
     * the use clearly calls for a change).
     */
    fun opened(context: Context, app: MoApps.App) {
        val now = System.currentTimeMillis()
        val day = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date(now))
        val u = usage(app)
        val days = u.optJSONObject("opensByDay") ?: JSONObject()
        days.put(day, days.optInt(day) + 1)
        u.put("opensByDay", days).put("lastOpened", now)
        if (!u.has("firstOpened")) u.put("firstOpened", now)
        val since = u.optInt("opensSinceReview") + 1
        val lastLook = u.optLong("lastReview", u.optLong("firstOpened", now))
        val due = now - lastLook >= WEEK_MS && since >= REVIEW_AFTER_OPENS
        u.put("opensSinceReview", if (due) 0 else since)
        if (due) u.put("lastReview", now)
        saveUsage(app, u)
        // in the person's language, which is the language the answer will be in
        if (due) tell(context, app, com.openminis.app.i18n.LocaleWrap.uiContext(context).getString(com.openminis.app.R.string.mo_app_review_ask), From.REVIEW)
    }

    /** Its Momoding's one improvement after a review (`momoding-app suggest`): the dot on the app until its chat is opened. */
    fun suggest(app: MoApps.App, text: String) {
        saveUsage(app, usage(app).put("suggestion", JSONObject().put("text", text.take(300)).put("at", System.currentTimeMillis())))
    }

    /**
     * The app's chat has something for the person: an improvement its Momoding suggested, or a reply
     * they have not seen — but not what it said while looking over the app's use (a review answers
     * with a dot only through [suggest]).
     */
    suspend fun hasNews(context: Context, app: MoApps.App): Boolean {
        val sid = app.botSession ?: return false
        val u = usage(app)
        if (u.optJSONObject("suggestion")?.optLong("at", 0) ?: 0 > u.optLong("chatSeenAt", 0)) return true
        val repo = repo(context)
        val total = repo.messageCount(sid)
        if (total <= u.optInt("chatSeen", total)) return false
        val recent = repo.loadMessagePage(sid, offset = maxOf(0, total - 12), limit = 12, maxChars = 40, includeTools = true)
        val last = recent.lastOrNull() ?: return false
        if (last.role != "assistant" || last.toolUses.isNotEmpty()) return false
        // what started this turn: the last row someone wrote, not a tool's result
        return recent.lastOrNull { it.role == "user" && it.toolResults.isEmpty() }?.text?.startsWith(REVIEW_CUE) != true
    }

    /** The person opened the app's chat. */
    suspend fun chatSeen(context: Context, app: MoApps.App) {
        val sid = app.botSession ?: return
        val total = repo(context).messageCount(sid)
        saveUsage(app, usage(app).put("chatSeen", total).put("chatSeenAt", System.currentTimeMillis()))
    }

    /** What the app's Bot is told on every turn, after the usual Momoding instructions. */
    fun standingNote(context: Context, sessionId: String): String? {
        val app = MoApps.ofSession(context, sessionId) ?: return null
        return """## You are ${app.title}'s Momoding
You are the Momoding that lives in the app "${app.title}" on the person's home screen: you build it, and you change it when they ask, from inside the app or here. Read the momoding-apps skill before you start work on it.
- The app is the folder ${app.linuxDir} (a git repository). Changes there show in the app at once; nothing to publish.
- ${app.linuxDir}/NOTES.md is your memory of this app: why it exists, what the person asked for, what was decided, how they use it. Read it when you start, keep it current.
- Messages that start with $WAKE_CUE come from the Momoding in the person's main chat, not from the person.
- What the person's app keeps is theirs: ${app.linuxDir}/.momoding/data.json is a read-only copy for you to read; in your browser the app has its own copy to test with.
- Messages that start with $APP_CUE come from the app itself (`momoding.tell`): do what it asks, in this chat.
- A message that starts with $REVIEW_CUE asks you to look at how the app has been used (${app.linuxDir}/.momoding/usage.json, its data, NOTES.md). Offer one improvement only when what you see clearly calls for it: say it in one or two lines and run `momoding-app suggest "<that improvement, in one line>"`, which puts a dot on the app for the person; change nothing until they say yes. When nothing calls for one, do not run it, and keep any reply to a few words.
- Apps live on the phone: an app can wake when a text or a notification arrives (`momoding.on`, the momoding-apps skill). When what the app is for arrives that way (bank texts for a ledger, pickup codes for a parcel list), make it come in by itself rather than asking the person to type it.
- What the app may use of the phone is the person's choice, per app: the first time it runs a command that reads their things or acts for them, they are asked on the app's screen. ${app.linuxDir}/.momoding/permissions.json has their answers.
- When a piece of work is done and checked, commit it and tell the main chat in one line: `momoding-app done "<what changed>"`."""
    }

    /** For the main chat's Momoding: what Momoding Apps are, and the person's. */
    fun directory(context: Context): String {
        val apps = MoApps.list(context).filter { it.botSession != null }
        return buildString {
            append("## Momoding Apps\n")
            append("When the person wants something to keep and use again (a tracker, a tool, a game, a list), make it a Momoding App on their home screen: ")
            append("`momoding-app new <name> --title \"<title>\" --brief \"<what they want, in their words, with what you know that helps>\"`. ")
            append("The app gets its own Momoding, who builds it and reports back here; don't build it yourself. ")
            append("To change one, `momoding-app tell <name> \"<what to do>\"`: it works on its own and reports back.")
            if (apps.isNotEmpty()) append("\nThe person's apps:\n").append(apps.joinToString("\n") { "- ${it.name}: ${it.title}" })
        }
    }
}
