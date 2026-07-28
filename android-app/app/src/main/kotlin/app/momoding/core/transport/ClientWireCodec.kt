package app.momoding.core.transport

import app.momoding.wire.P1bProtocol
import app.momoding.core.auth.VaultEnvelopeCodec
import app.momoding.core.auth.VaultSecret
import java.util.UUID
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

data class DurableResumeCursor(
    val taskId: String,
    val streamId: String,
    val lastAckedSequence: Long,
)

data class ClientHelloPayload(
    val requestId: String,
    val clientVersion: String,
    val secret: VaultSecret,
    val resume: List<DurableResumeCursor>,
)

object ClientWireCodec {
    fun encodeHello(payload: ClientHelloPayload): String {
        VaultEnvelopeCodec.validateSecret(payload.secret)
        requireUuid(payload.requestId, "requestId")
        require(payload.clientVersion.isNotBlank() && payload.clientVersion.length <= 64) {
            "clientVersion is invalid"
        }
        require(payload.resume.size <= MAX_RESUME_CURSORS) { "Too many resume cursors" }
        require(payload.resume.map { it.taskId }.toSet().size == payload.resume.size) {
            "Resume taskId values must be unique"
        }
        val resume = buildJsonArray {
            payload.resume.forEach { cursor ->
                requireUuid(cursor.taskId, "resume.taskId")
                requireUuid(cursor.streamId, "resume.streamId")
                require(cursor.lastAckedSequence in 0..P1bProtocol.MAX_SAFE_INTEGER) {
                    "resume.lastAckedSequence is invalid"
                }
                add(
                    buildJsonObject {
                        put("taskId", cursor.taskId)
                        put("streamId", cursor.streamId)
                        put("lastAckedSequence", cursor.lastAckedSequence)
                    },
                )
            }
        }
        return buildJsonObject {
            put("protocolVersion", 1)
            put("kind", "hello")
            put("requestId", payload.requestId)
            put("clientInstanceId", payload.secret.clientInstanceId)
            put("deviceId", payload.secret.deviceId)
            put("clientVersion", payload.clientVersion)
            putJsonObject("auth") {
                put("scheme", "device-credential")
                put("credential", payload.secret.deviceCredential)
            }
            put("resume", resume)
        }.toString()
    }

    fun encodeTaskList(requestId: String, cursor: String?, limit: Int = 100): String {
        requireUuid(requestId, "requestId")
        require(limit in 1..100) { "task.list limit is invalid" }
        cursor?.let { require(it.isNotEmpty() && it.length <= 4_096) { "task.list cursor is invalid" } }
        return buildJsonObject {
            put("protocolVersion", 1)
            put("kind", "task.list")
            put("requestId", requestId)
            cursor?.let { put("cursor", it) }
            put("limit", limit)
        }.toString()
    }

    fun encodeTaskCreate(
        requestId: String,
        commandId: String,
        draftId: String,
        title: String?,
    ): String {
        requireUuid(requestId, "requestId")
        requireUuid(commandId, "commandId")
        require(draftId.isNotBlank() && draftId.length <= 128) { "draftId is invalid" }
        title?.let { require(it.isNotBlank() && it.length <= 256) { "title is invalid" } }
        return buildJsonObject {
            put("protocolVersion", 1)
            put("kind", "task.create")
            put("requestId", requestId)
            put("commandId", commandId)
            put("draftId", draftId)
            title?.let { put("title", it) }
        }.toString()
    }

    fun encodeTaskOpen(requestId: String, taskId: String): String {
        requireUuid(requestId, "requestId")
        requireUuid(taskId, "taskId")
        return buildJsonObject {
            put("protocolVersion", 1)
            put("kind", "task.open")
            put("requestId", requestId)
            put("taskId", taskId)
        }.toString()
    }

    fun encodeTaskSnapshotRequest(
        requestId: String,
        taskId: String,
        knownSnapshotVersion: Long? = null,
    ): String {
        requireUuid(requestId, "requestId")
        requireUuid(taskId, "taskId")
        knownSnapshotVersion?.let {
            require(it in 0..P1bProtocol.MAX_SAFE_INTEGER) {
                "knownSnapshotVersion is invalid"
            }
        }
        return buildJsonObject {
            put("protocolVersion", 1)
            put("kind", "task.snapshot.request")
            put("requestId", requestId)
            put("taskId", taskId)
            knownSnapshotVersion?.let { put("knownSnapshotVersion", it) }
        }.toString()
    }

    fun encodeSessionPrompt(
        requestId: String,
        commandId: String,
        taskId: String,
        text: String,
    ): String = encodeSessionTextCommand(
        kind = "session.prompt",
        requestId = requestId,
        commandId = commandId,
        taskId = taskId,
        text = text,
    )

    fun encodeSessionSteer(
        requestId: String,
        commandId: String,
        taskId: String,
        text: String,
    ): String = encodeSessionTextCommand(
        kind = "session.steer",
        requestId = requestId,
        commandId = commandId,
        taskId = taskId,
        text = text,
    )

    fun encodeSessionFollowUp(
        requestId: String,
        commandId: String,
        taskId: String,
        text: String,
    ): String = encodeSessionTextCommand(
        kind = "session.follow_up",
        requestId = requestId,
        commandId = commandId,
        taskId = taskId,
        text = text,
    )

    fun encodeSessionStop(
        requestId: String,
        commandId: String,
        taskId: String,
        reason: String,
    ): String {
        requireUuid(requestId, "requestId")
        requireUuid(commandId, "commandId")
        requireUuid(taskId, "taskId")
        require(reason.isNotBlank() && reason.length <= MAX_STOP_REASON_UTF16_UNITS) {
            "reason is invalid"
        }
        return buildJsonObject {
            put("protocolVersion", 1)
            put("kind", "session.stop")
            put("requestId", requestId)
            put("commandId", commandId)
            put("taskId", taskId)
            put("reason", reason)
        }.toString()
    }

    fun newRequestId(): String = UUID.randomUUID().toString()

    private fun requireUuid(value: String, field: String) {
        require(UUID_PATTERN.matches(value)) { "$field is invalid" }
    }

    private fun encodeSessionTextCommand(
        kind: String,
        requestId: String,
        commandId: String,
        taskId: String,
        text: String,
    ): String {
        require(kind in SESSION_TEXT_KINDS) { "session text kind is invalid" }
        requireUuid(requestId, "requestId")
        requireUuid(commandId, "commandId")
        requireUuid(taskId, "taskId")
        require(text.isNotBlank() && text.length <= MAX_PROMPT_UTF16_UNITS) { "text is invalid" }
        return buildJsonObject {
            put("protocolVersion", 1)
            put("kind", kind)
            put("requestId", requestId)
            put("commandId", commandId)
            put("taskId", taskId)
            put("text", text)
        }.toString()
    }

    private const val MAX_RESUME_CURSORS = 128
    const val MAX_PROMPT_UTF16_UNITS = 131_072
    private const val MAX_STOP_REASON_UTF16_UNITS = 4_096
    private val SESSION_TEXT_KINDS = setOf("session.prompt", "session.steer", "session.follow_up")
    private val UUID_PATTERN = Regex(
        "^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$",
    )
}
