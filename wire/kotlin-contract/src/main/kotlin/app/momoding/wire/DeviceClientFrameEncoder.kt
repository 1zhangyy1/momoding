package app.momoding.wire

import java.time.OffsetDateTime
import java.time.format.DateTimeParseException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

object DeviceClientFrameEncoder {
    fun encode(frame: DeviceCapabilitiesReportClientFrame): ByteArray {
        require(frame.capabilityVersion == DEVICE_CAPABILITY_VERSION) {
            "capabilityVersion differs from the device capability contract"
        }
        require(frame.manifest == DEVICE_CAPABILITY_MANIFEST) {
            "manifest differs from the device capability contract"
        }
        val requestId = normalizeWireString(frame.requestId, "requestId", 128)
        val commandId = normalizeUuid(frame.commandId, "commandId")
        val deviceId = normalizeWireString(frame.deviceId, "deviceId", 128)
        val expiresAt = normalizeRfc3339(frame.expiresAt, "expiresAt")
        return encodePhysicalFrame(
            buildJsonObject {
                put("protocolVersion", 1)
                put("kind", "device.capabilities.report")
                put("requestId", requestId)
                put("commandId", commandId)
                put("deviceId", deviceId)
                put("capabilityVersion", frame.capabilityVersion)
                put("manifest", DEVICE_CAPABILITY_MANIFEST)
                put("expiresAt", expiresAt)
            },
        )
    }

    fun encode(frame: DeviceToolProgressClientFrame): ByteArray {
        val callId = normalizeUuid(frame.callId, "callId")
        val taskId = normalizeUuid(frame.taskId, "taskId")
        val deviceId = normalizeWireString(frame.deviceId, "deviceId", 128)
        requireSafeInteger(frame.progressSequence, "progressSequence", 1)
        val summary = frame.summary?.let { normalizeWireString(it, "summary", 2_048) }
        return encodePhysicalFrame(
            buildJsonObject {
                put("protocolVersion", 1)
                put("kind", "device.tool.progress")
                put("callId", callId)
                put("taskId", taskId)
                put("deviceId", deviceId)
                put("progressSequence", frame.progressSequence)
                put("phase", frame.phase.wireValue)
                summary?.let { put("summary", it) }
            },
        )
    }

    fun encode(frame: DeviceToolResultClientFrame): ByteArray {
        val callId = normalizeUuid(frame.callId, "callId")
        val taskId = normalizeUuid(frame.taskId, "taskId")
        val deviceId = normalizeWireString(frame.deviceId, "deviceId", 128)
        when (frame.terminal) {
            DeviceToolTerminalKind.SUCCEEDED -> require(frame.result != null && frame.error == null) {
                "succeeded requires result only"
            }
            else -> require(frame.result == null && frame.error != null) {
                "non-success terminal requires error only"
            }
        }
        frame.result?.let { validateJsonValue(it, "result") }
        val error = frame.error?.normalize("error")
        return encodePhysicalFrame(
            buildJsonObject {
                put("protocolVersion", 1)
                put("kind", "device.tool.result")
                put("callId", callId)
                put("taskId", taskId)
                put("deviceId", deviceId)
                put("terminal", frame.terminal.wireValue)
                frame.result?.let { put("result", it) }
                error?.let { put("error", it) }
            },
        )
    }

