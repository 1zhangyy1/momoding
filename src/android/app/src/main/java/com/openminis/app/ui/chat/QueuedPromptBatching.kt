package com.openminis.app.ui.chat

/**
 * [T-scheduled-preemptive-insert] Which queued prompts go out together, and
 * when.
 *
 * Two delivery points exist. The TOOL BOUNDARY — a turn's tool calls have all
 * finished and their results are in history, the next model request has not
 * been made — is where a prompt can be slipped into a running loop without
 * cutting any tool short. The DRAIN runs once the loop has converged (and
 * after a Stop).
 *
 * The rules, and why:
 *  - A scheduled fire always travels ALONE. It carries its own envelope and
 *    possibly its own prefilled tool call; merged with other prompts it
 *    became one message holding several unrelated triggers, and a prefill
 *    had no single turn to belong to.
 *  - At the tool boundary only a user follow-up or a scheduled fire may go
 *    in. Other programmatic prompts (agent callbacks, RPC/CLI sends) keep
 *    waiting for the drain, as [QueuedPromptOrigin] describes.
 *  - One scheduled fire per boundary. Several pending fires are delivered one
 *    boundary (or drain round) at a time, oldest first, so the original task
 *    still gets a model turn between them.
 *  - Momoding (调研/M19): the person's words are never merged with agent
 *    reports. A user follow-up goes in alone (with any other user follow-ups);
 *    reports wait for the drain, where the person's words still go first and
 *    the reports that arrived together then go as one turn — "answer the user
 *    first", and several results become one message instead of several.
 */
internal object QueuedPromptBatching {

    /** What to slip in at a tool boundary; empty = nothing may go in now. */
    fun nextInsertBatch(queue: List<QueuedPrompt>): List<QueuedPrompt> {
        val first = queue.firstOrNull { it.isScheduledFire || it.origin == QueuedPromptOrigin.USER }
            ?: return emptyList()
        return if (first.isScheduledFire) listOf(first) else queue.filter { it.isUserFollowUp }
    }

    /** What the drain sends as its next turn; empty = the queue is empty. */
    fun nextDrainBatch(queue: List<QueuedPrompt>): List<QueuedPrompt> {
        val first = queue.firstOrNull() ?: return emptyList()
        if (first.isScheduledFire) return listOf(first)
        val users = queue.filter { it.isUserFollowUp }
        return users.ifEmpty { queue.filterNot { it.isScheduledFire } }
    }

    private val QueuedPrompt.isUserFollowUp: Boolean
        get() = origin == QueuedPromptOrigin.USER && !isScheduledFire

    /**
     * Ids of queued fires a new fire of [taskId] replaces. Two undelivered
     * fires of the same task would run the same check twice in a row; the
     * newer one has the fresher prefilled output, so it wins. Fires of
     * different tasks never replace each other.
     */
    fun supersededBy(queue: List<QueuedPrompt>, taskId: String): List<String> =
        queue.filter { it.scheduledTaskId == taskId }.map { it.id }
}
