package app.momoding.core.transport

import app.momoding.wire.ReliabilityContractDecoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskCommandTransportTest {
    @Test
    fun `steer follow-up and stop encoders preserve exact command identity`() {
        val steer = Json.parseToJsonElement(
            ClientWireCodec.encodeSessionSteer(REQUEST_ID, COMMAND_ID, TASK_ID, "change direction"),
        ).jsonObject
        assertEquals(setOf("protocolVersion", "kind", "requestId", "commandId", "taskId", "text"), steer.keys)
        assertEquals("session.steer", steer.getValue("kind").toString().trim('"'))

        val followUp = Json.parseToJsonElement(
            ClientWireCodec.encodeSessionFollowUp(REQUEST_ID, COMMAND_ID, TASK_ID, "summarize next"),
        ).jsonObject
        assertEquals("session.follow_up", followUp.getValue("kind").toString().trim('"'))

        val stop = Json.parseToJsonElement(
            ClientWireCodec.encodeSessionStop(REQUEST_ID, COMMAND_ID, TASK_ID, "user"),
        ).jsonObject
        assertEquals(setOf("protocolVersion", "kind", "requestId", "commandId", "taskId", "reason"), stop.keys)
        assertEquals("user", stop.getValue("reason").toString().trim('"'))
    }

    @Test
    fun `response decoder accepts only exact native Pi outcomes`() {
        val queue = TaskCommandResponseDecoder.decode(
            "session.steer",
            REQUEST_ID,
            ReliabilityContractDecoder.decode(
                """{"protocolVersion":1,"kind":"response","requestId":"$REQUEST_ID","ok":true,"data":{"accepted":true,"queueDepth":2}}""",
            ),
        )
        assertEquals(2L, (queue as ExactWireOutcome.Success).value.let { it as QueueAcceptedSuccess }.queueDepth)

        val stop = TaskCommandResponseDecoder.decode(
            "session.stop",
            REQUEST_ID,
            ReliabilityContractDecoder.decode(
                """{"protocolVersion":1,"kind":"response","requestId":"$REQUEST_ID","ok":true,"data":{"accepted":true,"runState":"stopped"}}""",
            ),
        )
        assertEquals("stopped", (stop as ExactWireOutcome.Success).value.let { it as StopAcceptedSuccess }.runState)

        val failure = TaskCommandResponseDecoder.decode(
            "session.follow_up",
            REQUEST_ID,
            ReliabilityContractDecoder.decode(
                """{"protocolVersion":1,"kind":"response","requestId":"$REQUEST_ID","ok":false,"error":{"code":"SESSION_BUSY","message":"busy","retryable":true}}""",
            ),
        )
        assertTrue(failure is ExactWireOutcome.Failure)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `queue response rejects extra fields`() {
        TaskCommandResponseDecoder.decode(
            "session.steer",
            REQUEST_ID,
            ReliabilityContractDecoder.decode(
                """{"protocolVersion":1,"kind":"response","requestId":"$REQUEST_ID","ok":true,"data":{"accepted":true,"queueDepth":1,"invented":true}}""",
            ),
        )
    }

    private companion object {
        const val REQUEST_ID = "11111111-1111-4111-8111-111111111111"
        const val COMMAND_ID = "22222222-2222-4222-8222-222222222222"
        const val TASK_ID = "33333333-3333-4333-8333-333333333333"
    }
}
