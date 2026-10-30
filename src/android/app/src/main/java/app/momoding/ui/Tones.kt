package app.momoding.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance

/**
 * A Bot's own colour, inside its chat: there it is Momoding with its own name, face and colour, so
 * the accents below take this one in place of Momoding's green. Null in the main Momoding.
 */
val LocalAccent = staticCompositionLocalOf<Color?> { null }

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

    /** The brand greens as plain ARGB, for the screen overlays drawn with Android views (capsule, glow). */
    const val ACTION_ARGB = 0xFF2E7A12.toInt()
    const val BRIGHT_ARGB = 0xFF6CC24A.toInt() // on dark glass: the capsule's ring, the glow, the aim
    const val LIME_ARGB = 0xFFA6E07A.toInt() // the trail of a moving finger

    /** The primary action: Momoding's deep green (or the Bot's colour), readable with white text. */
    val action: Color
        @Composable @ReadOnlyComposable get() = LocalAccent.current ?: Color(0xFF2E7A12)

    /** Green text and outlines on the page (next-step pills, live status): deep green, bright on dark. */
    val actionText: Color
        @Composable @ReadOnlyComposable get() = LocalAccent.current?.let { if (dark) lerp(it, Color.White, 0.35f) else it }
            ?: if (dark) Color(0xFF6CC24A) else Color(0xFF2E7A12)

    /** The person's bubble: Momoding green, a step stronger than the prototype's brand-soft so the turns read apart. */
    val userBubble: Color
        @Composable @ReadOnlyComposable get() = LocalAccent.current?.copy(alpha = if (dark) 0.30f else 0.16f)?.compositeOver(MaterialTheme.colorScheme.background)
            ?: if (dark) Color(0xFF2A3D1F) else Color(0xFFDFF2CF)

    /** Momoding's bubble: the prototype's warm grey surface-2. */
    val agentBubble: Color
        @Composable @ReadOnlyComposable get() = if (dark) Color(0xFF22271F) else Color(0xFFF2F1EB)
}
