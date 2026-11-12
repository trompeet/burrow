package app.burrow.remote

import okhttp3.HttpUrl
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream

data class OpdsBook(
    val id: String,
    val title: String,
    val summary: String,
    val language: String,
    val name: String,
    val flavour: String,
    val category: String,
    val author: String,
    val articleCount: Int,
    val mediaCount: Int,
    val sizeBytes: Long,
    /** `<root>/content/<bookName>`: opens the book's main page. */
    val contentUrl: HttpUrl?,
    /** Acquisition link of type application/x-zim, when the server publishes one. */
    val downloadUrl: HttpUrl?,
    val thumbnailUrl: HttpUrl?,
)

data class OpdsFeed(val totalResults: Int?, val books: List<OpdsBook>)

/** Parses kiwix-serve's OPDS v2 acquisition feeds (`/catalog/v2/entries`). */
object OpdsParser {
    private const val REL_ACQUISITION = "http://opds-spec.org/acquisition/open-access"
    private const val REL_THUMBNAIL = "http://opds-spec.org/image/thumbnail"

    private class EntryBuilder {
        val fields = HashMap<String, String>()
        var author = ""
        var contentUrl: HttpUrl? = null
        var downloadUrl: HttpUrl? = null
        var downloadLength = 0L
        var thumbnailUrl: HttpUrl? = null
    }

    /** [feedUrl] is the URL the feed was fetched from; relative links are resolved against it. */
    fun parse(input: InputStream, feedUrl: HttpUrl, parser: XmlPullParser): OpdsFeed {
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        parser.setInput(input, null)
        val path = ArrayList<String>()
        val text = StringBuilder()
        val books = ArrayList<OpdsBook>()
        var entry: EntryBuilder? = null
        var total: Int? = null

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    val name = parser.name
                    path += name
                    text.setLength(0)
                    if (name == "entry") {
                        entry = EntryBuilder()
                    } else if (name == "link" && entry != null) {
                        readLink(parser, feedUrl, entry)
                    }
                }
                XmlPullParser.TEXT -> text.append(parser.text)
                XmlPullParser.END_TAG -> {
                    val name = parser.name
                    val parent = path.getOrNull(path.size - 2)
                    val value = text.toString().trim()
                    val current = entry
                    if (current != null) {
                        when {
                            name == "entry" -> {
                                current.build()?.let(books::add)
                                entry = null
                            }
                            parent == "entry" -> current.fields[name] = value
                            parent == "author" && name == "name" -> current.author = value
                        }
                    } else if (name == "totalResults") {
                        total = value.toIntOrNull()
                    }
                    path.removeAt(path.lastIndex)
                    text.setLength(0)
                }
            }
            event = parser.next()
        }
        return OpdsFeed(total, books)
    }

    private fun readLink(parser: XmlPullParser, base: HttpUrl, entry: EntryBuilder) {
        val rel = parser.getAttributeValue(null, "rel").orEmpty()
        val type = parser.getAttributeValue(null, "type").orEmpty()
        val href = parser.getAttributeValue(null, "href")?.trim()?.takeIf { it.isNotEmpty() } ?: return
        val url = base.resolve(href) ?: return
        when {
            rel == REL_ACQUISITION && type.startsWith("application/x-zim") -> {
                entry.downloadUrl = url
                entry.downloadLength = parser.getAttributeValue(null, "length")?.toLongOrNull() ?: 0L
            }
            rel == REL_THUMBNAIL -> entry.thumbnailUrl = url
            type.startsWith("text/html") && (rel.isEmpty() || rel == "alternate") -> entry.contentUrl = url
        }
    }

    private fun EntryBuilder.build(): OpdsBook? {
        val title = fields["title"].orEmpty()
        if (title.isEmpty() && contentUrl == null) return null
        return OpdsBook(
            id = fields["id"].orEmpty().removePrefix("urn:uuid:"),
            title = title.ifEmpty { fields["name"].orEmpty() },
            summary = fields["summary"].orEmpty(),
            language = fields["language"].orEmpty(),
            name = fields["name"].orEmpty(),
            flavour = fields["flavour"].orEmpty(),
            category = fields["category"].orEmpty(),
            author = author,
            articleCount = fields["articleCount"]?.toIntOrNull() ?: 0,
            mediaCount = fields["mediaCount"]?.toIntOrNull() ?: 0,
            sizeBytes = downloadLength,
            contentUrl = contentUrl,
            downloadUrl = downloadUrl,
            thumbnailUrl = thumbnailUrl,
        )
    }
}
