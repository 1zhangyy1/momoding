package app.momoding.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.momoding.ui.Tones
import com.openminis.app.ui.theme.ChatColors
import app.momoding.ui.Design

/** What the mark slot of a [MetaLine] says. */
enum class Mark { DONE, UNDONE, NONE }

/**
 * The one kind of quiet line in the chat (design rules in 阶段UI-目标.md): 12sp, secondary colour,
 * a 16dp mark slot (✓ done in Momoding green, – not done, or blank), the words, and optionally a
 * green action or a chevron. Steps, remembered facts, decisions, sent / discarded / expired cards
 * all use it, so they read as one family.
 */
@Composable
fun MetaLine(
    text: String,
    mark: Mark = Mark.NONE,
    action: String? = null,
    onAction: (() -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    chevron: Boolean? = null, // null: none; false: pointing right (folded); true: pointing down (open)
) {
    Row(
        Modifier.padding(vertical = 2.dp).clip(RoundedCornerShape(Design.Radius.Press))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(top = 3.dp, bottom = 3.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(16.dp), contentAlignment = Alignment.Center) {
            when (mark) {
                Mark.DONE -> Box(Modifier.size(14.dp).clip(CircleShape).background(Tones.action.copy(alpha = .14f)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Check, contentDescription = null, tint = Tones.actionText, modifier = Modifier.size(10.dp))
                }
                Mark.UNDONE -> Box(Modifier.size(width = 8.dp, height = 1.5.dp).background(ChatColors.secondaryText))
                Mark.NONE -> Unit
            }
        }
        Text(text, fontSize = Design.Size.Caption, lineHeight = Design.Line.Caption, color = ChatColors.secondaryText, maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 6.dp).weight(1f, fill = false))
        if (action != null) Text(
            " · $action", fontSize = Design.Size.Caption, lineHeight = Design.Line.Caption, color = Tones.actionText, fontWeight = FontWeight.Medium,
            modifier = if (onAction != null) Modifier.clickable(onClick = onAction) else Modifier,
        )
        if (chevron != null) Chevron(open = chevron)
    }
}

/** The one "there is more" mark: a small chevron, pointing down when [open]. */
@Composable
fun Chevron(open: Boolean = false, tint: androidx.compose.ui.graphics.Color = ChatColors.secondaryText) {
    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = tint, modifier = Modifier.size(16.dp).rotate(if (open) 90f else 0f))
}
