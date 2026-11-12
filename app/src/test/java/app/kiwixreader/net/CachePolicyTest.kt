package app.kiwixreader.net

import app.kiwixreader.data.ServerEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Cache
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream

class CachePolicyTest {
    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var cache: Cache
    private lateinit var client: OkHttpClient
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var online = true
    private var offlineStore: (String) -> RemoteFetcher.Stored? = { null }
    private lateinit var fetcher: RemoteFetcher

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        cache = Cache(temp.newFolder("http"), 10L * 1024 * 1024)
        client = OkHttpClient.Builder()
            .cache(cache)
            .addNetworkInterceptor(CachePolicy.networkInterceptor)
            .followRedirects(false)
            .build()
        fetcher = RemoteFetcher({ client }, { online }, scope, { offlineStore(it) })
    }

    @After
    fun tearDown() {
        scope.cancel()
        server.close()
        cache.close()
    }

    // ---- Header rewriting ---------------------------------------------------------------------------------

    @Test
    fun `keeps freshness lifetime but drops directives that forbid stale use`() {
        assertEquals("max-age=3600", CachePolicy.rewriteCacheControl("max-age=3600, must-revalidate"))
        assertEquals("max-age=31536000, immutable", CachePolicy.rewriteCacheControl("max-age=31536000, immutable"))
        assertEquals("max-age=60, immutable", CachePolicy.rewriteCacheControl("private, max-age=60, immutable"))
    }

    @Test
    fun `responses without a lifetime become immediately stale`() {
        assertEquals("max-age=0", CachePolicy.rewriteCacheControl(null))
        assertEquals("max-age=0", CachePolicy.rewriteCacheControl(""))
        assertEquals("max-age=0", CachePolicy.rewriteCacheControl("No-Store, no-cache"))
        assertEquals("public, max-age=0", CachePolicy.rewriteCacheControl("public, proxy-revalidate"))
    }

    // ---- Stale-while-revalidate ---------------------------------------------------------------------------

    @Test
    fun `cached page is shown immediately and revalidated in the background`() = runBlocking<Unit> {
        server.enqueue(page("v1", etag = "\"1\""))
        val url = server.url("/content/book/A/Page").toString()

        val first = load(url)
        assertEquals(RemoteFetcher.Source.NETWORK, first.source)
        assertEquals("v1", first.text)

        server.enqueue(page("v2", etag = "\"2\""))
        val second = load(url)
        assertEquals(RemoteFetcher.Source.CACHE, second.source)
        assertEquals("v1", second.text) // served from cache without waiting for the server
        assertNotNull(second.revalidation)
        second.revalidation!!.join()

        server.takeRequest()
        val revalidation = server.takeRequest()
        assertEquals("\"1\"", revalidation.headers["If-None-Match"])

        server.enqueue(MockResponse.Builder().code(304).build())
        val third = load(url)
        assertEquals(RemoteFetcher.Source.CACHE, third.source)
        assertEquals("v2", third.text) // the background revalidation replaced the entry
        third.revalidation?.join()
    }

    @Test
    fun `stale must-revalidate page is still served when offline`() {
        server.enqueue(page("article", etag = "\"a\""))
        val url = server.url("/content/book/A/Offline").toString()
        load(url)

        online = false
        server.close()
        val cached = load(url)
        assertEquals(RemoteFetcher.Source.CACHE, cached.source)
        assertEquals("article", cached.text)
        assertNull(cached.revalidation) // no revalidation attempt while offline
    }

    @Test
    fun `missing page while offline reports offline`() {
        online = false
        val url = server.url("/content/book/A/Never").toString()
        server.close()
        val outcome = fetcher.fetch(url, { true })
        assertTrue(outcome is RemoteFetcher.Outcome.Failed)
        assertTrue((outcome as RemoteFetcher.Outcome.Failed).offline)
    }

    @Test
    fun `offline store is preferred over cache and network`() {
        val url = server.url("/content/book/A/Saved").toString()
        offlineStore = { if (it == url) RemoteFetcher.Stored("text/html", ByteArrayInputStream("saved".toByteArray())) else null }
        val resource = load(url)
        assertEquals(RemoteFetcher.Source.OFFLINE, resource.source)
        assertEquals("saved", resource.text)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `redirects are followed hop by hop and other hosts are never contacted`() {
        val start = server.url("/content/book")
        server.enqueue(MockResponse.Builder().code(302).addHeader("Location", "/content/book/A/Main").build())
        server.enqueue(page("main", etag = "\"m\""))
        val allowed = { url: okhttp3.HttpUrl -> sameOrigin(url, start) }

        val outcome = fetcher.fetch(start.toString(), allowed) as RemoteFetcher.Outcome.Loaded
        assertEquals(server.url("/content/book/A/Main").toString(), outcome.resource.url)

        server.enqueue(MockResponse.Builder().code(302).addHeader("Location", "http://tracker.invalid/pixel").build())
        val blocked = fetcher.fetch(server.url("/content/book/A/Out").toString(), allowed)
        assertEquals(RemoteFetcher.Outcome.Blocked, blocked)
    }

    // ---- Size limit (LRU) ---------------------------------------------------------------------------------

    @Test
    fun `lowering the limit trims the cache`() {
        val stack = HttpStack(temp.newFolder("stack"), { 10L * 1024 * 1024 }, { emptyList() })
        val body = "x".repeat(100 * 1024)
        repeat(5) { i ->
            server.enqueue(page(body, etag = "\"$i\""))
            stack.client.newCall(Request.Builder().url(server.url("/big/$i")).build()).execute().use { it.body.string() }
        }
        assertTrue(stack.cache.size() > 400 * 1024)

        stack.setCacheSize(250L * 1024)
        assertEquals(250L * 1024, stack.cache.maxSize())
        assertTrue("size ${stack.cache.size()}", stack.cache.size() <= 250L * 1024)
        stack.cache.close()
    }

    // ---- Basic auth ---------------------------------------------------------------------------------------

    @Test
    fun `basic auth is only sent to the configured server`() {
        val other = MockWebServer().apply { start() }
        val configured = ServerEntity(id = 1, baseUrl = server.url("/").toString().trimEnd('/'), name = "", username = "alice", password = "secret")
        val authClient = OkHttpClient.Builder().addInterceptor(BasicAuthInterceptor { listOf(configured) }).build()
        server.enqueue(page("ok", etag = "\"x\""))
        other.enqueue(page("ok", etag = "\"y\""))

        authClient.newCall(Request.Builder().url(server.url("/catalog/v2/entries")).build()).execute().close()
        authClient.newCall(Request.Builder().url(other.url("/catalog/v2/entries")).build()).execute().close()

        assertEquals("Basic YWxpY2U6c2VjcmV0", server.takeRequest().headers["Authorization"])
        assertNull(other.takeRequest().headers["Authorization"])
        other.close()
    }

    // ---- Helpers ------------------------------------------------------------------------------------------

    private class Loaded(val text: String, val source: RemoteFetcher.Source, val revalidation: kotlinx.coroutines.Job?)

    private fun load(url: String): Loaded {
        val outcome = fetcher.fetch(url, { true })
        assertTrue("unexpected $outcome", outcome is RemoteFetcher.Outcome.Loaded)
        val resource = (outcome as RemoteFetcher.Outcome.Loaded).resource
        val text = resource.body.use { String(it.readBytes()) }
        return Loaded(text, resource.source, resource.revalidation)
    }

    /** kiwix-serve style headers (must-revalidate + ETag); max-age=0 so every cache hit is stale. */
    private fun page(body: String, etag: String) = MockResponse.Builder()
        .code(200)
        .addHeader("Content-Type", "text/html; charset=utf-8")
        .addHeader("Cache-Control", "max-age=0, must-revalidate")
        .addHeader("ETag", etag)
        .body(body)
        .build()
}
