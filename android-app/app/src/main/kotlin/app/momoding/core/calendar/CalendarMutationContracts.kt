package app.momoding.core.calendar

import app.momoding.core.runtime.local.PiNativeAndroidToolResult

data class CalendarMutationPlan(
    val taskId: String,
    val piToolCallId: String,
    val action: CalendarToolAction,
    val requestDigest: String,
    val snapshotDigest: String,
    val planDigest: String,
    val summary: String,
    val details: String,
    val calendar: CalendarRecord,
    val before: CalendarEventRecord?,
    val write: CalendarEventWrite?,
)

sealed interface CalendarMutationPreparation {
    data class Ready(val plan: CalendarMutationPlan) : CalendarMutationPreparation
    data class Failed(val result: PiNativeAndroidToolResult) : CalendarMutationPreparation
}
