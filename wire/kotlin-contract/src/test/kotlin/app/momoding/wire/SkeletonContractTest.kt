package app.momoding.wire

import kotlin.test.Test
import kotlin.test.assertEquals

class SkeletonContractTest {
    @Test
    fun `locks protocol and Pi versions without claiming event decoding`() {
        assertEquals(1, CoreProtocol.PROTOCOL_VERSION)
        assertEquals("0.80.6", CoreProtocol.PI_VERSION)
        assertEquals(20_000, CoreProtocol.HEARTBEAT_INTERVAL_MS)
        assertEquals(1_048_576, CoreProtocol.MAX_FRAME_BYTES)
        assertEquals(768 * 1024, CoreProtocol.MAX_SNAPSHOT_BYTES)
    }
}
