package app.momoding.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

/** The few colours Momoding's own cards use (approval cards, grants), in both themes. */
object Tones {
    private val dark: Boolean
        @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.background.luminance() < 0.5f

    /** Cards and sheets. */
    val surface: Color
        @Composable @ReadOnlyComposable get() = if (dark) Color(0xFF1C2019) else Color.White

    /** A secondary button, a selected row. */
    val fill: Color
        @Composable @ReadOnlyComposable get() = if (dark) Color(0xFF22271F) else Color(0xFFF2F1EB)

    /** Hairlines and separators. */
    val hairline: Color
        @Composable @ReadOnlyComposable get() = if (dark) Color(0xFF2E3429) else Color(0xFFE8E6DC)

    /** The primary action: Momoding's deep green, readable with white text. */
    val action: Color = Color(0xFF2E7A12)
}
