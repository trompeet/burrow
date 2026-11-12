package app.kiwixreader.books

import android.graphics.Paint
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.LeadingMarginSpan
import android.text.style.LineHeightSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.SubscriptSpan
import android.text.style.SuperscriptSpan
import android.text.style.TypefaceSpan
import android.text.style.UnderlineSpan
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode

class Heading(val offset: Int, val level: Int, val title: String)

class ConvertedText(val text: SpannableStringBuilder, val anchors: Map<String, Int>, val headings: List<Heading>)

/** Adds space below the last line of a paragraph (StaticLayout has no paragraph spacing of its own). */
class ParagraphSpacingSpan(private val factor: Float = 0.6f) : LineHeightSpan {
    override fun chooseHeight(text: CharSequence, start: Int, end: Int, spanstartv: Int, lineHeight: Int, fm: Paint.FontMetricsInt) {
        val spanEnd = (text as? Spanned)?.getSpanEnd(this) ?: return
        if (end >= spanEnd) {
            val extra = ((fm.descent - fm.ascent) * factor).toInt()
            fm.descent += extra
            fm.bottom += extra
        }
    }
}

fun SpannableStringBuilder.markParagraph(start: Int, end: Int = length) {
    if (end <= start) return
    // Nested blocks (div > p) share an end; one spacing span is enough.
    if (getSpans(start, end, ParagraphSpacingSpan::class.java).any { getSpanEnd(it) == end }) return
    setSpan(ParagraphSpacingSpan(), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
}

/** Converts XHTML/HTML (EPUB chapters, HTML books) into styled text for paginated rendering. */
class HtmlTextConverter {
    private val out = SpannableStringBuilder()
    private val anchors = HashMap<String, Int>()
    private val headings = ArrayList<Heading>()

    fun convert(root: Element): ConvertedText {
        walk(root, pre = false)
        while (out.isNotEmpty() && out[out.length - 1].isWhitespace()) out.delete(out.length - 1, out.length)
        return ConvertedText(out, anchors, headings)
    }

    private fun walk(node: Node, pre: Boolean) {
        when (node) {
            is TextNode -> appendText(if (pre) node.wholeText else node.wholeText.replace(whitespace, " "))
            is Element -> element(node, pre)
        }
    }

    private fun element(element: Element, pre: Boolean) {
        val tag = element.normalName()
        if (tag in skipped) return
        element.id().takeIf { it.isNotEmpty() }?.let { anchors.putIfAbsent(it, out.length) }
        if (tag == "a") element.attr("name").takeIf { it.isNotEmpty() }?.let { anchors.putIfAbsent(it, out.length) }
        when (tag) {
            "br" -> {
                out.append('\n')
                return
            }
            "hr" -> {
                paragraphBreak()
                return
            }
        }
        val block = tag in blocks
        if (block) paragraphBreak()
        if (tag == "li") out.append("• ")
        val start = out.length
        element.childNodes().forEach { walk(it, pre || tag == "pre") }
        val end = out.length
        if (tag == "td" || tag == "th") out.append(' ')
        if (end > start) style(tag, start, end)
        if (block) {
            paragraphBreak()
            if (end > start) out.markParagraph(start)
        }
    }

    private fun appendText(raw: String) {
        var text = raw
        if (out.isEmpty() || out[out.length - 1] == '\n' || out[out.length - 1] == ' ') text = text.trimStart(' ')
        if (text.isNotEmpty()) out.append(text)
    }

    private fun paragraphBreak() {
        while (out.isNotEmpty() && out[out.length - 1] == ' ') out.delete(out.length - 1, out.length)
        if (out.isNotEmpty() && out[out.length - 1] != '\n') out.append('\n')
    }

    private fun style(tag: String, start: Int, end: Int) {
        fun span(what: Any) = out.setSpan(what, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        when (tag) {
            "h1", "h2", "h3", "h4", "h5", "h6" -> {
                val level = tag[1].digitToInt()
                span(StyleSpan(Typeface.BOLD))
                span(RelativeSizeSpan(headingSizes[level - 1]))
                val title = out.subSequence(start, end).toString().replace(whitespace, " ").trim()
                if (title.isNotEmpty()) headings += Heading(start, level, title)
            }
            "b", "strong" -> span(StyleSpan(Typeface.BOLD))
            "i", "em", "cite", "var", "dfn" -> span(StyleSpan(Typeface.ITALIC))
            "u", "ins" -> span(UnderlineSpan())
            "s", "strike", "del" -> span(StrikethroughSpan())
            "sup" -> {
                span(SuperscriptSpan())
                span(RelativeSizeSpan(0.7f))
            }
            "sub" -> {
                span(SubscriptSpan())
                span(RelativeSizeSpan(0.7f))
            }
            "small" -> span(RelativeSizeSpan(0.85f))
            "code", "tt", "kbd", "samp", "pre" -> span(TypefaceSpan("monospace"))
            "blockquote" -> span(LeadingMarginSpan.Standard(48))
        }
    }

    companion object {
        private val whitespace = Regex("\\s+")
        private val headingSizes = floatArrayOf(1.5f, 1.35f, 1.2f, 1.1f, 1f, 1f)
        private val skipped = setOf(
            "script", "style", "head", "title", "svg", "math", "noscript", "template", "img", "audio", "video",
            "object", "iframe", "button", "input", "select", "textarea",
        )
        private val blocks = setOf(
            "p", "div", "section", "article", "header", "footer", "aside", "main", "h1", "h2", "h3", "h4", "h5", "h6",
            "li", "ul", "ol", "dl", "dt", "dd", "blockquote", "pre", "table", "tr", "figure", "figcaption", "address",
            "center", "body", "html",
        )
    }
}

/** Removes Project Gutenberg licence header/footer from an HTML or EPUB document. */
object GutenbergHtml {
    class Markers(val hadStart: Boolean, val hadEnd: Boolean)

    private val start = Regex("""\*{3}\s*START OF\b""", RegexOption.IGNORE_CASE)
    private val end = Regex("""\*{3}\s*END OF\b""", RegexOption.IGNORE_CASE)

    fun clean(document: Document): Markers {
        // Current Gutenberg HTML/EPUB wrap the boilerplate in dedicated sections.
        val header = document.select("#pg-header, #pg-machine-header")
        val footer = document.select("#pg-footer")
        val hadHeader = header.isNotEmpty()
        val hadFooter = footer.isNotEmpty()
        header.remove()
        footer.remove()
        document.select(".pg-boilerplate").remove()
        val body = document.body() ?: return Markers(hadHeader, hadFooter)
        // Older files only have the text markers: cut everything before START and after END.
        val startElement = body.select("*").firstOrNull { start.containsMatchIn(it.ownText()) }
        startElement?.let { removeWithSiblings(it, body, before = true) }
        val endElement = body.select("*").firstOrNull { end.containsMatchIn(it.ownText()) }
        endElement?.let { removeWithSiblings(it, body, before = false) }
        return Markers(hadHeader || startElement != null, hadFooter || endElement != null)
    }

    private fun removeWithSiblings(target: Element, root: Element, before: Boolean) {
        var current: Node = target
        while (current !== root) {
            val parent = current.parent() ?: break
            val siblings = if (before) {
                generateSequence(current.previousSibling()) { it.previousSibling() }.toList()
            } else {
                generateSequence(current.nextSibling()) { it.nextSibling() }.toList()
            }
            siblings.forEach { it.remove() }
            current = parent
        }
        target.remove()
    }
}
