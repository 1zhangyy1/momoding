package app.momoding.wire

import java.math.BigDecimal
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException
import java.util.Base64
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.put

object ReliabilityContractDecoder {
    private val json = Json {
        ignoreUnknownKeys = false
        isLenient = false
        coerceInputValues = false
        explicitNulls = true
    }

    fun decode(bytes: ByteArray): ReceivedReliabilityServerFrame {
        requirePhysicalLimit(bytes.size, CoreProtocol.MAX_FRAME_BYTES, "Reliability server frame")
        val text = try {
            bytes.decodeToString(throwOnInvalidSequence = true)
        } catch (error: CharacterCodingException) {
            throw SerializationException("Reliability server frame must be valid UTF-8", error)
        }
        val element = try {
            json.parseToJsonElement(text)
        } catch (error: SerializationException) {
            throw error
        } catch (error: IllegalArgumentException) {
            throw SerializationException("Reliability server frame must contain valid JSON", error)
        }
        val objectValue = element as? JsonObject
            ?: throw SerializationException("Reliability server frame must be a JSON object")
        val kind = readKind(objectValue)

        val frame = if (kind in CORE_SERVER_KINDS) {
            parseCoreFrame(objectValue, kind, text)
        } else {
            parseExtension(objectValue, kind, bytes.size)
        }
        return ReceivedReliabilityServerFrame(frame, bytes)
    }

    fun decode(text: String): ReceivedReliabilityServerFrame = decode(text.encodeToByteArray())

    /**
     * Decodes exact logical bytes after a transport chunk transfer has passed
     * count, length and SHA validation. This deliberately bypasses physical
     * WebSocket frame limits while retaining every Wire/opaque validation.
     */
    internal fun decodeReassembled(
        bytes: ByteArray,
        contentKind: String,
    ): ReceivedReliabilityServerFrame {
        requirePhysicalLimit(
            bytes.size,
            ReliabilityProtocol.CHUNK_MAX_TRANSFER_BYTES,
            "Reassembled logical frame",
        )
        val directThreshold = when (contentKind) {
            "pi.event" -> ReliabilityProtocol.DIRECT_PI_EVENT_MAX_BYTES
            "task.snapshot.page" -> ReliabilityProtocol.SNAPSHOT_PAGE_MAX_PHYSICAL_BYTES
            else -> throw SerializationException("Unsupported chunk contentKind: $contentKind")
        }
        if (bytes.size <= directThreshold) {
            throw SerializationException(
                "Reassembled $contentKind must exceed its $directThreshold-byte direct threshold",
            )
        }

        val text = try {
            bytes.decodeToString(throwOnInvalidSequence = true)
        } catch (error: CharacterCodingException) {
            throw SerializationException("Reassembled logical frame must be valid UTF-8", error)
        }
        val frame = try {
            json.parseToJsonElement(text) as? JsonObject
                ?: throw SerializationException("Reassembled logical frame must be a JSON object")
        } catch (error: SerializationException) {
            throw error
        } catch (error: IllegalArgumentException) {
            throw SerializationException("Reassembled logical frame must contain valid JSON", error)
        }
        val kind = readKind(frame)
        if (kind != contentKind) {
            throw SerializationException(
                "Reassembled content kind $kind does not match declared $contentKind",
            )
        }

        val decoded = when (contentKind) {
            "pi.event" -> parseReassembledPiEvent(frame)
            "task.snapshot.page" -> parseExtension(frame, kind, physicalBytes = null)
            else -> error("contentKind checked above")
        }
        return ReceivedReliabilityServerFrame(decoded, bytes)
    }

    internal fun encodeAck(frame: PiEventAckFrame): ByteArray {
        val taskId = requireUuidValue(frame.taskId, "taskId")
        val streamId = requireUuidValue(frame.streamId, "streamId")
        requireSafeIntegerValue(frame.throughSequence, "throughSequence", 0)
        return buildJsonObject {
            put("protocolVersion", CoreProtocol.PROTOCOL_VERSION)
            put("kind", "pi.event.ack")
            put("taskId", taskId)
            put("streamId", streamId)
            put("throughSequence", frame.throughSequence)
        }.toString().encodeToByteArray()
    }

