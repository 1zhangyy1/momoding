package app.momoding.web

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.select.NodeTraversor
import org.jsoup.select.NodeVisitor

/** One search hit, as the page listed it. */
data class Hit(val title: String, val url: String, val snippet: String, val date: String? = null)

/**
 * Turning HTML into what the agent reads: search result pages into hits, any page into its text.
 * Pure parsing, no network — [WebLookup] fetches.
 */
object WebText {

    /** 360 Search (so.com): each `li.res-list` carries the real address in `data-mdurl`. */
    fun hits360(html: String): List<Hit> = Jsoup.parse(html, "https://www.so.com/").select("li.res-list").mapNotNull { li ->
        val a = li.selectFirst("h3 a") ?: return@mapNotNull null
        val url = a.attr("data-mdurl").ifBlank { a.absUrl("href") }
        val snippet = li.selectFirst(".res-list-summary, .res-desc, p.res-desc")?.text()
            ?: li.text().removePrefix(a.text())
        hit(a.text(), url, snippet, li.selectFirst("span.g-c-gray")?.text()?.trim { it.isWhitespace() || it == ' ' || it == '-' })
    }

    /** Sogou: each `div.vrwrap` / `div.rb` has the real address in a `data-url` somewhere inside. */
    fun hitsSogou(html: String): List<Hit> = Jsoup.parse(html, "https://www.sogou.com/").select("div.vrwrap, div.rb").mapNotNull { box ->
        val a = box.selectFirst("h3 a") ?: return@mapNotNull null
        val url = box.selectFirst("[data-url]")?.attr("data-url")?.takeIf { it.startsWith("http") } ?: a.absUrl("href")
        val snippet = box.selectFirst(".space-txt, .star-wiki, .str_info, .text-layout, p")?.text().orEmpty()
        hit(a.text(), url, snippet)
    }

    private fun hit(title: String, url: String, snippet: String, date: String? = null): Hit? {
        val t = title.trim()
        if (t.isEmpty() || !url.startsWith("http")) return null
        return Hit(t, url, snippet.trim().take(300), date?.takeIf { it.isNotBlank() })
    }

    /** Where a redirect page (a search engine's link hop) sends the browser, if that is all it does. */
    fun redirectTarget(html: String): String? {
        if (html.length > 4000) return null
        Regex("""location\.replace\(["']([^"']+)["']\)""").find(html)?.let { return it.groupValues[1] }
        return Regex("""http-equiv=["']?refresh["']?[^>]*URL=['"]?([^'">]+)""", RegexOption.IGNORE_CASE).find(html)?.groupValues?.get(1)
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
