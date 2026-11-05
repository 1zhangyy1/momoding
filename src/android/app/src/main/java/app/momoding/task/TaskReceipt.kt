package app.momoding.task

import org.json.JSONArray
import org.json.JSONObject

/** Observations from the existing journal; a successful return is not proof of delivery. */
internal object TaskReceipt {
    data class Entry(val row: TaskLogEntity, val value: JSONObject?) {
        val id: String get() = (value?.opt("id") as? String)?.takeIf(String::isNotBlank) ?: "log:${row.id}"
    }

    fun entries(history: List<TaskLogEntity>, kind: TaskLogEntity.Kind): List<Entry> =
        history.filter { it.kind == kind.name }.map { Entry(it, runCatching { JSONObject(it.body) }.getOrNull()) }

    /** Observe an intent without rebuilding execution parameters that an old row may lack. */
    fun pending(history: List<TaskLogEntity>, kind: TaskLogEntity.Kind, resultKind: TaskLogEntity.Kind): List<Entry> {
        val finished = entries(history, resultKind).filter { outcome(it.value) }.map { it.id }.toSet()
        return entries(history, kind).filter { it.id !in finished }.distinctBy { it.id }
    }

    private fun outcome(value: JSONObject?): Boolean = value?.opt("success") is Boolean || value?.opt("unknown") == true

    fun result(history: List<TaskLogEntity>, kind: TaskLogEntity.Kind, id: String): JSONObject? =
        entries(history, kind).asReversed().firstOrNull { it.id == id && outcome(it.value) }?.value

    fun belongsToEarlierAttempt(history: List<TaskLogEntity>, kind: TaskLogEntity.Kind, id: String): Boolean {
        val source = history.firstOrNull { it.kind == kind.name && runCatching { JSONObject(it.body).optString("id") == id }.getOrDefault(false) }
        return source != null && history.any { it.kind == TaskLogEntity.Kind.END.name && it.id > source.id }
    }

    /** Capture before the stop commits. Later receipts add facts without rewriting this snapshot. */
    suspend fun stopped(dao: TaskDao, root: TaskEntity): String {
        val where = JSONArray()
        val outward = JSONArray()
        val commands = JSONArray()
        val unread = JSONArray()
        val visited = mutableSetOf<String>()
        suspend fun visit(task: TaskEntity) {
            if (!visited.add(task.id)) return
            val history = dao.log(task.id)
            val ends = history.indices.filter { history[it].kind == TaskLogEntity.Kind.END.name }
            val start = (if (task.state == TaskEntity.State.ENDED.name) ends.dropLast(1).lastOrNull() else ends.lastOrNull())?.plus(1) ?: 0
            val through = if (task.state == TaskEntity.State.ENDED.name) ends.lastOrNull()?.plus(1) ?: history.size else history.size
            val current = history.subList(start, through)
            val checkpoint = task.progress ?: current.lastOrNull { it.kind == TaskLogEntity.Kind.SCREEN_RESULT.name }
                ?.body?.let { runCatching { JSONObject(it).optJSONArray("log") }.getOrNull() }
                ?.let { if (it.length() == 0) null else it.optString(it.length() - 1) }
            where.put(JSONObject().put("task", task.id).put("title", task.title).put("hand", task.hand)
                .put("state_before_stop", task.state.lowercase()).put("checkpoint", checkpoint ?: "No further checkpoint was recorded.")
                .put("waiting_on", task.ask).put("hold", task.hold))
            val actions = entries(current, TaskLogEntity.Kind.ABOUT_TO)
            val actionIds = actions.map { it.id }.toSet()
            for (action in actions) {
                val about = action.value
                val id = action.id
                val value = result(history, TaskLogEntity.Kind.RESULT, id)
                outward.put(JSONObject().put("task", task.id).put("id", id)
                    .put("action", brief(about?.optString("summary").orEmpty().ifBlank { about?.optString("preview").orEmpty().ifBlank { "Unparseable outward action; verify its effect." } }, 300))
                    .put("preview", brief(about?.optString("preview").orEmpty(), 1000))
                    .put("return", status(value)).put("output", brief(value?.optString("output").orEmpty(), 1000)))
            }
            for (command in entries(current, TaskLogEntity.Kind.COMMAND)) {
                if (command.id in actionIds) continue
                val value = result(history, TaskLogEntity.Kind.COMMAND_RESULT, command.id)
                commands.put(JSONObject().put("task", task.id).put("id", command.id).put("command",
                    brief(command.value?.optString("text").orEmpty().ifBlank { "Unreadable command; verify its effect." }, 300))
                    .put("return", status(value)).put("output", brief(value?.optString("output").orEmpty(), 1000)))
            }
            val received = dao.unread(task.hand).filter { it.taskId == task.id }.map { it.text }
            val notStarted = if (task.state == TaskEntity.State.QUEUED.name && received.isEmpty())
                current.filter { it.kind == TaskLogEntity.Kind.PASSED_ON.name }.map { it.body } else received
            notStarted.forEach { unread.put(JSONObject().put("task", task.id).put("text", it)) }
            for (id in current.filter { it.kind == TaskLogEntity.Kind.CHILD.name }.map { it.body }.distinct()) dao.task(id)?.let { visit(it) }
        }
        visit(root)
        return "Stop snapshot:\n" + JSONObject().put("checkpoints", where).put("outward_actions", outward)
            .put("other_commands", commands).put("unread_instructions", unread)
            .put("note", "These are recorded returns at stop time, not independent delivery confirmation. Missing or unknown returns must be verified before any retry.").toString(2)
    }

    private fun status(value: JSONObject?): String = when {
        value == null || value.optBoolean("unknown") || !value.has("success") -> "unknown"
        value.optBoolean("success") -> "success"
        else -> "failure"
    }
    private fun brief(text: String, limit: Int) = if (text.length <= limit) text else text.take(limit) + "… [summary truncated]"
}