    private fun parseCoreFrame(
        frame: JsonObject,
        kind: String,
        exactText: String,
    ): CoreServerFrame = try {
        when (kind) {
            "hello.accepted" -> {
                frame.requireExactKeys(
                    "protocolVersion",
                    "kind",
                    "requestId",
                    "connectionId",
                    "serverVersion",
                    "piVersion",
                    "heartbeatIntervalMs",
                    "maxFrameBytes",
                )
                requireProtocol(frame)
            }
            "response" -> {
                frame.requireExactKeys(
                    "protocolVersion",
                    "kind",
                    "requestId",
                    "ok",
                    optional = setOf("data", "error"),
                )
                requireProtocol(frame)
                frame["data"]?.let { validateJsonValue(it, "data") }
            }
            "error" -> {
                frame.requireExactKeys(
                    "protocolVersion",
                    "kind",
                    "error",
                    optional = setOf("requestId"),
                )
                requireProtocol(frame)
            }
            "pi.event" -> validateDirectPiEvent(frame)
            "task.snapshot" -> validateDirectTaskSnapshot(frame)
            else -> throw SerializationException("Unsupported Core server frame kind: $kind")
        }
        CoreServerFrameDecoder.decode(exactText)
    } catch (error: SerializationException) {
        throw error
    } catch (error: IllegalArgumentException) {
        throw SerializationException("Invalid $kind frame: ${error.message}", error)
    }

    private fun validateDirectPiEvent(frame: JsonObject) {
        frame.requireExactKeys(
            "protocolVersion",
            "kind",
            "taskId",
            "piSessionId",
            "piVersion",
            "streamId",
            "sequence",
            "emittedAt",
            "event",
            optional = setOf("requestId"),
        )
        requireProtocol(frame)
        frame.requireUuid("taskId")
        frame.requireUuid("piSessionId")
        frame.requireUuid("streamId")
        frame.requireSafeInteger("sequence", 1)
        frame.requireRfc3339("emittedAt")
        frame.optionalString("requestId", 128)
        val event = frame.required("event") as? JsonObject
            ?: throw IllegalArgumentException("event must be an object")
        validateJsonValue(event, "event")
    }

    private fun parseReassembledPiEvent(frame: JsonObject): PiEventFrame = try {
        validateDirectPiEvent(frame)
        json.decodeFromJsonElement<PiEventFrame>(frame)
    } catch (error: SerializationException) {
        throw error
    } catch (error: IllegalArgumentException) {
        throw SerializationException("Invalid pi.event frame: ${error.message}", error)
    }

    private fun validateDirectTaskSnapshot(frame: JsonObject) {
        frame.requireExactKeys(
            "kind",
            "taskId",
            "snapshotVersion",
            "recoveryState",
            "runState",
            "piSessionId",
            "pi",
            "pendingAttention",
            "deviceCalls",
            "cursor",
            optional = setOf("requestId"),
        )
        frame.requireUuid("taskId")
        frame.requireSafeInteger("snapshotVersion", 1)
        frame.requireRecoveryState("recoveryState")
        frame.requireTaskRunState("runState")
        frame.requireUuid("piSessionId")

        val pi = frame.required("pi") as? JsonObject
            ?: throw IllegalArgumentException("pi must be an object")
        pi.requireExactKeys("messages", "isStreaming", "queue")
        pi.requireJsonArray("messages")
        pi.requireBoolean("isStreaming")
        pi.requireJsonArray("queue")

        frame.requireJsonArray("pendingAttention")
        frame.requireSnapshotDeviceCalls("deviceCalls")
        frame.requireStreamCursor("cursor")
    }

