package app.momoding.wire

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonPrimitive

sealed interface P1aServerFrame : P1bServerFrame

@Serializable
data class HelloAcceptedFrame(
    val protocolVersion: Int,
    val kind: String,
    val requestId: String,
    val connectionId: String,
    val serverVersion: String,
    val piVersion: String,
    val heartbeatIntervalMs: Int,
    val maxFrameBytes: Int,
) : P1aServerFrame {
    init {
        requireProtocolVersion(protocolVersion)
        requireKind(kind, "hello.accepted")
        requirePiVersion(piVersion)
        requireContract(heartbeatIntervalMs == P1aProtocol.HEARTBEAT_INTERVAL_MS) {
            "heartbeatIntervalMs must be ${P1aProtocol.HEARTBEAT_INTERVAL_MS}"
        }
        requireContract(maxFrameBytes == P1aProtocol.MAX_FRAME_BYTES) {
            "maxFrameBytes must be ${P1aProtocol.MAX_FRAME_BYTES}"
        }
    }
}

@Serializable
data class PiEventFrame(
    val protocolVersion: Int,
    val kind: String,
    val taskId: String,
    val piSessionId: String,
    val piVersion: String,
    val streamId: String,
    val sequence: Long,
    val emittedAt: String,
    val requestId: String? = null,
    val event: JsonObject,
) : P1aServerFrame {
    init {
        requireProtocolVersion(protocolVersion)
        requireKind(kind, "pi.event")
        requirePiVersion(piVersion)
        requireContract(sequence > 0) { "pi.event sequence must be positive" }
        val nativeType = event["type"] as? JsonPrimitive
        requireContract(nativeType?.isString == true && nativeType.content.isNotBlank()) {
            "pi.event must retain a non-empty native event.type"
        }
    }
}

@Serializable
data class CommandResponseFrame(
    val protocolVersion: Int,
    val kind: String,
    val requestId: String,
    val ok: Boolean,
    val data: JsonElement? = null,
    val error: WireErrorBody? = null,
) : P1aServerFrame {
    init {
        requireProtocolVersion(protocolVersion)
        requireKind(kind, "response")
        requireContract(if (ok) error == null else error != null && data == null) {
            "response must contain data or error according to ok"
        }
    }
}

@Serializable
data class WireErrorFrame(
    val protocolVersion: Int,
    val kind: String,
    val requestId: String? = null,
    val error: WireErrorBody,
) : P1aServerFrame {
    init {
        requireProtocolVersion(protocolVersion)
        requireKind(kind, "error")
    }
}

@Serializable
data class WireErrorBody(
    val code: WireErrorCode,
    val message: String,
    val retryable: Boolean,
)

@Serializable
enum class WireErrorCode {
    PROTOCOL_MISMATCH,
    UNAUTHORIZED,
    BAD_REQUEST,
    FRAME_TOO_LARGE,
    PAYLOAD_TOO_LARGE,
    TASK_NOT_FOUND,
    SESSION_BUSY,
    INVALID_SESSION_STATE,
    CURSOR_INVALID,
    REPLAY_EXPIRED,
    RECOVERY_REQUIRED,
    DEVICE_OFFLINE,
    TOOL_TIMEOUT,
    ABORTED,
}

@Serializable
data class TaskSnapshotFrame(
    val kind: String,
    val requestId: String? = null,
    val taskId: String,
    val snapshotVersion: Long,
    val recoveryState: RecoveryState,
    val runState: TaskRunState,
    val piSessionId: String,
    val pi: RawPiSnapshot,
    val pendingAttention: List<JsonElement>,
    val deviceCalls: List<SnapshotDeviceCall>,
    val cursor: StreamCursor,
) : P1aServerFrame {
    init {
        requireKind(kind, "task.snapshot")
        requireContract(snapshotVersion > 0) { "snapshotVersion must be positive" }
    }
}

@Serializable
enum class RecoveryState {
    @SerialName("normal")
    NORMAL,

    @SerialName("interrupted")
    INTERRUPTED,

    @SerialName("reconciling_device_calls")
    RECONCILING_DEVICE_CALLS,
}

@Serializable
enum class TaskRunState {
    @SerialName("idle")
    IDLE,

    @SerialName("starting")
    STARTING,

    @SerialName("running")
    RUNNING,

    @SerialName("waiting")
    WAITING,

    @SerialName("stopping")
    STOPPING,

    @SerialName("stopped")
    STOPPED,

    @SerialName("completed")
    COMPLETED,

    @SerialName("failed")
    FAILED,

    @SerialName("interrupted")
    INTERRUPTED,
}

@Serializable
data class RawPiSnapshot(
    val messages: List<JsonElement>,
    val isStreaming: Boolean,
    val queue: List<JsonElement>,
)

@Serializable
data class SnapshotDeviceCall(
    val callId: String,
    val operationId: String? = null,
    val state: String,
)

@Serializable
data class StreamCursor(
    val streamId: String,
    val highWatermarkSequence: Long,
    val oldestReplayableSequence: Long,
)

