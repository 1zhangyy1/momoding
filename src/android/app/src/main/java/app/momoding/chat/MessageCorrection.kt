package app.momoding.chat

import org.json.JSONArray
import org.json.JSONObject

/** A correction is another message. Both the quote and the new words survive inbox/reload. */
data class MessageCorrection(val source: String, val quote: String, val replacement: String) {
    fun prepend(parts: String): String {
        val original = JSONArray(parts)
        val result = JSONArray().put(JSONObject().put("type", "text")
            .put("value", quote.lineSequence().joinToString("\n") { "> $it" } + "\n\n")
            .put("correction", JSONObject().put("source", source).put("quote", quote).put("replacement", replacement)))
        for (i in 0 until original.length()) result.put(original.get(i))
        return result.toString()
    }

    companion object {
        fun fromPart(part: JSONObject): MessageCorrection? = runCatching {
            part.optJSONObject("correction")?.let { MessageCorrection(it.getString("source"), it.getString("quote"), it.getString("replacement")) }
        }.getOrNull()
    }
}
