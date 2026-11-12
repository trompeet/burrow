package app.kiwixreader.net

import okhttp3.Interceptor

/**
 * Cache rules for content fetched from kiwix-serve.
 *
 * kiwix-serve marks ZIM content `max-age=3600, must-revalidate` and catalog pages `max-age=0, must-revalidate`.
 * `must-revalidate` (and `no-cache`/`no-store`/`private`) would stop OkHttp from ever serving a stale copy, which
 * defeats offline reading. Before a response is stored we therefore keep its freshness lifetime but drop the
 * directives that forbid stale use. Fresh entries are served directly, stale ones are revalidated in the
 * background when online (see [RemoteFetcher]) and still served when offline.
 */
object CachePolicy {
    private val forbidStale = setOf("no-cache", "no-store", "must-revalidate", "proxy-revalidate", "private")

    fun rewriteCacheControl(value: String?): String {
        val kept = value.orEmpty()
            .split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() && directiveName(it) !in forbidStale }
        val hasMaxAge = kept.any { directiveName(it) == "max-age" }
        return (if (hasMaxAge) kept else kept + "max-age=0").joinToString(", ")
    }

    private fun directiveName(directive: String) = directive.substringBefore('=').trim().lowercase()

    /** Network interceptor: rewrites headers of responses before OkHttp's cache sees them. */
    val networkInterceptor = Interceptor { chain ->
        val response = chain.proceed(chain.request())
        if (chain.request().method != "GET") {
            response
        } else {
            response.newBuilder()
                .header("Cache-Control", rewriteCacheControl(response.header("Cache-Control")))
                .removeHeader("Pragma")
                .build()
        }
    }
}
