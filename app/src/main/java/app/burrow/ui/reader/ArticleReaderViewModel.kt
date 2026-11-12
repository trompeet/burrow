package app.burrow.ui.reader

import android.app.Application
import android.net.Uri
import android.os.Bundle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.burrow.AppContainer
import app.burrow.data.ArticleBookmarkEntity
import app.burrow.offline.OfflineStore
import app.burrow.zim.ZimArchive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

class ArticleReaderViewModel(
    private val app: AppContainer,
    private val context: Application,
    args: Bundle,
) : ViewModel() {

    sealed interface State {
        data object Loading : State
        class Ready(val source: ArticleSource) : State
        class Error(val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Loading)
    val state: StateFlow<State> = _state

    private val _hits = MutableStateFlow<List<ArticleHit>?>(null)
    val hits: StateFlow<List<ArticleHit>?> = _hits
    private var searchJob: Job? = null

    val startUrl: String? = args.getString(ArticleReaderFragment.ARG_START_URL)

    init {
        viewModelScope.launch(Dispatchers.IO) {
            _state.value = try {
                State.Ready(createSource(args))
            } catch (e: Throwable) {
                State.Error(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    private fun createSource(args: Bundle): ArticleSource {
        args.getString(ArticleReaderFragment.ARG_ZIM_URI)?.let { uri ->
            app.ensureNative()
            return ArticleSource.Local(ZimArchive.open(context, Uri.parse(uri)), uri)
        }
        val contentRoot = args.getString(ArticleReaderFragment.ARG_CONTENT_ROOT)?.toHttpUrlOrNull()
            ?: throw IllegalArgumentException("No book")
        return ArticleSource.Remote(
            contentRoot = contentRoot,
            bookTitle = args.getString(ArticleReaderFragment.ARG_TITLE).orEmpty(),
            serverBase = args.getString(ArticleReaderFragment.ARG_SERVER_BASE)?.toHttpUrlOrNull(),
            fetcher = app.remoteFetcher,
            servers = app.servers,
        )
    }

    private val source get() = (_state.value as? State.Ready)?.source

    @OptIn(ExperimentalCoroutinesApi::class)
    val bookmarks = _state.flatMapLatest { state ->
        if (state is State.Ready) app.db.articleBookmarkDao().observe(state.source.bookKey) else emptyFlow()
    }

    fun search(query: String, debounce: Boolean) {
        searchJob?.cancel()
        val source = source ?: return
        if (query.isBlank()) {
            _hits.value = null
            return
        }
        searchJob = viewModelScope.launch {
            if (debounce) delay(300)
            _hits.value = withContext(Dispatchers.IO) { runCatching { source.search(query) }.getOrDefault(emptyList()) }
        }
    }

    fun clearSearch() {
        searchJob?.cancel()
        _hits.value = null
    }

    fun toggleBookmark(url: String, title: String, bookmarked: Boolean) {
        val source = source ?: return
        val dao = app.db.articleBookmarkDao()
        viewModelScope.launch {
            if (bookmarked) {
                dao.delete(source.bookKey, url)
            } else {
                dao.insert(ArticleBookmarkEntity(bookKey = source.bookKey, url = url, title = title, createdAt = System.currentTimeMillis()))
            }
        }
    }

    fun deleteBookmark(bookmark: ArticleBookmarkEntity) {
        viewModelScope.launch { app.db.articleBookmarkDao().delete(bookmark.bookKey, bookmark.url) }
    }

    /** Saves the current remote page and its assets; [onDone] gets null on success or an error message. */
    fun saveOffline(url: String, title: String, onDone: (String?) -> Unit) {
        val remote = source as? ArticleSource.Remote ?: return
        // App scope: saving finishes even if the user closes the reader.
        app.appScope.launch {
            val result = runCatching {
                app.offline.save(
                    OfflineStore.SaveRequest(
                        pageUrl = url,
                        title = title.ifBlank { remote.bookTitle },
                        bookTitle = remote.bookTitle,
                        contentRoot = remote.homeUrl,
                        serverBaseUrl = remote.serverBase?.toString()?.trimEnd('/'),
                    ),
                    app.remoteFetcher,
                    remote::allowed,
                )
            }
            withContext(Dispatchers.Main) { onDone(result.exceptionOrNull()?.let { it.message ?: it.javaClass.simpleName }) }
        }
    }

    override fun onCleared() {
        source?.close()
    }
}
