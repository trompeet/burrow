package app.kiwixreader

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import androidx.fragment.app.Fragment
import app.kiwixreader.books.BookRepository
import app.kiwixreader.data.AppDatabase
import app.kiwixreader.data.ServerEntity
import app.kiwixreader.data.Settings
import app.kiwixreader.net.HttpStack
import app.kiwixreader.net.RemoteFetcher
import app.kiwixreader.net.WebViewCookieJar
import app.kiwixreader.offline.OfflineStore
import app.kiwixreader.remote.ServerRepository
import app.kiwixreader.zim.ZimLibrary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.kiwix.libkiwix.JNIKiwix
import java.io.File

class KiwixReaderApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

val Context.container: AppContainer get() = (applicationContext as KiwixReaderApp).container
val Fragment.container: AppContainer get() = requireContext().container

/** Manual dependency wiring; everything here lives as long as the process. */
class AppContainer(private val app: Application) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val db = AppDatabase.build(app)
    val settings = Settings(app)

    @Volatile
    private var serverSnapshot: List<ServerEntity>? = null

    /** Called from OkHttp threads; the first call may load the list synchronously. */
    private fun serversNow(): List<ServerEntity> =
        serverSnapshot ?: runBlocking { db.serverDao().all() }.also { serverSnapshot = it }

    val http = HttpStack(
        cacheDir = File(app.cacheDir, "http"),
        initialCacheBytes = { runBlocking { settings.cacheSizeMbNow() } * MB },
        serversNow = ::serversNow,
        cookieJar = WebViewCookieJar(),
    )

    val servers = ServerRepository(db.serverDao(), http)
    val offline = OfflineStore(File(app.filesDir, "offline"), db.offlineDao())
    val zimLibrary = ZimLibrary(app, db.zimDao(), ::ensureNative)
    val books = BookRepository(app, db.bookDao())

    /** Fetcher for remote reader pages: offline store, then HTTP cache (stale-while-revalidate), then network. */
    val remoteFetcher = RemoteFetcher(
        client = { http.noRedirectClient },
        isOnline = ::isOnline,
        scope = appScope,
        offlineLookup = offline::lookup,
    )

    private val native by lazy {
        JNIKiwix(app).also { jni -> icuDirectory()?.let(jni::setDataDirectory) }
    }

    /** Loads libkiwix/libzim native code on first use (call off the main thread). */
    fun ensureNative() {
        native
    }

    init {
        appScope.launch { db.serverDao().observeAll().collect { serverSnapshot = it } }
        appScope.launch { settings.cacheSizeMb.drop(1).collect { http.setCacheSize(it * MB) } }
    }

    fun isOnline(): Boolean {
        val manager = app.getSystemService(ConnectivityManager::class.java) ?: return false
        val capabilities = manager.getNetworkCapabilities(manager.activeNetwork) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /** libkiwix needs ICU data for full-text search tokenisation; kiwix-android ships it the same way. */
    private fun icuDirectory(): String? = try {
        val directory = File(app.filesDir, "icu").apply { mkdirs() }
        app.assets.list("icu").orEmpty().forEach { name ->
            val target = File(directory, name)
            if (!target.exists()) app.assets.open("icu/$name").use { input -> target.outputStream().use { input.copyTo(it) } }
        }
        directory.absolutePath
    } catch (e: Exception) {
        Log.w("KiwixReader", "Cannot install ICU data", e)
        null
    }

    companion object {
        private const val MB = 1_000_000L // decimal, matching how Android formats sizes
    }
}
