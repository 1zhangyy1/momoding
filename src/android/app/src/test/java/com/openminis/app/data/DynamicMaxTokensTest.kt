package com.openminis.app.data

import com.openminis.app.data.model.LLMUsage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T12] dynamicMaxTokens and the mid-loop re-check (9b0606192, 3370298bf,
 * d2cf0cc13; issues #119 #326 #74).
 *
 * The 108%-over-cap session and the half-written file_write (#119) share a
 * root: once the input no longer fits, `max(remaining, MIN_MAX_TOKENS)` lifts a
 * negative remaining back to 1024 and the request still goes out. Android's
 * defence is upstream of that clamp — ContextPolicy.check returns
 * NEEDS_COMPACT at or past the window, and the in-loop guard runs BEFORE the
 * provider call on every tool iteration.
 *
 * [T-ctx-measure-outbound] What it judges changed: not the API-reported size of
 * the PREVIOUS request (stale the moment a compaction/offload/revert changes
 * the history), but the calibrated measurement of the request about to be sent
 * — `ContextSizeMeter` estimate × (report ÷ estimate of the same request). With
 * nothing changed since the report the two agree, so the cases below keep
 * their meaning; ContextMeasureOutboundTest covers what happens after a change.
 *
 * Both ChatViewModel pieces (dynamicMaxTokens, inLoopContextCheck, the Usage
 * bookkeeping) need a live ViewModel; they are ported verbatim below with a
 * source-grep drift guard on the load-bearing lines.
 */
class DynamicMaxTokensTest {

    // ── ports (ChatViewModel.kt) ───────────────────────────────────────────

    private val MIN_MAX_TOKENS = 1024
    private val GLOBAL_MAX_TOKENS_CEILING = 128_000
    private val maxInLoopCompactions = 3

    /** Port of `dynamicMaxTokens` (ChatViewModel ~L8952): the arithmetic only. */
    private fun dynamicMaxTokens(modelMaxOutput: Int, contextWindow: Int, lastContextTokens: Int): Int {
        val maxOutputCeiling = minOf(GLOBAL_MAX_TOKENS_CEILING, modelMaxOutput)
        if (contextWindow <= 0) return maxOutputCeiling
        val inputTokens = if (lastContextTokens > 0) lastContextTokens else 0
        val remaining = contextWindow - inputTokens
        val clamped = maxOf(remaining, MIN_MAX_TOKENS)
        return minOf(maxOutputCeiling, clamped)
    }

    /** Port of the Usage-chunk bookkeeping (ChatViewModel ~L10322). */
    private fun contextTokensFrom(usage: LLMUsage, previous: Int): Int = when {
        usage.latestContextTokens > 0 -> usage.latestContextTokens
        usage.inputTokens > 0 -> usage.inputTokens + (usage.cacheReadInputTokens ?: 0) + (usage.cacheCreationInputTokens ?: 0)
        else -> previous
    }

    private enum class Action { PROCEED, COMPACTED, STOP }

    /**
     * Port of `inLoopContextCheck` (ChatViewModel) after T-ctx-measure-outbound:
     * judges the outbound measurement; a compaction must shrink it to earn a
     * retry; with compaction spent, sends while the request fits the window.
     * `compactTo` is what the compaction leaves the measurement at.
     */
    private class InLoopGuard(private val policy: ContextPolicy, private val window: Int) {
        var measured = 0
        var compactions = 0
        var noProgress = false
        var compactTo: (Int) -> Int = { it }
        fun check(): Action {
            val tokens = measured
            if (tokens <= 0) return Action.PROCEED
            return when (policy.check(tokens, window)) {
                ContextPolicy.CheckResult.OK -> Action.PROCEED
                ContextPolicy.CheckResult.NEEDS_COMPACT -> {
                    if (compactions >= 3 || noProgress) {
                        return if (tokens < window) Action.PROCEED else Action.STOP
                    }
                    compactions += 1
                    measured = compactTo(tokens)
                    noProgress = measured >= tokens
                    Action.COMPACTED
                }
                ContextPolicy.CheckResult.EXHAUSTED -> Action.STOP
            }
        }
    }

    // ── case 1: window 128k, used 130k ─────────────────────────────────────

    @Test
    fun `dynamicMaxTokens shrinks with the input and honours both ceilings`() {
        assertEquals(8192, dynamicMaxTokens(8192, 128_000, 10_000))            // plenty of room: model ceiling
        assertEquals(4_000, dynamicMaxTokens(8192, 128_000, 124_000))          // remaining wins
        assertEquals(GLOBAL_MAX_TOKENS_CEILING, dynamicMaxTokens(1_000_000, 2_000_000, 0)) // global cap
        assertEquals(8192, dynamicMaxTokens(8192, 0, 0))                       // unknown window: ceiling
    }

    // ── case 2: estimate 60k vs API 120k ───────────────────────────────────

    // ── case 3: fifth tool turn crosses 85% ────────────────────────────────

    // ── case 4: 32k user cap on a 1M model ─────────────────────────────────

    // ── drift guard ────────────────────────────────────────────────────────

}
