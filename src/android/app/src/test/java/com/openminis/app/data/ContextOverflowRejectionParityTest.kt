package com.openminis.app.data

import com.openminis.app.ProductionSources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Which provider rejections Android recognises as "the request has too many
 * tokens", compared with iOS, and what the loop does with the valve after one.
 *
 * Guards:
 *   - 9aad0ffb5 / df35445ee  T-ctx-measure-outbound — noteContextOverflow raises
 *     the calibration ratio, but only for errors [ContextOverflowGuard] accepts;
 *   - 6a3f9e0da  T-ctx-overflow-no-cross-fallback — a direct-entry session must
 *     not fall back to another vendor on an overflow, which ALSO keys off
 *     [ContextOverflowGuard];
 *   - 0e0a8b339  T-ctx-valve-rearm — "a rejection spends the valve".
 *
 * iOS classifies with `ContextSizeMeter.isContextOverflow` (ContextPolicy.swift),
 * Android with [ContextOverflowGuard]; the two lists have drifted. Every miss on
 * Android means: no ratio raise, no GH#352 self-heal, and — because a
 * ProviderError is `isFallbackable` — a silent answer from a different model
 * on a session pinned to one.
 *
 * Tests marked EXPECTED TO FAIL document bugs found in review; they stay red
 * until production is fixed.
 */
class ContextOverflowRejectionParityTest {

    private val repoRoot: File by lazy {
        var dir: File? = ProductionSources.mainRoot()
        while (dir != null && !File(dir, "src/ios").isDirectory) dir = dir.parentFile
        requireNotNull(dir) { "repo root with src/ios not found" }
    }

    private fun iosMarkers(): List<String> {
        val src = File(repoRoot, "src/ios/Agent/Chat/ContextPolicy.swift").readText()
        val start = src.indexOf("static let overflowMarkers = [")
        require(start >= 0) { "iOS overflowMarkers not found" }
        val body = src.substring(start, src.indexOf(']', start))
        return Regex("\"([^\"]+)\"").findAll(body).map { it.groupValues[1] }.toList()
    }

    /**
     * Anthropic's input + max_tokens rejection matches no marker on either
     * platform. EXPECTED TO FAIL until fixed.
     */
    @Test
    fun `Anthropic input plus max_tokens over the limit is an overflow`() {
        assertTrue(
            ContextOverflowGuard.isContextOverflow(
                400,
                "[invalid_request_error] input length and `max_tokens` exceed context limit: " +
                    "188240 + 21333 > 200000, decrease input length or `max_tokens` and try again",
            ),
        )
    }

    @Test
    fun `a per-minute token rate limit is still not an overflow`() {
        assertFalse(ContextOverflowGuard.isContextOverflow(429, "too many tokens per minute"))
    }

    /** What the valve does on that retry, through the production decision table. */
    @Test
    fun `after a rejection the retry stops rather than re-sending`() {
        val window = 128_000
        val raw = 120_000
        val ratio = ContextSizeMeter.ratioAfterOverflow(1.0, raw, 156_000, window)
        val measured = ContextSizeMeter.calibrated(raw, ratio)
        val step = ContextPolicy.inLoopStep(
            verdict = ContextPolicy.CheckResult.NEEDS_COMPACT,
            measured = measured,
            rawTokens = raw,
            window = window,
            canCompact = false,
            ratio = ratio,
            uncalibratedSendUsed = true, // the rejection spent it
        )
        assertEquals(ContextPolicy.InLoopStep.STOP, step)
    }
}
