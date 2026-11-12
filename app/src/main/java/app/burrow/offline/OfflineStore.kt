package app.burrow.offline

import app.burrow.data.OfflineArticleEntity
import app.burrow.data.OfflineDao
import app.burrow.data.OfflineResourceEntity
import app.burrow.net.RemoteFetcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.Jsoup
import java.io.File
import java.io.InputStream
import java.nio.charset.Charset
import java.util.UUID

/**
 * "Save for offline": stores a remote article and the assets it references (images, CSS, scripts, fonts
 * referenced from CSS) under app storage, and records every URL in the offline index (Room). The reader looks
 * URLs up there first, so saved articles open with no network.
 */
class OfflineStore(private val root: File, private val dao: OfflineDao) {
    val articles = dao.observeArticles()

    suspend fun isSaved(url: String) = dao.articleByUrl(url) != null

    /** Blocking lookup used by [RemoteFetcher] on WebView request threads. */
    fun lookup(url: String): RemoteFetcher.Stored? {
        val stored = dao.findResourceBlocking(url) ?: return null
        val file = File(File(root, stored.directory), stored.fileName)
        return if (file.isFile) RemoteFetcher.Stored(stored.contentType, file.inputStream()) else null
    }

    class SaveRequest(
        val pageUrl: String,
        val title: String,
        val bookTitle: String,
        val contentRoot: String,
        val serverBaseUrl: String?,
    )

    /** Fetches through [fetcher] (cache first), so assets the page already loaded come from the cache. */
    suspend fun save(request: SaveRequest, fetcher: RemoteFetcher, allowed: (HttpUrl) -> Boolean): OfflineArticleEntity =
        withContext(Dispatchers.IO) {
            dao.articleByUrl(request.pageUrl)?.let { delete(it) }
            val directoryName = UUID.randomUUID().toString()
            val directory = File(root, directoryName).apply { mkdirs() }
            try {
                val resources = ArrayList<OfflineResourceEntity>()
                var total = 0L
                var counter = 0

                fun store(url: String): Pair<ByteArray, String?>? {
                    val outcome = fetcher.fetch(url, allowed) as? RemoteFetcher.Outcome.Loaded ?: return null
                    val resource = outcome.resource
                    val bytes = resource.body.use { it.readCapped(MAX_RESOURCE_BYTES) } ?: return null
                    if (resource.code !in 200..299) return null
                    val fileName = (counter++).toString()
                    File(directory, fileName).writeBytes(bytes)
                    total += bytes.size
                    // Index both the requested URL and, after redirects, the final one.
                    resources += OfflineResourceEntity(0, url, fileName, resource.contentType)
                    if (resource.url != url) resources += OfflineResourceEntity(0, resource.url, fileName, resource.contentType)
                    return bytes to resource.contentType
                }

                val (html, htmlType) = store(request.pageUrl) ?: throw IllegalStateException("Page could not be loaded")
                val pageUrl = request.pageUrl.toHttpUrlOrNull() ?: throw IllegalStateException("Invalid URL")
                val queue = ArrayDeque(assetUrls(String(html, charsetOf(htmlType)), pageUrl.toString()))
                val seen = HashSet<String>(queue)
                // Stylesheets can pull in fonts, images and other stylesheets; `seen` stops cycles.
                while (queue.isNotEmpty() && seen.size <= MAX_ASSETS) {
                    val url = queue.removeFirst()
                    val (bytes, type) = store(url) ?: continue
                    if (type.orEmpty().startsWith("text/css")) {
                        cssUrls(String(bytes, charsetOf(type)), url).filter(seen::add).forEach(queue::addLast)
                    }
                }

                val article = OfflineArticleEntity(
                    url = request.pageUrl,
                    title = request.title,
                    bookTitle = request.bookTitle,
                    contentRoot = request.contentRoot,
                    serverBaseUrl = request.serverBaseUrl,
                    directory = directoryName,
                    sizeBytes = total,
                    savedAt = System.currentTimeMillis(),
                )
                val id = dao.insertArticle(article)
                dao.insertResources(resources.distinctBy { it.url }.map { it.copy(articleId = id) })
                article.copy(id = id)
            } catch (e: Exception) {
                directory.deleteRecursively()
                throw e
            }
        }

    suspend fun delete(article: OfflineArticleEntity) = withContext(Dispatchers.IO) {
        dao.deleteArticle(article.id)
        File(root, article.directory).deleteRecursively()
    }

    fun usedBytes(): Long = root.walkBottomUp().filter { it.isFile }.sumOf { it.length() }

    companion object {
        private const val MAX_RESOURCE_BYTES = 50L * 1024 * 1024
        private const val MAX_ASSETS = 500

        /** Same-document assets a page needs to render: images, stylesheets, scripts, media, icons. */
        fun assetUrls(html: String, baseUrl: String): List<String> {
            val document = Jsoup.parse(html, baseUrl)
            val urls = LinkedHashSet<String>()
            document.select("img[src], script[src], audio[src], video[src], source[src], track[src], embed[src]")
                .forEach { urls += it.absUrl("src") }
            document.select("img[srcset], source[srcset]").forEach { element ->
                element.attr("srcset").split(',').mapNotNull { candidate ->
                    candidate.trim().substringBefore(' ').takeIf { it.isNotEmpty() }
                }.forEach { urls += resolve(baseUrl, it) }
            }
            document.select("video[poster]").forEach { urls += it.absUrl("poster") }
            document.select("link[href]").filter { link ->
                val rel = link.attr("rel").lowercase()
                "stylesheet" in rel || "icon" in rel || "preload" in rel
            }.forEach { urls += it.absUrl("href") }
            document.select("style").forEach { urls += cssUrls(it.data(), baseUrl) }
            return urls.map { it.substringBefore('#') }.filter { it.startsWith("http") }.distinct()
        }

        private val cssUrl = Regex("""url\(\s*['"]?([^'")\s]+)['"]?\s*\)""")
        private val cssImport = Regex("""@import\s+['"]([^'"]+)['"]""")

        fun cssUrls(css: String, baseUrl: String): List<String> =
            (cssUrl.findAll(css).map { it.groupValues[1] } + cssImport.findAll(css).map { it.groupValues[1] })
                .filterNot { it.startsWith("data:") }
                .map { resolve(baseUrl, it).substringBefore('#') }
                .filter { it.startsWith("http") }
                .distinct()
                .toList()

        private fun resolve(base: String, relative: String): String =
            base.toHttpUrlOrNull()?.resolve(relative)?.toString() ?: relative

        private fun charsetOf(contentType: String?): Charset =
            contentType?.substringAfter("charset=", "")?.trim()?.trim('"')?.takeIf { it.isNotEmpty() }
                ?.let { runCatching { Charset.forName(it) }.getOrNull() } ?: Charsets.UTF_8

        private fun InputStream.readCapped(max: Long): ByteArray? {
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val read = read(buffer)
                if (read < 0) break
                total += read
                if (total > max) return null
                out.write(buffer, 0, read)
            }
            return out.toByteArray()
        }
    }
}