    private fun parseExtension(
        frame: JsonObject,
        kind: String,
        physicalBytes: Int?,
    ): ReliabilityServerFrame {
        val extensionPhysicalLimit = when (kind) {
            "task.snapshot.page" -> ReliabilityProtocol.SNAPSHOT_PAGE_MAX_PHYSICAL_BYTES
            "transport.chunk.data" -> ReliabilityProtocol.CHUNK_MAX_PHYSICAL_FRAME_BYTES
            else -> null
        }
        if (extensionPhysicalLimit != null && physicalBytes != null) {
            requirePhysicalLimit(
                physicalBytes,
                extensionPhysicalLimit,
                kind,
            )
        }
        return try {
            requireProtocol(frame)
            when (kind) {
                "pi.replay.complete" -> parseReplayComplete(frame)
                "pi.resync_required" -> parseResyncRequired(frame)
                "transport.chunk.start" -> parseChunkStart(frame)
                "transport.chunk.data" -> parseChunkData(frame)
                "transport.chunk.end" -> parseChunkEnd(frame)
                "task.snapshot.begin" -> parseSnapshotBegin(frame)
                "task.snapshot.page" -> parseSnapshotPage(frame)
                "task.snapshot.end" -> parseSnapshotEnd(frame)
                "device.tool.request" -> parseDeviceToolRequest(frame)
                "device.tool.cancel" -> parseDeviceToolCancel(frame)
                "device.tool.reconcile.request" -> parseDeviceReconcileRequest(frame)
                else -> throw SerializationException("Unsupported Reliability server frame kind: $kind")
            }
        } catch (error: SerializationException) {
            throw error
        } catch (error: IllegalArgumentException) {
            throw SerializationException("Invalid $kind frame: ${error.message}", error)
        }
    }

    private fun parseReplayComplete(frame: JsonObject): PiReplayCompleteFrame {
        frame.requireExactKeys(
            "protocolVersion",
            "kind",
            "taskId",
            "streamId",
            "replayedThroughSequence",
            "liveFromSequence",
        )
        val replayed = frame.requireSafeInteger("replayedThroughSequence", 0)
        val liveFrom = frame.requireSafeInteger("liveFromSequence", 1)
        requireContract(liveFrom == replayed + 1) {
            "liveFromSequence must immediately follow replayedThroughSequence"
        }
        return PiReplayCompleteFrame(
            protocolVersion = CoreProtocol.PROTOCOL_VERSION,
            kind = "pi.replay.complete",
            taskId = frame.requireUuid("taskId"),
            streamId = frame.requireUuid("streamId"),
            replayedThroughSequence = replayed,
            liveFromSequence = liveFrom,
        )
    }

    private fun parseResyncRequired(frame: JsonObject): PiResyncRequiredFrame {
        frame.requireExactKeys(
            "protocolVersion",
            "kind",
            "taskId",
            "reason",
            "requestedStreamId",
            "currentStreamId",
            "snapshotVersion",
        )
        val reason = when (frame.requireLiteralString("reason")) {
            "stream_changed" -> PiResyncReason.STREAM_CHANGED
            "cursor_expired" -> PiResyncReason.CURSOR_EXPIRED
            "cursor_invalid" -> PiResyncReason.CURSOR_INVALID
            else -> throw IllegalArgumentException("reason is not supported")
        }
        return PiResyncRequiredFrame(
            protocolVersion = CoreProtocol.PROTOCOL_VERSION,
            kind = "pi.resync_required",
            taskId = frame.requireUuid("taskId"),
            reason = reason,
            requestedStreamId = frame.requireUuid("requestedStreamId"),
            currentStreamId = frame.requireUuid("currentStreamId"),
            snapshotVersion = frame.requireSafeInteger("snapshotVersion", 1),
        )
    }

