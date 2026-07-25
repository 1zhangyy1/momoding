package app.momoding.core.transport

import app.momoding.wire.CommandResponseFrame
import app.momoding.wire.ReceivedReliabilityServerFrame
import app.momoding.wire.WireErrorCode
import java.util.UUID
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

data class TaskCreateSuccess(val taskId: String, val piSessionId: String)

data class PromptAcceptedSuccess(val accepted: Boolean, val runState: String)

data class QueueAcceptedSuccess(val accepted: Boolean, val queueDepth: Long)

data class StopAcceptedSuccess(val accepted: Boolean, val runState: String)

data class TaskOpenSuccess(val snapshotVersion: Long)

data class ExactWireFailure(val code: WireErrorCode, val retryable: Boolean)

sealed interface ExactWireOutcome<out T> {
    data class Success<T>(val value: T) : ExactWireOutcome<T>
    data class Failure(val error: ExactWireFailure) : ExactWireOutcome<Nothing>
}

object TaskCreationResponseDecoder {
    fun decodeTaskOpen(requestId: String, received: ReceivedReliabilityServerFrame): ExactWireOutcome<TaskOpenSuccess> =
        decode(requestId, received) { data ->
            requireExactKeys(data, setOf("snapshotVersion"), "task.open response")
            val version = data["snapshotVersion"]?.jsonPrimitive?.longOrNull
                ?: throw IllegalArgumentException("snapshotVersion is missing")
            require(version >= 0) { "snapshotVersion is invalid" }
            TaskOpenSuccess(version)
        }

    fun decodeCreate(requestId: String, received: ReceivedReliabilityServerFrame): ExactWireOutcome<TaskCreateSuccess> =
        decode(requestId, received) { data ->
            requireExactKeys(data, setOf("taskId", "piSessionId"), "task.create response")
            TaskCreateSuccess(
                taskId = requireUuid(data, "taskId"),
                piSessionId = requireUuid(data, "piSessionId"),
            )
        }

    fun decodePrompt(requestId: String, received: ReceivedReliabilityServerFrame): ExactWireOutcome<PromptAcceptedSuccess> =
        decode(requestId, received) { data ->
            requireExactKeys(data, setOf("accepted", "runState"), "session.prompt response")
            require(data["accepted"]?.jsonPrimitive?.booleanOrNull == true) {
                "session.prompt accepted must be true"
            }
            require(data["runState"]?.jsonPrimitive?.contentOrNull == "starting") {
                "session.prompt runState must be starting"
            }
            PromptAcceptedSuccess(accepted = true, runState = "starting")
        }

    private inline fun <T> decode(
        requestId: String,
        received: ReceivedReliabilityServerFrame,
        success: (JsonObject) -> T,
    ): ExactWireOutcome<T> {
        val frame = received.frame as? CommandResponseFrame
            ?: throw IllegalArgumentException("Expected a command response")
        require(frame.requestId == requestId) { "Response requestId does not match journal requestId" }
        return if (frame.ok) {
            val data = frame.data as? JsonObject
                ?: throw IllegalArgumentException("Successful response is missing object data")
            ExactWireOutcome.Success(success(data))
        } else {
            val error = requireNotNull(frame.error) { "Failed response is missing error" }
            ExactWireOutcome.Failure(ExactWireFailure(error.code, error.retryable))
        }
    }

    private fun requireExactKeys(data: JsonObject, keys: Set<String>, context: String) {
        require(data.keys == keys) { "$context has unexpected keys" }
    }

    private fun requireUuid(data: JsonObject, key: String): String {
        val value = data[key]?.jsonPrimitive?.contentOrNull
            ?: throw IllegalArgumentException("$key is missing")
        require(runCatching { UUID.fromString(value) }.getOrNull()?.toString() == value) { "$key is invalid" }
        return value
    }
}

object TaskCommandResponseDecoder {
    fun decode(
        kind: String,
        requestId: String,
        received: ReceivedReliabilityServerFrame,
    ): ExactWireOutcome<Any> = when (kind) {
        "session.prompt" -> TaskCreationResponseDecoder.decodePrompt(requestId, received)
        "session.steer", "session.follow_up" -> decodeQueue(kind, requestId, received)
        "session.stop" -> decodeStop(requestId, received)
        else -> throw IllegalArgumentException("Unsupported task command response kind: $kind")
    }

    private fun decodeQueue(
        kind: String,
        requestId: String,
        received: ReceivedReliabilityServerFrame,
    ): ExactWireOutcome<QueueAcceptedSuccess> = decodeExact(requestId, received) { data ->
        requireExactKeys(data, setOf("accepted", "queueDepth"), "$kind response")
        require(data["accepted"]?.jsonPrimitive?.booleanOrNull == true) {
            "$kind accepted must be true"
        }
        val queueDepth = data["queueDepth"]?.jsonPrimitive?.longOrNull
            ?: throw IllegalArgumentException("$kind queueDepth is missing")
        require(queueDepth >= 0) { "$kind queueDepth is invalid" }
        QueueAcceptedSuccess(accepted = true, queueDepth = queueDepth)
    }

    private fun decodeStop(
        requestId: String,
        received: ReceivedReliabilityServerFrame,
    ): ExactWireOutcome<StopAcceptedSuccess> = decodeExact(requestId, received) { data ->
        requireExactKeys(data, setOf("accepted", "runState"), "session.stop response")
        require(data["accepted"]?.jsonPrimitive?.booleanOrNull == true) {
            "session.stop accepted must be true"
        }
        val runState = data["runState"]?.jsonPrimitive?.contentOrNull
            ?: throw IllegalArgumentException("session.stop runState is missing")
        require(runState == "stopping" || runState == "stopped") {
            "session.stop runState is invalid"
        }
        StopAcceptedSuccess(accepted = true, runState = runState)
    }

    private inline fun <T> decodeExact(
        requestId: String,
        received: ReceivedReliabilityServerFrame,
        success: (JsonObject) -> T,
    ): ExactWireOutcome<T> {
        val frame = received.frame as? CommandResponseFrame
            ?: throw IllegalArgumentException("Expected a command response")
        require(frame.requestId == requestId) { "Response requestId does not match journal requestId" }
        return if (frame.ok) {
            val data = frame.data as? JsonObject
                ?: throw IllegalArgumentException("Successful response is missing object data")
            ExactWireOutcome.Success(success(data))
        } else {
            val error = requireNotNull(frame.error) { "Failed response is missing error" }
            ExactWireOutcome.Failure(ExactWireFailure(error.code, error.retryable))
        }
    }

    private fun requireExactKeys(data: JsonObject, keys: Set<String>, context: String) {
        require(data.keys == keys) { "$context has unexpected keys" }
    }
}
