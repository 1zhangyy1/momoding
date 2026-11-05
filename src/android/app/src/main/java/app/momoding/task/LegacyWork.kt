package app.momoding.task

import android.content.SharedPreferences
import org.json.JSONObject
import java.util.UUID

/** The retired WorkResume flag is evidence of a lost loop, never an execution recipe. */
internal object LegacyWork {
    const val KEY = "work.running"
    data class Entry(val session: String, val raw: String, val key: String)
    fun entries(raw: String?): List<Entry> = runCatching {
        if (raw == null) emptyList() else JSONObject(raw).let { value -> value.keys().asSequence().map { sid ->
            Entry(sid, value.get(sid).toString(), "legacy:" + UUID.nameUUIDFromBytes((sid + "\u0000" + raw).toByteArray()))
        }.toList() }
    }.getOrDefault(emptyList())

    /** Only acknowledge the exact snapshot whose Room transaction committed. Keep unroutable evidence. */
    fun acknowledge(prefs: SharedPreferences, raw: String?, saved: Set<String>): Boolean {
        if (raw == null || saved.isEmpty() || prefs.getString(KEY, null) != raw) return true
        val remaining = JSONObject(raw).apply { saved.forEach { remove(it) } }
        val edit = prefs.edit()
        if (remaining.length() == 0) edit.remove(KEY) else edit.putString(KEY, remaining.toString())
        return edit.commit()
    }
}
