package app.momoding.core.notification

import app.momoding.core.runtime.local.PiNativeAndroidToolResult

data class NotificationMutationPlan(
    val taskId: String,
    val piToolCallId: String,
    val action: NotificationToolAction,
    val identity: NotificationIdentity,
    val notificationHandle: String?,
    val existingStateDigest: String?,
    val title: String?,
    val message: String?,
    val requestDigest: String,
    val planDigest: String,
    val summary: String,
    val details: String,
)

sealed interface NotificationMutationPreparation {
    data class Ready(val plan: NotificationMutationPlan) : NotificationMutationPreparation
    data class Failed(val result: PiNativeAndroidToolResult) : NotificationMutationPreparation
}
