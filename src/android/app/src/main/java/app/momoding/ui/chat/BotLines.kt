package app.momoding.ui.chat

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.momoding.apps.AppBots
import app.momoding.apps.AppIcon
import app.momoding.apps.BotStatus
import app.momoding.apps.MoApps
import app.momoding.Identity
import app.momoding.ui.Design
import app.momoding.ui.Tones
import com.openminis.app.R
import com.openminis.app.ui.chat.AssistantBlock
import com.openminis.app.ui.theme.ChatColors
import org.json.JSONObject

/**
 * How the Momodings' dealings with each other read in a chat (调研/M40), as in Grok Bot: one line,
 * the Bot's name first. Handing work over is a line of its own ("已交给 记账 · 在做…"), and
 * what a Bot says back starts with who it is.
 */
object BotLines {
    /** Work handed to a Bot: `momoding-app new|tell <name> …` in a step. */
    data class Handoff(val name: String, val text: String, val created: Boolean)

    private val CALL = Regex("""momoding-app\s+(new|tell)\s+([a-z0-9_-]+)""")

    /** Every hand-over in a step: one command may hand work to several Bots. */
    fun handoffs(block: AssistantBlock): List<Handoff> {
        if (block.kind != "tool_use") return emptyList()
        val command = runCatching { JSONObject(block.toolArgs).optString("command") }.getOrNull() ?: return emptyList()
        val calls = CALL.findAll(command).toList()
        return calls.mapIndexed { i, m ->
            val rest = command.substring(m.range.last + 1, calls.getOrNull(i + 1)?.range?.first ?: command.length)
            val created = m.groupValues[1] == "new"
            // what the person reads: what a new one is for. A message to a Bot is written for the Bot
            // (its details, its commands); what was asked is the person's own line just above
            val text = (if (created) quoted(rest, "--what") ?: quoted(rest, "--title") else null).orEmpty()
            Handoff(m.groupValues[2], text.lineSequence().first().trim(), created)
        }
    }

    /** The quoted argument after [flag]. */
    private fun quoted(s: String, flag: String): String? {
        val from = s.indexOf(flag).takeIf { it >= 0 }?.plus(flag.length) ?: return null
        val m = Regex(""""((?:[^"\\]|\\.)*)"|'([^']*)'""").find(s, from) ?: return null
        return (m.groups[1]?.value?.replace("\\\"", "\"") ?: m.groups[2]?.value)
    }

    /**
     * What a Bot's line here opens: the links it gave, and its app's card when it has an app —
     * its report is about that app, so the card is always there, once.
     */
    fun links(context: android.content.Context, name: String?, own: List<Pair<String, String>>?): List<Pair<String, String>>? {
        val app = name?.let { MoApps.get(context, it) }?.takeIf { it.hasApp } ?: return own
        if (own.orEmpty().any { appOf(context, it.second)?.name == app.name }) return own
        return own.orEmpty() + (app.title to "minis://apps/${app.name}")
    }

    /** What a Bot said in this chat ([AppBots.FROM_CUE]): its name, and the words. */
    fun said(text: String): Pair<String, String>? {
        if (!text.startsWith(AppBots.FROM_CUE)) return null
        val name = text.removePrefix(AppBots.FROM_CUE).substringBefore(']')
        return name to text.substringAfter(']').trimStart()
    }
}

/** "已交给 记账 · 在做…" (a new one: "新建了 取件码 · 在建…：短信来了自动记下取件码") — the status only on the newest hand-over to that Bot. */
@Composable
fun HandoffRow(handoff: BotLines.Handoff, live: Boolean, onOpen: (String) -> Unit) {
    val context = LocalContext.current
    val app = remember(handoff.name) { MoApps.get(context, handoff.name) } ?: return
    val bots by BotStatus.all(context).collectAsState()
    val status = if (live) bots.firstOrNull { it.session == app.botSession }?.status?.kind else null
    val state = when (status) {
        BotStatus.Kind.WORKING -> stringResource(if (handoff.created) R.string.mo_bot_line_building else R.string.mo_bot_line_working)
        BotStatus.Kind.WAITING -> stringResource(R.string.mo_bot_line_waiting)
        else -> null
    }
    val head = stringResource(if (handoff.created) R.string.mo_bot_line_made else R.string.mo_bot_line_handed, app.title)
    Row(
        Modifier.clip(RoundedCornerShape(Design.Radius.Row)).clickable { onOpen(home(app)) }.padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.Top,
    ) {
        BotFace(app, 18)
        Text(
            // who and how it stands; for a new one, what it is for
            listOfNotNull(head, state).joinToString(" · ") + handoff.text.takeIf { it.isNotEmpty() }?.let { "：$it" }.orEmpty(),
            Modifier.padding(start = 8.dp), fontSize = Design.Size.Caption,
            color = if (status == BotStatus.Kind.WAITING) Color(AppIcon.color(app)) else ChatColors.secondaryText,
        )
    }
}

/** Over what a Bot said here: its face and name, in its colour; a tap goes to it. */
@Composable
fun FromBotHeader(name: String, onOpen: (String) -> Unit) {
    val context = LocalContext.current
    val app = remember(name) { MoApps.get(context, name) } ?: return
    val face = remember(app.name) { AppIcon.bitmap(context, app, 60, adaptive = false).asImageBitmap() }
    FromHeader(face, app.title, Color(AppIcon.color(app))) { onOpen(home(app)) }
}

/** Over what the main chat's Momoding said in a Bot's chat: the same, in Momoding's green. */
@Composable
fun FromMomodingHeader() {
    val context = LocalContext.current
    val face = remember { Identity.face(context)?.asImageBitmap() }
    FromHeader(face, Identity.name(context), Color(Tones.ACTION_ARGB))
}

@Composable
private fun FromHeader(face: ImageBitmap?, name: String, color: Color, onClick: (() -> Unit)? = null) {
    Row(
        Modifier.padding(start = 8.dp).clip(RoundedCornerShape(Design.Radius.Row))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (face != null) Image(face, null, Modifier.size(20.dp).clip(CircleShape))
        Text(name, Modifier.padding(start = 6.dp), fontSize = Design.Size.Caption, fontWeight = FontWeight.SemiBold, color = color)
    }
}

/** Where a Bot lives: its app, else its chat. */
private fun home(app: MoApps.App) = if (app.hasApp) "minis://apps/${app.name}" else "minis://session/${app.botSession}"

@Composable
private fun BotFace(app: MoApps.App, size: Int) {
    val context = LocalContext.current
    val icon = remember(app.name) { AppIcon.bitmap(context, app, size * 3, adaptive = false).asImageBitmap() }
    Image(icon, null, Modifier.size(size.dp).clip(CircleShape))
}
