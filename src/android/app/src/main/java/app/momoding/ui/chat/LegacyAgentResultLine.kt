package app.momoding.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.momoding.ui.Design
import com.openminis.app.R
import com.openminis.app.agent.jobs.LegacyAgentCallback

/** Saved reports remain readable; current work uses task rows and task reports. */
@Composable
fun LegacyAgentResultLine(callback: LegacyAgentCallback, onTap: () -> Unit) {
    val context = LocalContext.current
    val title = callback.title.ifBlank { context.getString(R.string.mo_bg_untitled) }
    val text = when {
        callback.kind == LegacyAgentCallback.Kind.PROGRESS -> context.getString(R.string.mo_bg_progress, title)
        callback.status == "done" -> listOfNotNull(context.getString(R.string.mo_bg_done, title), duration(context, callback.elapsed)).joinToString(" · ")
        callback.status == "cancelled" -> context.getString(R.string.mo_bg_stopped, title)
        else -> context.getString(R.string.mo_bg_failed, title)
    }
    Box(Modifier.fillMaxWidth().clickable(onClick = onTap).padding(horizontal = 16.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
        Text("$text ›", fontSize = Design.Size.Caption, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** "16m53s" → "17 分钟", "45s" → "45 秒"; null when there is nothing to show. */
internal fun duration(context: android.content.Context, elapsed: String?): String? {
    val (value, minutes) = durationParts(elapsed) ?: return null
    return context.getString(if (minutes) R.string.mo_bg_minutes else R.string.mo_bg_seconds, value)
}

/** The number to show and whether it is minutes (rounded) or seconds. */
internal fun durationParts(elapsed: String?): Pair<Int, Boolean>? {
    val m = Regex("""^(?:(\d+)m)?(\d+)s$""").find(elapsed?.trim().orEmpty()) ?: return null
    val min = m.groupValues[1].toIntOrNull() ?: 0
    val sec = m.groupValues[2].toInt()
    return if (min == 0) sec to false else (if (sec >= 30) min + 1 else min) to true
}
