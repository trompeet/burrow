package app.burrow.remote

import okhttp3.HttpUrl
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream

data class SearchHit(val title: String, val url: HttpUrl, val snippet: String)

data class SearchResults(val totalResults: Int?, val hits: List<SearchHit>)

/**
 * Parses kiwix-serve full-text search results (`/search?...&format=xml`, OpenSearch RSS 2.0).
 * The `<description>` snippet is emitted unescaped, so highlight tags such as `<b>` arrive as child
 * elements; all descendant text of a field is collected.
 */
object SearchResultsParser {
    fun parse(input: InputStream, requestUrl: HttpUrl, parser: XmlPullParser): SearchResults {
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        parser.setInput(input, null)
        val hits = ArrayList<SearchHit>()
        val text = StringBuilder()
        val fields = HashMap<String, String>()
        var itemDepth = -1
        // Depth of the element whose text is being collected, or -1.
        var fieldDepth = -1
        var total: Int? = null

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when {
                    parser.name == "item" && itemDepth < 0 -> {
                        itemDepth = parser.depth
                        fields.clear()
                    }
                    fieldDepth < 0 && isField(parser, itemDepth) -> {
                        fieldDepth = parser.depth
                        text.setLength(0)
                    }
                }
                XmlPullParser.TEXT -> if (fieldDepth >= 0) text.append(parser.text)
                XmlPullParser.END_TAG -> when {
                    parser.depth == fieldDepth -> {
                        val value = text.toString().replace(Regex("\\s+"), " ").trim()
                        if (itemDepth >= 0 && parser.depth == itemDepth + 1) {
                            fields[parser.name] = value
                        } else if (itemDepth < 0 && parser.name == "totalResults") {
                            total = value.toIntOrNull()
                        }
                        fieldDepth = -1
                    }
                    parser.depth == itemDepth && parser.name == "item" -> {
                        itemDepth = -1
                        val link = fields["link"].orEmpty()
                        val url = if (link.isEmpty()) null else requestUrl.resolve(link)
                        if (url != null) {
                            val title = fields["title"].orEmpty().ifEmpty { url.pathSegments.lastOrNull().orEmpty() }
                            hits += SearchHit(title, url, fields["description"].orEmpty())
                        }
                    }
                }
            }
            event = parser.next()
        }
        return SearchResults(total, hits)
    }

    private fun isField(parser: XmlPullParser, itemDepth: Int) =
        if (itemDepth >= 0) parser.depth == itemDepth + 1 else parser.name == "totalResults"
}
