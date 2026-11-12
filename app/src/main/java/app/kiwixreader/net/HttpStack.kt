package app.kiwixreader.net

import app.kiwixreader.data.ServerEntity
import okhttp3.Cache
import okhttp3.CookieJar
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Owns the shared OkHttp client and its disk cache (LRU eviction by [Cache]'s DiskLruCache).
 * The cache size is fixed per [Cache] instance, so changing the limit swaps in a new client.
 */
class HttpStack(
    private val cacheDir: File,
    private val initialCacheBytes: () -> Long,
    private val serversNow: () -> List<ServerEntity>,
    private val cookieJar: CookieJar = CookieJar.NO_COOKIES,
) {
    private class Clients(val cache: Cache, val client: OkHttpClient, val noRedirects: OkHttpClient)

    @Volatile
    private var current: Clients? = null

    private fun clients(): Clients =
        current ?: synchronized(this) { current ?: build(initialCacheBytes()).also { current = it } }

    /** Shared client: disk cache, basic auth for configured servers, follows redirects. */
    val client: OkHttpClient get() = clients().client

    /** Same client without automatic redirects, so callers can vet every hop (used for WebView content). */
    val noRedirectClient: OkHttpClient get() = clients().noRedirects

    val cache: Cache get() = clients().cache

    fun setCacheSize(bytes: Long) = synchronized(this) {
        val old = current ?: return@synchronized
        if (old.cache.maxSize() == bytes) return@synchronized
        old.cache.close()
        current = build(bytes).also {
            // flush() trims the cache down to the new limit right away.
            it.cache.initialize()
            it.cache.flush()
        }
    }

    private fun build(cacheBytes: Long): Clients {
        val cache = Cache(cacheDir, cacheBytes)
        val client = OkHttpClient.Builder()
            .cache(cache)
            .cookieJar(cookieJar)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .addInterceptor(BasicAuthInterceptor(serversNow))
            .addNetworkInterceptor(CachePolicy.networkInterceptor)
            .build()
        val noRedirects = client.newBuilder().followRedirects(false).followSslRedirects(false).build()
        return Clients(cache, client, noRedirects)
    }
}

/** Adds HTTP basic auth to requests aimed at a configured server that has credentials. */
class BasicAuthInterceptor(private val serversNow: () -> List<ServerEntity>) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): okhttp3.Response {
        val request = chain.request()
        if (request.header("Authorization") != null) return chain.proceed(request)
        val server = serversNow().firstOrNull { !it.username.isNullOrEmpty() && it.covers(request.url) }
            ?: return chain.proceed(request)
        val credential = Credentials.basic(server.username.orEmpty(), server.password.orEmpty())
        return chain.proceed(request.newBuilder().header("Authorization", credential).build())
    }
}

/** True when [url] lives under this server's base URL (same scheme, host, port and path prefix). */
fun ServerEntity.covers(url: HttpUrl): Boolean {
    val base = baseUrl.toHttpUrlOrNull() ?: return false
    return sameOrigin(base, url) && url.encodedPath.startsWith(base.encodedPath.trimEnd('/'))
}

fun sameOrigin(a: HttpUrl, b: HttpUrl) = a.scheme == b.scheme && a.host == b.host && a.port == b.port
