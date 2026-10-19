package app.momoding.ui.chat

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.material.icons.filled.Check
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.momoding.ui.Tones
import com.openminis.app.ui.theme.ChatColors
import app.momoding.ui.Design

/*
 * Grok Bot's transcript cards (card kit in its renderer, `_a` styles):
 *   - something waiting on the person (a draft, a permission) is an elevated card: page-white,
 *     a hairline ring and a soft shadow, large corners;
 *   - anything else (a question with options) sits on the agent-bubble fill, 16dp corners, 12dp in;
 *   - once settled, a card folds into one line saying what happened ("已发送至 X：“…”").
 */

/** A card that waits for the person's decision. */
@Composable
fun DecisionCard(content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(Design.Radius.Card)
    Column(
        Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 4.dp)
            .shadow(2.dp, shape, clip = false, ambientColor = Color.Black.copy(alpha = .06f), spotColor = Color.Black.copy(alpha = .06f))
            .clip(shape).background(Tones.surface).border(BorderStroke(1.dp, Tones.hairline), shape)
            .animateContentSize()
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        content = content,
    )
}

/** A card in Momoding's own voice, on the bubble fill. */
@Composable
fun QuietCard(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 4.dp, end = 40.dp)
            .clip(RoundedCornerShape(Design.Radius.Panel)).background(Tones.agentBubble)
            .animateContentSize()
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}

/** "Draft · to 张阿姨 · SMS": what the card is, then what it is about. */
@Composable
fun CardTitlebar(label: String, summary: String? = null, labelColor: Color = Tones.action) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = Design.Size.Note, fontWeight = FontWeight.SemiBold, color = labelColor)
        if (!summary.isNullOrBlank()) {
            Text("  ·  $summary", fontSize = Design.Size.Note, color = ChatColors.secondaryText, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
fun CardTitle(text: String) {
    Text(text, fontSize = Design.Size.Body, lineHeight = Design.Line.Body, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
}

/** What a settled card leaves behind: one [MetaLine], ✓ when it went through. */
@Composable
fun SettledLine(text: String, done: Boolean = true) = MetaLine(text, if (done) Mark.DONE else Mark.UNDONE)

/** The card's buttons, right-aligned, the main one last (Grok: secondary actions before the primary). */
@Composable
fun CardActions(content: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
        content()
    }
}

@Composable
fun PillButton(text: String, primary: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    Text(
        text, fontSize = Design.Size.Body, fontWeight = if (primary) FontWeight.SemiBold else FontWeight.Medium,
        color = when {
            !enabled -> ChatColors.secondaryText
            primary -> Color.White
            else -> MaterialTheme.colorScheme.onSurface
        },
        modifier = Modifier.clip(CircleShape)
            .background(if (primary && enabled) Tones.action else Tones.fill)
            .clickable(enabled = enabled, onClick = onClick)
            .heightIn(min = 40.dp).padding(horizontal = 18.dp, vertical = 9.dp),
    )
}

/** One option of a question: its number (a check once picked), then the words. */
@Composable
fun OptionRow(index: Int, text: String, picked: Boolean, settled: Boolean, onPick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().alpha(if (settled && !picked) .4f else 1f)
            .clip(RoundedCornerShape(Design.Radius.Row)).background(Tones.surface)
            .clickable(enabled = !settled, onClick = onPick)
            .heightIn(min = 44.dp).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(22.dp).clip(CircleShape).background(if (picked) Tones.action else Tones.fill),
            contentAlignment = Alignment.Center,
        ) {
            if (picked) androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Filled.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
            else Text("$index", fontSize = Design.Size.Caption, fontWeight = FontWeight.SemiBold, color = ChatColors.secondaryText)
        }
        Text(text, fontSize = Design.Size.Body, lineHeight = Design.Line.Body, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(start = 10.dp))
    }
}
