package app.momoding

import app.momoding.wire.HelloAcceptedFrame
import app.momoding.wire.CoreProtocol
import app.momoding.wire.CoreServerFrameDecoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SharedWireConsumerTest {
    @Test
    fun `formal Android app consumes the shared protocol constants`() {
        assertEquals(1, CoreProtocol.PROTOCOL_VERSION)
        assertEquals("0.80.6", CoreProtocol.PI_VERSION)
        assertEquals(1_048_576, CoreProtocol.MAX_FRAME_BYTES)
    }

    @Test
    fun `formal Android app decodes a server frame through the shared decoder`() {
        val frame = CoreServerFrameDecoder.decode(
            """
            {
              "protocolVersion": 1,
              "kind": "hello.accepted",
              "requestId": "android-bootstrap",
              "connectionId": "synthetic-connection",
              "serverVersion": "0.1.0-fixture",
              "piVersion": "0.80.6",
              "heartbeatIntervalMs": 20000,
              "maxFrameBytes": 1048576
            }
            """.trimIndent(),
        )

        assertTrue(frame is HelloAcceptedFrame)
        assertEquals("synthetic-connection", (frame as HelloAcceptedFrame).connectionId)
    }
}
