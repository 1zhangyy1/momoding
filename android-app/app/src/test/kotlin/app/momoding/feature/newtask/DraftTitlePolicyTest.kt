package app.momoding.feature.newtask

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class DraftTitlePolicyTest {
    @Test
    fun usesFirstNonBlankLineAndCollapsesWhitespace() {
        assertEquals("Plan a careful release", DraftTitlePolicy.title("\n  Plan   a\tcareful release  \nignored"))
    }

    @Test
    fun truncatesByUnicodeCodePointWithoutSplittingEmoji() {
        val title = DraftTitlePolicy.title("😀".repeat(121))
        assertEquals(120, title.codePointCount(0, title.length))
        assertEquals("😀".repeat(120), title)
    }

    @Test
    fun rejectsBlankDraft() {
        assertThrows(IllegalArgumentException::class.java) { DraftTitlePolicy.title(" \n\t") }
    }
}
