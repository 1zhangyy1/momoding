package app.momoding.apps

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.momoding.ui.Design
import app.momoding.ui.Tones
import com.openminis.app.R
import com.openminis.app.ui.theme.ChatColors
import kotlinx.coroutines.delay

/**
 * The way from an app to its Momoding, bottom right on its page: a pencil in the Bot's colour on
 * Momoding's white, saying "改一改" as the app opens and then keeping to a circle. Its corner shows
 * how the Bot stands, as its face does in the header: waiting (orange), working (a turning ring),
 * something new (a dot in its colour).
 */
@Composable
fun AppPen(app: MoApps.App, onClick: () -> Unit) {
    val context = LocalContext.current
    val bots by BotStatus.all(context).collectAsState()
    val kind = bots.firstOrNull { it.app?.name == app.name }?.status?.kind
    var hint by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) { delay(2600); hint = false }
    val accent = Color(AppIcon.color(app))
    Surface(
        onClick = onClick, shape = CircleShape, color = Tones.surface, shadowElevation = 6.dp,
        border = BorderStroke(1.dp, Tones.hairline),
    ) {
        Row(Modifier.height(44.dp).padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(24.dp)) {
                Icon(PEN, stringResource(R.string.mo_app_chat, app.title), tint = accent, modifier = Modifier.size(24.dp))
                val corner = Modifier.align(Alignment.TopEnd).size(9.dp)
                when (kind) {
                    BotStatus.Kind.WAITING -> Box(corner.background(WAITING, CircleShape).border(1.5.dp, Tones.surface, CircleShape))
                    BotStatus.Kind.NEWS -> Box(corner.background(accent, CircleShape).border(1.5.dp, Tones.surface, CircleShape))
                    BotStatus.Kind.WORKING -> CircularProgressIndicator(corner, color = accent, strokeWidth = 1.5.dp)
                    else -> Unit
                }
            }
            AnimatedVisibility(hint, enter = fadeIn() + expandHorizontally(), exit = fadeOut() + shrinkHorizontally()) {
                Text(
                    stringResource(R.string.mo_app_pen_hint), Modifier.padding(start = 6.dp, end = 4.dp),
                    fontSize = Design.Size.Body, fontWeight = FontWeight.Medium, color = ChatColors.primaryText, maxLines = 1,
                )
            }
        }
    }
}

private val WAITING = Color(0xFFE8590C)

/** A pencil writing, with a spark by its tip: Momoding changing the app for you. */
private val PEN: ImageVector = ImageVector.Builder("MoPen", 24.dp, 24.dp, 24f, 24f).apply {
    path(stroke = SolidColor(Color.Black), strokeLineWidth = 1.8f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
        // the body, tip down-left
        moveTo(4.5f, 19.5f); lineTo(5.6f, 15.4f); lineTo(14.9f, 6.1f)
        arcTo(1.9f, 1.9f, 0f, false, true, 17.6f, 6.1f); lineTo(17.9f, 6.4f)
        arcTo(1.9f, 1.9f, 0f, false, true, 17.9f, 9.1f); lineTo(8.6f, 18.4f); close()
        // where the cap meets the body
        moveTo(13.2f, 7.8f); lineTo(16.2f, 10.8f)
    }
    path(fill = SolidColor(Color.Black)) {
        // the spark: a four-pointed star by the tip
        moveTo(18.5f, 13.5f)
        quadTo(18.9f, 15.6f, 21f, 16f); quadTo(18.9f, 16.4f, 18.5f, 18.5f)
        quadTo(18.1f, 16.4f, 16f, 16f); quadTo(18.1f, 15.6f, 18.5f, 13.5f); close()
    }
}.build()
