package app.momoding.core.transport

import kotlin.math.floor

class FullJitterReconnectPolicy(
    private val randomUnit: () -> Double = Math::random,
) {
    fun delayMillis(attempt: Int): Long {
        require(attempt in 1..MAX_AUTOMATIC_ATTEMPTS) { "Reconnect attempt is invalid" }
        val unit = randomUnit()
        require(unit >= 0.0 && unit < 1.0 && unit.isFinite()) { "Reconnect random source is invalid" }
        val cap = CAPS_MILLIS[attempt - 1]
        return floor(unit * (cap + 1.0)).toLong().coerceAtMost(cap)
    }

    companion object {
        const val MAX_AUTOMATIC_ATTEMPTS = 8
        private val CAPS_MILLIS = longArrayOf(1_000, 2_000, 4_000, 8_000, 16_000, 30_000, 30_000, 30_000)
    }
}
