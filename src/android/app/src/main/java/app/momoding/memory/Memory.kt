package app.momoding.memory

import android.content.Context
import java.io.File

/**
 * What Momoding remembers of the person: a git repo of Markdown at /var/minis/memory (调研/M54).
 *
 * The format is the Agent Memory Repo spec (Cognition, MIT): MEMORY.md is the entry, always in the
 * prompt and kept short, with an index of the topic files; one fact per line, `- … [added: date]`;
 * files linked with `[[path]]`. The chat keeps it with its shell as the conversation goes, searching
 * before it writes and changing the line that is no longer true (supermemoryai/memoryrepo, MIT). The
 * app commits after each turn ([MemoryGit]), so where a line came from and what it said before live
 * in git, not in the file (Letta Code, Apache-2.0). How to write it is the `memory` skill.
 */
object Memory {
    const val SANDBOX_DIR = "/var/minis/memory"
    const val MAX_CHARS = 2000

    fun dir(context: Context): File = File(context.filesDir, "minis-global/memory")
    private fun entry(context: Context): File = File(dir(context), "MEMORY.md")

    fun read(context: Context): String =
        runCatching { entry(context).takeIf { it.exists() }?.readText() }.getOrNull().orEmpty().trim()

    /**
     * Once, from the layout before: USER.md becomes MEMORY.md, and the daily notes upstream wrote
     * (YYYY-MM-DD.md) move to days/, still there to grep but no longer in every prompt.
     */
    fun migrate(context: Context) = runCatching {
        val dir = dir(context).apply { mkdirs() }
        val user = File(dir, "USER.md")
        if (!entry(context).exists()) {
            val facts = user.takeIf { it.exists() }?.readLines().orEmpty()
                .map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
                .map { if (it.startsWith("- ")) it else "- $it" }
            entry(context).writeText(TEMPLATE + facts.joinToString("") { "$it\n" })
        }
        user.delete()
        val day = Regex("""\d{4}-\d{2}-\d{2}\.md""")
        dir.listFiles { f -> f.isFile && day.matches(f.name) }.orEmpty().forEach { note ->
            val days = File(dir, "days").apply { mkdirs() }
            note.renameTo(File(days, note.name))
        }
    }

    private const val TEMPLATE = "# Memory\n\n"

    fun promptFragment(context: Context): String = render(read(context))

    internal fun render(text: String): String = buildString {
        appendLine("## Memory")
        append("Your memory of the person is a folder of notes at $SANDBOX_DIR, kept in git (the app commits it after each turn). ")
        append("MEMORY.md, below, is always with you: how to call them, who they are, what matters now, and links to the other files. ")
        appendLine("When they mention a person, place, plan or anything from before that it does not cover, look first (`grep -ril \"<word>\" $SANDBOX_DIR`, follow [[links]]) instead of answering from a guess.")
        append("When they tell you something worth keeping (about them, someone in their life, a preference, a plan with a date, a decision, a change to what you knew), save it before you reply. ")
        append("MEMORY.md and its index are below, so you usually know where it goes: what every conversation needs in MEMORY.md, what is about one person, place or plan in that subject's file, linked from the index. ")
        append("Change the line that is no longer true rather than add another; one fact per line as `- … [added: YYYY-MM-DD]`. ")
        appendLine("Only what they said, never what you guessed; never passwords or codes. To tidy or reorganize memory, read the memory skill.")
        appendLine("After saving, end your reply with a fenced block the app shows as one quiet line:")
        appendLine("```momoding-remember")
        appendLine("{\"text\": \"<just the fact, in the person's language, e.g. 开会尽量放下午 / meetings in the afternoon>\"}")
        appendLine("```")
        val facts = text.lines().any { it.trimStart().startsWith("- ") }
        if (!facts) {
            append("MEMORY.md has nothing yet. When they tell you how to call them, write that first.")
        } else {
            appendLine("<memory>")
            appendLine(text.take(MAX_CHARS))
            if (text.length > MAX_CHARS) appendLine("(MEMORY.md is over $MAX_CHARS characters: move details into topic files and leave a link.)")
            append("</memory>")
        }
    }

    // ── first conversation: ask how to call them ─────────────────────────────

    private const val PREFS = "momoding"
    private const val KEY_ASKED = "firstRun.askedName"

    /** Memory is empty and the name was never asked: this turn should end by asking it. */
    fun shouldAskName(context: Context): Boolean =
        facts(context).isEmpty() && !context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ASKED, false)

    /** After the turn that carried the question (asked once, whatever the answer). */
    fun markNameAsked(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ASKED, true).apply()
    }

    /** The last words of the system prompt for that one turn (where the model weighs them most). */
    const val ASK_NAME_ADDENDUM = "## This turn\nYou do not know how to call the person yet. If this message tells you, save it to memory like anything worth keeping. If not, do what they asked first, then end your reply with one short line asking how they would like to be called (in their language, e.g. \"对了，我该怎么称呼你？\"). Nothing else about yourself."

    // ── what a turn remembered, for the "记住了：…" line ───────────────────────

    /** The facts in memory as each chat's current turn began. */
    private val atTurnStart = java.util.concurrent.ConcurrentHashMap<String, Set<String>>()

    /** Not the person's memory: the identity, the person's own global note, site know-how, old notes. */
    private val notMemory = setOf("SOUL.md", "GLOBAL.md", "know-how", "days", ".git")

    internal fun facts(dir: File): Set<String> =
        dir.walkTopDown().onEnter { it == dir || it.name !in notMemory }
            .filter { it.isFile && it.name.endsWith(".md") && it.name !in notMemory }
            .flatMap { f -> runCatching { f.readLines() }.getOrDefault(emptyList()).asSequence() }
            .map { it.trim() }.filter { it.startsWith("- ") }
            .map { it.removePrefix("- ").trim() }.filter { it.isNotEmpty() }
            .toSet()

    private fun facts(context: Context): Set<String> = facts(dir(context))

    fun markTurnStart(context: Context, sessionId: String) { atTurnStart[sessionId] = facts(context) }

    /** The facts this turn wrote, without their metadata — shown whether or not the model said it. */
    fun added(context: Context, sessionId: String): List<String> {
        val before = atTurnStart.remove(sessionId) ?: return emptyList()
        return facts(context).filter { it !in before }.map(::plain).filter { it.isNotEmpty() }
    }

    /** A fact as the person reads it: no `[added: …]`, no `[[link]]` brackets. */
    internal fun plain(fact: String): String = fact
        .replace(Regex("""\s*\[(?!\[)[a-z]+:[^\]]*]\s*$"""), "")
        .replace(Regex("""\s*[（(]\d{4}-\d{2}-\d{2}[)）]\s*"""), " ")
        .replace(Regex("""\[\[(?:[^\]|]*/)?([^\]|]+)]]"""), "$1")
        .trim()
}
