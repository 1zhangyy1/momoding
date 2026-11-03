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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.momoding.apps.AppBots
import app.momoding.apps.AppIcon
import app.momoding.apps.MoApps
import app.momoding.Identity
import app.momoding.ui.Design
import app.momoding.ui.Tones

/**
 * How the Momodings' dealings with each other read in a chat (调研/M40), as in Grok Bot: what a
 * Bot says starts with who it is. Work handed to one is a task, with a line of its own ([TaskRow]).
 */
object BotLines {
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

/** Over what a Bot said here: its face and name, in its colour; a tap goes to it. */
@Composable
fun FromBotHeader(name: String, onOpen: (String) -> Unit) {
    val context = LocalContext.current
    val app = remember(name) { MoApps.get(context, name) } ?: return
    val face = remember(app.name) { AppIcon.bitmap(context, app, 60, adaptive = false).asImageBitmap() }
    FromHeader(face, app.title, Color(AppIcon.color(app))) { onOpen(botHome(app)) }
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
internal fun botHome(app: MoApps.App) = if (app.hasApp) "minis://apps/${app.name}" else "minis://session/${app.botSession}"

@Composable
internal fun BotFace(app: MoApps.App, size: Int) {
    val context = LocalContext.current
    val icon = remember(app.name) { AppIcon.bitmap(context, app, size * 3, adaptive = false).asImageBitmap() }
    Image(icon, null, Modifier.size(size.dp).clip(CircleShape))
}