    fun encode(frame: DeviceToolReconcileResultClientFrame): ByteArray {
        val requestId = normalizeWireString(frame.requestId, "requestId", 128)
        val commandId = normalizeUuid(frame.commandId, "commandId")
        val taskId = normalizeUuid(frame.taskId, "taskId")
        val deviceId = normalizeWireString(frame.deviceId, "deviceId", 128)
        require(frame.results.isNotEmpty() && frame.results.size <= P1bProtocol.MAX_DEVICE_ITEMS) {
            "results must contain 1-${P1bProtocol.MAX_DEVICE_ITEMS} items"
        }
        val normalizedCallIds = frame.results.mapIndexed { index, item ->
            normalizeUuid(item.callId, "results[$index].callId")
        }
        require(normalizedCallIds.toSet().size == normalizedCallIds.size) {
            "results repeats a callId"
        }
        val results = buildJsonArray {
            frame.results.forEachIndexed { index, item ->
                val resultSummary = item.resultSummary
                val error = item.error
                val operationId = item.operationId?.let {
                    normalizeUuid(it, "results[$index].operationId")
                }
                when (item.state) {
                    DeviceLedgerState.NEVER_STARTED,
                    DeviceLedgerState.RUNNING,
                    DeviceLedgerState.UNKNOWN,
                    -> require(resultSummary == null && error == null) {
                        "results[$index].${item.state.wireValue} cannot contain terminal payload"
                    }
                    DeviceLedgerState.SUCCEEDED -> require(resultSummary != null && error == null) {
                        "results[$index].succeeded requires resultSummary only"
                    }
                    DeviceLedgerState.FAILED,
                    DeviceLedgerState.CANCELLED,
                    -> require(resultSummary == null && error != null) {
                        "results[$index].${item.state.wireValue} requires error only"
                    }
                }
                resultSummary?.let { validateJsonValue(it, "results[$index].resultSummary") }
                val normalizedError = error?.normalize("results[$index].error")
                add(
                    buildJsonObject {
                        put("callId", normalizedCallIds[index])
                        operationId?.let { put("operationId", it) }
                        put("state", item.state.wireValue)
                        resultSummary?.let { put("resultSummary", it) }
                        normalizedError?.let { put("error", it) }
                    },
                )
            }
        }
        return encodePhysicalFrame(
            buildJsonObject {
                put("protocolVersion", 1)
                put("kind", "device.tool.reconcile.result")
                put("requestId", requestId)
                put("commandId", commandId)
                put("taskId", taskId)
                put("deviceId", deviceId)
                put("results", results)
            },
        )
    }

    private fun DeviceClientWireError.normalize(field: String): JsonObject {
        val normalizedCode = normalizeWireString(code, "$field.code", 128)
        val normalizedMessage = normalizeWireString(message, "$field.message", 2_048)
        return buildJsonObject {
            put("code", normalizedCode)
            put("message", normalizedMessage)
            put("retryable", false)
        }
    }

    private fun encodePhysicalFrame(frame: JsonObject): ByteArray {
        val bytes = frame.toString().encodeToByteArray()
        require(bytes.size <= P1aProtocol.MAX_FRAME_BYTES) {
            "Physical frame exceeds ${P1aProtocol.MAX_FRAME_BYTES} bytes"
        }
        return bytes
    }
}

private fun normalizeWireString(value: String, field: String, maximumLength: Int): String {
    val normalized = value.trim()
    require(normalized.isNotEmpty() && normalized.length <= maximumLength) {
        "$field must contain 1-$maximumLength characters"
    }
    return normalized
}

private fun normalizeUuid(value: String, field: String): String {
    val normalized = normalizeWireString(value, field, 36)
    require(UUID_PATTERN.matches(normalized)) { "$field must be a UUID" }
    return normalized.lowercase()
}

private fun normalizeRfc3339(value: String, field: String): String {
    val normalized = normalizeWireString(value, field, 64)
    require(RFC3339_PATTERN.matches(normalized)) { "$field must be an RFC 3339 timestamp" }
    try {
        OffsetDateTime.parse(normalized)
    } catch (error: DateTimeParseException) {
        throw IllegalArgumentException("$field must be an RFC 3339 timestamp", error)
    }
    return normalized
}

private fun requireSafeInteger(
    value: Long,
    field: String,
    minimum: Long,
    maximum: Long = P1bProtocol.MAX_SAFE_INTEGER,
) {
    require(value in minimum..maximum) {
        "$field must be an integer from $minimum through $maximum"
    }
}

private fun validateJsonValue(value: JsonElement, field: String, depth: Int = 0) {
    require(depth <= P1bProtocol.MAX_JSON_DEPTH) {
        "$field exceeds JSON depth ${P1bProtocol.MAX_JSON_DEPTH}"
    }
    when (value) {
        JsonNull -> Unit
        is JsonArray -> value.forEachIndexed { index, child ->
            validateJsonValue(child, "$field[$index]", depth + 1)
        }
        is JsonObject -> value.forEach { (key, child) ->
            validateJsonValue(child, "$field.$key", depth + 1)
        }
        is JsonPrimitive -> {
            if (!value.isString && value.booleanOrNull == null) {
                val number = value.content.toDoubleOrNull()
                require(number != null && number.isFinite()) { "$field must contain JSON data" }
            }
        }
    }
}

private val UUID_PATTERN = Regex(
    "^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$",
    RegexOption.IGNORE_CASE,
)

private val RFC3339_PATTERN = Regex(
    "^(\\d{4})-(\\d{2})-(\\d{2})T(\\d{2}):(\\d{2}):(\\d{2})(?:\\.\\d{3})?(Z|[+-]\\d{2}:\\d{2})$",
)
