package app.momoding.core.clipboard

import app.momoding.core.runtime.local.PiNativeAndroidToolResult

data class ClipboardMutationPlan(
    val taskId: String,
    val piToolCallId: String,
    val action: ClipboardToolAction,
    val text: String?,
    val sensitive: Boolean,
    val requestDigest: String,
    val planDigest: String,
    val summary: String,
    val details: String,
)

sealed interface ClipboardMutationPreparation {
    data class Ready(val plan: ClipboardMutationPlan) : ClipboardMutationPreparation
    data class Failed(val result: PiNativeAndroidToolResult) : ClipboardMutationPreparation
}
