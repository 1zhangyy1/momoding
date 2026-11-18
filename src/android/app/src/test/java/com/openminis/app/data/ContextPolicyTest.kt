package com.openminis.app.data

import com.openminis.app.data.ContextPolicy.CheckResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * momoding: one rule for every model. A conversation works within a budget (200K, or the group's
 * cap), never more than the model's window less room for the reply; it compacts when less than
 * 5% of that is left, and first moves old tool output to disk at 70%.
 */
class ContextPolicyTest {

    @Test fun `a 1M model compacts near 190K, not near its window`() {
        val p = ContextPolicy.forModel(1_048_576)
        assertEquals(200_000, p.budget)
        assertEquals(180_000, p.backgroundStart)
        assertEquals(190_000, p.compactThreshold)
        assertEquals(140_000, p.offloadThreshold)
        assertEquals(110_000, p.offloadTarget)
        assertEquals(CheckResult.OK, p.check(189_999, 1_048_576))
        assertEquals(CheckResult.NEEDS_COMPACT, p.check(190_000, 1_048_576))
    }

    @Test fun `a smaller model keeps room for the reply`() {
        assertEquals(180_000, ContextPolicy.forModel(200_000).budget)
        val p = ContextPolicy.forModel(128_000)
        assertEquals(108_000, p.budget)
        assertEquals(102_600, p.compactThreshold)
        assertEquals(25_600, ContextPolicy.forModel(32_000).budget) // a fifth of a small window
    }

    @Test fun `the group's cap is the budget, within the model's window`() {
        assertEquals(64_000, ContextPolicy.forModel(1_048_576, cap = 64_000).budget)
        assertEquals(59_000, ContextPolicy.forModel(1_048_576, cap = 64_000).compactThreshold)
        assertEquals(108_000, ContextPolicy.forModel(128_000, cap = 500_000).budget)
        assertEquals(200_000, ContextPolicy.forModel(1_048_576, cap = 0).budget)
    }

    @Test fun `switching to a smaller model judges the same history by that model's budget`() {
        val tokens = 150_000
        assertEquals(CheckResult.OK, ContextPolicy.forModel(1_048_576).check(tokens, 1_048_576))
        assertEquals(CheckResult.NEEDS_COMPACT, ContextPolicy.forModel(128_000).check(tokens, 128_000))
    }

    @Test fun `too small for a summary - only report the chat is full`() {
        val p = ContextPolicy.forModel(16_000)
        assertTrue(p.exhaustedOnly)
        assertFalse(p.manualCompactAllowed)
        assertEquals(0, p.compactThreshold)
        assertEquals(CheckResult.OK, p.check(1_000, 16_000))
        assertEquals(CheckResult.EXHAUSTED, p.check(15_000, 16_000))
    }

    @Test fun `every usable model can compact, and only at or past its line`() {
        for (window in listOf(32_000, 64_000, 128_000, 200_000, 400_000, 1_048_576)) {
            val p = ContextPolicy.forModel(window)
            assertFalse("$window", p.exhaustedOnly)
            assertTrue("$window", p.offloadTarget < p.offloadThreshold && p.offloadThreshold < p.compactThreshold)
            assertTrue("$window", p.compactThreshold < p.budget && p.budget < window)
            assertTrue("$window", p.backgroundStart in 1 until p.compactThreshold)
            assertEquals("$window", CheckResult.OK, p.check(p.compactThreshold - 1, window))
            assertEquals("$window", CheckResult.NEEDS_COMPACT, p.check(p.compactThreshold, window))
        }
    }
}
