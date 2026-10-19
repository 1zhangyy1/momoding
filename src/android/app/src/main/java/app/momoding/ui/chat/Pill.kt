package app.momoding.ui.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import app.momoding.ui.Design
import app.momoding.ui.Tones

/** Grey: something to say next (a suggested step, an example). Outlined: somewhere to go (在日历里看). */
enum class PillKind { SUGGEST, LINK }

/** The one pill button in the chat; the same height, padding and type everywhere it appears. */
@Composable
fun Pill(text: String, kind: PillKind, onClick: () -> Unit) {
    val shape = CircleShape
    Text(
        text, fontSize = Design.Size.Label, lineHeight = Design.Line.Label, color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.clip(shape)
            .then(if (kind == PillKind.LINK) Modifier.background(Tones.surface).border(BorderStroke(1.dp, Tones.hairline), shape) else Modifier.background(Tones.fill))
            .clickable(onClick = onClick)
            .heightIn(min = 38.dp).wrapContentHeight()
            .padding(horizontal = Design.Space.Gutter, vertical = 9.dp),
    )
}
