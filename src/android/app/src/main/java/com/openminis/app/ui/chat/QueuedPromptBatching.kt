package com.openminis.app.ui.chat

/** Select a contiguous prefix of the persisted inbox; later messages never overtake earlier ones. */
internal object QueuedPromptBatching {

    /** Arrival order is the same at a tool boundary and at the end of a loop. A routine's
     * prefilled tool call belongs to that one message, so it travels alone.
     * momoding: a task's result does not interrupt a running turn: it waits for the turn to end
     * (调研/M50 P4), and so does everything after it, keeping arrival order. */
    fun nextInsertBatch(queue: List<QueuedPrompt>): List<QueuedPrompt> =
        nextDrainBatch(queue.takeWhile { !it.isTaskResult })

    fun nextDrainBatch(queue: List<QueuedPrompt>): List<QueuedPrompt> {
        val first = queue.firstOrNull() ?: return emptyList()
        return if (first.isScheduledFire) listOf(first) else queue.takeWhile { !it.isScheduledFire }
    }

    private val QueuedPrompt.isTaskResult: Boolean
        get() = origin == QueuedPromptOrigin.PROGRAMMATIC && text.startsWith(app.momoding.task.TaskEngine.REPORT_CUE)
}
