package com.openminis.app.ui.chat

/** Select a contiguous prefix of the persisted inbox; later messages never overtake earlier ones. */
internal object QueuedPromptBatching {

    /** Arrival order is the same at a tool boundary and at the end of a loop. A routine's
     * prefilled tool call belongs to that one message, so it travels alone. */
    fun nextInsertBatch(queue: List<QueuedPrompt>): List<QueuedPrompt> = nextDrainBatch(queue)

    fun nextDrainBatch(queue: List<QueuedPrompt>): List<QueuedPrompt> {
        val first = queue.firstOrNull() ?: return emptyList()
        return if (first.isScheduledFire) listOf(first) else queue.takeWhile { !it.isScheduledFire }
    }

}