    private fun parseChunkStart(frame: JsonObject): TransportChunkStartFrame {
        val contentKind = frame.requireLiteralString("contentKind")
        val commonRequired = arrayOf(
            "protocolVersion",
            "kind",
            "transferId",
            "contentKind",
            "taskId",
            "totalBytes",
            "sha256",
            "chunkCount",
        )
        val transferId = frame.requireUuid("transferId")
        val taskId = frame.requireUuid("taskId")
        val totalBytes = frame.requireSafeInteger(
            "totalBytes",
            1,
            ReliabilityProtocol.CHUNK_MAX_TRANSFER_BYTES.toLong(),
        )
        val sha256 = frame.requireSha256("sha256")
        val chunkCount = frame.requireSafeInteger(
            "chunkCount",
            1,
            ReliabilityProtocol.CHUNK_MAX_COUNT.toLong(),
        ).toInt()

        return when (contentKind) {
            "pi.event" -> {
                frame.requireExactKeys(
                    *(commonRequired + arrayOf("streamId", "sequence")),
                )
                requireContract(totalBytes > ReliabilityProtocol.DIRECT_PI_EVENT_MAX_BYTES) {
                    "pi.event chunk totalBytes must exceed ${ReliabilityProtocol.DIRECT_PI_EVENT_MAX_BYTES}"
                }
                PiEventChunkStartFrame(
                    protocolVersion = CoreProtocol.PROTOCOL_VERSION,
                    kind = "transport.chunk.start",
                    transferId = transferId,
                    contentKind = contentKind,
                    taskId = taskId,
                    streamId = frame.requireUuid("streamId"),
                    sequence = frame.requireSafeInteger("sequence", 1),
                    totalBytes = totalBytes,
                    sha256 = sha256,
                    chunkCount = chunkCount,
                )
            }
            "task.snapshot.page" -> {
                frame.requireExactKeys(
                    *(commonRequired + arrayOf("snapshotVersion", "pageIndex")),
                )
                requireContract(totalBytes > ReliabilityProtocol.SNAPSHOT_PAGE_MAX_PHYSICAL_BYTES) {
                    "task.snapshot.page chunk totalBytes must exceed ${ReliabilityProtocol.SNAPSHOT_PAGE_MAX_PHYSICAL_BYTES}"
                }
                SnapshotPageChunkStartFrame(
                    protocolVersion = CoreProtocol.PROTOCOL_VERSION,
                    kind = "transport.chunk.start",
                    transferId = transferId,
                    contentKind = contentKind,
                    taskId = taskId,
                    snapshotVersion = frame.requireSafeInteger("snapshotVersion", 1),
                    pageIndex = frame.requireSafeInteger(
                        "pageIndex",
                        0,
                        (ReliabilityProtocol.SNAPSHOT_MAX_PAGES - 1).toLong(),
                    ).toInt(),
                    totalBytes = totalBytes,
                    sha256 = sha256,
                    chunkCount = chunkCount,
                )
            }
            else -> throw IllegalArgumentException("contentKind is not supported")
        }
    }

    private fun parseChunkData(frame: JsonObject): TransportChunkDataFrame {
        frame.requireExactKeys(
            "protocolVersion",
            "kind",
            "transferId",
            "chunkIndex",
            "data",
        )
        return TransportChunkDataFrame(
            protocolVersion = CoreProtocol.PROTOCOL_VERSION,
            kind = "transport.chunk.data",
            transferId = frame.requireUuid("transferId"),
            chunkIndex = frame.requireSafeInteger(
                "chunkIndex",
                0,
                (ReliabilityProtocol.CHUNK_MAX_COUNT - 1).toLong(),
            ).toInt(),
            data = frame.requireCanonicalBase64("data"),
        )
    }

    private fun parseChunkEnd(frame: JsonObject): TransportChunkEndFrame {
        frame.requireExactKeys("protocolVersion", "kind", "transferId")
        return TransportChunkEndFrame(
            protocolVersion = CoreProtocol.PROTOCOL_VERSION,
            kind = "transport.chunk.end",
            transferId = frame.requireUuid("transferId"),
        )
    }

    private fun parseSnapshotBegin(frame: JsonObject): TaskSnapshotBeginFrame {
        frame.requireExactKeys(
            "protocolVersion",
            "kind",
            "transferMode",
            "taskId",
            "snapshotVersion",
            "recoveryState",
            "runState",
            "piSessionId",
            "isStreaming",
            "queue",
            "pendingAttention",
            "deviceCalls",
            "cursor",
            "totalMessages",
            "window",
            optional = setOf("requestId"),
        )
        val totalMessages = frame.requireSafeInteger("totalMessages", 0)
        return TaskSnapshotBeginFrame(
            protocolVersion = CoreProtocol.PROTOCOL_VERSION,
            kind = "task.snapshot.begin",
            requestId = frame.optionalString("requestId", 128),
            transferMode = when (frame.requireLiteralString("transferMode")) {
                "replace" -> SnapshotTransferMode.REPLACE
                "prepend_history" -> SnapshotTransferMode.PREPEND_HISTORY
                else -> throw IllegalArgumentException("transferMode is not supported")
            },
            taskId = frame.requireUuid("taskId"),
            snapshotVersion = frame.requireSafeInteger("snapshotVersion", 1),
            recoveryState = frame.requireRecoveryState("recoveryState"),
            runState = frame.requireTaskRunState("runState"),
            piSessionId = frame.requireUuid("piSessionId"),
            isStreaming = frame.requireBoolean("isStreaming"),
            queue = frame.requireJsonArray("queue"),
            pendingAttention = frame.requireJsonArray("pendingAttention"),
            deviceCalls = frame.requireSnapshotDeviceCalls("deviceCalls"),
            cursor = frame.requireStreamCursor("cursor"),
            totalMessages = totalMessages,
            window = frame.requireSnapshotWindow("window", totalMessages),
        )
    }

