package app.momoding.apps

import android.content.Context
import app.momoding.guard.RiskGate
import app.momoding.home.MainChat
import com.openminis.app.MinisApp
import com.openminis.app.service.SessionActivityTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn

/**
 * What each Bot is doing, the one answer everything that shows a Bot reads (the header, the list,
 * the pen on its app). Three states, as in Grok Bot (调研/M31): waiting for the person (with what
 * for), working (on what the person last asked), something new for them; waiting comes first.
 */
object BotStatus {
    enum class Kind { NONE, WORKING, NEWS, WAITING }

    /** [line]: what it waits for, what it works on, or the last thing it said. */
    data class Status(val kind: Kind, val line: String? = null)

    /** A Bot as the person sees it: the main Momoding ([app] null) or one with its own folder. */
    data class Bot(val session: String, val app: MoApps.App?, val status: Status)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val touched = MutableStateFlow(0L)
    @Volatile private var all: StateFlow<List<Bot>>? = null

    /** Something the state is read from changed outside a turn (a suggestion came, a Bot came or went). */
    fun touch() { touched.value = System.nanoTime() }

    /** The chat the person has in front of them right now (the main window's or an app's panel), if any. */
    private val onScreen = MutableStateFlow<String?>(null)
    fun showing(session: String?) { onScreen.value = session }
    fun hidden(session: String?) { if (onScreen.value == session) onScreen.value = null }
    fun looking(session: String): Boolean = onScreen.value == session

    /** Every Bot, the main Momoding first, in a fixed order, with its state; kept current while anyone looks. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun all(context: Context): StateFlow<List<Bot>> = all ?: synchronized(this) {
        all ?: combine(SessionActivityTracker.activeSessions, RiskGate.open, onScreen, touched) { active, open, _, _ -> active to open }
            .mapLatest { (active, open) -> read(context.applicationContext, active, open) }
            .stateIn(scope, SharingStarted.WhileSubscribed(5_000), emptyList())
            .also { all = it }
    }

    private suspend fun read(context: Context, active: Set<String>, open: List<app.momoding.guard.RiskRequest>): List<Bot> {
        val repo = (context as MinisApp).chatRepository
        val bots = listOf(MainChat.resolve(context, repo) to null) +
            MoApps.list(context).mapNotNull { a -> a.botSession?.let { it to a } }
        return bots.map { (sid, app) ->
            if (looking(sid) && sid !in active) seen(context, sid)
            val waiting = open.firstOrNull { it.sessionId == sid || (app != null && it.sessionId == AppBridge.GATE_PREFIX + app.name) }
            val status = when {
                waiting != null -> Status(Kind.WAITING, waiting.summary ?: waiting.preview)
                sid in active -> Status(Kind.WORKING, lastWords(context, sid, "user"))
                news(context, sid, app) -> Status(Kind.NEWS, lastWords(context, sid, "assistant"))
                else -> Status(Kind.NONE, lastWords(context, sid, "assistant"))
            }
            Bot(sid, app, status)
        }
    }

    /** The Bot an approval request comes from (an app's own commands are filed under the app). */
    fun botOf(context: Context, requestSession: String): String =
        MoApps.get(context, requestSession.removePrefix(AppBridge.GATE_PREFIX))?.botSession ?: requestSession

    /** The name the person knows a Momoding by: its own, or the main Momoding's. */
    fun name(context: Context, session: String): String =
        MoApps.ofSession(context, session)?.title ?: app.momoding.Identity.name(context)

    // ── what the person has seen ──────────────────────────────────────────────

    private const val PREFS = "momoding"
    private const val KEY_SEEN = "bots.seen"
    private const val KEY_QUIET = "bots.quiet"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun seenAll(context: Context) = runCatching { org.json.JSONObject(prefs(context).getString(KEY_SEEN, "{}")!!) }.getOrDefault(org.json.JSONObject())

    /** The person has [session] in front of them: what is there is seen, and any notice of it goes. */
    private suspend fun seen(context: Context, session: String) {
        val total = (context as MinisApp).chatRepository.messageCount(session)
        val all = seenAll(context)
        if (all.optJSONObject(session)?.optInt("n") == total) return
        all.put(session, org.json.JSONObject().put("n", total).put("at", System.currentTimeMillis()))
        prefs(context).edit().putString(KEY_SEEN, all.toString()).apply()
        androidx.core.app.NotificationManagerCompat.from(context).cancel(session.hashCode())
    }

    /**
     * Something for the person they have not seen: an improvement a Bot suggested, or an answer to
     * what the person (or their app) asked — not a turn another Momoding started, nor a Bot's weekly
     * look over its app (that answers with a dot only through `momoding-app suggest`).
     */
    private suspend fun news(context: Context, session: String, app: MoApps.App?): Boolean {
        val seen = seenAll(context).optJSONObject(session)
        val u = app?.let { AppBots.usage(it) }
        if ((u?.optJSONObject("suggestion")?.optLong("at") ?: 0) > (seen?.optLong("at") ?: 0)) return true
        val repo = (context as MinisApp).chatRepository
        val total = repo.messageCount(session)
        // before seen moved here, an app's chat kept it with the app
        if (total <= (seen?.optInt("n") ?: u?.optInt("chatSeen") ?: 0)) return false
        return forPerson(context, session)
    }

    /** The last turn in [session] ended with an answer to the person (or their app), not to another Momoding. */
    suspend fun forPerson(context: Context, session: String): Boolean {
        val repo = (context as MinisApp).chatRepository
        val total = repo.messageCount(session)
        val recent = repo.loadMessagePage(session, offset = maxOf(0, total - 12), limit = 12, maxChars = 40, includeTools = true)
        val last = recent.lastOrNull() ?: return false
        if (last.role != "assistant" || last.toolUses.isNotEmpty()) return false
        // what started this turn: the last row someone wrote, not a tool's result
        val asked = recent.lastOrNull { it.role == "user" && it.toolResults.isEmpty() }?.text ?: return false
        return !asked.startsWith(AppBots.WAKE_CUE) && !asked.startsWith(AppBots.REVIEW_CUE)
    }

    /** A Bot the person has silenced: no notice when it is done or waits (its state still shows). */
    fun quiet(context: Context, session: String): Boolean = session in prefs(context).getStringSet(KEY_QUIET, emptySet())!!

    fun setQuiet(context: Context, session: String, quiet: Boolean) {
        val now = prefs(context).getStringSet(KEY_QUIET, emptySet())!!
        prefs(context).edit().putStringSet(KEY_QUIET, if (quiet) now + session else now - session).apply()
    }

    /** The last thing said in [sid] by [role], in a line; for the person, without who passed it on. */
    suspend fun lastWords(context: Context, sid: String, role: String, max: Int = 60): String? {
        val repo = (context.applicationContext as MinisApp).chatRepository
        val total = repo.messageCount(sid)
        val recent = repo.loadMessagePage(sid, offset = maxOf(0, total - 12), limit = 12, maxChars = max + 20, includeTools = true)
        val text = recent.lastOrNull { it.role == role && it.toolResults.isEmpty() && it.text.isNotBlank() }?.text ?: return null
        val cues = listOf(AppBots.WAKE_CUE, AppBots.APP_CUE, AppBots.REVIEW_CUE)
        return cues.fold(text.trim()) { t, c -> t.removePrefix(c).trim() }.lineSequence().first().take(max)
    }
}
