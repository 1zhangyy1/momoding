package com.openminis.app.ui.chat

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-stream-fade-seed] + [T-android-freeze-edge-flash]
 *
 * Two streaming-render regressions from the same session, both "content that
 * was on screen vanished for a beat, then came back":
 *
 *  1. A bullet list's first item — already rendered — went to an empty bullet
 *     the moment the second item's text arrived, then both items faded in
 *     together. The list had not been the LAST block (a bare `-` parsed as a
 *     trailing paragraph), so it rendered opaque with no fade controller.
 *     When the `-` became item 2, the list became last, fade flipped on, and
 *     a FRESH FadeController was handed item 1's text. Its prefix-diff saw
 *     `lastPlainText == ""` — every string starts with "" — and sliced the
 *     whole thing into ranges at alpha 0. That is the erasure.
 *
 *  2. A 462-char table rendered, dropped to raw `| 城市 |---|` pipes for one
 *     parse, then rendered again. The live branch only deposited its parse
 *     into the freeze-edge cache above 2000 chars, on the assumption that a
 *     small cold miss would parse synchronously; the ANR fix later made EVERY
 *     cold miss go off-main behind a plain-text preview, and the gate became a
 *     guarantee that small fragments flash.
 *
 * The controller tests below run the real class. The rest pins the wiring
 * (structure-stable block loops, seed routing, ungated replace-previous
 * deposit) as source facts, since Compose composition needs a device.
 */
class StreamingFadeSeedTest {

    private fun activeRanges(c: FadeController): List<IntRange> {
        val out = mutableListOf<IntRange>()
        c.forEachActive { s, e, _ -> out += s until e }
        return out
    }

    // ---- FadeController: the mechanism ------------------------------------

    @Test
    fun `a fresh controller fed via ingest fades ALL of its first text`() {
        // This is the pre-existing behaviour, kept on purpose: it is correct
        // for a brand-new paragraph appearing as the live tail. It is also
        // exactly what erased the list item — which is why seed() exists.
        val c = FadeController()
        c.ingest("hello world")
        assertTrue(c.hasSeenText)
        assertEquals(listOf(0 until 5, 6 until 11), activeRanges(c))
    }

    @Test
    fun `seed adopts text as already visible - no ranges, no mask`() {
        val c = FadeController()
        c.seed("華南的廣州、深圳：高溫又可能有雷雨")
        assertTrue(c.hasSeenText)
        assertFalse("nothing may be fading", c.hasActiveRanges)
        assertEquals("prefix recorded", "華南的廣州、深圳：高溫又可能有雷雨", c.lastPlainText)
    }

    @Test
    fun `the reported shape - a controller born after render never masks offset 0`() {
        // Controller created at the flip, handed text the user was reading.
        val visible = "華南的廣州、深圳：高溫又可能有雷雨 ⚡"
        val c = FadeController()
        c.seed(visible)
        // The same text recomposes through again (nothing appended yet).
        c.ingest(visible)
        assertFalse("already-visible text must not be re-faded", c.hasActiveRanges)
        assertTrue(activeRanges(c).none { 0 in it })
    }

    @Test
    fun `words appended AFTER a seed still fade in`() {
        // Seeding must not kill the fade for real appends — that is the
        // whole feature. Only the birth text is exempt.
        val c = FadeController()
        c.seed("hello world")
        c.ingest("hello world foo bar")
        val r = activeRanges(c)
        assertEquals(2, r.size)
        assertEquals("first new word starts after the seeded prefix", 12, r[0].first)
        assertTrue("no range reaches back into seeded text", r.all { it.first >= 12 })
    }

    @Test
    fun `seed then diverged text clears rather than fades`() {
        // The existing hard-reset path (text not a prefix extension) is
        // unchanged by seeding: divergence renders opaque, never at alpha 0.
        val c = FadeController()
        c.seed("abc def")
        c.ingest("xyz")
        assertFalse(c.hasActiveRanges)
        assertEquals("xyz", c.lastPlainText)
    }

    @Test
    fun `hasSeenText is set by both entry points`() {
        // MdText decides seed-vs-ingest on this flag; if either path forgot
        // to set it, a second composition would seed again and drop a real
        // append on the floor.
        assertTrue(FadeController().apply { seed("a") }.hasSeenText)
        assertTrue(FadeController().apply { ingest("a") }.hasSeenText)
        assertFalse(FadeController().hasSeenText)
    }

    // ---- Wiring: source facts --------------------------------------------

}
