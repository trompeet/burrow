package app.kiwixreader.net

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import okhttp3.CacheControl
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okio.blackholeSink
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * Loads remote content for the reader with this order of preference:
 *
 * 1. the offline store (articles the user saved),
 * 2. the HTTP cache, served immediately and revalidated in the background when online (stale-while-revalidate),
 * 3. the network.
 *
 * Redirects are followed here, hop by hop, so that every URL is checked against [fetch]'s `allowed` predicate
 * before any request is made. That keeps all traffic on the servers the user configured.
 */
class RemoteFetcher(
    /** Must not follow redirects itself (see [HttpStack.noRedirectClient]). */
    private val client: () -> OkHttpClient,
    private val isOnline: () -> Boolean,
    private val scope: CoroutineScope,
    private val offlineLookup: (String) -> Stored? = { null },
) {
    enum class Source { OFFLINE, CACHE, NETWORK }

    class Stored(val contentType: String?, val body: InputStream)

    class Resource(
        val url: String,
        val code: Int,
        val reason: String,
        val contentType: String?,
        val body: InputStream,
        val source: Source,
        /** Background revalidation started for a cache hit, if any (exposed for tests). */
        val revalidation: Job?,
    )

    sealed interface Outcome {
        class Loaded(val resource: Resource) : Outcome
        class Failed(val offline: Boolean, val message: String) : Outcome
        data object Blocked : Outcome
    }

    private sealed interface Hop {
        class Redirect(val location: String) : Hop
        class Done(val outcome: Outcome) : Hop
    }

    private val revalidating = ConcurrentHashMap.newKeySet<String>()

    /** Blocking; call from a background thread. */
    fun fetch(url: String, allowed: (HttpUrl) -> Boolean, maxRedirects: Int = 5): Outcome {
        var current = url
        repeat(maxRedirects + 1) {
            val httpUrl = current.toHttpUrlOrNull() ?: return Outcome.Failed(false, "Invalid URL: $current")
            if (!allowed(httpUrl)) return Outcome.Blocked
            when (val hop = fetchOnce(httpUrl)) {
                is Hop.Redirect -> current = hop.location
                is Hop.Done -> return hop.outcome
            }
        }
        return Outcome.Failed(false, "Too many redirects")
    }

    private fun fetchOnce(url: HttpUrl): Hop {
        offlineLookup(url.toString())?.let {
            return Hop.Done(Outcome.Loaded(Resource(url.toString(), 200, "OK", it.contentType, it.body, Source.OFFLINE, null)))
        }
        val http = client()
        val cached = try {
            http.newCall(request(url, CacheControl.FORCE_CACHE)).execute()
        } catch (_: IOException) {
            null
        }
        if (cached != null && cached.cacheResponse != null) {
            val revalidation = if (isOnline()) revalidate(url) else null
            return hop(url, cached, Source.CACHE, revalidation)
        }
        cached?.close()
        return try {
            hop(url, http.newCall(request(url, null)).execute(), Source.NETWORK, null)
        } catch (e: IOException) {
            Hop.Done(Outcome.Failed(offline = !isOnline(), message = e.message ?: e.javaClass.simpleName))
        }
    }

    private fun hop(url: HttpUrl, response: Response, source: Source, revalidation: Job?): Hop {
        if (response.isRedirect) {
            val location = response.header("Location")?.let { url.resolve(it) }
            response.close()
            return if (location != null) {
                Hop.Redirect(location.toString())
            } else {
                Hop.Done(Outcome.Failed(false, "Redirect without location"))
            }
        }
        val resource = Resource(
            url = url.toString(),
            code = response.code,
            reason = response.message.ifBlank { if (response.isSuccessful) "OK" else "Error" },
            contentType = response.header("Content-Type"),
            body = response.body.byteStream(),
            source = source,
            revalidation = revalidation,
        )
        return Hop.Done(Outcome.Loaded(resource))
    }

    /** Conditional GET through the cache: a 304 refreshes the stored headers, a 200 replaces the entry. */
    private fun revalidate(url: HttpUrl): Job? {
        val key = url.toString()
        if (!revalidating.add(key)) return null
        return scope.launch(Dispatchers.IO) {
            try {
                client().newCall(request(url, null)).execute().use { response ->
                    val network = response.networkResponse
                    if (network != null && network.code != 304) {
                        response.body.source().readAll(blackholeSink())
                    }
                }
            } catch (_: IOException) {
                // Offline or server gone: keep serving the cached copy.
            } finally {
                revalidating.remove(key)
            }
        }
    }

    private fun request(url: HttpUrl, cacheControl: CacheControl?) =
        Request.Builder().url(url).apply { cacheControl?.let(::cacheControl) }.build()
}
