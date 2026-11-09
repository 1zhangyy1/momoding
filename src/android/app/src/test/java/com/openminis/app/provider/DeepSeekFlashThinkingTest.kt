package com.openminis.app.provider

import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.provider.thinking.ThinkingRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-deepseek-flash-scope] — GH#356, port of iOS 466b00f9b.
 *
 * DeepSeek now recommends the bare `deepseek-flash` (`deepseek-v4-flash` is
 * kept only as a legacy alias; both are served by the same
 * DeepSeek-V4.1-Flash). The vendor-native thinking rule was scoped
 * `*deepseek-v4*`, which the new id does not satisfy, so it fell through to
 * the generic `reasoning_effort` default — the wrong wire shape for this
 * vendor — and the catalog's XHIGH default offered a tier DeepSeek rejects.
 *
 * The scope claim is exercised against the SHIPPING glob
 * (`ThinkingRule.Scope.matches`), not a reimplementation, so a change to the
 * matcher's semantics is caught here rather than silently re-breaking the id.
 */
class DeepSeekFlashThinkingTest {

    private fun claims(pattern: String, modelId: String): Boolean =
        ThinkingRule.Scope.ModelPattern(pattern).matches(modelId)

    // ---- The bug: the legacy pattern misses the id DeepSeek recommends -----

    @Test
    fun `the legacy pattern does not match the bare id`() {
        // Pre-fix behaviour, pinned so the reason for the sibling rule stays
        // legible: this is why `deepseek-flash` fell through to the generic
        // default.
        assertFalse(claims("*deepseek-v4*", "deepseek-flash"))
        assertFalse(claims("*deepseek-v4*", "deepseek-flash-lite"))
    }

    @Test
    fun `the new rule claims the bare id and its lite sibling`() {
        assertTrue(claims("deepseek-flash*", "deepseek-flash"))
        assertTrue(claims("deepseek-flash*", "deepseek-flash-lite"))
    }

    @Test
    fun `the legacy alias stays on the original rule`() {
        // Both rules are OFFICIAL_VENDOR with the same wire format, so an id
        // matching both would be harmless — but they must not overlap by
        // accident, or a later edit to one would silently change the other's
        // coverage.
        assertTrue(claims("*deepseek-v4*", "deepseek-v4-flash"))
        assertTrue(claims("*deepseek-v4*", "deepseek-v4-pro"))
        assertFalse(claims("deepseek-flash*", "deepseek-v4-flash"))
        assertFalse(claims("deepseek-flash*", "deepseek-v4-pro"))
    }

    @Test
    fun `chat and reasoner are NOT claimed`() {
        // The anchored pattern is the point. A naive `*deepseek*` would sweep
        // these in, and their wire format may differ.
        for (id in listOf("deepseek-chat", "deepseek-reasoner")) {
            assertFalse("`deepseek-flash*` must not claim $id", claims("deepseek-flash*", id))
            assertFalse("`*deepseek-v4*` must not claim $id", claims("*deepseek-v4*", id))
        }
    }

    @Test
    fun `dotted spellings normalise to the same claim`() {
        // `Scope.matches` maps "." to "-" before globbing, because third-party
        // proxies publish dotted ids. A relay serving "deepseek.flash" must
        // land on the same rule.
        assertTrue(claims("deepseek-flash*", "deepseek.flash"))
        assertTrue(claims("*deepseek-v4*", "deepseek.v4.flash"))
    }

    // ---- The ceiling fallback ---------------------------------------------

    @Test
    fun `the catalog caps deepseek at max, not the xhigh default`() {
        // Consulted only when the model declares no tiers of its own. Without
        // it the conservative XHIGH default offers a level DeepSeek refuses.
        assertEquals(ThinkingLevel.MAX, ThinkingLevelCatalog.declaredMaxLevel("deepseek-flash"))
        assertEquals(ThinkingLevel.MAX, ThinkingLevelCatalog.declaredMaxLevel("deepseek-flash-lite"))
        assertEquals(ThinkingLevel.MAX, ThinkingLevelCatalog.declaredMaxLevel("deepseek-v4-flash"))
        assertEquals(ThinkingLevel.MAX, ThinkingLevelCatalog.declaredMaxLevel("deepseek-v4-pro"))
    }

    @Test
    fun `the catalog rule does not lift other vendors`() {
        // The neighbouring HIGH ceilings must be untouched by this addition.
        assertEquals(ThinkingLevel.HIGH, ThinkingLevelCatalog.declaredMaxLevel("mimo-v2.5"))
        assertEquals(ThinkingLevel.HIGH, ThinkingLevelCatalog.declaredMaxLevel("seed-2.0"))
    }

    // ---- Source facts ------------------------------------------------------

}
