// Status line adapted from nanoMuse (GPL-3.0): io/github/nanomuse/ui/header/NanoMuseHeader.kt
package app.momoding.ui.chat

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.momoding.Identity
import app.momoding.guard.RiskGate
import app.momoding.hands.Hands
import app.momoding.ui.Tones
import com.openminis.app.R
import com.openminis.app.agent.SoulStore
import com.openminis.app.service.SessionActivityTracker
import com.openminis.app.ui.theme.ChatColors

/**
 * The main chat's header: Momoding's face, its name, and one line of what it is doing. Idle, the
 * line is empty — nothing to report. With the steps hidden from the chat this line is the whole
 * account of the work, so it never just says "thinking": the step under way, the app and step
 * while operating the screen, "Writing the reply" while words arrive, and between steps the
 * request it is on. Waiting on the person reads as such, in the action colour.
 */
@Composable
fun MomodingHeader(isStreaming: Boolean, replying: Boolean, request: String?) {
    val context = LocalContext.current
    val soul by SoulStore.cachedMetadata.collectAsState()
    val face = remember(soul.name) { Identity.face(context)?.asImageBitmap() }
    val status = rememberStatusLine(isStreaming, replying, request)
    val waiting = status?.second == true
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (face != null) Image(face, contentDescription = null, modifier = Modifier.size(34.dp).clip(CircleShape))
        Column {
            Text(
                soul.name.ifBlank { stringResource(R.string.app_name) },
                fontSize = 16.sp, lineHeight = 19.sp, fontWeight = FontWeight.SemiBold, color = ChatColors.primaryText, maxLines = 1,
                style = NO_FONT_PAD,
            )
            // always reserved, so the header does not jump when the line comes and goes
            Box(Modifier.height(16.dp), contentAlignment = Alignment.CenterStart) {
                if (status != null) Text(
                    status.first, fontSize = 12.sp, lineHeight = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, style = NO_FONT_PAD,
                    color = if (waiting) Tones.action else ChatColors.secondaryText,
                    fontWeight = if (waiting) FontWeight.Medium else FontWeight.Normal,
                )
            }
        }
    }
}

private val NO_FONT_PAD = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))

/** (text, waitingOnThePerson), or null when there is nothing to say. */
@Composable
private fun rememberStatusLine(isStreaming: Boolean, replying: Boolean, request: String?): Pair<String, Boolean>? {
    val toolTitle by SessionActivityTracker.currentToolTitle.collectAsState()
    val toolRunning by SessionActivityTracker.isToolRunning.collectAsState()
    val lastToolTitle by SessionActivityTracker.lastToolTitle.collectAsState()
    val pendingRisk by RiskGate.pending.collectAsState()
    val hands by Hands.progress.collectAsState()
    val h = hands
    return when {
        pendingRisk != null -> stringResource(R.string.mo_status_needs_approval) to true
        h != null -> (if (h.app != null) stringResource(R.string.mo_status_hands_in, h.app, h.step) else stringResource(R.string.mo_status_hands, h.step)) to false
        isStreaming && toolRunning && !toolTitle.isNullOrBlank() -> toolTitle!! to false
        isStreaming && replying -> stringResource(R.string.mo_status_replying) to false
        isStreaming && !lastToolTitle.isNullOrBlank() -> lastToolTitle!! to false
        isStreaming && !request.isNullOrBlank() -> stringResource(R.string.mo_status_on, request) to false
        isStreaming -> stringResource(R.string.mo_status_working) to false
        else -> null
    }
}

/** The request in a breath for the status line: first line, whitespace folded, cut with an ellipsis. */
fun requestBrief(text: String?, max: Int = 24): String? {
    val line = text?.lineSequence()?.map { it.trim() }?.firstOrNull { it.isNotEmpty() }?.replace(Regex("\\s+"), " ") ?: return null
    return if (line.length <= max) line else line.take(max).trimEnd() + "…"
}
