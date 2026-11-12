package app.kiwixreader.books

import android.graphics.Typeface
import android.net.Uri
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.util.Xml
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.util.zip.ZipFile

enum class BookFormat(val extension: String) { EPUB("epub"), TXT("txt"), HTML("html") }

data class TocEntry(val title: String, val depth: Int, val section: Int, val offset: Int)

data class BookMetadata(val title: String, val author: String)

/**
 * A book ready for pagination: its text split into [sections] (each laid out separately) and a table of
 * contents pointing at (section, offset) positions.
 */
class BookContent(val sections: List<CharSequence>, val toc: List<TocEntry>) {
    val sectionStarts: IntArray = IntArray(sections.size).also { starts ->
        var total = 0
        sections.forEachIndexed { i, s ->
            starts[i] = total
            total += s.length
        }
    }
    val totalChars: Int = sections.sumOf { it.length }

    fun progress(section: Int, offset: Int): Float =
        if (totalChars == 0) 0f else ((sectionStarts.getOrElse(section) { 0 } + offset).toFloat() / totalChars).coerceIn(0f, 1f)

    /** Title of the TOC entry the position falls in. */
    fun chapterTitleAt(section: Int, offset: Int): String? =
        toc.lastOrNull { it.section < section || (it.section == section && it.offset <= offset) }?.title
}

private class SectionBuilder(private val maxChars: Int = 30_000) {
    val sections = ArrayList<CharSequence>()

    inner class Locator(private val firstSection: Int, private val starts: IntArray) {
        fun locate(offset: Int): Pair<Int, Int> {
            val index = Paginator.pageForOffset(starts, offset)
            return (firstSection + index) to (offset - starts[index]).coerceAtLeast(0)
        }
    }

    /** Adds a chapter; [breaks] are offsets (e.g. TOC targets) that must start a new page. */
    fun add(text: CharSequence, breaks: Collection<Int> = emptyList()): Locator {
        val first = sections.size
        val bounds = (breaks.filter { it in 1 until text.length } + 0).distinct().sorted() + text.length
        val starts = bounds.zipWithNext().flatMap { (from, to) ->
            Paginator.sectionStarts(text.subSequence(from, to), maxChars).map { from + it }
        }.toIntArray()
        starts.forEachIndexed { i, start ->
            val end = if (i + 1 < starts.size) starts[i + 1] else text.length
            sections += text.subSequence(start, end)
        }
        return Locator(first, starts)
    }
}

object BookParser {
    fun metadata(file: File, format: BookFormat, fallbackTitle: String): BookMetadata = when (format) {
        BookFormat.EPUB -> ZipFile(file).use { zip -> readOpf(zip, containerRoot(zip)).metadata(fallbackTitle) }
        BookFormat.HTML -> htmlMetadata(Jsoup.parse(file, null), fallbackTitle)
        BookFormat.TXT -> textMetadata(PlainText.decode(file.readBytes()), fallbackTitle)
    }

    fun load(file: File, format: BookFormat): BookContent = when (format) {
        BookFormat.EPUB -> loadEpub(file)
        BookFormat.HTML -> loadHtml(file)
        BookFormat.TXT -> loadText(file)
    }

    // ---- Plain text -------------------------------------------------------------------------------------

