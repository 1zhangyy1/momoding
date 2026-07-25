package app.momoding.ui.icons

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MomodingIconsTest {
    @Test
    fun auditedRegularFamily_hasSixteenUnique24DpGlyphs() {
        val icons = MomodingIcons.auditedRegular

        assertEquals(16, icons.size)
        assertEquals(16, icons.map { it.name }.toSet().size)
        assertTrue(icons.all { it.defaultWidth == 24.dp && it.defaultHeight == 24.dp })
    }

    @Test
    fun filledFamily_isLimitedToActiveControls() {
        val icons = listOf(
            MomodingFilledIcons.Tasks,
            MomodingFilledIcons.Settings,
            MomodingFilledIcons.Send,
            MomodingFilledIcons.Stop,
        )

        assertEquals(4, icons.map { it.name }.toSet().size)
        assertTrue(icons.all { it.defaultWidth == 24.dp && it.defaultHeight == 24.dp })
    }
}
