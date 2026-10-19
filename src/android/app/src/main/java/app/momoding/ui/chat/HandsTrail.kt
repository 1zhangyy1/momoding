package app.momoding.ui.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.momoding.ui.Tones
import coil.compose.AsyncImage
import com.openminis.app.ui.chat.ChatLinkAction
import com.openminis.app.ui.chat.ChatLinkResolver

/**
 * The hands' trail under a finished screen step: every step's screenshot in order, small, and
 * scrollable; a tap opens one full size. [lastScreen] is the run's last `minis://…/step-N.jpg`.
 */
@Composable
fun HandsTrail(lastScreen: String, sessionId: String?, onOpen: (String) -> Unit) {
    val context = LocalContext.current
    val steps = remember(lastScreen, sessionId) {
        val file = (ChatLinkResolver.resolve(lastScreen, sessionId, context) as? ChatLinkAction.SandboxFile)?.item?.file
        val dir = file?.parentFile ?: return@remember emptyList()
        val base = lastScreen.substringBeforeLast('/')
        dir.listFiles { f -> f.name.matches(Regex("""step-\d+\.jpg""")) }.orEmpty()
            .sortedBy { it.name.removePrefix("step-").removeSuffix(".jpg").toIntOrNull() ?: 0 }
            .map { it to "$base/${it.name}" }
    }
    if (steps.isEmpty()) return
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 4.dp),
    ) {
        steps.forEach { (file, url) ->
            AsyncImage(
                model = file, contentDescription = null, contentScale = ContentScale.Crop,
                modifier = Modifier.width(54.dp).height(120.dp).clip(RoundedCornerShape(8.dp))
                    .border(BorderStroke(1.dp, Tones.hairline), RoundedCornerShape(8.dp))
                    .clickable { onOpen(url) },
            )
        }
    }
}
