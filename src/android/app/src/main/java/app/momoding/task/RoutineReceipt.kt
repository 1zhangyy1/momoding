package app.momoding.task

import com.openminis.app.data.db.MessageEntity
import com.openminis.app.data.repository.ChatRepository
import org.json.JSONArray
import org.json.JSONObject

/** Extra fields on the existing text part, like inputContinuation; never model text or authorization. */
internal object RoutineReceipt {
    private const val RESERVE = 2048
    data class Result(val key: String, val done: Boolean, val response: String?, val fallback: String, val recorded: Boolean)
    fun frame(parts: String, fire: RoutineFire): String = JSONArray(parts).also {
        require(it.length() > 0 && fire.dispatchId != null)
        it.getJSONObject(0).put("routineFire", JSONObject(fire.json()))
    }.toString().also { require(it.length <= ChatRepository.MAX_MESSAGE_PARTS_JSON_LENGTH - RESERVE) { "The routine input leaves no room for its completion receipt." } }

    fun fire(message: MessageEntity): RoutineFire? = runCatching {
        JSONArray(message.partsJson).getJSONObject(0).getJSONObject("routineFire").toString().let(RoutineFire::parse)
            ?.takeIf { it.dispatchId == message.id && message.role == "user" }
    }.getOrNull()
    fun result(parts: String): Result? = runCatching {
        val r = JSONArray(parts).getJSONObject(0).getJSONObject("routineResult")
        Result(r.getString("key"), r.getBoolean("done"), r.optString("response").takeIf(String::isNotBlank), r.optString("fallback"), r.optBoolean("recorded"))
    }.getOrNull()

    suspend fun completeIn(dao: TaskDao, rows: List<InboxEntity>, completion: TaskCompletion, reply: MessageEntity?) {
        for (input in rows.filter { it.sender == InboxEntity.Sender.ROUTINE.name }) {
            val message = dao.routineMessage(input.id) ?: continue
            if (fire(message) == null) continue
            val key = reply?.id ?: input.failedAt?.let { "failed:$it" } ?: java.util.UUID.randomUUID().toString()
            if (result(message.partsJson)?.key == key) continue
            val parts = JSONArray(message.partsJson)
            // A routine's normal reply completes its run. TaskCompletion.done separately
            // requires an explicit report from a delegated Bot; that is not this receipt.
            val done = reply?.let { it.role == "assistant" && it.errorInfo.isNullOrBlank() && text(it.partsJson).isNotBlank() } ?: completion.done
            parts.getJSONObject(0).put("routineResult", JSONObject().put("key", key).put("done", done)
                .put("response", reply?.id).put("fallback", completion.words.take(200)).put("recorded", false))
            val json = parts.toString(); check(json.length <= ChatRepository.MAX_MESSAGE_PARTS_JSON_LENGTH)
            dao.routineMessageParts(message.id, json)
        }
    }
    suspend fun acknowledgeIn(dao: TaskDao, id: String, key: String) {
        val row = dao.routineMessage(id) ?: return
        if (result(row.partsJson)?.key != key) return
        val parts = JSONArray(row.partsJson); parts.getJSONObject(0).getJSONObject("routineResult").put("recorded", true)
        dao.routineMessageParts(id, parts.toString())
    }
    fun text(parts: String): String = JSONArray(parts).let { p -> (0 until p.length()).mapNotNull {
        p.getJSONObject(it).takeIf { value -> value.optString("type") == "text" }?.optString("value")
    }.joinToString("\n") }
}