    private fun parseSnapshotPage(frame: JsonObject): TaskSnapshotPageFrame {
        frame.requireExactKeys(
            "protocolVersion",
            "kind",
            "taskId",
            "snapshotVersion",
            "pageIndex",
            "messageStartIndex",
            "messages",
        )
        return TaskSnapshotPageFrame(
            protocolVersion = CoreProtocol.PROTOCOL_VERSION,
            kind = "task.snapshot.page",
            taskId = frame.requireUuid("taskId"),
            snapshotVersion = frame.requireSafeInteger("snapshotVersion", 1),
            pageIndex = frame.requireSafeInteger(
                "pageIndex",
                0,
                (ReliabilityProtocol.SNAPSHOT_MAX_PAGES - 1).toLong(),
            ).toInt(),
            messageStartIndex = frame.requireSafeInteger("messageStartIndex", 0),
            messages = frame.requireJsonArray("messages"),
        )
    }

    private fun parseSnapshotEnd(frame: JsonObject): TaskSnapshotEndFrame {
        frame.requireExactKeys(
            "protocolVersion",
            "kind",
            "taskId",
            "snapshotVersion",
            "pageCount",
            "sha256",
        )
        return TaskSnapshotEndFrame(
            protocolVersion = CoreProtocol.PROTOCOL_VERSION,
            kind = "task.snapshot.end",
            taskId = frame.requireUuid("taskId"),
            snapshotVersion = frame.requireSafeInteger("snapshotVersion", 1),
            pageCount = frame.requireSafeInteger(
                "pageCount",
                1,
                ReliabilityProtocol.SNAPSHOT_MAX_PAGES.toLong(),
            ).toInt(),
            sha256 = frame.requireSha256("sha256"),
        )
    }

    private fun parseDeviceToolRequest(frame: JsonObject): DeviceToolRequestFrame {
        frame.requireExactKeys(
            "protocolVersion",
            "kind",
            "callId",
            "taskId",
            "piToolCallId",
            "deviceId",
            "toolName",
            "arguments",
            "sideEffect",
            "expiresAt",
            "capabilityVersion",
            optional = setOf("operationId"),
        )
        val sideEffect = frame.requireBoolean("sideEffect")
        val operationId = frame.optionalUuid("operationId")
        requireContract(sideEffect == (operationId != null)) {
            "operationId must be present exactly when sideEffect is true"
        }
        val arguments = frame.required("arguments")
        validateJsonValue(arguments, "arguments")
        return DeviceToolRequestFrame(
            protocolVersion = CoreProtocol.PROTOCOL_VERSION,
            kind = "device.tool.request",
            callId = frame.requireUuid("callId"),
            taskId = frame.requireUuid("taskId"),
            piToolCallId = frame.requireString("piToolCallId", 256),
            deviceId = frame.requireString("deviceId", 128),
            toolName = frame.requireString("toolName", 128),
            arguments = arguments,
            sideEffect = sideEffect,
            operationId = operationId,
            expiresAt = frame.requireRfc3339("expiresAt"),
            capabilityVersion = frame.requireSafeInteger("capabilityVersion", 1),
        )
    }

    private fun parseDeviceToolCancel(frame: JsonObject): DeviceToolCancelFrame {
        frame.requireExactKeys("protocolVersion", "kind", "callId", "taskId", "reason")
        val reason = when (frame.requireLiteralString("reason")) {
            "session_stop" -> DeviceToolCancelReason.SESSION_STOP
            "tool_abort" -> DeviceToolCancelReason.TOOL_ABORT
            "timeout" -> DeviceToolCancelReason.TIMEOUT
            "host_shutdown" -> DeviceToolCancelReason.HOST_SHUTDOWN
            else -> throw IllegalArgumentException("reason is not supported")
        }
        return DeviceToolCancelFrame(
            protocolVersion = CoreProtocol.PROTOCOL_VERSION,
            kind = "device.tool.cancel",
            callId = frame.requireUuid("callId"),
            taskId = frame.requireUuid("taskId"),
            reason = reason,
        )
    }

