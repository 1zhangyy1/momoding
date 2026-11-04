package app.momoding.task

import org.json.JSONObject

/** A live executor's receipt handle. The task log, rather than this handle, owns the action. */
data class TaskAction(val taskId: String, val id: String, val command: ShellWork.Command? = null) {
    internal class NotStarted(message: String) : IllegalStateException(message)

    companion object {
        fun pending(history: List<TaskLogEntity>): List<Approval.Spec> {
            val results = history.filter { it.kind == TaskLogEntity.Kind.RESULT.name }
                .mapNotNull { runCatching { JSONObject(it.body).getString("id") }.getOrNull() }.toSet()
            return history.filter { it.kind == TaskLogEntity.Kind.ABOUT_TO.name }
                .map { Approval.Spec.parse(JSONObject(it.body)) }.filter { it.request.id !in results }
        }
    }
}
