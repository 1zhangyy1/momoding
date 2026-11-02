package app.momoding.task

import android.content.Context
import com.openminis.app.MinisApp
import org.json.JSONArray
import org.json.JSONObject

/**
 * Momoding (调研/M39, M41): the only code that changes [TaskEntity] rows. Each change is one
 * transaction; what it sets off (waking a session, freeing the screen) runs after it commits.
 */
object TaskEngine {

    fun dao(context: Context): TaskDao = (context.applicationContext as MinisApp).database.taskDao()

    /** Everything recent, for the debug RPC `task.dump` (walkthroughs and evals read it). */
    suspend fun dump(context: Context, limit: Int = 50): JSONObject {
        val dao = dao(context)
        val tasks = JSONArray()
        for (t in dao.recentTasks(limit)) {
            val log = JSONArray()
            dao.log(t.id).forEach { log.put(JSONObject().put("at", it.at).put("kind", it.kind).put("body", it.body)) }
            tasks.put(
                JSONObject().put("id", t.id).put("conversation", t.conversation).put("source", t.sourceMessageId)
                    .put("title", t.title).put("hand", t.hand).put("state", t.state).put("progress", t.progress)
                    .put("ask", t.ask).put("hold", t.hold).put("outcome", t.outcome).put("log", log),
            )
        }
        val inbox = JSONArray()
        dao.recentInbox(limit).forEach {
            inbox.put(
                JSONObject().put("id", it.id).put("to", it.toSession).put("task", it.taskId).put("from", it.sender)
                    .put("text", it.text).put("arrived", it.arrivedAt).put("read", it.readAt),
            )
        }
        return JSONObject().put("tasks", tasks).put("inbox", inbox)
    }
}
