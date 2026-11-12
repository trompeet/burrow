package app.burrow.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.settingsStore by preferencesDataStore(name = "settings")

enum class ReaderFont { SERIF, SANS }
enum class ReaderTheme { LIGHT, SEPIA, DARK }

data class ReaderPrefs(
    val fontSizeSp: Float = 18f,
    val font: ReaderFont = ReaderFont.SERIF,
    val lineSpacing: Float = 1.4f,
    val marginDp: Int = 24,
    /** null = follow the system light/dark setting until the user picks a theme. */
    val theme: ReaderTheme? = null,
)

class Settings(context: Context) {
    private val store = context.applicationContext.settingsStore

    val cacheSizeMb: Flow<Int> = store.data.map { it[CACHE_SIZE_MB] ?: DEFAULT_CACHE_MB }

    suspend fun cacheSizeMbNow(): Int = cacheSizeMb.first()

    suspend fun setCacheSizeMb(value: Int) {
        store.edit { it[CACHE_SIZE_MB] = value }
    }

    val readerPrefs: Flow<ReaderPrefs> = store.data.map { it.toReaderPrefs() }

    suspend fun updateReaderPrefs(transform: (ReaderPrefs) -> ReaderPrefs) {
        store.edit { prefs ->
            val updated = transform(prefs.toReaderPrefs())
            prefs[FONT_SIZE] = updated.fontSizeSp
            prefs[FONT] = updated.font.name
            prefs[LINE_SPACING] = updated.lineSpacing
            prefs[MARGIN] = updated.marginDp
            updated.theme?.let { prefs[THEME] = it.name }
        }
    }

    private fun Preferences.toReaderPrefs() = ReaderPrefs(
        fontSizeSp = this[FONT_SIZE] ?: 18f,
        font = this[FONT]?.let { runCatching { ReaderFont.valueOf(it) }.getOrNull() } ?: ReaderFont.SERIF,
        lineSpacing = this[LINE_SPACING] ?: 1.4f,
        marginDp = this[MARGIN] ?: 24,
        theme = this[THEME]?.let { runCatching { ReaderTheme.valueOf(it) }.getOrNull() },
    )

    companion object {
        const val DEFAULT_CACHE_MB = 500
        val CACHE_SIZE_CHOICES_MB = listOf(100, 250, 500, 1000, 2000, 5000)

        private val CACHE_SIZE_MB = intPreferencesKey("cache_size_mb")
        private val FONT_SIZE = floatPreferencesKey("reader_font_size")
        private val FONT = stringPreferencesKey("reader_font")
        private val LINE_SPACING = floatPreferencesKey("reader_line_spacing")
        private val MARGIN = intPreferencesKey("reader_margin_dp")
        private val THEME = stringPreferencesKey("reader_theme")
    }
}