    private fun parseDeviceReconcileRequest(frame: JsonObject): DeviceToolReconcileRequestFrame {
        frame.requireExactKeys(
            "protocolVersion",
            "kind",
            "requestId",
            "taskId",
            "deviceId",
            "calls",
        )
        val requestId = frame.requireString("requestId", 128)
        val callsValue = frame.required("calls") as? JsonArray
            ?: throw IllegalArgumentException("calls must be an array")
        requireContract(callsValue.isNotEmpty() && callsValue.size <= ReliabilityProtocol.MAX_DEVICE_ITEMS) {
            "calls must contain 1-${ReliabilityProtocol.MAX_DEVICE_ITEMS} items"
        }
        val calls = callsValue.mapIndexed { index, value ->
            val call = value as? JsonObject
                ?: throw IllegalArgumentException("calls[$index] must be an object")
            call.requireExactKeys(
                "callId",
                "toolName",
                "lastKnownState",
                optional = setOf("operationId"),
            )
            DeviceToolReconcileCall(
                callId = call.requireUuid("callId"),
                operationId = call.optionalUuid("operationId"),
                toolName = call.requireString("toolName", 128),
                lastKnownState = when (call.requireLiteralString("lastKnownState")) {
                    "created" -> DeviceHostCallState.CREATED
                    "sent" -> DeviceHostCallState.SENT
                    "running" -> DeviceHostCallState.RUNNING
                    "terminal" -> DeviceHostCallState.TERMINAL
                    "reconciled" -> DeviceHostCallState.RECONCILED
                    else -> throw IllegalArgumentException(
                        "calls[$index].lastKnownState is not supported",
                    )
                },
            )
        }
        return DeviceToolReconcileRequestFrame(
            protocolVersion = CoreProtocol.PROTOCOL_VERSION,
            kind = "device.tool.reconcile.request",
            requestId = requestId,
            taskId = frame.requireUuid("taskId"),
            deviceId = frame.requireString("deviceId", 128),
            calls = calls,
        )
    }

    private fun requireProtocol(frame: JsonObject) {
        val protocolVersion = frame.requireSafeInteger("protocolVersion", 1, 1)
        requireContract(protocolVersion == CoreProtocol.PROTOCOL_VERSION.toLong()) {
            "protocolVersion must be ${CoreProtocol.PROTOCOL_VERSION}"
        }
    }
}

private fun readKind(frame: JsonObject): String {
    val primitive = frame["kind"] as? JsonPrimitive
    return primitive?.takeIf(JsonPrimitive::isString)?.contentOrNull
        ?: throw SerializationException("Reliability server frame must contain a string kind")
}

private fun requirePhysicalLimit(actual: Int, maximum: Int, label: String) {
    if (actual > maximum) {
        throw SerializationException("$label exceeds $maximum UTF-8 bytes")
    }
}

private fun JsonObject.requireExactKeys(
    vararg required: String,
    optional: Set<String> = emptySet(),
) {
    val requiredSet = required.toSet()
    val allowed = requiredSet + optional
    val unexpected = keys.firstOrNull { it !in allowed }
    requireContract(unexpected == null) { "Unexpected field: $unexpected" }
    val missing = requiredSet.firstOrNull { it !in this }
    requireContract(missing == null) { "Missing field: $missing" }
}

private fun JsonObject.required(key: String): JsonElement =
    get(key) ?: throw IllegalArgumentException("Missing field: $key")

private fun JsonObject.requireLiteralString(key: String): String {
    val primitive = required(key) as? JsonPrimitive
    requireContract(primitive?.isString == true) { "$key must be a string" }
    return primitive!!.content
}

private fun JsonObject.requireString(key: String, maximumLength: Int): String {
    val normalized = requireLiteralString(key).trim()
    requireContract(normalized.isNotEmpty() && normalized.length <= maximumLength) {
        "$key must contain 1-$maximumLength characters"
    }
    return normalized
}

