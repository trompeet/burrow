package app.burrow.ui.reader

import android.net.Uri
import android.text.Html
import android.util.Log
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import app.burrow.net.RemoteFetcher
import app.burrow.net.sameOrigin
import app.burrow.remote.KiwixServe
import app.burrow.remote.ServerRepository
import app.burrow.zim.ZimArchive
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.ByteArrayInputStream
import java.io.Closeable

class ArticleHit(val title: String, val url: String, val snippet: String)

/** Where the article reader gets its pages from. Methods may be called from WebView background threads. */
sealed class ArticleSource : Closeable {
    abstract val bookKey: String
    abstract val bookTitle: String
    abstract val homeUrl: String

    /** True if [url] belongs to this book and should load inside the reader. */
    abstract fun handles(url: String): Boolean

    abstract fun intercept(request: WebResourceRequest): WebResourceResponse?

    abstract suspend fun search(query: String): List<ArticleHit>

    override fun close() = Unit

    /** A local ZIM file served from libkiwix under a reserved, never-resolving host. No network involved. */
    class Local(private val archive: ZimArchive, private val uri: String) : ArticleSource() {
        override val bookKey = "zim:$uri"
        override val bookTitle = archive.title
        override val homeUrl = PREFIX + (archive.mainPagePath()?.let(::encodePath) ?: "")

        override fun handles(url: String) = url.startsWith(PREFIX)

        override fun intercept(request: WebResourceRequest): WebResourceResponse? {
            val url = request.url.toString()
            if (!handles(url)) {
                Log.d(TAG, "Blocked request outside the ZIM: $url")
                return blocked()
            }
            val path = request.url.encodedPath?.removePrefix("/")?.let(Uri::decode).orEmpty()
            if (request.isForMainFrame) {
                // Load the redirect target itself so relative links resolve against the right path.
                archive.redirectTarget(path)?.let { return redirectPage(PREFIX + encodePath(it)) }
            }
            val item = archive.item(path) ?: return if (request.isForMainFrame) {
                Log.d(TAG, "Missing ZIM entry: $path")
                errorPage("Not found", "This article is not in the ZIM file.")
            } else {
                Log.d(TAG, "Missing ZIM entry: $path")
                notFound()
            }
            val mime = item.mimeType.substringBefore(';').trim().ifEmpty { "application/octet-stream" }
            return WebResourceResponse(mime, if (mime.startsWith("text/") || mime.contains("javascript")) "utf-8" else null, ByteArrayInputStream(item.data))
        }

        override suspend fun search(query: String) =
            archive.search(query).map { ArticleHit(it.title, PREFIX + encodePath(it.path), it.snippet) }

        override fun close() = archive.close()

        companion object {
            const val PREFIX = "https://zim.burrow.invalid/"

            /** Encodes each path segment; ZIM paths may contain spaces, '?', '#', etc. */
            fun encodePath(path: String) = path.split('/').joinToString("/") { Uri.encode(it) }
        }
    }

    /**
     * A book on a kiwix-serve instance. Requests may only go to the server's origin and the book's content origin
     * (they differ when kiwix-serve uses --contentServerURL); everything else is blocked.
     */
    class Remote(
        private val contentRoot: HttpUrl,
        override val bookTitle: String,
        val serverBase: HttpUrl?,
        private val fetcher: RemoteFetcher,
        private val servers: ServerRepository,
    ) : ArticleSource() {
        override val bookKey = "remote:$contentRoot"
        override val homeUrl = contentRoot.toString()

        fun allowed(url: HttpUrl) = sameOrigin(url, contentRoot) || (serverBase != null && sameOrigin(url, serverBase))

        override fun handles(url: String) = url.toHttpUrlOrNull()?.let(::allowed) == true

        override fun intercept(request: WebResourceRequest): WebResourceResponse? {
            if (request.method != "GET") return blocked()
            val url = request.url.toString()
            return when (val outcome = fetcher.fetch(url, ::allowed)) {
                is RemoteFetcher.Outcome.Loaded -> {
                    val resource = outcome.resource
                    if (request.isForMainFrame && resource.url != url) {
                        resource.body.close()
                        redirectPage(resource.url)
                    } else {
                        response(resource)
                    }
                }
                is RemoteFetcher.Outcome.Failed -> when {
                    !request.isForMainFrame -> notFound()
                    outcome.offline -> errorPage("Not available offline", "This article is not available offline. Save articles for offline reading while connected.")
                    else -> errorPage("Cannot load page", outcome.message)
                }
                RemoteFetcher.Outcome.Blocked -> if (request.isForMainFrame) errorPage("Blocked", "This page is not on a configured server.") else blocked()
            }
        }

        override suspend fun search(query: String): List<ArticleHit> =
            servers.search(contentRoot, query).hits.map { hit ->
                ArticleHit(hit.title, hit.url.toString(), Html.fromHtml(hit.snippet, Html.FROM_HTML_MODE_COMPACT).toString())
            }

        val searchSupported get() = KiwixServe.searchUrl(contentRoot, "x") != null

        private fun response(resource: RemoteFetcher.Resource): WebResourceResponse {
            val type = resource.contentType.orEmpty()
            val mime = type.substringBefore(';').trim().ifEmpty { "application/octet-stream" }
            val charset = type.substringAfter("charset=", "").trim().trim('"').ifEmpty { if (mime.startsWith("text/")) "utf-8" else null }
            // WebView rejects 3xx and other unusual codes in synthetic responses.
            val code = if (resource.code in 200..299 || resource.code in 400..599) resource.code else 200
            return WebResourceResponse(mime, charset, code, resource.reason, emptyMap(), resource.body)
        }
    }
}

private const val TAG = "ArticleSource"

private fun blocked() = WebResourceResponse("text/plain", "utf-8", 403, "Blocked", emptyMap(), ByteArrayInputStream(ByteArray(0)))

private fun notFound() = WebResourceResponse("text/plain", "utf-8", 404, "Not Found", emptyMap(), ByteArrayInputStream(ByteArray(0)))

/** location.replace keeps the redirect out of the back/forward history. */
private fun redirectPage(target: String): WebResourceResponse {
    val escaped = target.replace("\\", "\\\\").replace("'", "\\'")
    val html = "<!doctype html><meta charset=utf-8><script>location.replace('$escaped')</script>"
    return WebResourceResponse("text/html", "utf-8", ByteArrayInputStream(html.toByteArray()))
}

private fun errorPage(title: String, message: String): WebResourceResponse {
    val html = """
        <!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
        <style>body{font-family:sans-serif;margin:32px 24px;color:#212121;background:#fafafa}
        @media (prefers-color-scheme:dark){body{color:#fafafa;background:#212121}}h1{font-size:1.25em}</style></head>
        <body><h1>${Html.escapeHtml(title)}</h1><p>${Html.escapeHtml(message)}</p></body></html>
    """.trimIndent()
    return WebResourceResponse("text/html", "utf-8", ByteArrayInputStream(html.toByteArray()))
}
