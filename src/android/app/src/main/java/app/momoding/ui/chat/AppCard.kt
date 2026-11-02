package app.momoding.ui.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import app.momoding.apps.AppHome
import app.momoding.apps.AppIcon
import app.momoding.apps.MoApps
import app.momoding.ui.Design
import app.momoding.ui.Tones
import com.openminis.app.R
import kotlinx.coroutines.delay

/**
 * An app in the chat (a `minis://apps/<name>` link): its icon and name, and what the person does
 * with it — open it, put it on the home screen, add its widget. What is already there says so;
 * the launcher's own confirmation comes up over this chat, and the card shows the answer.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AppCard(app: MoApps.App, onOpen: () -> Unit) {
    val context = LocalContext.current
    // back from the launcher's confirmation (its answer can arrive a moment later): look again
    var looked by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { looked++ }
    var state by remember { mutableStateOf(emptyMap<AppHome.What, Boolean>()) }
    LaunchedEffect(looked) {
        repeat(2) {
            state = AppHome.What.entries.filter { AppHome.can(context, app, it) || AppHome.there(context, app, it) }
                .associateWith { AppHome.there(context, app, it) }
            delay(1500)
        }
    }
    val icon = remember(app.name) { AppIcon.bitmap(context, app, 120, adaptive = false).asImageBitmap() }
    Surface(shape = RoundedCornerShape(Design.Radius.Panel), color = Tones.surface, border = BorderStroke(1.dp, Tones.hairline)) {
        Column(Modifier.widthIn(max = 320.dp).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Image(icon, null, Modifier.size(40.dp).clip(RoundedCornerShape(Design.Radius.Row)))
                Text(app.title, Modifier.padding(start = 10.dp), fontSize = Design.Size.Body, fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PillButton(stringResource(R.string.mo_app_card_open), primary = true, onClick = onOpen)
                state.forEach { (what, there) ->
                    val (add, done) = when (what) {
                        AppHome.What.ICON -> R.string.mo_app_card_icon to R.string.mo_app_card_icon_there
                        AppHome.What.WIDGET -> R.string.mo_app_card_widget to R.string.mo_app_card_widget_there
                    }
                    if (there) PillButton("✓ " + stringResource(done), primary = false, enabled = false) {}
                    else PillButton(stringResource(add), primary = false) { AppHome.add(context, app, what) }
                }
            }
        }
    }
}

/** The app a `minis://apps/<name>` link opens (its front page, not a page inside it), if it has one. */
fun appOf(context: android.content.Context, url: String): MoApps.App? {
    val path = url.removePrefix("minis://apps/").takeIf { it != url }?.substringBefore('?')?.trimEnd('/') ?: return null
    if (path.isEmpty() || '/' in path) return null
    return MoApps.get(context, path)?.takeIf { it.hasApp }
}
