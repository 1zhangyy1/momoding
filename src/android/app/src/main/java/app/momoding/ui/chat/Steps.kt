package app.momoding.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import app.momoding.ui.Tones
import com.openminis.app.R
import com.openminis.app.ui.chat.AssistantBlock
import com.openminis.app.ui.chat.ToolBlockStatus
import com.openminis.app.ui.theme.ChatColors
import app.momoding.ui.Design

/*
 * How a turn's work is shown (Grok Bot's "Steps"): one line opening the turn sums it up —
 * "✓ 做了 3 步 · 18 秒 ›"; tapped, the steps unfold right under it in order, one plain line each,
 * on a thin rail, and the reply follows.
 */

/** The line that opens a finished turn's steps. */
@Composable
fun StepsToggle(expanded: Boolean, steps: Int, seconds: Int, onToggle: () -> Unit) {
    val what = buildString {
        append(pluralStringResource(R.plurals.mo_steps_count, steps, steps))
        if (seconds > 0) append(" · ").append(duration(seconds))
    }
    MetaLine(what, Mark.NONE, onClick = onToggle, chevron = expanded) // no check: it is just what happened
}

/** One step, in the words the model gave it, on the rail under the summary. Read-only: no detail viewer (kept simple). */
@Composable
fun StepLine(block: AssistantBlock) {
    val failed = block.toolStatus == ToolBlockStatus.FAILED || block.toolStatus == ToolBlockStatus.TIMEOUT || block.toolStatus == ToolBlockStatus.CANCELLED
    val running = block.toolStatus == ToolBlockStatus.RUNNING || block.toolStatus == ToolBlockStatus.STREAMING || block.toolStatus == ToolBlockStatus.PENDING
    val title = block.toolTitle.takeIf { it.isNotBlank() && it != block.toolName } ?: stringResource(R.string.mo_steps_one)
    Row(Modifier.height(IntrinsicSize.Min).padding(start = 7.dp)) {
        Box(Modifier.width(1.5.dp).fillMaxHeight().background(Tones.hairline))
        Row(Modifier.padding(start = 13.dp, top = 5.dp, bottom = 5.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (running) Text("…", fontSize = Design.Size.Caption, color = ChatColors.secondaryText)
            else Icon(
                if (failed) Icons.Filled.Close else Icons.Filled.Check,
                contentDescription = null, modifier = Modifier.size(12.dp),
                tint = if (failed) MaterialTheme.colorScheme.error else ChatColors.secondaryText,
            )
            Spacer(Modifier.width(8.dp))
            Text(title, fontSize = Design.Size.Note, color = ChatColors.primaryText, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            if (block.durationMs >= 1000) Text("  " + duration((block.durationMs / 1000).toInt()), fontSize = Design.Size.Caption, color = ChatColors.secondaryText)
        }
    }
}

@Composable
private fun duration(seconds: Int): String =
    if (seconds < 60) stringResource(R.string.mo_steps_seconds, seconds)
    else stringResource(R.string.mo_steps_minutes, seconds / 60, seconds % 60)
