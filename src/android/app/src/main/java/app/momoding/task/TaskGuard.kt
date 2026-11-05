package app.momoding.task

import android.content.Context
import androidx.room.withTransaction
import com.openminis.app.MinisApp
import com.openminis.app.ui.chat.ChatViewModelStore
import org.json.JSONObject

/** Capture the work's identity before waiting; check that same work before acting. */
object TaskGuard {
    data class Binding(val hand: String, val taskId: String?, val message: String? = null)

    suspend fun bind(context: Context, hand: String, taskId: String? = null, process: String? = null): Binding {
        TaskEngine.awaitRecovery()
        val db = (context.applicationContext as MinisApp).database
        val model = ChatViewModelStore.existing(hand)?.activeTaskId
        val call = kotlinx.coroutines.currentCoroutineContext()[ToolCall] ?: ShellWork.toolCall(process)
        return db.withTransaction { bindIn(db.taskDao(), hand, taskId, process, model, call?.message) }
    }

    internal suspend fun bindIn(dao: TaskDao, hand: String, taskId: String? = null,
        process: String? = null, modelTaskId: String? = null, message: String? = null,
    ): Binding {
        val command = process?.let { dao.byExecution(hand, "\"process\":" + JSONObject.quote(it))?.id }
        // A main chat can answer another message while its own detached work is still pending.
        val delegated = dao.heldBy(hand)?.takeIf { it.conversation != hand }?.id
        return Binding(hand, taskId ?: command ?: modelTaskId ?: delegated, message)
    }

    /** Null means allowed. A removed task never becomes an unbound, newly authorized call. */
    suspend fun check(context: Context, binding: Binding, outward: Boolean = false, allowWaiting: Boolean = false): String? {
        TaskEngine.awaitRecovery()
        val db = (context.applicationContext as MinisApp).database
        return db.withTransaction { checkIn(db.taskDao(), binding, outward, allowWaiting) }
    }

    internal suspend fun checkIn(dao: TaskDao, binding: Binding, outward: Boolean = false, allowWaiting: Boolean = false, targetTaskId: String? = null): String? {
        // Scope a recovery to the input that caused this invocation. Another user message
        // in the same chat is not an authorization to replay this input's uncertain work.
        if (outward && binding.message != null) {
            dao.recovered("call:" + binding.message)?.takeIf { it.conversation == binding.hand || it.hand == binding.hand }?.let {
                return "This invocation was interrupted and parked for verification. Use a new instruction after checking the old outcome; do not replay this old call."
            }
            val input = dao.inputForCall(binding.hand, binding.message)
            if (input != null) {
                if (input.sender != InboxEntity.Sender.USER.name && input.text.contains(" · late_result]") &&
                    (targetTaskId == null || dao.task(targetTaskId)?.state != TaskEntity.State.RUNNING.name))
                    return "This is a late receipt, not permission to restart work. Report it once; wait for the person before any new outward action."
                val sourceWork = dao.sourceTasks(binding.hand, input.id) + listOfNotNull(input.taskId?.let { dao.task(it) }
                    ?.takeIf { it.conversation == binding.hand })
                val work = when {
                    targetTaskId == null -> sourceWork
                    input.sender == InboxEntity.Sender.USER.name -> sourceWork.filter { it.id == targetTaskId }
                    else -> listOfNotNull(dao.task(targetTaskId)?.takeIf { it.conversation == binding.hand })
                }
                for (task in work.distinctBy { it.id }) {
                    if ((task.hand == binding.hand || (input.sender != InboxEntity.Sender.USER.name && input.taskId == task.id)) &&
                        task.state == TaskEntity.State.ENDED.name && task.outcome == TaskEntity.Outcome.STOPPED.name)
                        return "Task ${task.id} was stopped. Do not start more work from this old input."
                    val approvedWait = allowWaiting && Approval.pending(task.ask).isNotEmpty()
                    if (task.hold != null || (!approvedWait && (task.state == TaskEntity.State.WAITING.name || task.ask != null)))
                        return "Task ${task.id} is waiting for the person. Verify its outcome; do not repeat its outward work."
                }
            }
        }
        val id = binding.taskId ?: return null
        val task = dao.task(id) ?: return "Task $id was deleted. This old run may not use tools."
        if (task.hand != binding.hand) return "Task $id belongs to another hand. This run may not act for it."
        val remaining = ArrayDeque<TaskEntity>().apply { add(task) }
        val checked = mutableSetOf<String>()
        while (remaining.isNotEmpty()) {
            val current = remaining.removeFirst()
            if (!checked.add(current.id)) continue
            if (current.state == TaskEntity.State.ENDED.name) return "Task ${current.id} ended (${current.outcome?.lowercase() ?: "ended"}). This old run may not use tools."
            if (current.state == TaskEntity.State.QUEUED.name) return "Task ${current.id} has not started. Wait for its turn."
            if (outward && !current.hold.isNullOrBlank()) return "Task ${current.id} is on hold: ${current.hold}. Wait for the person before acting outside the workspace."
            if (outward && !allowWaiting && (current.state == TaskEntity.State.WAITING.name || current.ask != null)) return "Task ${current.id} is waiting for the person. Do not perform this outward action yet."
            val parent = dao.screenSpec(current.id)?.let { JSONObject(it).optString("parent").takeIf(String::isNotBlank) }
            if (parent != null) remaining.add(dao.task(parent) ?: return "The parent task $parent was deleted. This old run may not act for it.")
        }
        return null
    }
}
