package app.momoding.ui.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.momoding.ui.Design
import app.momoding.ui.Tones
import com.openminis.app.ui.theme.ChatColors

/** Green-outlined: something to say next (a suggested step, an example). Grey-outlined with ↗: somewhere to go (在日历里看). */
enum class PillKind { SUGGEST, LINK }

/**
 * The one pill button in the chat; the same height, padding and type everywhere it appears.
 * Smaller than a bubble and outlined on white, so a button never reads as something said.
 */
@Composable
fun Pill(text: String, kind: PillKind, onClick: () -> Unit) {
    val shape = CircleShape
    val link = kind == PillKind.LINK
    Row(
        Modifier.clip(shape).background(Tones.surface)
            .border(BorderStroke(1.dp, if (link) Tones.hairline else Tones.action.copy(alpha = .45f)), shape)
            .clickable(onClick = onClick)
            .heightIn(min = 34.dp)
            .padding(start = 12.dp, end = if (link) 10.dp else 12.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Text(
            text, fontSize = Design.Size.Note, lineHeight = Design.Line.Note,
            fontWeight = if (link) FontWeight.Normal else FontWeight.Medium,
            color = if (link) MaterialTheme.colorScheme.onSurface else Tones.action,
        )
        if (link) Icon(Icons.AutoMirrored.Filled.OpenInNew, null, Modifier.size(13.dp), tint = ChatColors.secondaryText)
    }
}
