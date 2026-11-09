package com.openminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-ctx-warmup-trim-undecided] The warm-up trim is decided once per
 * compaction marker and cached. When no budget can be computed yet (no entry,
 * unknown window, fixed tokens not seeded) the trim passes the warm-up through
 * unchanged — and the caller used to cache that as drop=0, pinning "keep
 * everything" for the marker forever even after the budget became known.
 */
class WarmUpTrimUndecidedTest {

    @Test
    fun `no budget without a known window`() {
        assertNull(warmUpBudget(window = null, compactThreshold = 1000, ratio = 1.0, fixedTokens = 100))
        assertNull(warmUpBudget(window = 0, compactThreshold = 1000, ratio = 1.0, fixedTokens = 100))
    }

    @Test
    fun `no budget without an entry (no context policy)`() {
        assertNull(warmUpBudget(window = 100_000, compactThreshold = null, ratio = 1.0, fixedTokens = 100))
    }

    @Test
    fun `no budget before fixed tokens are seeded`() {
        assertNull(warmUpBudget(window = 100_000, compactThreshold = 85_000, ratio = 1.0, fixedTokens = 0))
    }

    @Test
    fun `budget matches the previous inline formula once everything is known`() {
        // line = compactThreshold, scaled by the calibration ratio, minus fixed.
        assertEquals((85_000 / 1.25).toInt() - 3_000,
            warmUpBudget(window = 100_000, compactThreshold = 85_000, ratio = 1.25, fixedTokens = 3_000))
        // A threshold of 0 means "compact at the window".
        assertEquals(100_000 - 3_000,
            warmUpBudget(window = 100_000, compactThreshold = 0, ratio = 1.0, fixedTokens = 3_000))
    }

}
