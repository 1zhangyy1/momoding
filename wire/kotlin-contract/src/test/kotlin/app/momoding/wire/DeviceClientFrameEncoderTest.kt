package app.momoding.wire

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class DeviceClientFrameEncoderTest {
    private val fixture: JsonObject by lazy {
        val path = Path.of("../fixtures/pi-0.80.6/attention-client-contract.json")
        Json.parseToJsonElement(Files.readString(path)).jsonObject
    }

    @Test
    fun `capability encoder emits the exact metadata and attention manifest`() {
        val frame = DeviceCapabilitiesReportClientFrame(
            requestId = "capabilities-request-attention",
            commandId = COMMAND_ID,
            deviceId = DEVICE_ID,
            expiresAt = "2026-07-17T01:15:00.000Z",
        )

        assertEquals(fixture.objectAt("capabilitiesReport"), encoded(frame))
        assertFailsWith<IllegalArgumentException> {
            DeviceClientFrameEncoder.encode(frame.copy(capabilityVersion = 2))
        }
        assertFailsWith<IllegalArgumentException> {
            DeviceClientFrameEncoder.encode(
                frame.copy(manifest = buildJsonObject { put("tools", JsonArray(emptyList())) }),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            DeviceClientFrameEncoder.encode(frame.copy(expiresAt = "2026-02-30T01:00:00Z"))
        }
        assertFailsWith<IllegalArgumentException> {
            DeviceClientFrameEncoder.encode(frame.copy(deviceId = " "))
        }
    }

    @Test
    fun `progress encoder preserves exact optional shape and validates bounds`() {
        val frame = DeviceToolProgressClientFrame(
            callId = CALL_ID,
            taskId = TASK_ID,
            deviceId = DEVICE_ID,
            progressSequence = 2,
            phase = DeviceToolProgressPhase.AWAITING_USER,
            summary = "Waiting for your answer",
        )

        assertEquals(fixture.objectAt("toolProgress"), encoded(frame))
        val withoutSummary = encoded(frame.copy(summary = null))
        assertEquals(fixture.objectAt("toolProgress").keys - "summary", withoutSummary.keys)
        assertFailsWith<IllegalArgumentException> {
            DeviceClientFrameEncoder.encode(frame.copy(progressSequence = 0))
        }
        assertFailsWith<IllegalArgumentException> {
            DeviceClientFrameEncoder.encode(frame.copy(progressSequence = ReliabilityProtocol.MAX_SAFE_INTEGER + 1))
        }
        assertFailsWith<IllegalArgumentException> {
            DeviceClientFrameEncoder.encode(frame.copy(summary = "x".repeat(2_049)))
        }
    }

    @Test
    fun `terminal encoder emits result xor error and rejects malformed payloads`() {
        val results = fixture.objectAt("toolResults")
        val question = DeviceToolResultClientFrame(
            callId = CALL_ID,
            taskId = TASK_ID,
            deviceId = DEVICE_ID,
            terminal = DeviceToolTerminalKind.SUCCEEDED,
            result = results.objectAt("questionOption").getValue("result"),
        )
        val declined = DeviceToolResultClientFrame(
            callId = DECLINED_CALL_ID,
            taskId = TASK_ID,
            deviceId = DEVICE_ID,
            terminal = DeviceToolTerminalKind.REJECTED,
            error = DeviceClientWireError("USER_DECLINED", "User declined the confirmation"),
        )

        assertEquals(results.objectAt("questionOption"), encoded(question))
        assertEquals(results.objectAt("confirmationDeclined"), encoded(declined))
        assertFailsWith<IllegalArgumentException> {
            DeviceClientFrameEncoder.encode(question.copy(result = null))
        }
        assertFailsWith<IllegalArgumentException> {
            DeviceClientFrameEncoder.encode(question.copy(error = declined.error))
        }
        assertFailsWith<IllegalArgumentException> {
            DeviceClientFrameEncoder.encode(declined.copy(error = null))
        }
        assertFailsWith<IllegalArgumentException> {
            DeviceClientFrameEncoder.encode(declined.copy(result = JsonPrimitive(true)))
        }
        assertFailsWith<IllegalArgumentException> {
            DeviceClientFrameEncoder.encode(declined.copy(error = DeviceClientWireError(" ", "bad")))
        }
    }

    @Test
    fun `terminal encoder covers every attention terminal wire value`() {
        val successPayloads = listOf(
            buildJsonObject {
                put("outcome", "answered")
                put("answer", buildJsonObject { put("kind", "custom"); put("text", "Safer sequence") })
            },
            buildJsonObject { put("outcome", "skipped") },
            buildJsonObject { put("outcome", "confirmed") },
        )
        successPayloads.forEach { result ->
            val encoded = encoded(
                DeviceToolResultClientFrame(
                    callId = CALL_ID,
                    taskId = TASK_ID,
                    deviceId = DEVICE_ID,
                    terminal = DeviceToolTerminalKind.SUCCEEDED,
                    result = result,
                ),
            )
            assertEquals("succeeded", encoded.getValue("terminal").toString().trim('"'))
            assertEquals(result, encoded.getValue("result"))
        }

        val errors = listOf(
            Triple(DeviceToolTerminalKind.FAILED, "UNSUPPORTED_DEVICE_CAPABILITY", "Device capability is unavailable"),
            Triple(DeviceToolTerminalKind.REJECTED, "USER_DECLINED", "User declined the confirmation"),
            Triple(DeviceToolTerminalKind.CANCELLED, "ATTENTION_CANCELLED", "Attention request was cancelled"),
            Triple(DeviceToolTerminalKind.TIMED_OUT, "ATTENTION_EXPIRED", "Attention request expired"),
        )
        errors.forEach { (terminal, code, message) ->
            val encoded = encoded(
                DeviceToolResultClientFrame(
                    callId = CALL_ID,
                    taskId = TASK_ID,
                    deviceId = DEVICE_ID,
                    terminal = terminal,
                    error = DeviceClientWireError(code, message),
                ),
            )
            assertEquals(terminal.wireValue, encoded.getValue("terminal").toString().trim('"'))
            assertEquals(false, encoded.objectAt("error").getValue("retryable").toString().toBoolean())
        }
    }

    @Test
    fun `reconcile encoder preserves all six states and rejects ambiguous ledgers`() {
        val frame = DeviceToolReconcileResultClientFrame(
            requestId = "reconcile-request-attention",
            commandId = RECONCILE_COMMAND_ID,
            taskId = TASK_ID,
            deviceId = DEVICE_ID,
            results = listOf(
                item(11, DeviceLedgerState.NEVER_STARTED),
                item(12, DeviceLedgerState.RUNNING),
                item(
                    13,
                    DeviceLedgerState.SUCCEEDED,
                    resultSummary = buildJsonObject { put("outcome", "skipped") },
                ),
                item(
                    14,
                    DeviceLedgerState.FAILED,
                    error = DeviceClientWireError("ATTENTION_EXPIRED", "Attention request expired"),
                ),
                item(
                    15,
                    DeviceLedgerState.CANCELLED,
                    error = DeviceClientWireError(
                        "ATTENTION_CANCELLED",
                        "Attention request was cancelled",
                    ),
                ),
                item(
                    16,
                    DeviceLedgerState.UNKNOWN,
                ),
            ),
        )

        assertEquals(fixture.objectAt("reconcileResult"), encoded(frame))
        encoded(frame).getValue("results").let { encodedResults ->
            encodedResults as JsonArray
            assertTrue(encodedResults.all { "operationId" !in it.jsonObject })
        }
        val withOperation = encoded(
            frame.copy(
                results = frame.results.mapIndexed { index, item ->
                    if (index == 1) item.copy(operationId = OPERATION_ID) else item
                },
            ),
        ).getValue("results").jsonArray
        assertEquals(
            OPERATION_ID,
            withOperation[1].jsonObject.getValue("operationId").jsonPrimitive.content,
        )
        assertFailsWith<IllegalArgumentException> {
            DeviceClientFrameEncoder.encode(frame.copy(results = emptyList()))
        }
        assertFailsWith<IllegalArgumentException> {
            DeviceClientFrameEncoder.encode(frame.copy(results = List(257) { item(it + 1, DeviceLedgerState.RUNNING) }))
        }
        assertFailsWith<IllegalArgumentException> {
            DeviceClientFrameEncoder.encode(frame.copy(results = listOf(frame.results[0], frame.results[0])))
        }
        assertFailsWith<IllegalArgumentException> {
            DeviceClientFrameEncoder.encode(
                frame.copy(results = listOf(item(20, DeviceLedgerState.SUCCEEDED))),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            DeviceClientFrameEncoder.encode(
                frame.copy(
                    results = listOf(
                        item(
                            20,
                            DeviceLedgerState.RUNNING,
                            error = DeviceClientWireError("BAD", "bad"),
                        ),
                    ),
                ),
            )
        }
    }

    @Test
    fun `encoder rejects non JSON values excessive depth and physical size`() {
        val base = DeviceToolResultClientFrame(
            callId = CALL_ID,
            taskId = TASK_ID,
            deviceId = DEVICE_ID,
            terminal = DeviceToolTerminalKind.SUCCEEDED,
            result = JsonPrimitive(true),
        )
        assertFailsWith<IllegalArgumentException> {
            DeviceClientFrameEncoder.encode(base.copy(result = JsonPrimitive(Double.NaN)))
        }
        fun nested(depth: Int): JsonObject {
            var value: JsonObject = buildJsonObject { put("leaf", true) }
            repeat(depth) {
                value = buildJsonObject { put("child", value) }
            }
            return value
        }
        DeviceClientFrameEncoder.encode(base.copy(result = nested(ReliabilityProtocol.MAX_JSON_DEPTH - 1)))
        assertFailsWith<IllegalArgumentException> {
            DeviceClientFrameEncoder.encode(base.copy(result = nested(ReliabilityProtocol.MAX_JSON_DEPTH)))
        }
        assertFailsWith<IllegalArgumentException> {
            DeviceClientFrameEncoder.encode(
                base.copy(result = JsonPrimitive("x".repeat(CoreProtocol.MAX_FRAME_BYTES))),
            )
        }
    }

    private fun encoded(frame: DeviceCapabilitiesReportClientFrame): JsonObject =
        parse(DeviceClientFrameEncoder.encode(frame))

    private fun encoded(frame: DeviceToolProgressClientFrame): JsonObject =
        parse(DeviceClientFrameEncoder.encode(frame))

    private fun encoded(frame: DeviceToolResultClientFrame): JsonObject =
        parse(DeviceClientFrameEncoder.encode(frame))

    private fun encoded(frame: DeviceToolReconcileResultClientFrame): JsonObject =
        parse(DeviceClientFrameEncoder.encode(frame))

    private fun parse(bytes: ByteArray): JsonObject =
        Json.parseToJsonElement(bytes.decodeToString()).jsonObject

    private fun item(
        suffix: Int,
        state: DeviceLedgerState,
        resultSummary: kotlinx.serialization.json.JsonElement? = null,
        error: DeviceClientWireError? = null,
    ) = DeviceToolReconcileResultItem(
        callId = "70000000-0000-4000-8000-${suffix.toString().padStart(12, '0')}",
        state = state,
        resultSummary = resultSummary,
        error = error,
    )

    private companion object {
        const val TASK_ID = "11111111-1111-4111-8111-111111111111"
        const val CALL_ID = "55555555-5555-4555-8555-555555555557"
        const val DECLINED_CALL_ID = "55555555-5555-4555-8555-555555555558"
        const val COMMAND_ID = "44444444-4444-4444-8444-444444444447"
        const val RECONCILE_COMMAND_ID = "66666666-6666-4666-8666-666666666667"
        const val OPERATION_ID = "77777777-7777-4777-8777-777777777777"
        const val DEVICE_ID = "android-attention-fixture"
    }
}

private fun JsonObject.objectAt(key: String): JsonObject = getValue(key).jsonObject
