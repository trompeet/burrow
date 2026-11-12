package app.burrow.remote

import android.util.Xml
import app.burrow.data.ServerDao
import app.burrow.data.ServerEntity
import app.burrow.net.HttpStack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.CacheControl
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.Response
import org.xmlpull.v1.XmlPullParserException
import java.io.IOException
import java.util.concurrent.TimeUnit

sealed interface ServerStatus {
    data object Checking : ServerStatus
    data class Online(val bookCount: Int?) : ServerStatus
    data object AuthRequired : ServerStatus
    data class HttpError(val code: Int) : ServerStatus
    data object NotKiwix : ServerStatus
    data class Unreachable(val message: String) : ServerStatus
}

class RemoteException(message: String) : Exception(message)

class ServerRepository(private val dao: ServerDao, private val http: HttpStack) {
    val servers = dao.observeAll()

    suspend fun get(id: Long) = dao.get(id)

    suspend fun save(server: ServerEntity) = dao.upsert(server)

    suspend fun delete(server: ServerEntity) = dao.delete(server)

    /** Network-only probe of the OPDS catalog. Never throws. */
    suspend fun checkStatus(server: ServerEntity): ServerStatus = withContext(Dispatchers.IO) {
        val base = server.baseUrl.toHttpUrlOrNull() ?: return@withContext ServerStatus.Unreachable("Invalid URL")
        val request = Request.Builder()
            .url(KiwixServe.entriesUrl(base, start = 0, count = 1, query = null))
            .cacheControl(CacheControl.FORCE_NETWORK)
            .build()
        try {
            http.client.newBuilder().callTimeout(8, TimeUnit.SECONDS).build().newCall(request).execute().use { response ->
                when {
                    response.code == 401 || response.code == 403 -> ServerStatus.AuthRequired
                    !response.isSuccessful -> ServerStatus.HttpError(response.code)
                    else -> ServerStatus.Online(parseFeed(response).totalResults)
                }
            }
        } catch (_: XmlPullParserException) {
            ServerStatus.NotKiwix
        } catch (e: IOException) {
            ServerStatus.Unreachable(e.message ?: e.javaClass.simpleName)
        } catch (e: RuntimeException) {
            ServerStatus.Unreachable(e.message ?: e.javaClass.simpleName)
        }
    }

    /** One page of the server's catalog. Falls back to the cached copy when the server is unreachable. */
    suspend fun catalog(server: ServerEntity, start: Int, count: Int, query: String?): OpdsFeed =
        withContext(Dispatchers.IO) {
            val base = server.baseUrl.toHttpUrlOrNull() ?: throw RemoteException("Invalid URL")
            val url = KiwixServe.entriesUrl(base, start, count, query)
            networkThenCache(url) { parseFeed(it) }
        }

    suspend fun search(contentRoot: HttpUrl, pattern: String): SearchResults = withContext(Dispatchers.IO) {
        val url = KiwixServe.searchUrl(contentRoot, pattern) ?: throw RemoteException("Search not available")
        networkThenCache(url) { response ->
            SearchResultsParser.parse(response.body.byteStream(), response.request.url, Xml.newPullParser())
        }
    }

    private fun <T> networkThenCache(url: HttpUrl, parse: (Response) -> T): T {
        val request = Request.Builder().url(url).build()
        val response = try {
            http.client.newCall(request).execute()
        } catch (e: IOException) {
            val cached = try {
                http.client.newCall(request.newBuilder().cacheControl(CacheControl.FORCE_CACHE).build()).execute()
            } catch (_: IOException) {
                null
            }
            if (cached?.cacheResponse == null) {
                cached?.close()
                throw RemoteException(e.message ?: "Server unreachable")
            }
            cached
        }
        return response.use {
            when {
                it.code == 401 || it.code == 403 -> throw RemoteException("Wrong or missing username/password")
                !it.isSuccessful -> throw RemoteException("HTTP ${it.code}")
                else -> try {
                    parse(it)
                } catch (e: XmlPullParserException) {
                    throw RemoteException("Unexpected response: not a kiwix-serve catalog")
                }
            }
        }
    }

    private fun parseFeed(response: Response) =
        OpdsParser.parse(response.body.byteStream(), response.request.url, Xml.newPullParser())
}
