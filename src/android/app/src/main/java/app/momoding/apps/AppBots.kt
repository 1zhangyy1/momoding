package app.momoding.apps

import android.content.Context
import app.momoding.home.MainChat
import app.momoding.task.TaskEngine
import com.openminis.app.MinisApp
import com.openminis.app.debug.HeadlessChatRunner
import com.openminis.app.logging.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Each app's own Momoding (Grok Bot's many Bots, 调研/M27): an ordinary chat, marked
 * `source = "app:<name>"`, with the same Momoding and the same rules, plus a standing note on
 * which app it is. Work the main chat hands it is a task (app.momoding.task); [tell] is a message for
 * it in its own chat.
 */
object AppBots {
    private const val TAG = "AppBots"
    const val SOURCE_PREFIX = "app:"
    const val WAKE_CUE = "[from Momoding]"
    /** A request the app itself sent (momoding.tell). */
    const val APP_CUE = "[from the app]"
    /** The weekly look at how the app is used, asked by its host. */
    const val REVIEW_CUE = "[app review]"
    /** What an app's Momoding said in another chat started `[from bot:<name>]`, before its reports became tasks (ui/chat/BotLines). */
    const val FROM_CUE = "[from bot:"

    /** Who a message for an app's Momoding is from. */
    enum class From(val cue: String?) { MOMODING(WAKE_CUE), PERSON(null), APP(APP_CUE), REVIEW(REVIEW_CUE) }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun repo(context: Context) = (context.applicationContext as MinisApp).chatRepository

    /**
     * The Bot's chat, bound to no model of its own, so it uses Momoding's (and changes when the person
     * changes that); then it is handed the brief as its first task, which reports back to [askedIn].
     * Returns that task's id.
     */
    suspend fun create(context: Context, app: MoApps.App, askedIn: String?, brief: String): String {
        val repo = repo(context)
        val session = repo.createSession(modelId = "", title = app.title)
        repo.dao.updateSource(session.id, SOURCE_PREFIX + app.name)
        app.setBot(session.id)
        val chat = askedIn?.takeIf { repo.getSession(it) != null } ?: MainChat.resolve(context, repo)
        return TaskEngine.open(context, chat, app.title, session.id, brief).id
    }

    /** A message for the Bot in its own chat (the person, its app, its weekly look); it answers there. */
    fun tell(context: Context, app: MoApps.App, text: String, from: From) {
        val sid = app.botSession ?: return
        scope.launch {
            runCatching {
                HeadlessChatRunner.prompt(context.applicationContext, sid, from.cue?.let { "$it $text" } ?: text, wait = false, timeoutMs = 30 * 60_000L)
            }.onFailure { AppLogger.warning(TAG, "tell ${app.name}: ${it.message}") }
        }
    }

    /**
     * A task of the Bot's for [chat] ended: its icon and name on the home screen follow its manifest,
     * its state shows, and the person hears of it when they are not in that chat.
     */
    suspend fun reported(context: Context, app: MoApps.App, chat: String, text: String) {
        if (app.hasApp) MoAppActivity.relabel(context, app)
        BotStatus.touch()
        if (BotStatus.looking(chat) || BotStatus.quiet(context, app.botSession ?: return)) return
        val link = if (app.hasApp) "minis://apps/${app.name}" else "minis://session/${app.botSession}"
        (context.applicationContext as MinisApp).backgroundTaskNotifier.notifyReceipt(chat, app.title, text.trim(), android.net.Uri.parse(link))
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
- Work the main Momoding hands you (its message starts with $WAKE_CUE) is a task, with its own line in the main chat. `momoding-task report progress "<where it stands>"` keeps that line current and sends no message; when it is done, `momoding-task report done "<what came of it, in a line or two>"`; when only the person can decide, `report ask "<the question>"`; when it cannot be done, `report failed "<why>"`. The main Momoding tells the person. What the person asks you here, in your own chat, you answer here."""
        if (!app.hasApp) return home + """
- You have no app. When the person wants something to keep and use again, build it as your app in your folder (read the momoding-apps skill first); it opens from the person's list of Momodings."""
        return home + """
- Your app is the folder: you build it, and you change it when the person asks, from inside the app or here. Read the momoding-apps skill before you start work on it. Changes there show in the app at once; nothing to publish.
- What the person's app keeps is theirs: ${app.linuxDir}/.momoding/data.json is a read-only copy for you to read; in your browser the app has its own copy to test with.
- Messages that start with $APP_CUE come from the app itself (`momoding.tell`): do what it asks, in this chat.
- A message that starts with $REVIEW_CUE asks you to look at how the app has been used (${app.linuxDir}/.momoding/usage.json, its data, NOTES.md). Offer one improvement only when what you see clearly calls for it: say it in one or two lines and run `momoding-app suggest "<that improvement, in one line>"`, which puts a dot on the app for the person; change nothing until they say yes. When nothing calls for one, do not run it, and keep any reply to a few words.
- Apps live on the phone: an app can wake when a text or a notification arrives (`momoding.on`, the momoding-apps skill). When what the app is for arrives that way (bank texts for a ledger, pickup codes for a parcel list), make it come in by itself rather than asking the person to type it.
- What the app may use of the phone is the person's choice, per app: the first time it runs a command that reads their things or acts for them, they are asked on the app's screen. ${app.linuxDir}/.momoding/permissions.json has their answers.
- Commit your work on the app once it is done and checked.
- ${AppHome.facts(context)} What this note says of the phone is how it is now; where NOTES.md says otherwise, NOTES.md is out of date: go by this, and correct it."""
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
            append("To give one work, `momoding-task new --to bot:<name> --title \"<a few words>\" \"<what to do>\"`: it becomes a task with its own line in this chat, and the Momoding works on it on its own, one task at a time. ")
            append("A message here starting ${TaskEngine.REPORT_CUE}<id> · <name> · done|ask|failed] is its report, not the person: tell the person what came of it in your own words, in a line or two, with a link to its app when there is something to see; for an ask, ask the person, and pass their answer on with `momoding-task tell <id> \"<their words>\"`, as anything more they want of that task. ")
            append("What they keep you can read: a question about something one looks after (\"这个月花了多少\"), answer from its folder — its NOTES.md, and its app's data — rather than asking it or working it out again; tell it only when something has to be done.")
            if (apps.isNotEmpty()) append("\nThe person's Momodings:\n").append(apps.joinToString("\n") { a ->
                "- ${a.name}: ${a.title}" + a.what.ifBlank { null }?.let { " — $it" }.orEmpty() +
                    if (a.hasApp) " (its app's data: ${a.linuxDir}/.momoding/data.json)" else ""
            }).append("\n").append(AppHome.facts(context))
        }
    }
}