    private fun loadText(file: File): BookContent {
        val text = Gutenberg.strip(PlainText.decode(file.readBytes()))
        val builder = SectionBuilder()
        val toc = ArrayList<TocEntry>()
        var chapter = SpannableStringBuilder()
        var chapterTitle: String? = null

        fun flush() {
            if (chapter.isNotBlank()) {
                val (section, offset) = builder.add(chapter).locate(0)
                chapterTitle?.let { toc += TocEntry(it, 0, section, offset) }
            }
            chapter = SpannableStringBuilder()
        }

        for (paragraph in PlainText.paragraphs(text)) {
            if (paragraph.isHeading) {
                flush()
                chapterTitle = paragraph.text.replace('\n', ' ')
            }
            val start = chapter.length
            chapter.append(paragraph.text).append('\n')
            if (paragraph.isHeading) {
                chapter.setSpan(StyleSpan(Typeface.BOLD), start, chapter.length - 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                chapter.setSpan(RelativeSizeSpan(1.3f), start, chapter.length - 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            chapter.markParagraph(start)
        }
        flush()
        return BookContent(builder.sections.ifEmpty { listOf("") }, toc)
    }

    private val gutenbergTitle = Regex("""^\s*Title:\s*(.+)$""", RegexOption.MULTILINE)
    private val gutenbergAuthor = Regex("""^\s*Author:\s*(.+)$""", RegexOption.MULTILINE)

    private fun textMetadata(text: String, fallbackTitle: String): BookMetadata {
        val header = text.take(8_000)
        return BookMetadata(
            title = gutenbergTitle.find(header)?.groupValues?.get(1)?.trim() ?: fallbackTitle,
            author = gutenbergAuthor.find(header)?.groupValues?.get(1)?.trim().orEmpty(),
        )
    }

    // ---- HTML ---------------------------------------------------------------------------------------------

    private fun loadHtml(file: File): BookContent {
        val document = Jsoup.parse(file, null)
        GutenbergHtml.clean(document)
        val converted = HtmlTextConverter().convert(document.body())
        val builder = SectionBuilder()
        val chapterHeadings = converted.headings.filter { it.level <= 3 }
        val locator = builder.add(converted.text, if (chapterHeadings.size >= 2) chapterHeadings.map { it.offset } else emptyList())
        val toc = chapterHeadings.map { heading ->
            val (section, offset) = locator.locate(heading.offset)
            TocEntry(heading.title, heading.level - 1, section, offset)
        }
        return BookContent(builder.sections, if (toc.size >= 2) toc else emptyList())
    }

    private fun htmlMetadata(document: org.jsoup.nodes.Document, fallbackTitle: String): BookMetadata {
        fun meta(vararg names: String) = names.firstNotNullOfOrNull { name ->
            document.select("meta[name]").firstOrNull { it.attr("name").equals(name, true) }?.attr("content")?.takeIf { it.isNotBlank() }
        }
        var title = meta("dc.title") ?: document.title().ifBlank { fallbackTitle }
        var author = meta("dc.creator", "author").orEmpty()
        // "The Project Gutenberg eBook of Pride and Prejudice, by Jane Austen"
        val pg = Regex("""^The Project Gutenberg e[Bb]ook of (.+?)(?:, by (.+))?$""").find(title)
        if (pg != null) {
            title = pg.groupValues[1]
            if (author.isEmpty()) author = pg.groupValues[2]
        }
        return BookMetadata(title.trim(), author.trim())
    }

    // ---- EPUB ---------------------------------------------------------------------------------------------

    private class ManifestItem(val href: String, val mediaType: String, val properties: String)

    private class Opf(
        val path: String,
        val title: String?,
        val author: String?,
        val manifest: Map<String, ManifestItem>,
        val spine: List<String>,
        val ncxId: String?,
    ) {
        val directory = path.substringBeforeLast('/', "")
        fun resolve(href: String) = resolvePath(directory, href)
        fun metadata(fallbackTitle: String) = BookMetadata(title ?: fallbackTitle, author.orEmpty())
    }

    private class RawToc(val title: String, val path: String, val fragment: String?, val depth: Int)

    private fun loadEpub(file: File): BookContent = ZipFile(file).use { zip ->
        val opf = readOpf(zip, containerRoot(zip))
        val documentPaths = opf.spine.mapNotNull { opf.manifest[it] }
            .filter { it.mediaType.contains("html") }
            .map { opf.resolve(it.href) }

        // Convert every spine document, noting where Gutenberg boilerplate starts and ends.
        val converted = ArrayList<Pair<String, ConvertedText>>()
        var firstKept = 0
        var lastKept = Int.MAX_VALUE
        documentPaths.forEachIndexed { index, path ->
            val entry = zip.getEntry(path) ?: return@forEachIndexed
            val document = zip.getInputStream(entry).use { Jsoup.parse(it, null, "") }
            val markers = GutenbergHtml.clean(document)
            if (markers.hadStart && lastKept == Int.MAX_VALUE) firstKept = converted.size
            if (markers.hadEnd && lastKept == Int.MAX_VALUE) lastKept = converted.size
            converted += path to HtmlTextConverter().convert(document.body())
        }
        val kept = converted.withIndex().filter { (i, _) -> i >= firstKept && i <= lastKept }.map { it.value }

        val rawToc = readToc(zip, opf)
        val builder = SectionBuilder()
        val locators = HashMap<String, Pair<SectionBuilder.Locator, ConvertedText>>()
        for ((path, text) in kept) {
            if (text.text.isBlank()) continue
            // Chapters often share one XHTML file; start each TOC target on a new page.
            val breaks = rawToc.filter { it.path == path }.mapNotNull { raw -> raw.fragment?.let { text.anchors[it] } }
            locators[path] = builder.add(text.text, breaks) to text
        }

        val toc = rawToc.mapNotNull { raw ->
            val (locator, text) = locators[raw.path] ?: return@mapNotNull null
            val (section, offset) = locator.locate(raw.fragment?.let { text.anchors[it] } ?: 0)
            TocEntry(raw.title, raw.depth, section, offset)
        }.ifEmpty {
            // No usable navigation document: fall back to the chapter headings.
            kept.flatMap { (path, text) ->
                val locator = locators[path]?.first ?: return@flatMap emptyList()
                text.headings.filter { it.level <= 2 }.map { heading ->
                    val (section, offset) = locator.locate(heading.offset)
                    TocEntry(heading.title, heading.level - 1, section, offset)
                }
            }
        }
        BookContent(builder.sections.ifEmpty { listOf("") }, toc)
    }

    private fun containerRoot(zip: ZipFile): String {
        val entry = zip.getEntry("META-INF/container.xml") ?: throw IllegalArgumentException("Not an EPUB file")
        zip.getInputStream(entry).use { input ->
            val parser = Xml.newPullParser()
            parser.setInput(input, null)
            while (parser.next() != XmlPullParser.END_DOCUMENT) {
                if (parser.eventType == XmlPullParser.START_TAG && parser.name == "rootfile") {
                    parser.getAttributeValue(null, "full-path")?.let { return it }
                }
            }
        }
        throw IllegalArgumentException("EPUB without package document")
    }

    private fun readOpf(zip: ZipFile, path: String): Opf {
        val entry = zip.getEntry(path) ?: throw IllegalArgumentException("Missing $path")
        var title: String? = null
        var author: String? = null
        var ncx: String? = null
        val manifest = LinkedHashMap<String, ManifestItem>()
        val spine = ArrayList<String>()
        zip.getInputStream(entry).use { input ->
            val parser = Xml.newPullParser()
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
            parser.setInput(input, null)
            while (parser.next() != XmlPullParser.END_DOCUMENT) {
                if (parser.eventType != XmlPullParser.START_TAG) continue
                when (parser.name) {
                    "title" -> if (title == null && parser.namespace == DC) title = parser.nextText().trim().ifEmpty { null }
                    "creator" -> if (author == null && parser.namespace == DC) author = parser.nextText().trim().ifEmpty { null }
                    "item" -> {
                        val id = parser.getAttributeValue(null, "id") ?: continue
                        manifest[id] = ManifestItem(
                            href = parser.getAttributeValue(null, "href").orEmpty(),
                            mediaType = parser.getAttributeValue(null, "media-type").orEmpty(),
                            properties = parser.getAttributeValue(null, "properties").orEmpty(),
                        )
                    }
                    "spine" -> ncx = parser.getAttributeValue(null, "toc")
                    "itemref" -> parser.getAttributeValue(null, "idref")?.let(spine::add)
                }
            }
        }
        return Opf(path, title, author, manifest, spine, ncx)
    }

    private fun readToc(zip: ZipFile, opf: Opf): List<RawToc> {
        // EPUB 3 navigation document first, then the EPUB 2 NCX.
        opf.manifest.values.firstOrNull { "nav" in it.properties.split(' ') }?.let { item ->
            val path = opf.resolve(item.href)
            val entries = zip.getEntry(path)?.let { entry ->
                zip.getInputStream(entry).use { readNav(Jsoup.parse(it, null, ""), path) }
            }
            if (!entries.isNullOrEmpty()) return entries
        }
        val ncxItem = opf.ncxId?.let { opf.manifest[it] }
            ?: opf.manifest.values.firstOrNull { it.mediaType == "application/x-dtbncx+xml" }
            ?: return emptyList()
        val path = opf.resolve(ncxItem.href)
        val entry = zip.getEntry(path) ?: return emptyList()
        return zip.getInputStream(entry).use { readNcx(it, path) }
    }

    private fun readNav(document: org.jsoup.nodes.Document, navPath: String): List<RawToc> {
        val nav = document.select("nav").firstOrNull { "toc" in it.attr("epub:type") } ?: document.selectFirst("nav")
            ?: return emptyList()
        val directory = navPath.substringBeforeLast('/', "")
        val out = ArrayList<RawToc>()
        fun walk(list: Element, depth: Int) {
            for (li in list.children().filter { it.normalName() == "li" }) {
                val link = li.children().firstOrNull { it.normalName() == "a" }
                    ?: li.children().firstOrNull { it.normalName() == "span" }?.selectFirst("a")
                val href = link?.attr("href").orEmpty()
                val title = link?.text()?.trim().orEmpty()
                if (href.isNotEmpty() && title.isNotEmpty()) out += rawToc(title, directory, href, depth)
                li.children().filter { it.normalName() == "ol" || it.normalName() == "ul" }.forEach { walk(it, depth + 1) }
            }
        }
        nav.children().filter { it.normalName() == "ol" || it.normalName() == "ul" }.forEach { walk(it, 0) }
        return out
    }

    private fun readNcx(input: java.io.InputStream, ncxPath: String): List<RawToc> {
        val directory = ncxPath.substringBeforeLast('/', "")
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        parser.setInput(input, null)
        val out = ArrayList<RawToc>()
        var depth = -1
        var label = ""
        var inLabel = false
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "navPoint" -> {
                        depth++
                        label = ""
                    }
                    "navLabel" -> inLabel = true
                    "text" -> if (inLabel) label = parser.nextText().trim()
                    "content" -> {
                        val src = parser.getAttributeValue(null, "src").orEmpty()
                        if (src.isNotEmpty() && label.isNotEmpty() && depth >= 0) out += rawToc(label, directory, src, depth)
                    }
                }
                XmlPullParser.END_TAG -> when (parser.name) {
                    "navPoint" -> depth--
                    "navLabel" -> inLabel = false
                }
            }
        }
        return out
    }

    private fun rawToc(title: String, directory: String, href: String, depth: Int): RawToc {
        val path = resolvePath(directory, href.substringBefore('#'))
        val fragment = href.substringAfter('#', "").takeIf { it.isNotEmpty() }?.let { Uri.decode(it) }
        return RawToc(title, path, fragment, depth)
    }

    /** Resolves an EPUB href (URL-encoded, relative, may contain ".." ) against a zip directory. */
    private fun resolvePath(directory: String, href: String): String {
        val parts = ArrayList<String>()
        if (directory.isNotEmpty()) parts += directory.split('/')
        for (segment in Uri.decode(href).split('/')) {
            when (segment) {
                "", "." -> Unit
                ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.lastIndex)
                else -> parts += segment
            }
        }
        return parts.joinToString("/")
    }

    private const val DC = "http://purl.org/dc/elements/1.1/"
}
