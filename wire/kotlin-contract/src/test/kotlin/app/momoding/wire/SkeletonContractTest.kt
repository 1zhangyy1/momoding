package app.momoding.wire

import kotlin.test.Test
import kotlin.test.assertEquals

class SkeletonContractTest {
    @Test
    fun `locks protocol and Pi versions without claiming event decoding`() {
        assertEquals(1, P1aProtocol.PROTOCOL_VERSION)
        assertEquals("0.80.6", P1aProtocol.PI_VERSION)
        assertEquals(20_000, P1aProtocol.HEARTBEAT_INTERVAL_MS)
        assertEquals(1_048_576, P1aProtocol.MAX_FRAME_BYTES)
        assertEquals(768 * 1024, P1aProtocol.MAX_SNAPSHOT_BYTES)
    }
}
