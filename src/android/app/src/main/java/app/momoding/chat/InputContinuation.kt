package app.momoding.chat

import org.json.JSONArray
import org.json.JSONObject

/** A model-context record for the same receive; the original bubble and inbox id stay unchanged. */
object InputContinuation {
    fun parts(source: String, saved: String): String {
        val reminder = "<system-reminder>Continue processing the same stored input below, using the recorded results and current state. This is not new authorization. Do not repeat completed or uncertain outward actions; verify or ask first.</system-reminder>\n"
        val parts = JSONArray().put(JSONObject().put("type", "text").put("value", reminder).put("inputContinuation", source))
        val original = JSONArray(saved)
        for (i in 0 until original.length()) parts.put(original.get(i))
        return parts.toString()
    }

    fun source(parts: String): String? = runCatching {
        JSONArray(parts).optJSONObject(0)?.optString("inputContinuation")?.takeIf(String::isNotBlank)
    }.getOrNull()
}
