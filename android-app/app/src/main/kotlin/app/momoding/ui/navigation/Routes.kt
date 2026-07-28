package app.momoding.ui.navigation

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

@Serializable
data object HostGateRoute : NavKey

@Serializable
data class ProviderSetupRoute(
    val onboarding: Boolean,
    val returnTaskId: String? = null,
) : NavKey {
    init {
        require(returnTaskId == null || returnTaskId.isNotBlank()) {
            "Provider return taskId is blank"
        }
        require(!onboarding || returnTaskId == null) {
            "Provider onboarding cannot return to a task"
        }
    }
}

@Serializable
data object TaskHomeRoute : NavKey

@Serializable
data class NewTaskRoute(
    val draftId: String,
    val fromAndroidShare: Boolean = false,
    val importNotice: String? = null,
) : NavKey {
    init {
        require(draftId.isNotBlank()) { "New Task draftId is blank" }
        require(importNotice == null || importNotice.length <= 240) {
            "New Task import notice is invalid"
        }
        require(importNotice == null || importNotice.none(Char::isISOControl)) {
            "New Task import notice contains control characters"
        }
        require(fromAndroidShare || importNotice == null) {
            "Only Android Share drafts can contain an import notice"
        }
    }
}

@Serializable
data class TaskDetailRoute(val taskId: String) : NavKey

@Serializable
data object SettingsRoute : NavKey

@Serializable
data object ExtensionsRoute : NavKey

@Serializable
data class AuthorizedFoldersRoute(
    val startPicker: Boolean = false,
    val pickerRunId: String? = null,
    val capabilityRequestId: String? = null,
) : NavKey

@Serializable
data class DeviceCapabilitiesRoute(
    val startFullAccessSetup: Boolean = false,
    val setupRunId: String? = null,
    val capabilityRequestId: String? = null,
    val requestedCapability: String? = null,
) : NavKey

@Serializable
data class AttentionRoute(
    val taskId: String,
    val callId: String,
    val originFocusKey: String,
) : NavKey {
    init {
        require(taskId.isNotBlank()) { "Attention taskId is blank" }
        require(callId.isNotBlank()) { "Attention callId is blank" }
        require(originFocusKey.isNotBlank()) { "Attention origin focus key is blank" }
    }
}

fun taskDetailAttentionFocusKey(taskId: String, callId: String): String {
    require(taskId.isNotBlank()) { "Attention taskId is blank" }
    require(callId.isNotBlank()) { "Attention callId is blank" }
    return "task-detail-attention:${taskId.length}:$taskId:${callId.length}:$callId"
}

@Serializable
data class OutputsPlaceholderRoute(val taskId: String) : NavKey

@Serializable
data class DiffPlaceholderRoute(val taskId: String) : NavKey

@Serializable
data class FileChangeRoute(
    val taskId: String,
    val commitCallId: String? = null,
) : NavKey {
    init {
        require(taskId.isNotBlank()) { "File-change taskId is blank" }
        require(commitCallId == null || commitCallId.isNotBlank()) {
            "File-change commitCallId is blank"
        }
    }
}
