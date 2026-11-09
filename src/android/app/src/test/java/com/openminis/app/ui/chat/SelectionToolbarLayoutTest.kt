package com.openminis.app.ui.chat

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-selection-add-to-input-first] Selection bar: Copy → Add to Chat
 * Input → Copy Full Text → Read Aloud (iOS order), as many inline as fit.
 */
class SelectionToolbarLayoutTest {

    private fun count(widths: List<Float>, max: Float, overflow: Float = 40f) =
        inlineSelectionActionCount(widths, overflow, max, MAX_INLINE_SELECTION_ACTIONS)

    @Test fun `all four fit - no overflow button needed`() {
        assertEquals(4, count(listOf(60f, 120f, 110f, 90f), max = 400f))
    }

    @Test fun `a fifth action goes to the overflow and the button is budgeted`() {
        // 4 inline = 380 + overflow 40 = 420 > 400, so only 3 inline.
        assertEquals(3, count(listOf(60f, 120f, 110f, 90f, 100f), max = 400f))
        assertEquals(4, count(listOf(60f, 120f, 110f, 90f, 100f), max = 430f))
    }

    @Test fun `long labels fold earlier`() {
        assertEquals(2, count(listOf(110f, 230f, 200f, 170f), max = 400f))
    }

    @Test fun `at least one action stays inline and an empty bar is empty`() {
        assertEquals(1, count(listOf(900f, 100f), max = 400f))
        assertEquals(0, count(emptyList(), max = 400f))
    }

}
