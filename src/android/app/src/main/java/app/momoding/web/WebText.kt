package app.momoding.web

import org.json.JSONObject
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.select.NodeTraversor
import org.jsoup.select.NodeVisitor

/** One search hit, as the page listed it. */
data class Hit(val title: String, val url: String, val snippet: String, val date: String? = null)

/**
 * Turning what comes back into what the agent reads: Exa's answer into hits, any page into its text.
 * Pure parsing, no network — [WebLookup] fetches.
 */
object WebText {

    /**
     * Exa's MCP answer (one JSON-RPC result, sometimes as SSE `data:` lines) into hits. Its text
     * lists results as `Title:` / `URL:` / `Published:` / `Highlights:` or `Text:` blocks.
     * Throws on an error answer, so the agent hears why instead of "no results".
     */
    fun exaHits(answer: String): List<Hit> {
        val frames = answer.lines().filter { it.startsWith("data:") }.map { it.removePrefix("data:").trim() }
            .ifEmpty { listOf(answer.trim()) }
        val texts = frames.mapNotNull { runCatching { JSONObject(it) }.getOrNull() }.flatMap { o ->
            o.optJSONObject("error")?.let { throw IllegalStateException(it.optString("message", "search failed")) }
            val content = o.optJSONObject("result")?.optJSONArray("content") ?: return@flatMap emptyList()
            (0 until content.length()).mapNotNull { content.optJSONObject(it)?.optString("text")?.takeIf { t -> t.isNotBlank() } }
        }
        return texts.flatMap { it.replace("\r\n", "\n").split(Regex("\n(?:---\n)?\n?(?=Title:)")) }.mapNotNull { block ->
            val url = Regex("(?m)^URL:\\s*(https?://\\S+)").find(block)?.groupValues?.get(1) ?: return@mapNotNull null
            val title = Regex("(?m)^Title:[ \t]*(.*)$").find(block)?.groupValues?.get(1)?.trim().orEmpty()
            val date = Regex("(?m)^Published:[ \t]*(\\S+)").find(block)?.groupValues?.get(1)?.takeIf { it != "N/A" }?.take(10)
            val body = Regex("(?s)^(?:Highlights|Text):[ \t]*(.*)", RegexOption.MULTILINE).find(block)?.groupValues?.get(1)?.trim().orEmpty()
            Hit(title.ifEmpty { url }, url, body.removeSuffix("---").trim(), date)
        }.distinctBy { it.url }
    }

    data class Page(val title: String, val text: String)

    private val DROP = "script, style, noscript, svg, iframe, template, nav, footer, header, form, aside, button, select, [aria-hidden=true], [hidden]"
    private val BLOCK = setOf(
        "p", "div", "section", "article", "main", "li", "ul", "ol", "tr", "table", "br", "hr",
        "h1", "h2", "h3", "h4", "h5", "h6", "blockquote", "pre", "dd", "dt", "figcaption",
    )

    /** The page's readable text: the main part if it marks one, paragraphs on their own lines. */
    fun page(doc: Document): Page {
        val title = doc.title().trim()
        doc.select(DROP).remove()
        // menus, tag clouds, "related" lists: blocks that are almost all links
        doc.select("ul, ol, dl, div, section, table").filter { el ->
            val all = el.text().length
            all > 0 && el.select("p").isEmpty() && el.select("a").sumOf { it.text().length } > all * 0.7
        }.forEach { if (it.parent() != null) it.remove() }
        val root: Element = doc.selectFirst("article, main, [role=main]")?.takeIf { it.text().length > 200 } ?: doc.body() ?: doc
        val out = StringBuilder()
        NodeTraversor.traverse(object : NodeVisitor {
            override fun head(node: Node, depth: Int) {
                when {
                    node is TextNode -> out.append(node.text().replace(Regex("\\s+"), " "))
                    node is Element && node.normalName() in BLOCK -> {
                        out.append('\n')
                        when (node.normalName()) {
                            "h1", "h2", "h3", "h4", "h5", "h6" -> out.append("# ")
                            "li" -> out.append("- ")
                        }
                    }
                    node is Element && node.normalName() in setOf("td", "th") -> out.append(" | ")
                }
            }
            override fun tail(node: Node, depth: Int) {
                if (node is Element && node.normalName() in BLOCK) out.append('\n')
            }
        }, root)
        val text = out.lineSequence().map { it.trim() }.filter { it.isNotEmpty() && it != "-" && it != "#" }
            .joinToString("\n")
        return Page(title, text)
    }
}
