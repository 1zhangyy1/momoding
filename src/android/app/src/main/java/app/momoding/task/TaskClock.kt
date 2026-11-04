package app.momoding.task

import org.json.JSONObject

/** Active time is carried by persisted start/pause records, including across queued continuations. */
internal object TaskClock {
    fun latest(history: List<TaskLogEntity>): TaskLogEntity? =
        history.lastOrNull { it.kind == "STARTED" || it.kind == "PAUSED" }

    fun elapsed(task: TaskEntity, history: List<TaskLogEntity>, now: Long = System.currentTimeMillis()): Long {
        val index = history.indexOfLast { it.kind == "STARTED" || it.kind == "PAUSED" }
        val mark = history.getOrNull(index)
        val carried = mark?.body?.let { runCatching { JSONObject(it).optLong("elapsed_ms") }.getOrDefault(0) } ?: 0
        if (mark?.kind == "PAUSED") return carried
        // Older approvals kept their paused time in ask; older waits have only updated_at.
        if (task.state == "WAITING") task.ask?.let {
            runCatching { JSONObject(it).takeIf { o -> o.has("elapsed_ms") }?.getLong("elapsed_ms") }
                .getOrNull()?.let { saved -> return saved }
        }
        if (mark == null && task.state == "QUEUED") return 0
        val until = when (task.state) {
            "WAITING", "QUEUED" -> task.updatedAt
            "ENDED" -> history.drop(index + 1).lastOrNull { it.kind == "END" }?.at ?: task.updatedAt
            else -> now
        }
        return carried + (until - (mark?.at ?: task.createdAt)).coerceAtLeast(0)
    }
}
