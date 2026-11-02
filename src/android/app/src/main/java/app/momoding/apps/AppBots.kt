package app.momoding.apps

import android.content.Context
import app.momoding.home.MainChat
import com.openminis.app.MinisApp
import com.openminis.app.debug.HeadlessChatRunner
import com.openminis.app.logging.AppLogger
import com.openminis.app.service.SessionActivityTracker
import com.openminis.app.ui.chat.ChatViewModelStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
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
    /** What an app's Momoding said in another chat starts `[from bot:<name>]` (ui/chat/BotLines). */
    const val FROM_CUE = "[from bot:"

    /** Who a message for an app's Momoding is from. */
    enum class From(val cue: String?) { MOMODING(WAKE_CUE), PERSON(null), APP(APP_CUE), REVIEW(REVIEW_CUE) }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun repo(context: Context) = (context.applicationContext as MinisApp).chatRepository

    /**
     * The Bot's chat, bound to no model of its own, so it uses Momoding's (and changes when the person
     * changes that); then it is handed the brief and starts.
     */
    suspend fun create(context: Context, app: MoApps.App, askedIn: String?, brief: String): String {
        val repo = repo(context)
        val session = repo.createSession(modelId = "", title = app.title)
        repo.dao.updateSource(session.id, SOURCE_PREFIX + app.name)
        app.setBot(session.id, null)
        tell(context, app, brief, by = askedIn)
        return session.id
    }

    /**
     * Who handed each Bot the work it is on (Bot's chat → the chat that told it): where its result
     * goes and where its questions for the person show, until that work is done. Kept in bot.json
     * (`askedIn`), so it outlives the process; this is its cache.
     */
    private val handedBy = java.util.concurrent.ConcurrentHashMap<String, String>()

    fun handedBy(context: Context, botSession: String): String? = handedBy.getOrPut(botSession) {
        MoApps.ofSession(context, botSession)?.bot()?.optString("askedIn").orEmpty()
    }.ifBlank { null }

    private fun setHandedBy(app: MoApps.App, sid: String, by: String?) {
        app.setBot(sid, by)
        handedBy[sid] = by.orEmpty()
    }

    /** What a stop in a chat reaches: the Bots it handed work to in the turn still running (asker → Bots). */
    private val handedThisTurn = java.util.concurrent.ConcurrentHashMap<String, MutableSet<String>>()

    /** A message for the Bot; it answers on its own turn, in its own chat. [by]: the chat that hands it work. */
    fun tell(context: Context, app: MoApps.App, text: String, from: From = From.MOMODING, by: String? = null) {
        val sid = app.botSession ?: return
        if (by != null && by != sid) {
            setHandedBy(app, sid, by)
            handedThisTurn.getOrPut(by) { java.util.concurrent.ConcurrentHashMap.newKeySet() }.add(sid)
            // once the asker's turn is over, stopping it later no longer reaches this Bot
            scope.launch { SessionActivityTracker.activeSessions.first { by !in it }; handedThisTurn[by]?.remove(sid) }
        }
        scope.launch {
            runCatching {
                HeadlessChatRunner.prompt(context.applicationContext, sid, from.cue?.let { "$it $text" } ?: text, wait = false, timeoutMs = 30 * 60_000L)
            }.onFailure { AppLogger.warning(TAG, "tell ${app.name}: ${it.message}") }
        }
    }

    /** Stopping [session] stops the Bots it handed work to in this turn that are still at it. */
    fun stopHandedBy(session: String) {
        handedThisTurn.remove(session).orEmpty()
            .filter { it in SessionActivityTracker.activeSessions.value }
            .forEach { ChatViewModelStore.existing(it)?.cancelStream() }
    }

    private data class Line(val chat: String, val id: String, val order: Int)
    /** The line an app's Momoding last wrote in a chat for the work at hand (progress, until done). */
    private val lastLine = java.util.concurrent.ConcurrentHashMap<String, Line>()

    /**
     * The Bot's line in the chat that handed it the work, else the main chat ("记账：加了月度图");
     * a card under it is the Bot's to give, by linking its app. Written when that chat is not mid-turn, so it never lands between a tool
     * call and its result; the chat's Momoding reads it next time like anything else said there.
     */
    fun receipt(context: Context, app: MoApps.App, text: String, finished: Boolean = true) {
        val sid = app.botSession ?: return
        val link = if (app.hasApp) "minis://apps/${app.name}" else "minis://session/$sid"
        val body = "$FROM_CUE${app.name}] ${text.trim()}"
        scope.launch {
            val target = handedBy(context, sid)?.takeIf { repo(context).getSession(it) != null } ?: MainChat.resolve(context, repo(context))
            if (finished) setHandedBy(app, sid, null) // done, not just progress: what it says next is its own
            repeat(600) {
                val vm = ChatViewModelStore.existing(target)
                if (vm == null || !vm.isStreaming.value) {
                    // one line per piece of work: while it is still the newest in that chat, the next report replaces it
                    val parts = """[{"type":"text","value":${JSONObject.quote(body)}}]"""
                    val last = lastLine[app.name]?.takeIf { it.chat == target && it.order == repo(context).dao.nextSortOrder(target) - 1 }
                    if (last != null) repo(context).updateMessageParts(last.id, parts)
                    else repo(context).appendMessage(target, "assistant", parts).let { lastLine[app.name] = Line(target, it.id, it.sortOrder) }
                    if (finished) lastLine.remove(app.name)
                    if (app.hasApp) AppShare.publish(context, app)
                    vm?.let { withContext(Dispatchers.Main) { it.reloadFromDisk() } }
                    // done for the person: the banner in Momoding (via the state), a notification outside it
                    BotStatus.touch()
                    if (!BotStatus.looking(target) && !BotStatus.quiet(context, sid))
                        (context.applicationContext as MinisApp).backgroundTaskNotifier.notifyReceipt(target, app.title, text.trim(), android.net.Uri.parse(link))
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
        BotStatus.touch()
    }

    /** What a Bot is told on every turn, after the usual Momoding instructions. */
    fun standingNote(context: Context, sessionId: String): String? {
        val app = MoApps.ofSession(context, sessionId) ?: return null
        val what = app.what.ifBlank { null }?.let { " You take care of this for them: $it." }.orEmpty()
        val home = """## You are ${app.title}, one of the person's Momodings
You are the Momoding called "${app.title}".$what The main Momoding in the person's main chat made you; you work on your own, in this chat.
- Your folder is ${app.linuxDir} (a git repository). ${app.linuxDir}/NOTES.md is your memory: why you exist, what the person asked for, what was decided, how they use you. Read it when you start, keep it current.
- Messages that start with $WAKE_CUE come from the Momoding in the person's main chat, not from the person.
- When a piece of work the main Momoding gave you is done, tell the main chat in one line: `momoding-app done "<what came of it>"`."""
        if (!app.hasApp) return home + """
- You have no app. When the person wants something to keep and use again, build it as your app in your folder (read the momoding-apps skill first); it opens from the person's list of Momodings."""
        return home + """
- Your app is the folder: you build it, and you change it when the person asks, from inside the app or here. Read the momoding-apps skill before you start work on it. Changes there show in the app at once; nothing to publish.
- What the person's app keeps is theirs: ${app.linuxDir}/.momoding/data.json is a read-only copy for you to read; in your browser the app has its own copy to test with.
- Messages that start with $APP_CUE come from the app itself (`momoding.tell`): do what it asks, in this chat.
- A message that starts with $REVIEW_CUE asks you to look at how the app has been used (${app.linuxDir}/.momoding/usage.json, its data, NOTES.md). Offer one improvement only when what you see clearly calls for it: say it in one or two lines and run `momoding-app suggest "<that improvement, in one line>"`, which puts a dot on the app for the person; change nothing until they say yes. When nothing calls for one, do not run it, and keep any reply to a few words.
- Apps live on the phone: an app can wake when a text or a notification arrives (`momoding.on`, the momoding-apps skill). When what the app is for arrives that way (bank texts for a ledger, pickup codes for a parcel list), make it come in by itself rather than asking the person to type it.
- What the app may use of the phone is the person's choice, per app: the first time it runs a command that reads their things or acts for them, they are asked on the app's screen. ${app.linuxDir}/.momoding/permissions.json has their answers.
- Commit your work on the app once it is done and checked."""
    }

    /** For the main chat's Momoding: what Momoding Apps are, and the person's. */
    fun directory(context: Context): String {
        val apps = MoApps.list(context).filter { it.botSession != null }
        return buildString {
            append("## Momodings and their apps\n")
            append("Besides you, the person can have other Momodings, each looking after one thing for them (a coach, a ledger), in its own chat; one may have an app on the home screen. ")
            append("When the person wants one, or wants something to keep and use again (a tracker, a tool, a game, a list), make a Momoding for it: ")
            append("`momoding-app new <name> --title \"<title>\" --what \"<what it takes care of, in a line>\" --brief \"<what they want, in their words, with what you know that helps>\"`. ")
            append("It builds an app when one is wanted and reports back here; don't do its work yourself. ")
            append("A routine and a Momoding are different things: a routine is one fixed action, its result coming back to the chat it was set up in; a Momoding looks after one thing over time, with its own chat and memory. ")
            append("What is kept and looked at belongs to the Momoding whose app keeps it, and that app takes in what feeds it (texts, notifications) itself — don't set up a routine of yours to write into it. ")
            append("Notice when one would help before they think of it, from what really happens, not what might: the same kind of thing asked of you again and again (\"这周第三次让我查快递了\"), or the same kind of text or notification the person keeps acting on (bank charges, pickup codes, school notices). ")
            append("A single ask is just a task. Once the pattern is there, offer — say what you noticed and what the app would do by itself, in a line, once in the conversation; if they don't take it up, saying nothing included, it's their call — don't raise it again. ")
            append("To give one work, `momoding-app tell <name> \"<what to do>\"`: it works on its own and reports back; the chat shows the hand-over as a line of its own. A message here starting ${FROM_CUE}<name>] is that Momoding's report, not something you said. ")
            append("What they keep you can read: a question about something one looks after (\"这个月花了多少\"), answer from its folder — its NOTES.md, and its app's data — rather than asking it or working it out again; tell it only when something has to be done.")
            if (apps.isNotEmpty()) append("\nThe person's Momodings:\n").append(apps.joinToString("\n") { a ->
                "- ${a.name}: ${a.title}" + a.what.ifBlank { null }?.let { " — $it" }.orEmpty() +
                    if (a.hasApp) " (its app's data: ${a.linuxDir}/.momoding/data.json)" else ""
            })
        }
    }
}
