package com.openminis.app.scheduled

import org.json.JSONObject
import java.util.UUID

/** The claimed fire's immutable recipe, until Room acknowledges its input or helper task. */
data class PendingScheduledFire(val id: String, val at: Long, val task: ScheduledTask) {
    fun json(): JSONObject = JSONObject().put("id", id).put("at", at).put("task", task.copy(pendingDispatch = null).toJson())
    companion object {
        fun create(task: ScheduledTask, at: Long = System.currentTimeMillis()) =
            PendingScheduledFire(UUID.randomUUID().toString(), at, task.copy(pendingDispatch = null))
        fun parse(value: JSONObject): PendingScheduledFire = PendingScheduledFire(value.getString("id"), value.getLong("at"),
            ScheduledTask.fromJson(value.getJSONObject("task"))).also { require(it.id.isNotBlank()) }
    }
}
