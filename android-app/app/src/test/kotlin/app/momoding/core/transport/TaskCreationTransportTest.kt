package app.momoding.core.transport

import app.momoding.wire.ReliabilityContractDecoder
import app.momoding.wire.WireErrorCode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskCreationTransportTest {
    @Test
    fun createAndPromptEncodersUseExactWireKeys() {
        val create = Json.parseToJsonElement(
            ClientWireCodec.encodeTaskCreate(REQUEST_ID, CREATE_COMMAND_ID, "draft-1", "Plan release"),
        ).jsonObject
        assertEquals(setOf("protocolVersion", "kind", "requestId", "commandId", "draftId", "title"), create.keys)
        assertEquals("task.create", create.getValue("kind").jsonPrimitive.content)

        val prompt = Json.parseToJsonElement(
            ClientWireCodec.encodeSessionPrompt(REQUEST_ID, PROMPT_COMMAND_ID, TASK_ID, "Ship it"),
        ).jsonObject
        assertEquals(setOf("protocolVersion", "kind", "requestId", "commandId", "taskId", "text"), prompt.keys)
        assertEquals("Ship it", prompt.getValue("text").jsonPrimitive.content)
    }

    @Test
    fun promptLimitUsesUtf16UnitsLikeTheHost() {
        ClientWireCodec.encodeSessionPrompt(REQUEST_ID, PROMPT_COMMAND_ID, TASK_ID, "a".repeat(131_072))
        ClientWireCodec.encodeSessionPrompt(REQUEST_ID, PROMPT_COMMAND_ID, TASK_ID, "😀".repeat(65_536))
        assertThrows(IllegalArgumentException::class.java) {
            ClientWireCodec.encodeSessionPrompt(REQUEST_ID, PROMPT_COMMAND_ID, TASK_ID, "a".repeat(131_073))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ClientWireCodec.encodeSessionPrompt(REQUEST_ID, PROMPT_COMMAND_ID, TASK_ID, "😀".repeat(65_537))
        }
    }

    @Test
    fun responseDecoderAcceptsOnlyExactSuccessShapes() {
        val create = ReliabilityContractDecoder.decode(
            """{"protocolVersion":1,"kind":"response","requestId":"$REQUEST_ID","ok":true,"data":{"taskId":"$TASK_ID","piSessionId":"$PI_SESSION_ID"}}""",
        )
        val outcome = TaskCreationResponseDecoder.decodeCreate(REQUEST_ID, create)
        assertEquals(TaskCreateSuccess(TASK_ID, PI_SESSION_ID), (outcome as ExactWireOutcome.Success).value)

        val extra = ReliabilityContractDecoder.decode(
            """{"protocolVersion":1,"kind":"response","requestId":"$REQUEST_ID","ok":true,"data":{"taskId":"$TASK_ID","piSessionId":"$PI_SESSION_ID","extra":true}}""",
        )
        assertThrows(IllegalArgumentException::class.java) {
            TaskCreationResponseDecoder.decodeCreate(REQUEST_ID, extra)
        }
    }

    @Test
    fun responseDecoderPreservesOnlyTypedFailureData() {
        val received = ReliabilityContractDecoder.decode(
            """{"protocolVersion":1,"kind":"response","requestId":"$REQUEST_ID","ok":false,"error":{"code":"SESSION_BUSY","message":"sensitive host detail","retryable":true}}""",
        )
        val outcome = TaskCreationResponseDecoder.decodePrompt(REQUEST_ID, received)
        val failure = (outcome as ExactWireOutcome.Failure).error
        assertEquals(WireErrorCode.SESSION_BUSY, failure.code)
        assertTrue(failure.retryable)
    }

    @Test
    fun mutatingTimeoutRemainsCorrelatedForLateDurableResponse() {
        var now = 1_000L
        val table = RequestTable { now }
        val mutating = pending(mutating = true)
        table.register(mutating)
        now = 2_001L
        assertEquals(mutating, table.expireForCaller(REQUEST_ID))
        assertTrue(table.lookup(REQUEST_ID, 1) is ResponseLookup.Pending)

        val readId = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        val read = pending(requestId = readId, mutating = false, deadlineAtMillis = 4_000)
        table.register(read)
        now = 4_001L
        assertEquals(read, table.expireForCaller(readId))
        assertEquals(ResponseLookup.Tombstoned, table.lookup(readId, 1))
    }

    private fun pending(
        requestId: String = REQUEST_ID,
        mutating: Boolean,
        deadlineAtMillis: Long = 2_000,
    ) = PendingWireRequest(
        requestId = requestId,
        kind = if (mutating) "task.create" else "task.list",
        canonicalPayload = "{}",
        mutating = mutating,
        commandId = if (mutating) CREATE_COMMAND_ID else null,
        taskId = null,
        deadlineAtMillis = deadlineAtMillis,
        completion = CompletableDeferred(),
        generation = 1,
    )

    private companion object {
        const val REQUEST_ID = "11111111-1111-4111-8111-111111111111"
        const val CREATE_COMMAND_ID = "22222222-2222-4222-8222-222222222222"
        const val PROMPT_COMMAND_ID = "33333333-3333-4333-8333-333333333333"
        const val TASK_ID = "44444444-4444-4444-8444-444444444444"
        const val PI_SESSION_ID = "55555555-5555-4555-8555-555555555555"
    }
}
