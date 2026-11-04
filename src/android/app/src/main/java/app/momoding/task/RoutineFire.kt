package app.momoding.task

import org.json.JSONObject

/** Immutable origin of a routine's task, kept in its log with the first handoff.
 * Completion can rebuild the routine's run record after process death. */
data class RoutineFire(val routineId: String, val firedAt: Long, val scheduled: Boolean) {
    fun json(): String = JSONObject().put("routine", routineId).put("firedAt", firedAt).put("scheduled", scheduled).toString()

    companion object {
        fun parse(json: String): RoutineFire? = runCatching {
            val row = JSONObject(json)
            RoutineFire(row.getString("routine"), row.getLong("firedAt"), row.getBoolean("scheduled"))
        }.getOrNull()
    }
}