object P1aServerFrameDecoder {
    private val json = Json {
        ignoreUnknownKeys = false
        isLenient = false
        coerceInputValues = false
        explicitNulls = true
    }

    fun decode(text: String): P1aServerFrame {
        val actualBytes = text.encodeToByteArray().size
        if (actualBytes > P1aProtocol.MAX_FRAME_BYTES) {
            throw SerializationException(
                "P1A server frame exceeds ${P1aProtocol.MAX_FRAME_BYTES} UTF-8 bytes",
            )
        }
        val element = json.parseToJsonElement(text)
        val frame = element as? JsonObject
            ?: throw SerializationException("P1A server frame must be a JSON object")
        if (readFrameKind(frame) == "task.snapshot" && actualBytes > P1aProtocol.MAX_SNAPSHOT_BYTES) {
            throw SerializationException(
                "task.snapshot exceeds ${P1aProtocol.MAX_SNAPSHOT_BYTES} received UTF-8 bytes",
            )
        }
        return decode(frame)
    }

    fun decode(element: JsonElement): P1aServerFrame {
        val frame = element as? JsonObject
            ?: throw SerializationException("P1A server frame must be a JSON object")
        val actualFrameBytes = frame.toString().encodeToByteArray().size
        if (actualFrameBytes > P1aProtocol.MAX_FRAME_BYTES) {
            throw SerializationException(
                "P1A server frame exceeds ${P1aProtocol.MAX_FRAME_BYTES} UTF-8 bytes",
            )
        }
        val kind = readFrameKind(frame)

        return try {
            when (kind) {
                "hello.accepted" -> json.decodeFromJsonElement<HelloAcceptedFrame>(frame)
                "response" -> {
                    validateResponseShape(frame)
                    json.decodeFromJsonElement<CommandResponseFrame>(frame)
                }
                "error" -> {
                    validateOptionalString(frame, "requestId")
                    json.decodeFromJsonElement<WireErrorFrame>(frame)
                }
                "pi.event" -> {
                    validateOptionalString(frame, "requestId")
                    json.decodeFromJsonElement<PiEventFrame>(frame)
                }
                "task.snapshot" -> {
                    val actualBytes = frame.toString().encodeToByteArray().size
                    requireContract(actualBytes <= P1aProtocol.MAX_SNAPSHOT_BYTES) {
                        "task.snapshot exceeds ${P1aProtocol.MAX_SNAPSHOT_BYTES} UTF-8 bytes"
                    }
                    validateOptionalString(frame, "requestId")
                    validateSnapshotDeviceCalls(frame)
                    json.decodeFromJsonElement<TaskSnapshotFrame>(frame)
                }
                else -> throw SerializationException("Unsupported P1A server frame kind: $kind")
            }
        } catch (error: SerializationException) {
            throw error
        } catch (error: IllegalArgumentException) {
            throw SerializationException("Invalid $kind frame: ${error.message}", error)
        }
    }
}

private fun readFrameKind(frame: JsonObject): String {
    val kindPrimitive = frame["kind"] as? JsonPrimitive
    return kindPrimitive?.takeIf(JsonPrimitive::isString)?.contentOrNull
        ?: throw SerializationException("P1A server frame must contain a string kind")
}

private fun validateOptionalString(value: JsonObject, key: String) {
    if (!value.containsKey(key)) return
    val primitive = value[key] as? JsonPrimitive
    requireContract(primitive?.isString == true) { "$key must be a string when present" }
}

private fun validateSnapshotDeviceCalls(frame: JsonObject) {
    val calls = frame["deviceCalls"] as? JsonArray ?: return
    calls.forEachIndexed { index, element ->
        val call = element as? JsonObject ?: return@forEachIndexed
        try {
            validateOptionalString(call, "operationId")
        } catch (error: IllegalArgumentException) {
            throw IllegalArgumentException("deviceCalls[$index].${error.message}", error)
        }
    }
}

private fun validateResponseShape(frame: JsonObject) {
    val ok = frame["ok"]?.jsonPrimitive?.booleanOrNull
        ?: throw IllegalArgumentException("response ok must be a boolean")
    val hasData = frame.containsKey("data")
    val hasError = frame.containsKey("error")
    requireContract(if (ok) !hasError else hasError && !hasData) {
        "response must contain data or error according to ok"
    }
}

private fun requireProtocolVersion(actual: Int) {
    requireContract(actual == P1aProtocol.PROTOCOL_VERSION) {
        "protocolVersion must be ${P1aProtocol.PROTOCOL_VERSION}"
    }
}

private fun requirePiVersion(actual: String) {
    requireContract(actual == P1aProtocol.PI_VERSION) {
        "piVersion must be ${P1aProtocol.PI_VERSION}"
    }
}

private fun requireKind(actual: String, expected: String) {
    requireContract(actual == expected) { "kind must be $expected" }
}

private inline fun requireContract(condition: Boolean, message: () -> String) {
    if (!condition) throw IllegalArgumentException(message())
}
