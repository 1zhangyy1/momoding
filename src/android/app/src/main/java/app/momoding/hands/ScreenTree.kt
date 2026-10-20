package app.momoding.hands

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.openminis.app.accessibility.MinisAccessibilityService

/**
 * The screen as a short numbered list of what can be acted on, read from the accessibility tree:
 * the way the best phone agents look (tree first, screenshot only where the tree is thin). A
 * clickable row carries the words of its children, so "[12] item “张阿姨 · 晚上好”" is one thing
 * to tap; text that belongs to nothing clickable is listed after, for reading. The model answers
 * with element numbers; [elements] maps them back to live nodes for this one screen.
 */
class ScreenTree private constructor(
    val elements: List<Element>,
    /** Readable text that is not on anything actionable (titles, values, messages). */
    private val texts: List<String>,
    val app: String?,
) {
    data class Element(val index: Int, val node: AccessibilityNodeInfo, val kind: String, val label: String, val value: String?, val bounds: Rect, val flags: List<String>) {
        val centerX: Int get() = bounds.centerX()
        val centerY: Int get() = bounds.centerY()
        val editable: Boolean get() = kind == "input"
        val scrollable: Boolean get() = kind == "list"
    }

    /** Things that can be acted on besides lists: below a few, the tree alone does not show the screen. */
    val actionable: Int get() = elements.count { !it.scrollable }

    operator fun get(index: Int): Element? = elements.firstOrNull { it.index == index }

    /** Structure only (no field values, no times): the same screen gives the same signature while you type. */
    val signature: Int = (app.hashCode() * 31) + elements.joinToString("|") { "${it.kind}:${it.label}:${it.bounds.left / 8},${it.bounds.top / 8}" }.hashCode()

    /** What the model reads. */
    fun render(maxChars: Int = 6000): String = buildString {
        app?.let { append("App in front: ").append(it).append('\n') }
        if (elements.isEmpty()) append("(no elements in the accessibility tree — use the screenshot)\n")
        for (e in elements) {
            val line = buildString {
                append('[').append(e.index).append("] ").append(e.kind)
                if (e.label.isNotBlank()) append(" “").append(e.label).append('”')
                e.value?.let { append(" = “").append(it).append('”') }
                if (e.flags.isNotEmpty()) append(" (").append(e.flags.joinToString(", ")).append(')')
            }
            if (length + line.length > maxChars) { append("… (more elements below; scroll to see them)\n"); break }
            append(line).append('\n')
        }
        if (texts.isNotEmpty() && length < maxChars) {
            append("Text on screen:\n")
            for (t in texts) {
                if (length + t.length > maxChars) break
                append("  ").append(t).append('\n')
            }
        }
    }

    companion object {
        private const val MAX_ELEMENTS = 120
        private const val MAX_TEXTS = 60
        private const val MAX_LABEL = 80

        /** Reads the windows the person sees, leaving out Momoding's own overlays and the system bars. */
        fun read(svc: MinisAccessibilityService, ownPackage: String, screen: Rect): ScreenTree {
            val roots = ArrayList<AccessibilityNodeInfo>()
            runCatching {
                for (w in svc.windows ?: emptyList()) {
                    val root = w.root ?: continue
                    val pkg = root.packageName?.toString()
                    if (pkg == ownPackage || pkg == "com.android.systemui") continue
                    if (w.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) continue
                    roots += root
                }
            }
            if (roots.isEmpty()) runCatching { svc.rootInActiveWindow }.getOrNull()?.let { roots += it }
            val elements = ArrayList<Element>()
            val texts = ArrayList<String>()
            for (root in roots) walk(root, false, screen, elements, texts)
            val app = svc.foregroundPackage().first?.takeIf { it != ownPackage }?.let { HandsApps.labelOf(svc, it) }
            return ScreenTree(elements, texts.distinct().take(MAX_TEXTS), app)
        }

        private fun walk(n: AccessibilityNodeInfo?, underClickable: Boolean, screen: Rect, out: MutableList<Element>, texts: MutableList<String>) {
            if (n == null || !n.isVisibleToUser) return
            val r = Rect(); n.getBoundsInScreen(r)
            if (r.width() <= 1 || r.height() <= 1 || !Rect.intersects(r, screen)) return
            val clickable = n.isClickable || n.isLongClickable
            val actionable = clickable || n.isEditable || n.isScrollable || n.isCheckable
            if (actionable && out.size < MAX_ELEMENTS) {
                val own = own(n)
                val kind = kind(n)
                val label = when {
                    n.isEditable -> listOfNotNull(n.hintText?.toString(), n.contentDescription?.toString(), n.viewIdResourceName?.substringAfter('/')).firstOrNull { it.isNotBlank() }.orEmpty()
                    own.isNotBlank() -> own
                    n.isScrollable -> ""
                    else -> childWords(n)
                }.clip()
                val value = if (n.isEditable) when {
                    n.isPassword -> "(password)"
                    n.text.isNullOrBlank() || n.text?.toString() == n.hintText?.toString() -> null
                    else -> n.text.toString().clip()
                } else null
                val flags = buildList {
                    if (n.isCheckable) add(if (n.isChecked) "on" else "off")
                    if (n.isSelected) add("selected")
                    if (!n.isEnabled) add("disabled")
                    if (n.isFocused && n.isEditable) add("cursor here")
                    if (n.isLongClickable && !n.isClickable) add("long-press")
                }
                // a bare container with nothing to say and nothing to toggle is noise
                if (label.isNotBlank() || n.isEditable || n.isScrollable || n.isCheckable || kind == "icon") {
                    out += Element(out.size + 1, n, kind, label, value, r, flags)
                }
            } else if (!underClickable) {
                own(n).takeIf { it.isNotBlank() }?.let { texts += it.clip() }
            }
            val nowUnder = underClickable || (clickable && !n.isScrollable)
            for (i in 0 until n.childCount) walk(runCatching { n.getChild(i) }.getOrNull(), nowUnder, screen, out, texts)
        }

        private fun own(n: AccessibilityNodeInfo): String =
            (n.text?.toString()?.takeIf { it.isNotBlank() } ?: n.contentDescription?.toString().orEmpty()).trim()

        /** The words of a clickable row's children, e.g. a contact's name and last message. */
        private fun childWords(n: AccessibilityNodeInfo): String {
            val words = ArrayList<String>()
            fun collect(c: AccessibilityNodeInfo?, depth: Int) {
                if (c == null || depth > 4 || words.size >= 4) return
                own(c).takeIf { it.isNotBlank() }?.let { words += it }
                for (i in 0 until c.childCount) collect(runCatching { c.getChild(i) }.getOrNull(), depth + 1)
            }
            for (i in 0 until n.childCount) collect(runCatching { n.getChild(i) }.getOrNull(), 1)
            return words.distinct().joinToString(" · ")
        }

        private fun kind(n: AccessibilityNodeInfo): String {
            val cls = n.className?.toString().orEmpty()
            return when {
                n.isEditable -> "input"
                cls.contains("Switch") || cls.contains("Toggle") -> "switch"
                n.isCheckable -> "checkbox"
                n.isScrollable -> "list"
                cls.contains("Button") && !cls.contains("Image") -> "button"
                cls.contains("Image") && own(n).isBlank() -> "icon"
                cls.contains("Tab") -> "tab"
                else -> "item"
            }
        }

        private fun String.clip(): String = replace('\n', ' ').trim().let { if (it.length > MAX_LABEL) it.take(MAX_LABEL) + "…" else it }

        /**
         * Waits until the screen stops changing (the same structure twice, 150 ms apart) or
         * [maxMs] runs out, instead of a fixed pause after every action.
         */
        fun settle(svc: MinisAccessibilityService, ownPackage: String, screen: Rect, minMs: Long = 150, maxMs: Long = 1500) {
            val start = System.currentTimeMillis()
            Thread.sleep(minMs)
            var last = runCatching { read(svc, ownPackage, screen).signature }.getOrNull()
            while (System.currentTimeMillis() - start < maxMs) {
                Thread.sleep(150)
                val now = runCatching { read(svc, ownPackage, screen).signature }.getOrNull()
                if (now != null && now == last) return
                last = now
            }
        }
    }
}
