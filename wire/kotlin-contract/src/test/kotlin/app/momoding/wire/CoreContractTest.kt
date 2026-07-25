package app.momoding.wire

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CoreContractTest {
    private val fixture: JsonObject by lazy {
        val text = checkNotNull(javaClass.getResource("/pi-0.80.6/core-contract.json")) {
            "Pi 0.80.6 Core fixture is missing from the test resources"
        }.readText()
        Json.parseToJsonElement(text).jsonObject
    }

    @Test
    fun `decodes every locked native Pi event without replacing its JSON shape`() {
        val sourceEnvelopes = fixture.getValue("envelopes").jsonArray
        val decoded = sourceEnvelopes.map(CoreServerFrameDecoder::decode)
            .map { assertIs<PiEventFrame>(it) }

        assertEquals(6, decoded.size)
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L, 6L), decoded.map(PiEventFrame::sequence))
        assertEquals(
            listOf(
                "agent_start",
                "message_update",
                "tool_execution_start",
                "tool_execution_end",
                "message_update",
                "agent_settled",
            ),
            decoded.map { it.event.getValue("type").jsonPrimitive.content },
        )

        sourceEnvelopes.zip(decoded).forEach { (source, frame) ->
            assertEquals(source.jsonObject.getValue("event"), frame.event)
            assertEquals(CoreProtocol.PI_VERSION, frame.piVersion)
            assertNull(frame.requestId)
        }

        val toolEnd = decoded[3].event
        assertEquals("device_files_list", toolEnd.getValue("toolName").jsonPrimitive.content)
        assertFalse(toolEnd.getValue("isError").jsonPrimitive.boolean)
        assertFalse(
            toolEnd.getValue("result").jsonObject
                .getValue("details").jsonObject
                .getValue("sideEffect").jsonPrimitive.boolean,
        )
    }

    @Test
    fun `decodes the bounded Core snapshot while keeping Pi payloads raw`() {
        val source = fixture.getValue("snapshot").jsonObject
        val snapshot = assertIs<TaskSnapshotFrame>(CoreServerFrameDecoder.decode(source))

        assertEquals(RecoveryState.NORMAL, snapshot.recoveryState)
        assertEquals(TaskRunState.IDLE, snapshot.runState)
        assertEquals(6, snapshot.cursor.highWatermarkSequence)
        assertEquals(7, snapshot.cursor.oldestReplayableSequence)
        assertFalse(snapshot.pi.isStreaming)
        assertEquals(source.getValue("pi").jsonObject.getValue("messages"), JsonArray(snapshot.pi.messages))
        assertEquals(source.getValue("pi").jsonObject.getValue("queue"), JsonArray(snapshot.pi.queue))
        assertEquals(source.getValue("pendingAttention"), JsonArray(snapshot.pendingAttention))
        assertTrue(source.toString().encodeToByteArray().size <= CoreProtocol.MAX_SNAPSHOT_BYTES)
    }

    @Test
    fun `decodes the remaining core server frame variants`() {
        val accepted = assertIs<HelloAcceptedFrame>(
            decode(
                """
                {
                  "protocolVersion": 1,
                  "kind": "hello.accepted",
                  "requestId": "hello-fixture",
                  "connectionId": "connection-fixture",
                  "serverVersion": "0.1.0-fixture",
                  "piVersion": "0.80.6",
                  "heartbeatIntervalMs": 20000,
                  "maxFrameBytes": 1048576
                }
                """.trimIndent(),
            ),
        )
        assertEquals("connection-fixture", accepted.connectionId)

        val success = assertIs<CommandResponseFrame>(
            decode(
                """
                {
                  "protocolVersion": 1,
                  "kind": "response",
                  "requestId": "request-success",
                  "ok": true,
                  "data": {"accepted": true, "runState": "starting"}
                }
                """.trimIndent(),
            ),
        )
        assertTrue(success.ok)
        assertEquals("starting", success.data?.jsonObject?.getValue("runState")?.jsonPrimitive?.content)
        assertNull(success.error)

        val failure = assertIs<CommandResponseFrame>(
            decode(
                """
                {
                  "protocolVersion": 1,
                  "kind": "response",
                  "requestId": "request-failure",
                  "ok": false,
                  "error": {
                    "code": "SESSION_BUSY",
                    "message": "Session is busy",
                    "retryable": true
                  }
                }
                """.trimIndent(),
            ),
        )
        assertFalse(failure.ok)
        assertEquals(WireErrorCode.SESSION_BUSY, failure.error?.code)
        assertNull(failure.data)

        val error = assertIs<WireErrorFrame>(
            decode(
                """
                {
                  "protocolVersion": 1,
                  "kind": "error",
                  "requestId": "request-error",
                  "error": {
                    "code": "BAD_REQUEST",
                    "message": "Request is invalid",
                    "retryable": false
                  }
                }
                """.trimIndent(),
            ),
        )
        assertEquals(WireErrorCode.BAD_REQUEST, error.error.code)
    }

    @Test
    fun `accepts unknown native Pi fields but rejects Wire drift and Reliability kinds`() {
        val source = fixture.getValue("envelopes").jsonArray.first().jsonObject
        val extendedEvent = JsonObject(source.getValue("event").jsonObject + ("futureNativeField" to JsonObject(emptyMap())))
        val extendedFrame = JsonObject(source + ("event" to extendedEvent))
        val decoded = assertIs<PiEventFrame>(CoreServerFrameDecoder.decode(extendedFrame))
        assertEquals(JsonObject(emptyMap()), decoded.event.getValue("futureNativeField"))

        assertFailsWith<SerializationException> {
            CoreServerFrameDecoder.decode(JsonObject(source + ("protocolVersion" to Json.parseToJsonElement("2"))))
        }
        assertFailsWith<SerializationException> {
            CoreServerFrameDecoder.decode(JsonObject(source + ("piVersion" to Json.parseToJsonElement("\"0.80.7\""))))
        }
        assertFailsWith<SerializationException> {
            CoreServerFrameDecoder.decode(JsonObject(source + ("translatedEvent" to extendedEvent)))
        }
        assertFailsWith<SerializationException> {
            CoreServerFrameDecoder.decode(
                buildJsonObject {
                    put("protocolVersion", 1)
                    put("kind", "pi.replay.complete")
                },
            )
        }
        assertFailsWith<SerializationException> {
            CoreServerFrameDecoder.decode(JsonObject(source + ("kind" to JsonObject(emptyMap()))))
        }
        assertFailsWith<SerializationException> {
            val numericType = JsonObject(source.getValue("event").jsonObject + ("type" to JsonPrimitive(7)))
            CoreServerFrameDecoder.decode(JsonObject(source + ("event" to numericType)))
        }
    }

    @Test
    fun `fails closed for malformed response discriminators`() {
        assertFailsWith<SerializationException> {
            decode(
                """
                {
                  "protocolVersion": 1,
                  "kind": "response",
                  "requestId": "bad-success",
                  "ok": true,
                  "error": {
                    "code": "BAD_REQUEST",
                    "message": "Request is invalid",
                    "retryable": false
                  }
                }
                """.trimIndent(),
            )
        }
        assertFailsWith<SerializationException> {
            decode(
                """
                {
                  "protocolVersion": 1,
                  "kind": "response",
                  "requestId": "bad-failure",
                  "ok": false
                }
                """.trimIndent(),
            )
        }
        assertFailsWith<SerializationException> {
            decode(
                """
                {
                  "protocolVersion": 1,
                  "kind": "response",
                  "requestId": "bad-failure-data",
                  "ok": false,
                  "data": null,
                  "error": {
                    "code": "BAD_REQUEST",
                    "message": "Request is invalid",
                    "retryable": false
                  }
                }
                """.trimIndent(),
            )
        }
    }

    @Test
    fun `enforces Core physical frame and single snapshot budgets`() {
        val oversizedFrame = " ".repeat(CoreProtocol.MAX_FRAME_BYTES + 1)
        assertFailsWith<SerializationException> { decode(oversizedFrame) }

        val snapshot = fixture.getValue("snapshot").jsonObject
        val compactSnapshot = snapshot.toString()
        val compactBytes = compactSnapshot.encodeToByteArray().size
        val exactTextSnapshot = " ".repeat(CoreProtocol.MAX_SNAPSHOT_BYTES - compactBytes) + compactSnapshot
        assertEquals(CoreProtocol.MAX_SNAPSHOT_BYTES, exactTextSnapshot.encodeToByteArray().size)
        assertIs<TaskSnapshotFrame>(decode(exactTextSnapshot))

        val oneByteOverTextSnapshot = " $exactTextSnapshot"
        assertEquals(CoreProtocol.MAX_SNAPSHOT_BYTES + 1, oneByteOverTextSnapshot.encodeToByteArray().size)
        assertFailsWith<SerializationException> { decode(oneByteOverTextSnapshot) }

        val oversizedPi = JsonObject(
            snapshot.getValue("pi").jsonObject +
                ("messages" to JsonArray(listOf(JsonPrimitive("x".repeat(CoreProtocol.MAX_SNAPSHOT_BYTES))))),
        )
        val oversizedSnapshot = JsonObject(snapshot + ("pi" to oversizedPi))
        assertFailsWith<SerializationException> {
            CoreServerFrameDecoder.decode(oversizedSnapshot)
        }
    }

    @Test
    fun `rejects explicit null for every optional Wire string while accepting omission`() {
        val sourceEvent = fixture.getValue("envelopes").jsonArray.first().jsonObject
        assertNull(assertIs<PiEventFrame>(CoreServerFrameDecoder.decode(sourceEvent)).requestId)
        assertFailsWith<SerializationException> {
            CoreServerFrameDecoder.decode(JsonObject(sourceEvent + ("requestId" to JsonNull)))
        }

        val errorWithoutRequestId = Json.parseToJsonElement(
            """
            {
              "protocolVersion": 1,
              "kind": "error",
              "error": {
                "code": "BAD_REQUEST",
                "message": "Request is invalid",
                "retryable": false
              }
            }
            """.trimIndent(),
        ).jsonObject
        assertNull(assertIs<WireErrorFrame>(CoreServerFrameDecoder.decode(errorWithoutRequestId)).requestId)
        assertFailsWith<SerializationException> {
            CoreServerFrameDecoder.decode(JsonObject(errorWithoutRequestId + ("requestId" to JsonNull)))
        }

        val sourceSnapshot = fixture.getValue("snapshot").jsonObject
        val snapshotWithoutRequestId = JsonObject(sourceSnapshot - "requestId")
        assertNull(
            assertIs<TaskSnapshotFrame>(CoreServerFrameDecoder.decode(snapshotWithoutRequestId)).requestId,
        )
        assertFailsWith<SerializationException> {
            CoreServerFrameDecoder.decode(JsonObject(sourceSnapshot + ("requestId" to JsonNull)))
        }

        val sourceCalls = sourceSnapshot.getValue("deviceCalls").jsonArray
        val callWithoutOperationId = sourceCalls.first().jsonObject
        assertFalse(callWithoutOperationId.containsKey("operationId"))
        val callsWithNullOperation = JsonArray(
            listOf(JsonObject(callWithoutOperationId + ("operationId" to JsonNull))) + sourceCalls.drop(1),
        )
        assertFailsWith<SerializationException> {
            CoreServerFrameDecoder.decode(
                JsonObject(sourceSnapshot + ("deviceCalls" to callsWithNullOperation)),
            )
        }
    }

    private fun decode(text: String): CoreServerFrame = CoreServerFrameDecoder.decode(text)
}
