package app.momoding.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** momoding: a routine pauses only after enough unread fires AND a long enough absence. */
class RoutineAttentionTest {
    private val now = 10_000_000_000L

    @Test fun `few unread fires never pause`() =
        assertFalse(RoutineAttention.shouldPause(2, lastSeenAt = now - 10 * RoutineAttention.AWAY_MS, now = now))

    @Test fun `frequent routine while person was here recently does not pause`() =
        assertFalse(RoutineAttention.shouldPause(10, lastSeenAt = now - 60_000, now = now))

    @Test fun `unread for days pauses`() =
        assertTrue(RoutineAttention.shouldPause(3, lastSeenAt = now - RoutineAttention.AWAY_MS, now = now))
}
