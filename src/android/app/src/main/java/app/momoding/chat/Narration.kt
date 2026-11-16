package app.momoding.chat

/**
 * What the model says between steps is a courtesy to the person, not the result. With thinking
 * off, DeepSeek opens some multi-step work with a line of its own planning in English ("I'll set
 * that up…") even to a person writing Chinese — 4 in 10 times on a routine, whatever the
 * instructions say (调研/M50). Such a line is the model talking to itself: the chat does not
 * show it. The history keeps it; replies are never hidden, only lines before a further step.
 */
object Narration {
    /** [line] comes before a further step and is not in the language the person writes in. */
    fun selfTalk(line: String, person: String?): Boolean =
        person != null && chinese(person) && line.none { it in '一'..'鿿' } && line.count { it in 'a'..'z' || it in 'A'..'Z' } >= 3

    private fun chinese(text: String): Boolean {
        val han = text.count { it in '一'..'鿿' }
        val latin = text.count { it in 'a'..'z' || it in 'A'..'Z' }
        return han >= 2 && han * 2 >= latin
    }
}
