// Adapted from nanoMuse (GPL-3.0): io/github/nanomuse/ui/chat/NmHomeChrome.kt (NmAssistantBubble)
package app.momoding.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
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
fun AgentBubble(rawText: String, last: Boolean, id: String? = null, delayMs: Int = 0, content: @Composable () -> Unit) {
    if (isBare(rawText)) { content(); return }
    val shape = BubbleShape.agent(last)
    if (id != null) PopIn(id, fromStart = true, delayMs) { Bubble(shape, content) } else Bubble(shape, content)
}

@Composable
private fun Bubble(shape: androidx.compose.ui.graphics.Shape, content: @Composable () -> Unit) {
    Box(Modifier.fillMaxWidth().padding(top = 4.dp, end = 40.dp), contentAlignment = Alignment.TopStart) {
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

/**
 * Which chat rows have already been on screen, so only new ones pop in. Everything present
 * when a chat is opened is primed as seen; history never animates.
 */
object PopRegistry {
    private val seen = java.util.Collections.synchronizedSet(HashSet<String>())
    fun prime(ids: Collection<String>) { seen.addAll(ids) }
    /** True the first time [id] is drawn after priming (and marks it). */
    fun claim(id: String): Boolean = seen.add(id)
}

/**
 * Grok Bot's bubble entrance: from 92% and transparent to full size in 180ms on
 * cubic-bezier(.22,1,.36,1), growing from the speaker's side.
 */
@Composable
fun PopIn(id: String, fromStart: Boolean, delayMs: Int = 0, content: @Composable () -> Unit) {
    val animate = androidx.compose.runtime.remember(id) { PopRegistry.claim(id) }
    val progress = androidx.compose.runtime.remember(id) { androidx.compose.animation.core.Animatable(if (animate) 0f else 1f) }
    if (animate) androidx.compose.runtime.LaunchedEffect(id) {
        progress.animateTo(1f, androidx.compose.animation.core.tween(180, delayMillis = delayMs, easing = androidx.compose.animation.core.CubicBezierEasing(.22f, 1f, .36f, 1f)))
    }
    Box(
        Modifier.graphicsLayer {
            val p = progress.value
            alpha = p
            scaleX = .92f + .08f * p; scaleY = .92f + .08f * p
            transformOrigin = androidx.compose.ui.graphics.TransformOrigin(if (fromStart) 0f else 1f, .5f)
        },
    ) { content() }
}

/** iMessage's typing bubble: three dots taking turns to rise, in an agent-grey bubble. */
@Composable
fun TypingBubble() {
    val tick = com.openminis.app.ui.components.rememberDecorativeTick()
    val dot = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .55f)
    Box(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp), contentAlignment = Alignment.TopStart) {
        androidx.compose.foundation.layout.Row(
            Modifier.clip(BubbleShape.agent(last = true)).background(Tones.agentBubble)
                .padding(horizontal = 14.dp, vertical = 14.dp),
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(5.dp),
        ) {
            repeat(3) { i ->
                Box(
                    Modifier.graphicsLayer {
                        val p = com.openminis.app.ui.components.decorativePingPong(
                            com.openminis.app.ui.components.decorativePhase(tick.value, 900, i * 150),
                        )
                        translationY = -3.dp.toPx() * p
                        alpha = .5f + .5f * p
                    }.size(7.dp).clip(androidx.compose.foundation.shape.CircleShape).background(dot),
                )
            }
        }
    }
}

/**
 * Where one bubble ends and the next begins (pi-human-message: one message per conversational
 * act). The model marks a new bubble with a blank line; a list, quote, table or line of links
 * stays in the bubble it belongs to; a fenced block (code, a Momoding card) stands alone. The
 * same grouping is used while streaming and afterwards, so bubbles never merge or jump.
 */
object BubbleSplit {
    fun group(fragments: List<String>): List<String> {
        val out = mutableListOf<StringBuilder>()
        var lastFence = true
        for (f in fragments) {
            val first = f.lineSequence().firstOrNull { it.isNotBlank() }?.trimStart() ?: continue
            val fence = first.startsWith("```") || first.startsWith("~~~")
            val attaches = !fence && !lastFence && out.isNotEmpty() && (
                LIST.containsMatchIn(first) || first.startsWith(">") || first.startsWith("|") ||
                    LinkLine.splitTrailing(f)?.first?.isBlank() == true
                )
            val asked = fence && first.startsWith("```momoding-ask") && !lastFence &&
                out.lastOrNull()?.trimEnd()?.let { it.endsWith("？") || it.endsWith("?") } == true
            if (attaches) out.last().append("\n\n").append(f)
            else out.add(StringBuilder(if (asked) withoutQuestion(f) else f))
            lastFence = fence
        }
        return out.map { it.toString() }
    }

    private val LIST = Regex("""^([-*+]|\d+[.)])\s""")

    /** The bubble above already asked it: the card keeps only the options. */
    private fun withoutQuestion(fence: String): String {
        val body = fence.substringAfter('\n').substringBeforeLast("```").trim()
        val o = runCatching { org.json.JSONObject(body) }.getOrNull() ?: return fence
        if (!o.has("question")) return fence
        o.remove("question")
        return "```momoding-ask\n$o\n```"
    }
}
