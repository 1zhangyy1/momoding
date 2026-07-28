package app.momoding.wire

object P1aProtocol {
    const val PROTOCOL_VERSION: Int = 1
    const val PI_VERSION: String = "0.80.6"
    const val HEARTBEAT_INTERVAL_MS: Int = 20_000
    const val MAX_FRAME_BYTES: Int = 1_048_576
    const val MAX_SNAPSHOT_BYTES: Int = 768 * 1024
}
