package app.momoding.task

import org.json.JSONObject
import org.json.JSONArray
import com.openminis.app.provider.ToolPairing
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** The persisted invocation this executor is serving, including concurrent tool calls. */
data class ToolCall(val message: String, val id: String, val name: String) : AbstractCoroutineContextElement(Key) {
    fun json(): JSONObject = JSONObject().put("message", message).put("id", id).put("name", name)

    internal suspend fun requireStoredIn(dao: TaskDao, session: String) {
        val raw = dao.toolMessage(session, message) ?: throw TaskAction.NotStarted("The original tool invocation was deleted.")
        val parts = JSONArray(raw)
        val matches = (0 until parts.length()).any { i ->
            val part = parts.getJSONObject(i)
            part.optString("type") == "toolUse" && part.getJSONObject("value").let {
                ToolPairing.key(it.getString("toolUseId")) == ToolPairing.key(id) && it.getString("name") == name
            }
        }
        if (!matches) throw TaskAction.NotStarted("The tool invocation does not match its stored source.")
    }

    companion object Key : CoroutineContext.Key<ToolCall> {
        fun parse(value: JSONObject): ToolCall = ToolCall(value.getString("message"), value.getString("id"), value.getString("name"))
    }
}
