package app.momoding.task

import android.content.Context
import androidx.room.withTransaction
import com.openminis.app.MinisApp
import com.openminis.app.ui.chat.ChatViewModelStore
import org.json.JSONObject

/** Capture the work's identity before waiting; check that same work before acting. */
object TaskGuard {
    data class Binding(val hand: String, val taskId: String?)

    suspend fun bind(context: Context, hand: String, taskId: String? = null, process: String? = null): Binding {
        val db = (context.applicationContext as MinisApp).database
        val model = ChatViewModelStore.existing(hand)?.activeTaskId
        return db.withTransaction { bindIn(db.taskDao(), hand, taskId, process, model) }
    }

    internal suspend fun bindIn(dao: TaskDao, hand: String, taskId: String? = null,
        process: String? = null, modelTaskId: String? = null,
    ): Binding {
        val command = process?.let { dao.byExecution(hand, "\"process\":" + JSONObject.quote(it))?.id }
        // A main chat can answer another message while its own detached work is still pending.
        val delegated = dao.heldBy(hand)?.takeIf { it.conversation != hand }?.id
        return Binding(hand, taskId ?: command ?: modelTaskId ?: delegated)
    }

    /** Null means allowed. A removed task never becomes an unbound, newly authorized call. */
    suspend fun check(context: Context, binding: Binding, outward: Boolean = false, allowWaiting: Boolean = false): String? {
        val db = (context.applicationContext as MinisApp).database
        return db.withTransaction { checkIn(db.taskDao(), binding, outward, allowWaiting) }
    }

    internal suspend fun checkIn(dao: TaskDao, binding: Binding, outward: Boolean = false, allowWaiting: Boolean = false): String? {
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
