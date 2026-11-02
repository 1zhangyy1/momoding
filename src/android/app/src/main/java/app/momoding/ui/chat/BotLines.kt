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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.momoding.apps.AppBots
import app.momoding.apps.AppIcon
import app.momoding.apps.BotStatus
import app.momoding.apps.MoApps
import app.momoding.ui.Design
import com.openminis.app.R
import com.openminis.app.ui.chat.AssistantBlock
import com.openminis.app.ui.theme.ChatColors
import org.json.JSONObject

/**
 * How the Momodings' dealings with each other read in a chat (调研/M40), as in Grok Bot: one line,
 * the Bot's name first. Handing work over is a line of its own ("已交给 记账 · 在做…：…"), and
 * what a Bot says back starts with who it is.
 */
object BotLines {
    /** Work handed to a Bot: `momoding-app new|tell <name> …` in a step. */
    data class Handoff(val name: String, val text: String, val created: Boolean)

    private val CALL = Regex("""momoding-app\s+(new|tell)\s+([a-z0-9_-]+)\s*(.*)""", RegexOption.DOT_MATCHES_ALL)

    fun handoff(block: AssistantBlock): Handoff? {
        if (block.kind != "tool_use") return null
        val command = runCatching { JSONObject(block.toolArgs).optString("command") }.getOrNull() ?: return null
        val m = CALL.find(command) ?: return null
        val rest = m.groupValues[3]
        val created = m.groupValues[1] == "new"
        // what it was given: the brief's "what" when made, the message when told
        val text = (if (created) quoted(rest, "--what") ?: quoted(rest, "--title") else quoted(rest, null)).orEmpty()
        return Handoff(m.groupValues[2], text.lineSequence().first().trim(), created)
    }

    /** The first quoted argument, or the one after [flag]. */
    private fun quoted(s: String, flag: String?): String? {
        val from = if (flag == null) 0 else s.indexOf(flag).takeIf { it >= 0 }?.plus(flag.length) ?: return null
        val m = Regex(""""((?:[^"\\]|\\.)*)"|'([^']*)'""").find(s, from) ?: return null
        return (m.groups[1]?.value?.replace("\\\"", "\"") ?: m.groups[2]?.value)
    }

    /** What a Bot said in this chat ([AppBots.FROM_CUE]): its name, and the words. */
    fun said(text: String): Pair<String, String>? {
        if (!text.startsWith(AppBots.FROM_CUE)) return null
        val name = text.removePrefix(AppBots.FROM_CUE).substringBefore(']')
        return name to text.substringAfter(']').trimStart()
    }
}

/** "已交给 记账 · 在做…：把那笔改成交通" — the status only on the newest hand-over to that Bot. */
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
    val link = if (app.hasApp) "minis://apps/${app.name}" else "minis://session/${app.botSession}"
    Row(
        Modifier.clip(RoundedCornerShape(Design.Radius.Row)).clickable { onOpen(link) }.padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BotFace(app, 18)
        Text(
            // who and how it stands first: a long message is what gets cut
            listOfNotNull(head, state).joinToString(" · ") + handoff.text.takeIf { it.isNotEmpty() }?.let { "：$it" }.orEmpty(),
            Modifier.padding(start = 8.dp), fontSize = Design.Size.Caption,
            color = if (status == BotStatus.Kind.WAITING) Color(AppIcon.color(app)) else ChatColors.secondaryText,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Over what a Bot said here: its face and name, in its colour. */
@Composable
fun FromBotHeader(name: String) {
    val context = LocalContext.current
    val app = remember(name) { MoApps.get(context, name) } ?: return
    Row(Modifier.padding(start = 16.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        BotFace(app, 20)
        Text(app.title, Modifier.padding(start = 6.dp), fontSize = Design.Size.Caption, fontWeight = FontWeight.SemiBold, color = Color(AppIcon.color(app)))
    }
}

@Composable
private fun BotFace(app: MoApps.App, size: Int) {
    val icon = remember(app.name) { AppIcon.bitmap(app, size * 3, adaptive = false).asImageBitmap() }
    Image(icon, null, Modifier.size(size.dp).clip(CircleShape))
}
