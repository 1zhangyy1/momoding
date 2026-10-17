package app.momoding.ui.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
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
        val lines = raw.trimEnd().lines()
        var i = lines.size
        val links = mutableListOf<Pair<String, String>>()
        while (i > 0) {
            val l = lines[i - 1]
            if (l.isBlank()) { i--; continue }
            val found = parse(l) ?: break
            links.addAll(0, found); i--
        }
        if (links.isEmpty()) return null
        return lines.take(i).joinToString("\n").trimEnd() to links
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
            Surface(shape = CircleShape, color = Tones.surface, border = BorderStroke(1.dp, Tones.hairline)) {
                Text(
                    label, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.clickable { onOpen(url) }.padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }
        }
    }
}
