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

    internal fun render(text: String): String = buildString {
        appendLine("## About the person (USER.md)")
        append("/var/minis/memory/USER.md is your short card on the person you work for: how to address them, ")
        append("their city and timezone, the people and places they mention often, and standing preferences ")
        append("(\"always\", \"never\", \"from now on\"). When they tell you something durable like that, update the file ")
        append("in the same turn with your shell (create it if missing; keep it under $MAX_CHARS characters, one line per fact, ")
        append("newest wins, date a preference as (YYYY-MM-DD); when a preference changes, replace the old line) and end your reply with ")
        appendLine("a fenced block the app shows as one quiet line:")
        appendLine("```momoding-remember")
        appendLine("{\"text\": \"<what you noted, in a few words>\"}")
        appendLine("```")
        appendLine("Never write secrets, passwords or codes into it.")
        if (text.isEmpty()) {
            append("It is empty so far: when you learn how they want to be addressed, write that first.")
        } else {
            append(text.take(MAX_CHARS))
            if (text.length > MAX_CHARS) append("\n(USER.md is over $MAX_CHARS characters; tidy it next time you edit it.)")
        }
    }
}
