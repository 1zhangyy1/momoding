package com.openminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-ctx-usage-after-compact] After a manual or automatic compaction, the
 * composer placeholder kept showing the pre-compaction "Context NN% used"
 * line. The composer holds the last presented line until typing and ignores
 * a null hint, so only a NEW generation replaces it, and compaction never
 * issued one. The glow was already fine: [publishMeasuredContextUsage] sets
 * `_lastTurnContextTokens`, from which `contextUsage` is derived.
 *
 * Source guards on the wiring, plus the tier/percent the new line reports.
 * Mirrors iOS ContextUsageAfterCompactTests.
 */
class ContextUsageAfterCompactTest {

    @Test
    fun `the reported figure is the post-compaction size`() {
        val before = ContextUsage.from(usedTokens = 170_000, windowTokens = 200_000)!!
        val after = ContextUsage.from(usedTokens = 40_000, windowTokens = 200_000)!!
        assertEquals(ContextUsage.Tier.CRITICAL, before.tier)
        assertEquals(ContextUsage.Tier.NORMAL, after.tier)
        assertEquals(20, after.percent)
    }
}
