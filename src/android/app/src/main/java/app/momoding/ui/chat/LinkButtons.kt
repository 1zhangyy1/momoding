package app.momoding.ui.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.momoding.ui.Tones

/**
 * The prototype's result buttons ("在日历里看", "打开设置"): a line of the reply that is only
 * links becomes a row of buttons; links inside a sentence stay links. The prompt asks the model
 * to put what can be opened on its own last line.
 */
object LinkLine {
    private val LINK = Regex("""(?<!!)\[([^\]\n]+)\]\(([^)\s]+)\)""")
    private val FILLER = Regex("""[\s·•|、,，;；\-–—]*""")

    /**
     * A finished reply's paragraphs are merged into one block, so the links line arrives glued to
     * the text above it: split off the trailing lines that are only links. Null when there are none.
     */
    fun splitTrailing(raw: String): Pair<String, List<Pair<String, String>>>? {
        // any line that is only links (usually the last; a question may follow it) becomes buttons
        val links = mutableListOf<Pair<String, String>>()
        val kept = raw.trimEnd().lines().filter { l ->
            val found = if (l.isBlank()) null else parse(l)
            if (found != null) links.addAll(found)
            found == null
        }
        if (links.isEmpty()) return null
        return kept.joinToString("\n").replace(Regex("\n{3,}"), "\n\n").trim() to links
    }

    /** (label, url) when [raw] is nothing but links (and separators), else null. */
    fun parse(raw: String): List<Pair<String, String>>? {
        val text = raw.trim()
        if (text.isEmpty() || text.startsWith("```")) return null
        val links = LINK.findAll(text).map { it.groupValues[1].trim() to it.groupValues[2] }.toList()
        if (links.isEmpty()) return null
        return if (FILLER.matches(LINK.replace(text, ""))) links else null
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LinkButtons(links: List<Pair<String, String>>, onOpen: (String) -> Unit) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(top = 4.dp, bottom = 6.dp),
    ) {
        links.forEach { (label, url) ->
            if (Works.isWork(url)) { WorkCard(label, url) { onOpen(url) }; return@forEach }
            Surface(shape = CircleShape, color = Tones.surface, border = BorderStroke(1.dp, Tones.hairline)) {
                Text(
                    label, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.clickable { onOpen(url) }.padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }
        }
    }
}

/**
 * A file Momoding made (a `minis://workspace/...` link) as a small card rather than a pill: what
 * kind of file, its name, and Open — Grok Bot's "comes back with" the thing it made.
 */
@Composable
private fun WorkCard(label: String, url: String, onOpen: () -> Unit) {
    val name = url.substringAfterLast('/').substringBefore('?')
    Surface(shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp), color = Tones.surface, border = BorderStroke(1.dp, Tones.hairline)) {
        androidx.compose.foundation.layout.Row(
            Modifier.clickable(onClick = onOpen).padding(start = 10.dp, end = 14.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            androidx.compose.foundation.layout.Box(
                Modifier.size(36.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(10.dp)).background(Tones.fill),
                contentAlignment = androidx.compose.ui.Alignment.Center,
            ) {
                Text(Works.kind(name), fontSize = 11.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, color = Tones.action)
            }
            androidx.compose.foundation.layout.Column(Modifier.padding(start = 10.dp).widthIn(max = 220.dp)) {
                Text(label, fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                if (name.isNotEmpty() && name != label) Text(name, fontSize = 12.sp, color = com.openminis.app.ui.theme.ChatColors.secondaryText, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            }
            Text("  ›", fontSize = 15.sp, color = com.openminis.app.ui.theme.ChatColors.secondaryText)
        }
    }
}

internal object Works {
    fun isWork(url: String): Boolean = url.startsWith("minis://workspace/") && !url.endsWith("/")

    /** The file kind in a few letters for the tile: CSV, XLSX, PDF, HTML… */
    fun kind(name: String): String = name.substringAfterLast('.', "").uppercase().take(4).ifEmpty { "FILE" }
}
