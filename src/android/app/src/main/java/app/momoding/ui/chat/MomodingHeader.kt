// Status line adapted from nanoMuse (GPL-3.0): io/github/nanomuse/ui/header/NanoMuseHeader.kt
package app.momoding.ui.chat

import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import app.momoding.ui.Design

/**
 * A Momoding's header: its face, its name, and one quiet line ("在你手机上 · 随时可以交代"); then the
 * faces of the person's other Momodings ([BotFaces]). A tap on the name opens them all ([BotList]).
 * The same header is on the main chat, a Bot's chat, and an app's chat panel.
 * What it is doing right now is not up here: it opens the running turn in the chat
 * ([LiveStatusLine]) and folds into that turn's "做了 3 步" when it is done.
 */
@Composable
fun MomodingHeader(sessionId: String? = null) {
    val context = LocalContext.current
    val soul by SoulStore.cachedMetadata.collectAsState()
    val uiMode = androidx.compose.ui.platform.LocalConfiguration.current.uiMode
    // another Momoding: the face and name of the Bot (its app's icon, or its letter)
    val ownApp = remember(sessionId) { sessionId?.let { app.momoding.apps.MoApps.ofSession(context, it) } }
    val face = remember(soul.name, uiMode, ownApp?.name) {
        (ownApp?.let { app.momoding.apps.AppIcon.bitmap(it, 96, adaptive = false) } ?: Identity.face(context))?.asImageBitmap()
    }
    var listOpen by remember { androidx.compose.runtime.mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) {
            Row(
                Modifier.clip(RoundedCornerShape(Design.Radius.Press)).clickable(onClickLabel = stringResource(R.string.mo_bots_switch)) { listOpen = !listOpen },
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                // A fixed slot, so the name never shifts while the face loads
                Box(Modifier.size(34.dp).clip(CircleShape)) {
                    if (face != null) Image(face, contentDescription = null, modifier = Modifier.size(34.dp))
                }
                Column(Modifier.weight(1f, fill = false)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            ownApp?.title ?: soul.name.ifBlank { stringResource(R.string.app_name) },
                            fontSize = Design.Size.Title, lineHeight = Design.Line.Caption, fontWeight = FontWeight.SemiBold, color = ChatColors.primaryText, maxLines = 1,
                            overflow = TextOverflow.Ellipsis, style = NO_FONT_PAD, modifier = Modifier.weight(1f, fill = false),
                        )
                        androidx.compose.material3.Icon(
                            androidx.compose.material.icons.Icons.Outlined.ExpandMore, null, tint = ChatColors.secondaryText,
                            modifier = Modifier.size(20.dp).graphicsLayer { rotationZ = if (listOpen) 180f else 0f },
                        )
                    }
                    Text(
                        stringResource(when { ownApp == null -> R.string.mo_status_idle; ownApp.hasApp -> R.string.mo_app_chat_status; else -> R.string.mo_bot_chat_status }),
                        fontSize = Design.Size.Caption, lineHeight = Design.Line.Micro, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, style = NO_FONT_PAD, color = ChatColors.secondaryText,
                    )
                }
            }
            BotList(listOpen, sessionId) { listOpen = false }
        }
        BotFaces(sessionId) { listOpen = true }
    }
}

/**
 * The line that opens a running turn: what Momoding is doing now — the step under way, the app
 * and step while on your screen, "在它的浏览器里 · …" (tap to watch), "正在写回复", "需要你决定".
 * A pulsing dot marks it live; when the turn ends the same place holds "做了 3 步 · 18 秒".
 */