private fun JsonObject.optionalString(key: String, maximumLength: Int): String? {
    if (key !in this) return null
    return requireString(key, maximumLength)
}

private fun JsonObject.requireBoolean(key: String): Boolean {
    val primitive = required(key) as? JsonPrimitive
    return primitive?.booleanOrNull
        ?: throw IllegalArgumentException("$key must be a boolean")
}

private fun JsonObject.requireSafeInteger(
    key: String,
    minimum: Long,
    maximum: Long = ReliabilityProtocol.MAX_SAFE_INTEGER,
): Long {
    val primitive = required(key) as? JsonPrimitive
    requireContract(primitive != null && !primitive.isString) { "$key must be an integer" }
    val decimal = try {
        BigDecimal(primitive!!.content)
    } catch (error: NumberFormatException) {
        throw IllegalArgumentException("$key must be an integer", error)
    }
    val value = try {
        decimal.toBigIntegerExact().longValueExact()
    } catch (error: ArithmeticException) {
        throw IllegalArgumentException("$key must be an integer", error)
    }
    requireSafeIntegerValue(value, key, minimum, maximum)
    return value
}

private fun requireSafeIntegerValue(
    value: Long,
    field: String,
    minimum: Long,
    maximum: Long = ReliabilityProtocol.MAX_SAFE_INTEGER,
) {
    requireContract(value in minimum..maximum) {
        "$field must be an integer from $minimum through $maximum"
    }
}

private fun JsonObject.requireUuid(key: String): String =
    requireUuidValue(requireString(key, 36), key)

private fun JsonObject.optionalUuid(key: String): String? {
    if (key !in this) return null
    return requireUuid(key)
}

private fun requireUuidValue(value: String, field: String): String {
    val normalized = value.trim()
    requireContract(UUID_PATTERN.matches(normalized)) { "$field must be a UUID" }
    return normalized.lowercase()
}

private fun JsonObject.requireSha256(key: String): String {
    val value = requireString(key, 64)
    requireContract(SHA256_PATTERN.matches(value)) { "$key must be lowercase SHA-256 hex" }
    return value
}

private fun JsonObject.requireCanonicalBase64(key: String): String {
    val value = requireString(key, ReliabilityProtocol.CHUNK_MAX_TRANSFER_BYTES * 2)
    requireContract(BASE64_PATTERN.matches(value)) { "$key must be canonical base64" }
    val decoded = try {
        Base64.getDecoder().decode(value)
    } catch (error: IllegalArgumentException) {
        throw IllegalArgumentException("$key must be canonical base64", error)
    }
    requireContract(Base64.getEncoder().encodeToString(decoded) == value) {
        "$key must be canonical base64"
    }
    return value
}

private fun JsonObject.requireRfc3339(key: String): String {
    val value = requireString(key, 64)
    requireContract(RFC3339_PATTERN.matches(value)) { "$key must be an RFC 3339 timestamp" }
    try {
        OffsetDateTime.parse(value)
    } catch (error: DateTimeParseException) {
        throw IllegalArgumentException("$key must be an RFC 3339 timestamp", error)
    }
    return value
}

private fun JsonObject.requireJsonArray(key: String): List<JsonElement> {
    val array = required(key) as? JsonArray
        ?: throw IllegalArgumentException("$key must be an array")
    array.forEachIndexed { index, value ->
        validateJsonValue(value, "$key[$index]")
    }
    return array.toList()
}

private fun validateJsonValue(
    value: JsonElement,
    field: String,
    depth: Int = 0,
) {
    requireContract(depth <= ReliabilityProtocol.MAX_JSON_DEPTH) {
        "$field exceeds JSON depth ${ReliabilityProtocol.MAX_JSON_DEPTH}"
    }
    when (value) {
        is JsonArray -> value.forEachIndexed { index, child ->
            validateJsonValue(child, "$field[$index]", depth + 1)
        }
        is JsonObject -> value.forEach { (key, child) ->
            validateJsonValue(child, "$field.$key", depth + 1)
        }
        is JsonPrimitive -> Unit
    }
}

