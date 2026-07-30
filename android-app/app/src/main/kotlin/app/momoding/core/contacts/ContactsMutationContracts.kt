package app.momoding.core.contacts

import app.momoding.core.runtime.local.PiNativeAndroidToolResult

data class ContactsMutationPlan(
    val taskId: String,
    val piToolCallId: String,
    val action: ContactsToolAction,
    val requestDigest: String,
    val snapshotDigest: String,
    val planDigest: String,
    val summary: String,
    val details: String,
    val before: ContactMutationSnapshot?,
    val rawContact: ContactRawContactRecord?,
    val write: ContactWrite?,
    val fields: Set<ContactMutationField>,
)

sealed interface ContactsMutationPreparation {
    data class Ready(val plan: ContactsMutationPlan) : ContactsMutationPreparation
    data class Failed(val result: PiNativeAndroidToolResult) : ContactsMutationPreparation
}
