package app.burrow.ui.books

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.burrow.AppContainer
import app.burrow.books.BookContent
import app.burrow.data.BookBookmarkEntity
import app.burrow.data.BookEntity
import app.burrow.data.ReaderPrefs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class BookReaderViewModel(private val app: AppContainer, private val bookId: Long) : ViewModel() {
    sealed interface State {
        data object Loading : State
        class Ready(val book: BookEntity, val content: BookContent) : State
        class Error(val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Loading)
    val state: StateFlow<State> = _state

    val prefs = app.settings.readerPrefs
    val bookmarks = app.books.bookmarks(bookId)

    /** Current position: section index and character offset of the first character on screen. */
    var section = 0
        private set
    var offset = 0
        private set

    init {
        viewModelScope.launch {
            _state.value = try {
                val book = app.books.get(bookId) ?: throw IllegalStateException("Book not found")
                val content = app.books.load(book)
                section = book.section.coerceIn(0, content.sections.lastIndex)
                offset = book.sectionOffset.coerceIn(0, content.sections[section].length)
                State.Ready(book, content)
            } catch (e: Exception) {
                State.Error(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    /** Persists the position right away so it survives the app being killed. */
    fun setPosition(section: Int, offset: Int) {
        val ready = _state.value as? State.Ready ?: return
        if (section == this.section && offset == this.offset) return
        this.section = section
        this.offset = offset
        val progress = ready.content.progress(section, offset)
        app.appScope.launch { app.books.savePosition(bookId, section, offset, progress) }
    }

    /** Marks the book as opened (also when the position did not change). */
    fun touch() {
        val ready = _state.value as? State.Ready ?: return
        val (s, o) = section to offset
        app.appScope.launch { app.books.savePosition(bookId, s, o, ready.content.progress(s, o)) }
    }

    fun addBookmark(label: String) {
        val ready = _state.value as? State.Ready ?: return
        val bookmark = BookBookmarkEntity(
            bookId = bookId,
            section = section,
            sectionOffset = offset,
            progress = ready.content.progress(section, offset),
            label = label,
            createdAt = System.currentTimeMillis(),
        )
        viewModelScope.launch { app.books.addBookmark(bookmark) }
    }

    fun deleteBookmark(id: Long) {
        viewModelScope.launch { app.books.deleteBookmark(id) }
    }

    fun updatePrefs(transform: (ReaderPrefs) -> ReaderPrefs) {
        app.appScope.launch { app.settings.updateReaderPrefs(transform) }
    }
}
