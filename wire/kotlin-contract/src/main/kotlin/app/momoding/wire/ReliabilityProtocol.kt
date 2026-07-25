package app.momoding.wire

object ReliabilityProtocol {
    const val MAX_SAFE_INTEGER: Long = 9_007_199_254_740_991L
    const val DIRECT_PI_EVENT_MAX_BYTES: Int = 1024 * 1024
    const val CHUNK_MAX_PHYSICAL_FRAME_BYTES: Int = 768 * 1024
    const val CHUNK_MAX_TRANSFER_BYTES: Int = 64 * 1024 * 1024
    const val CHUNK_MAX_COUNT: Int = 256
    const val CHUNK_TIMEOUT_MS: Int = 30_000
    const val SNAPSHOT_PAGE_MAX_PHYSICAL_BYTES: Int = 768 * 1024
    const val SNAPSHOT_MAX_LOGICAL_BYTES: Int = 64 * 1024 * 1024
    const val SNAPSHOT_MAX_PAGES: Int = 256
    const val HISTORY_MAX_BYTES: Int = 8 * 1024 * 1024
    const val MAX_DEVICE_ITEMS: Int = 256
    const val MAX_JSON_DEPTH: Int = 64
}