@Composable
fun LiveStatusLine(sessionId: String, isStreaming: Boolean, replying: Boolean, request: String?, onOpenBrowser: () -> Unit) {
    val status = rememberStatusLine(sessionId, isStreaming, replying, request) ?: return
    val accent = status.waiting || status.inBrowser
    val pulse = androidx.compose.animation.core.rememberInfiniteTransition(label = "live").animateFloat(
        initialValue = .35f, targetValue = 1f, animationSpec = androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(700), androidx.compose.animation.core.RepeatMode.Reverse), label = "dot",
    )
    Row(
        Modifier.padding(vertical = 2.dp).clip(RoundedCornerShape(Design.Radius.Press))
            .then(if (status.inBrowser) Modifier.clickable(onClick = onOpenBrowser) else Modifier)
            .padding(top = 3.dp, bottom = 3.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(16.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.size(7.dp).graphicsLayer { alpha = pulse.value }.clip(CircleShape).background(Tones.actionText))
        }
        Text(
            status.text, fontSize = Design.Size.Caption, lineHeight = Design.Line.Caption, maxLines = 1, overflow = TextOverflow.Ellipsis,
            color = if (accent) Tones.actionText else ChatColors.secondaryText, fontWeight = if (accent) FontWeight.Medium else FontWeight.Normal,
            modifier = Modifier.padding(start = 6.dp).weight(1f, fill = false),
        )
        if (status.inBrowser) Chevron(tint = Tones.actionText)
    }
}

private val NO_FONT_PAD = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))

/** One line of what Momoding is doing; [inBrowser] when it is working in its own browser (M14: which computer). */
private data class StatusLine(val text: String, val waiting: Boolean = false, val inBrowser: Boolean = false)

@Composable
private fun rememberStatusLine(sessionId: String, isStreaming: Boolean, replying: Boolean, request: String?): StatusLine? {
    val toolTitle by SessionActivityTracker.currentToolTitle.collectAsState()
    val toolRunning by SessionActivityTracker.isToolRunning.collectAsState()
    val lastToolTitle by SessionActivityTracker.lastToolTitle.collectAsState()
    val pendingRisk = RiskGate.open.collectAsState().value.firstOrNull { it.sessionId == sessionId }
    val hands by Hands.progress.collectAsState()
    val toolName by SessionActivityTracker.currentToolName.collectAsState()
    val lastToolName by SessionActivityTracker.lastToolName.collectAsState()
    val browsing = isStreaming && (if (toolRunning) toolName else lastToolName) == "browser_use"
    val h = hands
    if (!isStreaming && pendingRisk == null && hands == null) return null
    return when {
        pendingRisk != null -> StatusLine(stringResource(R.string.mo_status_needs_approval), waiting = true)
        h?.waiting != null -> StatusLine(stringResource(R.string.mo_status_hands_waiting, h.waiting), waiting = true)
        h != null && h.background -> StatusLine(if (h.app != null) stringResource(R.string.mo_status_hands_bg_in, h.app, h.step) else stringResource(R.string.mo_status_hands_bg, h.step))
        h != null -> StatusLine(if (h.app != null) stringResource(R.string.mo_status_hands_in, h.app, h.step) else stringResource(R.string.mo_status_hands, h.step))
        browsing && !replying -> StatusLine(
            (if (toolRunning) toolTitle else lastToolTitle)?.takeIf { it.isNotBlank() }?.let { stringResource(R.string.mo_status_browser, it) }
                ?: stringResource(R.string.mo_status_browser_plain),
            inBrowser = true,
        )
        isStreaming && toolRunning && !toolTitle.isNullOrBlank() -> StatusLine(toolTitle!!)
        isStreaming && replying -> StatusLine(stringResource(R.string.mo_status_replying))
        isStreaming && !lastToolTitle.isNullOrBlank() -> StatusLine(lastToolTitle!!)
        isStreaming && !request.isNullOrBlank() -> StatusLine(stringResource(R.string.mo_status_on, request))
        isStreaming -> StatusLine(stringResource(R.string.mo_status_working))
        else -> null
    }
}

/** The request in a breath for the status line: first line, whitespace folded, cut with an ellipsis. */
fun requestBrief(text: String?, max: Int = 24): String? {
    val line = text?.lineSequence()?.map { it.trim() }?.firstOrNull { it.isNotEmpty() }?.replace(Regex("\\s+"), " ") ?: return null
    return if (line.length <= max) line else line.take(max).trimEnd() + "…"
}