private fun JsonObject.requireRecoveryState(key: String): RecoveryState =
    when (requireLiteralString(key)) {
        "normal" -> RecoveryState.NORMAL
        "interrupted" -> RecoveryState.INTERRUPTED
        "reconciling_device_calls" -> RecoveryState.RECONCILING_DEVICE_CALLS
        else -> throw IllegalArgumentException("$key is not supported")
    }

private fun JsonObject.requireTaskRunState(key: String): TaskRunState =
    when (requireLiteralString(key)) {
        "idle" -> TaskRunState.IDLE
        "starting" -> TaskRunState.STARTING
        "running" -> TaskRunState.RUNNING
        "waiting" -> TaskRunState.WAITING
        "stopping" -> TaskRunState.STOPPING
        "stopped" -> TaskRunState.STOPPED
        "completed" -> TaskRunState.COMPLETED
        "failed" -> TaskRunState.FAILED
        "interrupted" -> TaskRunState.INTERRUPTED
        else -> throw IllegalArgumentException("$key is not supported")
    }

private fun JsonObject.requireSnapshotDeviceCalls(key: String): List<SnapshotDeviceCall> {
    val array = required(key) as? JsonArray
        ?: throw IllegalArgumentException("$key must be an array")
    requireContract(array.size <= ReliabilityProtocol.MAX_DEVICE_ITEMS) {
        "$key must contain at most ${ReliabilityProtocol.MAX_DEVICE_ITEMS} items"
    }
    return array.mapIndexed { index, value ->
        val call = value as? JsonObject
            ?: throw IllegalArgumentException("$key[$index] must be an object")
        call.requireExactKeys("callId", "state", optional = setOf("operationId"))
        SnapshotDeviceCall(
            callId = call.requireUuid("callId"),
            operationId = call.optionalUuid("operationId"),
            state = call.requireString("state", 64),
        )
    }
}

private fun JsonObject.requireStreamCursor(key: String): StreamCursor {
    val cursor = required(key) as? JsonObject
        ?: throw IllegalArgumentException("$key must be an object")
    cursor.requireExactKeys(
        "streamId",
        "highWatermarkSequence",
        "oldestReplayableSequence",
    )
    val high = cursor.requireSafeInteger("highWatermarkSequence", 0)
    val oldest = cursor.requireSafeInteger("oldestReplayableSequence", 1)
    requireContract(oldest <= high + 1) {
        "$key.oldestReplayableSequence exceeds the high watermark boundary"
    }
    return StreamCursor(
        streamId = cursor.requireUuid("streamId"),
        highWatermarkSequence = high,
        oldestReplayableSequence = oldest,
    )
}

private fun JsonObject.requireSnapshotWindow(
    key: String,
    totalMessages: Long,
): SnapshotWindow {
    val window = required(key) as? JsonObject
        ?: throw IllegalArgumentException("$key must be an object")
    window.requireExactKeys(
        "messageStartIndex",
        "messageEndExclusive",
        "hasMoreBefore",
        optional = setOf("historyCursor"),
    )
    val start = window.requireSafeInteger("messageStartIndex", 0, totalMessages)
    val end = window.requireSafeInteger("messageEndExclusive", start, totalMessages)
    val hasMore = window.requireBoolean("hasMoreBefore")
    val historyCursor = window.optionalString("historyCursor", 2048)
    requireContract(hasMore == (historyCursor != null)) {
        "historyCursor must be present exactly when hasMoreBefore is true"
    }
    return SnapshotWindow(
        messageStartIndex = start,
        messageEndExclusive = end,
        hasMoreBefore = hasMore,
        historyCursor = historyCursor,
    )
}

private inline fun requireContract(condition: Boolean, message: () -> String) {
    if (!condition) throw IllegalArgumentException(message())
}

private val CORE_SERVER_KINDS = setOf(
    "hello.accepted",
    "response",
    "error",
    "task.snapshot",
    "pi.event",
)

private val UUID_PATTERN =
    Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-8][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$")
private val SHA256_PATTERN = Regex("^[0-9a-f]{64}$")
private val BASE64_PATTERN = Regex("^(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?$")
private val RFC3339_PATTERN =
    Regex("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:\\.\\d{3})?(?:Z|[+-]\\d{2}:\\d{2})$")
