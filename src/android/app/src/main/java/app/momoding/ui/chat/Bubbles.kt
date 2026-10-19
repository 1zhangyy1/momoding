// Adapted from nanoMuse (GPL-3.0): io/github/nanomuse/ui/chat/NmHomeChrome.kt (NmAssistantBubble)
package app.momoding.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import app.momoding.ui.Tones

/** Grok Bot / iMessage geometry: 18dp corners, the corner nearest the speaker pulled in on a turn's last bubble. */
object BubbleShape {
    private val R = 18.dp
    private val TAIL = 6.dp
    fun agent(last: Boolean) = RoundedCornerShape(topStart = R, topEnd = R, bottomEnd = R, bottomStart = if (last) TAIL else R)
    fun user(last: Boolean = true) = RoundedCornerShape(topStart = R, topEnd = R, bottomStart = R, bottomEnd = if (last) TAIL else R)
}

/**
 * One block of Momoding's reply in its own warm-grey bubble, on the left, as wide as the words
 * need. Code blocks, tables and the app's own cards (momoding-* fences) carry their own frame
 * and stay bare.
 */
@Composable
fun AgentBubble(rawText: String, last: Boolean, content: @Composable () -> Unit) {
    if (isBare(rawText)) { content(); return }
    val shape = BubbleShape.agent(last)
    Box(Modifier.fillMaxWidth().padding(end = 40.dp), contentAlignment = Alignment.TopStart) {
        Box(
            Modifier.widthIn(max = 520.dp).clip(shape).background(Tones.agentBubble)
                .padding(horizontal = 14.dp, vertical = 9.dp),
        ) { content() }
    }
}

private fun isBare(raw: String): Boolean {
    val t = raw.trimStart()
    return t.isEmpty() || t.startsWith("```") || t.startsWith("~~~") || t.startsWith("|") || t.startsWith("<")
}
