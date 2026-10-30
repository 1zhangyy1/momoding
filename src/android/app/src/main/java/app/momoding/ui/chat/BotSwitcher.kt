package app.momoding.ui.chat

import android.app.Activity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.momoding.Identity
import app.momoding.apps.AppChat
import app.momoding.apps.AppIcon
import app.momoding.apps.BotStatus
import app.momoding.ui.Design
import app.momoding.ui.Tones
import com.openminis.app.R
import com.openminis.app.ui.theme.ChatColors
import kotlinx.coroutines.delay

/**
 * The person's other Momodings, in the header (调研/M32 第十一节): a face for each beside the name,
 * with what it is doing on its corner; a tap goes straight there. The name opens the whole list,
 * in a fixed order, with a line for each, then "new" and "manage".
 */
@Composable
fun BotFaces(current: String?, onOpenList: () -> Unit) {
    val context = LocalContext.current
    val bots by BotStatus.all(context).collectAsState()
    val others = bots.filter { it.session != current }
    Row(horizontalArrangement = Arrangement.spacedBy((-6).dp), verticalAlignment = Alignment.CenterVertically) {
        others.take(MAX_FACES).forEach { b ->
            Box(Modifier.clip(CircleShape).clickable { open(context, b, current) }) { Face(b, 28.dp) }
        }
        if (others.size > MAX_FACES) {
            Box(
                Modifier.size(28.dp).clip(CircleShape).background(Tones.fill).border(2.dp, ChatColors.background, CircleShape).clickable(onClick = onOpenList),
                contentAlignment = Alignment.Center,
            ) { Text("+${others.size - MAX_FACES}", fontSize = Design.Size.Caption, color = ChatColors.secondaryText) }
        }
    }
}

/** The whole list, under the header. */
@Composable
fun BotList(expanded: Boolean, current: String?, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val bots by BotStatus.all(context).collectAsState()
    val width = LocalConfiguration.current.screenWidthDp.dp - 24.dp
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss, modifier = Modifier.width(width)) {
        bots.forEach { b ->
            Row(
                Modifier.fillMaxWidth().clickable { onDismiss(); open(context, b, current) }
                    .background(if (b.session == current) Tones.fill else Color.Transparent)
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Face(b, 36.dp)
                Column(Modifier.weight(1f)) {
                    Text(name(b) + if (b.app == null) " ★" else "", fontSize = Design.Size.Body, fontWeight = FontWeight.SemiBold, color = ChatColors.primaryText, maxLines = 1)
                    line(b)?.let { (text, accent) ->
                        Text(text, fontSize = Design.Size.Caption, color = if (accent) WAITING else ChatColors.secondaryText, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        HorizontalDivider(color = Tones.hairline)
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            val ask = stringResource(R.string.mo_bots_new_ask)
            Foot(Icons.Outlined.Add, stringResource(R.string.mo_bots_new)) { onDismiss(); newBot(context, bots.firstOrNull(), current, ask) }
            Foot(Icons.Outlined.Tune, stringResource(R.string.mo_bots_manage)) { onDismiss(); manage(context) }
        }
    }
}

@Composable
private fun Foot(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Row(
        Modifier.clip(CircleShape).clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(icon, null, Modifier.size(18.dp), tint = Tones.action)
        Text(label, fontSize = Design.Size.Body, color = Tones.action, fontWeight = FontWeight.Medium)
    }
}

/** A Bot's face, its state on the corner: orange waiting, a turning ring working, a green dot something new. */
@Composable
private fun Face(b: BotStatus.Bot, size: Dp) {
    val context = LocalContext.current
    val uiMode = LocalConfiguration.current.uiMode
    val img: ImageBitmap? = remember(b.app?.name, b.app?.title, uiMode) {
        (b.app?.let { AppIcon.bitmap(it, 96, adaptive = false) } ?: Identity.face(context))?.asImageBitmap()
    }
    Box(Modifier.size(size)) {
        Box(Modifier.size(size).clip(CircleShape).background(Tones.fill).border(2.dp, ChatColors.background, CircleShape)) {
            if (img != null) Image(img, null, Modifier.size(size).clip(CircleShape))
        }
        val corner = Modifier.align(Alignment.BottomEnd).size(size * 0.38f)
        when (b.status.kind) {
            BotStatus.Kind.WAITING -> Box(corner.clip(CircleShape).background(WAITING).border(1.5.dp, ChatColors.background, CircleShape))
            BotStatus.Kind.NEWS -> Box(corner.clip(CircleShape).background(Tones.action).border(1.5.dp, ChatColors.background, CircleShape))
            BotStatus.Kind.WORKING -> Box(corner.clip(CircleShape).background(ChatColors.background), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(size * 0.3f), color = Tones.action, strokeWidth = 1.5.dp)
            }
            BotStatus.Kind.NONE -> Unit
        }
    }
}

@Composable
private fun name(b: BotStatus.Bot): String = b.app?.title ?: Identity.name(LocalContext.current)

@Composable
private fun line(b: BotStatus.Bot): Pair<String, Boolean>? {
    val text = b.status.line ?: return null
    return when (b.status.kind) {
        BotStatus.Kind.WAITING -> stringResource(R.string.mo_bot_waiting, text) to true
        BotStatus.Kind.WORKING -> stringResource(R.string.mo_bot_working, text) to false
        else -> text to false
    }
}

private val WAITING = Color(0xFFE8590C)
private const val MAX_FACES = 3

/** To [b]'s home; from an app's chat panel, the panel goes. */
private fun open(context: android.content.Context, b: BotStatus.Bot, current: String?) {
    if (b.session == current) return
    val raise = b.status.kind == BotStatus.Kind.WAITING || b.status.kind == BotStatus.Kind.NEWS
    AppChat.home(context, b.session, b.app, raise)
    if (context is app.momoding.apps.AppChatActivity) context.finish()
}

/** A new Bot is the main Momoding's to make: its chat, with the ask begun in the composer. */
private fun newBot(context: android.content.Context, main: BotStatus.Bot?, current: String?, ask: String) {
    main ?: return
    AppChat.ask(context, main.session, null, ask)
    if (context is app.momoding.apps.AppChatActivity && current != main.session) context.finish()
}

private fun manage(context: android.content.Context) {
    context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("momoding://apps"))
        .setPackage(context.packageName).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
    if (context is app.momoding.apps.AppChatActivity) context.finish()
}

/**
 * Another Momoding has just finished something for the person, who is here and not there: one
 * line for a few seconds, and a way there (调研/M32: in the app, a banner; outside, a notification).
 */
@Composable
fun BotDoneBanner(current: String?) {
    val context = LocalContext.current
    val bots by BotStatus.all(context).collectAsState()
    val before = remember { mutableMapOf<String, BotStatus.Kind>() }
    var shown by remember { mutableStateOf<BotStatus.Bot?>(null) }
    LaunchedEffect(bots) {
        bots.firstOrNull { b ->
            b.session != current && b.status.kind == BotStatus.Kind.NEWS &&
                before[b.session].let { it != null && it != BotStatus.Kind.NEWS } && !BotStatus.quiet(context, b.session)
        }?.let { shown = it }
        bots.forEach { before[it.session] = it.status.kind }
    }
    LaunchedEffect(shown) { if (shown != null) { delay(6_000); shown = null } }
    AnimatedVisibility(shown != null, enter = fadeIn(), exit = fadeOut()) {
        val b = shown ?: return@AnimatedVisibility
        app.momoding.ui.guard.BannerPill(stringResource(R.string.mo_bot_done_banner, name(b), b.status.line.orEmpty())) {
            shown = null
            open(context, b, current)
        }
    }
}
