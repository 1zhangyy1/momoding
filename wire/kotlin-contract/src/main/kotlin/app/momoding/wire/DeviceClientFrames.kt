package app.momoding.wire

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

const val DEVICE_CAPABILITY_VERSION: Long = 1

val DEVICE_CAPABILITY_MANIFEST: JsonObject
    get() = buildJsonObject {
        put(
            "tools",
            buildJsonArray {
                add("device_capabilities_get")
                add("device_files_list")
                add("device_files_read")
                add("device_files_prepare_changes")
                add("device_files_commit_changes")
                add("request_user_question")
                add("request_user_confirmation")
            },
        )
    }

@Deprecated("Use DEVICE_CAPABILITY_VERSION")
const val P2_ATTENTION_CAPABILITY_VERSION: Long = DEVICE_CAPABILITY_VERSION

@Deprecated("Use DEVICE_CAPABILITY_MANIFEST")
val P2_ATTENTION_CAPABILITY_MANIFEST: JsonObject
    get() = DEVICE_CAPABILITY_MANIFEST

data class DeviceCapabilitiesReportClientFrame(
    val requestId: String,
    val commandId: String,
    val deviceId: String,
    val expiresAt: String,
    val capabilityVersion: Long = DEVICE_CAPABILITY_VERSION,
    val manifest: JsonObject = DEVICE_CAPABILITY_MANIFEST,
)

enum class DeviceToolProgressPhase(val wireValue: String) {
    RECEIVED("received"),
    RUNNING("running"),
    AWAITING_USER("awaiting_user"),
    COMMITTING("committing"),
}

data class DeviceToolProgressClientFrame(
    val callId: String,
    val taskId: String,
    val deviceId: String,
    val progressSequence: Long,
    val phase: DeviceToolProgressPhase,
    val summary: String? = null,
)

enum class DeviceToolTerminalKind(val wireValue: String) {
    SUCCEEDED("succeeded"),
    FAILED("failed"),
    REJECTED("rejected"),
    CANCELLED("cancelled"),
    TIMED_OUT("timed_out"),
}

data class DeviceClientWireError(
    val code: String,
    val message: String,
)

data class DeviceToolResultClientFrame(
    val callId: String,
    val taskId: String,
    val deviceId: String,
    val terminal: DeviceToolTerminalKind,
    val result: JsonElement? = null,
    val error: DeviceClientWireError? = null,
)

enum class DeviceLedgerState(val wireValue: String) {
    NEVER_STARTED("never_started"),
    RUNNING("running"),
    SUCCEEDED("succeeded"),
    FAILED("failed"),
    CANCELLED("cancelled"),
    UNKNOWN("unknown"),
}

data class DeviceToolReconcileResultItem(
    val callId: String,
    val state: DeviceLedgerState,
    val resultSummary: JsonElement? = null,
    val error: DeviceClientWireError? = null,
    val operationId: String? = null,
)

data class DeviceToolReconcileResultClientFrame(
    val requestId: String,
    val commandId: String,
    val taskId: String,
    val deviceId: String,
    val results: List<DeviceToolReconcileResultItem>,
)
