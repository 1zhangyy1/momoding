package app.momoding.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BackgroundResultLineTest {
    @Test
    fun `elapsed reads as whole minutes, or seconds under a minute`() {
        assertEquals(17 to true, durationParts("16m53s"))
        assertEquals(16 to true, durationParts("16m05s"))
        assertEquals(45 to false, durationParts("45s"))
        assertNull(durationParts(null))
        assertNull(durationParts("soon"))
    }
}
