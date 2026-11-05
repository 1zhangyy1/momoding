package app.momoding.task

import android.content.Context
import androidx.room.withTransaction
import com.openminis.app.MinisApp
import com.openminis.app.data.db.ChatDao
import com.openminis.app.data.db.MessageEntity
import com.openminis.app.data.repository.ChatRepository
import com.openminis.app.provider.ToolPairing
import org.json.JSONArray
import org.json.JSONObject

/** Tool results go in the row right after their call; a call the app never returned from gets "interrupted". */
object TaskTranscript {

    suspend fun results(context: Context, message: String, parts: String): String {
        val db = (context.applicationContext as MinisApp).database
        return db.withTransaction { resultsIn(db.chatDao(), message, JSONArray(parts)).id }
    }

    internal suspend fun resultsIn(chat: ChatDao, message: String, incoming: JSONArray): MessageEntity {
        val source = requireNotNull(chat.message(message)) { "The tool invocation was deleted." }
        require(source.role == "assistant")
        val uses = parts(source.partsJson).filter { it.optString("type") == "toolUse" }
            .map { key(it.getJSONObject("value")) }.toSet()
        val added = parts(incoming.toString())
        require(added.isNotEmpty() && added.all { it.optString("type") == "toolResult" && key(it.getJSONObject("value")) in uses })
        val next = chat.nextMessage(source.sessionId, source.sortOrder)
        val target = next?.takeIf { it.role == "user" && parts(it.partsJson).let { p -> p.isNotEmpty() && p.all { value -> value.optString("type") == "toolResult" } } }
        val combined = linkedMapOf<String, JSONObject>()
        target?.let { parts(it.partsJson).forEach { p -> combined[key(p.getJSONObject("value"))] = p } }
        added.forEach { p -> combined[key(p.getJSONObject("value"))] = p }
        val ordered = uses.mapNotNull { combined.remove(it) } + combined.values
        val json = bounded(JSONArray(ordered))
        val row = if (target != null) {
            chat.updateMessageParts(target.id, json)
            target.copy(partsJson = json)
        } else {
            check(chat.message("$message:tools") == null) { "The receipt row is no longer beside its invocation." }
            chat.shiftMessagesAfter(source.sessionId, source.sortOrder)
            MessageEntity("$message:tools", source.sessionId, "user", json, System.currentTimeMillis(), sortOrder = source.sortOrder + 1)
                .also { chat.insertMessage(it) }
        }
        chat.touchSession(source.sessionId, System.currentTimeMillis())
        return row
    }

    /** Must run in the startup transaction, before a chat can load or resume its history. */
    internal suspend fun recoverIn(chat: ChatDao): Int {
        var restored = 0
        for (source in chat.interruptedToolTurns()) {
            val next = chat.nextMessage(source.sessionId, source.sortOrder)
            val returned = next?.takeIf { it.role == "user" }?.let { parts(it.partsJson) }
                .orEmpty().filter { it.optString("type") == "toolResult" }.map { key(it.getJSONObject("value")) }.toSet()
            val interrupted = JSONArray()
            for (part in parts(source.partsJson)) {
                if (part.optString("type") != "toolUse") continue
                val value = part.getJSONObject("value")
                if (key(value) in returned) continue
                val output = "The app exited before this tool returned a recorded result. Execution was interrupted; inspect the current state before retrying."
                interrupted.put(JSONObject().put("type", "toolResult").put("value", JSONObject()
                    .put("toolUseId", value.getString("toolUseId")).put("name", value.getString("name"))
                    .put("output", output).put("success", false).put("recovered", true)))
            }
            if (interrupted.length() > 0) {
                resultsIn(chat, source.id, interrupted)
                restored += interrupted.length()
            }
        }
        return restored
    }

    private fun key(value: JSONObject) = ToolPairing.key(value.getString("toolUseId"))
    private fun parts(json: String): List<JSONObject> = JSONArray(json).let { a -> List(a.length()) { a.getJSONObject(it) } }

    /** Preserve every call id even when a large output must fit Room's existing row budget. */
    private fun bounded(array: JSONArray): String {
        val original = array.toString()
        val max = ChatRepository.MAX_MESSAGE_PARTS_JSON_LENGTH
        if (original.length <= max) return original
        val values = parts(original)
        fun shortened(limit: Int): String = JSONArray(values.map { p ->
            val copy = JSONObject(p.toString())
            val value = copy.getJSONObject("value")
            val output = value.optString("output")
            val kept = if (output.length <= limit) output else output.take(limit) + "\n[Output truncated for storage; original length ${output.length}]"
            value.put("output", kept).put("snapshot", JSONObject().put("type", "text").put("text", kept.takeLast(minOf(limit, 1000))))
            copy
        }).toString()
        var low = 0
        var high = values.maxOf { it.getJSONObject("value").optString("output").length }
        check(shortened(0).length <= max) { "Tool receipt identifiers exceed the row budget." }
        while (low < high) {
            val mid = low + (high - low + 1) / 2
            if (shortened(mid).length <= max) low = mid else high = mid - 1
        }
        return shortened(low)
    }
}
