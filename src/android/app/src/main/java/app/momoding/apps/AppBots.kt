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
    fun tell(context: Context, app: MoApps.App, text: String) {
        val sid = app.botSession ?: return
        scope.launch {
            runCatching {
                HeadlessChatRunner.prompt(context.applicationContext, sid, "$WAKE_CUE $text", wait = false, timeoutMs = 30 * 60_000L)
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

    /** What the app's Bot is told on every turn, after the usual Momoding instructions. */
    fun standingNote(context: Context, sessionId: String): String? {
        val app = MoApps.ofSession(context, sessionId) ?: return null
        return """## You are ${app.title}'s Momoding
You are the Momoding that lives in the app "${app.title}" on the person's home screen: you build it, and you change it when they ask, from inside the app or here. Read the momoding-apps skill before you start work on it.
- The app is the folder ${app.linuxDir} (a git repository). Changes there show in the app at once; nothing to publish.
- ${app.linuxDir}/NOTES.md is your memory of this app: why it exists, what the person asked for, what was decided, how they use it. Read it when you start, keep it current.
- Messages that start with $WAKE_CUE come from the Momoding in the person's main chat, not from the person.
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
