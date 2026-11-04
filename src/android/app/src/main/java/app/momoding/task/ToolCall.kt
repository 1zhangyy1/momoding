package app.momoding.task

import org.json.JSONObject
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** The persisted invocation this executor is serving, including concurrent tool calls. */
data class ToolCall(val message: String, val id: String, val name: String) : AbstractCoroutineContextElement(Key) {
    fun json(): JSONObject = JSONObject().put("message", message).put("id", id).put("name", name)

    companion object Key : CoroutineContext.Key<ToolCall> {
        fun parse(value: JSONObject): ToolCall = ToolCall(value.getString("message"), value.getString("id"), value.getString("name"))
    }
}
