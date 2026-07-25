package app.momoding.wire

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Complete Host-to-client Reliability Wire union.
 *
 * Core frames extend this interface without changing their public Core decoder.
 * Pi events, messages, queues, attention payloads and device arguments remain
 * raw JSON rather than being copied into a second Agent event model.
 */
sealed interface ReliabilityServerFrame

class ReceivedReliabilityServerFrame(
    val frame: ReliabilityServerFrame,
    rawBytes: ByteArray,
) {
    private val retainedRawBytes = rawBytes.copyOf()

    val rawBytes: ByteArray
        get() = retainedRawBytes.copyOf()

    val byteLength: Int
        get() = retainedRawBytes.size
}

data class PiReplayCompleteFrame(
    val protocolVersion: Int,
    val kind: String,
    val taskId: String,
    val streamId: String,
    val replayedThroughSequence: Long,
    val liveFromSequence: Long,
) : ReliabilityServerFrame

enum class PiResyncReason {
    STREAM_CHANGED,
    CURSOR_EXPIRED,
    CURSOR_INVALID,
}

data class PiResyncRequiredFrame(
    val protocolVersion: Int,
    val kind: String,
    val taskId: String,
    val reason: PiResyncReason,
    val requestedStreamId: String,
    val currentStreamId: String,
    val snapshotVersion: Long,
) : ReliabilityServerFrame

sealed interface TransportChunkStartFrame : ReliabilityServerFrame {
    val protocolVersion: Int
    val kind: String
    val transferId: String
    val taskId: String
    val totalBytes: Long
    val sha256: String
    val chunkCount: Int
}

data class PiEventChunkStartFrame(
    override val protocolVersion: Int,
    override val kind: String,
    override val transferId: String,
    val contentKind: String,
    override val taskId: String,
    val streamId: String,
    val sequence: Long,
    override val totalBytes: Long,
    override val sha256: String,
    override val chunkCount: Int,
) : TransportChunkStartFrame

data class SnapshotPageChunkStartFrame(
    override val protocolVersion: Int,
    override val kind: String,
    override val transferId: String,
    val contentKind: String,
    override val taskId: String,
    val snapshotVersion: Long,
    val pageIndex: Int,
    override val totalBytes: Long,
    override val sha256: String,
    override val chunkCount: Int,
) : TransportChunkStartFrame

data class TransportChunkDataFrame(
    val protocolVersion: Int,
    val kind: String,
    val transferId: String,
    val chunkIndex: Int,
    val data: String,
) : ReliabilityServerFrame

data class TransportChunkEndFrame(
    val protocolVersion: Int,
    val kind: String,
    val transferId: String,
) : ReliabilityServerFrame

enum class SnapshotTransferMode {
    REPLACE,
    PREPEND_HISTORY,
}

data class SnapshotWindow(
    val messageStartIndex: Long,
    val messageEndExclusive: Long,
    val hasMoreBefore: Boolean,
    val historyCursor: String? = null,
)

data class TaskSnapshotBeginFrame(
    val protocolVersion: Int,
    val kind: String,
    val requestId: String? = null,
    val transferMode: SnapshotTransferMode,
    val taskId: String,
    val snapshotVersion: Long,
    val recoveryState: RecoveryState,
    val runState: TaskRunState,
    val piSessionId: String,
    val isStreaming: Boolean,
    val queue: List<JsonElement>,
    val pendingAttention: List<JsonElement>,
    val deviceCalls: List<SnapshotDeviceCall>,
    val cursor: StreamCursor,
    val totalMessages: Long,
    val window: SnapshotWindow,
) : ReliabilityServerFrame

data class TaskSnapshotPageFrame(
    val protocolVersion: Int,
    val kind: String,
    val taskId: String,
    val snapshotVersion: Long,
    val pageIndex: Int,
    val messageStartIndex: Long,
    val messages: List<JsonElement>,
) : ReliabilityServerFrame

data class TaskSnapshotEndFrame(
    val protocolVersion: Int,
    val kind: String,
    val taskId: String,
    val snapshotVersion: Long,
    val pageCount: Int,
    val sha256: String,
) : ReliabilityServerFrame

data class DeviceToolRequestFrame(
    val protocolVersion: Int,
    val kind: String,
    val callId: String,
    val taskId: String,
    val piToolCallId: String,
    val deviceId: String,
    val toolName: String,
    val arguments: JsonElement,
    val sideEffect: Boolean,
    val operationId: String? = null,
    val expiresAt: String,
    val capabilityVersion: Long,
) : ReliabilityServerFrame

enum class DeviceToolCancelReason {
    SESSION_STOP,
    TOOL_ABORT,
    TIMEOUT,
    HOST_SHUTDOWN,
}

data class DeviceToolCancelFrame(
    val protocolVersion: Int,
    val kind: String,
    val callId: String,
    val taskId: String,
    val reason: DeviceToolCancelReason,
) : ReliabilityServerFrame

enum class DeviceHostCallState {
    CREATED,
    SENT,
    RUNNING,
    TERMINAL,
    RECONCILED,
}

data class DeviceToolReconcileCall(
    val callId: String,
    val operationId: String? = null,
    val toolName: String,
    val lastKnownState: DeviceHostCallState,
)

data class DeviceToolReconcileRequestFrame(
    val protocolVersion: Int,
    val kind: String,
    val requestId: String,
    val taskId: String,
    val deviceId: String,
    val calls: List<DeviceToolReconcileCall>,
) : ReliabilityServerFrame

data class PiEventAckFrame(
    val taskId: String,
    val streamId: String,
    val throughSequence: Long,
)

object PiEventAckEncoder {
    fun encode(frame: PiEventAckFrame): ByteArray =
        ReliabilityContractDecoder.encodeAck(frame)
}
