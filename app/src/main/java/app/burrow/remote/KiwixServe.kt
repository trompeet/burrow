package app.burrow.remote

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * kiwix-serve HTTP API (see kiwix-tools docs/kiwix-serve.rst). Public endpoints used here:
 * - `/catalog/v2/entries?start=&count=&q=`: OPDS acquisition feed of the books on the server
 * - `/search?books.name=&pattern=&format=xml`: full-text search, OpenSearch RSS
 * Articles are loaded from the book's `text/html` OPDS link (`<root>/content/<bookName>`), which may point
 * at a separate content host (`--contentServerURL`).
 */
object KiwixServe {
    /** Accepts `192.168.1.10:8080`, `http://host:8080/`, `https://host/kiwix` and returns a clean base URL. */
    fun normalizeBaseUrl(input: String): HttpUrl? {
        var text = input.trim()
        if (text.isEmpty()) return null
        if (!text.contains("://")) text = "http://$text"
        val url = text.toHttpUrlOrNull() ?: return null
        if (url.host.isBlank()) return null
        val path = url.encodedPath.trimEnd('/')
        return url.newBuilder()
            .encodedPath(path.ifEmpty { "/" })
            .query(null)
            .fragment(null)
            .username("")
            .password("")
            .build()
    }

    fun baseUrlString(url: HttpUrl): String = url.toString().trimEnd('/')

    fun entriesUrl(base: HttpUrl, start: Int, count: Int, query: String?): HttpUrl =
        base.newBuilder()
            .addPathSegments("catalog/v2/entries")
            .addQueryParameter("start", start.toString())
            .addQueryParameter("count", count.toString())
            .apply { if (!query.isNullOrBlank()) addQueryParameter("q", query.trim()) }
            .build()

    /** Book name as used by `/content/<name>` and `/search?books.name=<name>`. */
    fun bookName(contentRoot: HttpUrl): String? {
        val segments = contentRoot.pathSegments.filter { it.isNotEmpty() }
        val index = segments.lastIndexOf("content")
        return if (index >= 0 && index < segments.lastIndex) segments[index + 1] else null
    }

    fun searchUrl(contentRoot: HttpUrl, pattern: String, pageLength: Int = 50): HttpUrl? {
        val segments = contentRoot.pathSegments.filter { it.isNotEmpty() }
        val index = segments.lastIndexOf("content")
        val book = bookName(contentRoot) ?: return null
        return contentRoot.newBuilder()
            .encodedPath("/")
            .apply { segments.subList(0, index).forEach { addPathSegment(it) } }
            .addPathSegment("search")
            .query(null)
            .fragment(null)
            .addQueryParameter("books.name", book)
            .addQueryParameter("pattern", pattern)
            .addQueryParameter("format", "xml")
            .addQueryParameter("pageLength", pageLength.toString())
            .build()
    }

    /** kiwix.org catalogs link `.zim.meta4` metalinks; the same path without `.meta4` is the ZIM itself. */
    fun directZimUrl(downloadUrl: HttpUrl): HttpUrl {
        val text = downloadUrl.toString()
        return if (text.endsWith(".meta4")) text.removeSuffix(".meta4").toHttpUrlOrNull() ?: downloadUrl else downloadUrl
    }
}
