package app.momoding.memory

/**
 * Memory as the prompt shows it, rendered once per compaction period, as Grok Bot does: the
 * system prompt then stays the same from one turn to the next and the provider's prompt cache
 * keeps hitting, where re-rendering every turn broke it whenever a line was saved.
 *
 * Nothing is lost meanwhile. What the chat saves is in the conversation it saved it from, and
 * what the background keeps was said in this conversation too; the next compaction, which starts
 * a new period, renders memory afresh.
 */
class FrozenMemory {
    private var period: String? = null
    private var text: String? = null

    /** The memory for compaction [period] (the latest marker's id, null before the first). */
    @Synchronized fun get(period: String?, render: () -> String): String =
        text?.takeIf { this.period == period } ?: render().also { this.period = period; text = it }
}
