package app.momoding.task

import org.json.JSONObject

/** Active time is carried by persisted start/pause records, including across queued continuations. */
internal object TaskClock {
    fun latest(history: List<TaskLogEntity>): TaskLogEntity? =
        history.lastOrNull { it.kind == "STARTED" || it.kind == "PAUSED" }

    fun elapsed(task: TaskEntity, history: List<TaskLogEntity>, now: Long = System.currentTimeMillis()): Long {
        val index = history.indexOfLast { it.kind == "STARTED" || it.kind == "PAUSED" }
        val mark = history.getOrNull(index)
        val clock = mark?.body?.let { runCatching { JSONObject(it) }.getOrNull() }
        val carried = clock?.optLong("elapsed_ms") ?: 0
        if (mark?.kind == "PAUSED") return carried
        // Older approvals kept their paused time in ask; older waits have only updated_at.
        if (task.state == "WAITING" && clock?.has("elapsed_ms") != true) task.ask?.let {
            runCatching { JSONObject(it).takeIf { o -> o.has("elapsed_ms") }?.getLong("elapsed_ms") }
                .getOrNull()?.let { saved -> return saved }
        }
        if (mark == null && task.state == "QUEUED") return 0
        val until = when (task.state) {
            // A hand can read while another part of its task waits. New clock records say
            // explicitly whether time is running; old rows only have updated_at.
            "WAITING" -> if (clock?.has("elapsed_ms") == true) now else task.updatedAt
            "QUEUED" -> task.updatedAt
            "ENDED" -> history.drop(index + 1).lastOrNull { it.kind == "END" }?.at ?: task.updatedAt
            else -> now
        }
        return carried + (until - (mark?.at ?: task.createdAt)).coerceAtLeast(0)
    }

    /** Only charge through a saved proof of liveness, never through the next launch's wall time. */
    fun interrupted(task: TaskEntity, history: List<TaskLogEntity>, lastAliveAt: Long?, now: Long): Long {
        val durable = maxOf(task.createdAt, task.updatedAt, history.maxOfOrNull { it.at } ?: task.createdAt)
        val cutoff = maxOf(lastAliveAt ?: durable, durable).coerceAtMost(now)
        return elapsed(task, history, cutoff)
    }
}
