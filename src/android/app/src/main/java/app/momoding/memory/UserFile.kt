// Adapted from nanoMuse (GPL-3.0): io/github/nanomuse/sysfiles/SystemFiles.kt (UserFile)
package app.momoding.memory

import android.content.Context
import java.io.File

/**
 * USER.md: what the Bot knows about the person (how to address them, where they are, what they
 * care about), a file both of them can edit, at `/var/minis/memory/USER.md` in the sandbox.
 *
 * nanoMuse only injected the paragraph once the file had content, and only its first-run flow
 * ever created the file — so on a fresh install the model was never told USER.md exists and it
 * stayed empty for good. Here the instruction is always in the prompt; an empty file just says so.
 * The content is capped (like Hermes's bounded USER.md) so it cannot crowd the prompt.
 */
object UserFile {
    const val MAX_CHARS = 2000

    fun file(context: Context): File = File(File(context.filesDir, "minis-global/memory"), "USER.md")

    fun read(context: Context): String =
        runCatching { file(context).takeIf { it.exists() }?.readText() }.getOrNull().orEmpty().trim()

    fun promptFragment(context: Context): String = render(read(context))

    private const val PREFS = "momoding"
    private const val KEY_ASKED = "firstRun.askedName"

    /** USER.md is empty and the name was never asked: this turn should end by asking it. */
    fun shouldAskName(context: Context): Boolean =
        read(context).isEmpty() && !context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ASKED, false)

    /** USER.md as each chat's current turn began, to tell what that turn added ([added]). */
    private val atTurnStart = java.util.concurrent.ConcurrentHashMap<String, Set<String>>()

    private fun facts(context: Context): Set<String> =
        read(context).lines().map { it.trim().removePrefix("- ").trim() }.filter { it.isNotEmpty() }.toSet()

    fun markTurnStart(context: Context, sessionId: String) { atTurnStart[sessionId] = facts(context) }

    /**
     * The facts this turn wrote into USER.md, without their dates, for the "记住了：…" line —
     * shown by the app whether or not the model also said it.
     */
    fun added(context: Context, sessionId: String): List<String> {
        val before = atTurnStart.remove(sessionId) ?: return emptyList()
        return facts(context).filter { it !in before }
            .map { it.replace(Regex("""\s*[（(]\d{4}-\d{2}-\d{2}[)）]\s*"""), " ").trim() }
            .filter { it.isNotEmpty() }
    }

    /** After the turn that carried the question (asked once, whatever the answer). */
    fun markNameAsked(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ASKED, true).apply()
    }

    /** The last words of the system prompt for that one turn (where the model weighs them most). */
    const val ASK_NAME_ADDENDUM = "## This turn\nYou do not know the person yet. Do what they asked first; then end your reply with one short line asking how they would like to be called (in their language, e.g. \"对了，我该怎么称呼你？\"). Nothing else about yourself."

    internal fun render(text: String): String = buildString {
        appendLine("## About the person (USER.md)")
        append("/var/minis/memory/USER.md is your short card on the person you work for: how to address them, ")
        append("their city and timezone, the people and places they mention often, and standing preferences ")
        append("(\"always\", \"never\", \"from now on\"). When they tell you something durable like that, update the file ")
        append("in the same turn with your shell (create it if missing; keep it under $MAX_CHARS characters, one line per fact, ")
        append("newest wins, date a preference as (YYYY-MM-DD); when a preference changes, replace the old line) and end your reply with ")
        appendLine("a fenced block the app shows as one quiet line:")
        appendLine("```momoding-remember")
        appendLine("{\"text\": \"<just the fact, in the person's language, e.g. 开会尽量放下午 / meetings in the afternoon>\"}")
        appendLine("```")
        appendLine("Never write secrets, passwords or codes into it.")
        if (text.isEmpty()) {
            append("It is empty so far. When they tell you how to call them, write that first. Only write what they told you, never what you guessed.")
        } else {
            append(text.take(MAX_CHARS))
            if (text.length > MAX_CHARS) append("\n(USER.md is over $MAX_CHARS characters; tidy it next time you edit it.)")
        }
    }
}
