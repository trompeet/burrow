package app.burrow.zim

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import org.kiwix.libzim.Archive
import org.kiwix.libzim.Query
import org.kiwix.libzim.Searcher
import org.kiwix.libzim.SuggestionSearcher
import java.io.Closeable
import java.io.File
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

class ZimItem(val path: String, val mimeType: String, val data: ByteArray)

class ZimHit(val title: String, val path: String, val snippet: String)

/**
 * An open ZIM archive. WebView request threads, search and [close] may race, so every native call goes through
 * a read lock and [close] takes the write lock; calls after [close] return null instead of touching freed memory.
 */
class ZimArchive private constructor(
    private val archive: Archive,
    private val descriptor: ParcelFileDescriptor?,
) : Closeable {
    private val lock = ReentrantReadWriteLock()
    private var closed = false

    internal fun <T> withArchive(block: (Archive) -> T): T? = lock.read {
        if (closed) null else runCatching { block(archive) }.getOrNull()
    }

    val title: String get() = withArchive { it.metadata("Title") ?: it.filename } ?: ""

    /** Path of the main page after following redirects. */
    fun mainPagePath(): String? = withArchive { if (it.hasMainEntry()) it.mainEntry.getItem(true).path else null }

    /** If [path] is a redirect entry, the path it points to; otherwise null. */
    fun redirectTarget(path: String): String? = withArchive {
        val entry = it.getEntryByPath(path)
        if (entry.isRedirect) entry.getItem(true).path else null
    }

    fun item(path: String): ZimItem? = withArchive {
        val item = it.getEntryByPath(path).getItem(true)
        ZimItem(item.path, item.mimetype, item.data.data)
    }

    /** Full-text search when the ZIM has an index, title suggestions otherwise. */
    fun search(query: String, limit: Int = 50): List<ZimHit> = withArchive { archive ->
        if (archive.hasFulltextIndex()) fullText(archive, query, limit) else suggestions(archive, query, limit)
    }.orEmpty()

    private fun fullText(archive: Archive, query: String, limit: Int): List<ZimHit> {
        val searcher = Searcher(archive)
        val search = searcher.search(Query(query))
        val iterator = search.getResults(0, limit)
        val hits = ArrayList<ZimHit>()
        try {
            while (iterator.hasNext()) {
                // The getters describe the element next() is about to return.
                hits += ZimHit(iterator.title, iterator.path, iterator.snippet.orEmpty())
                iterator.next()
            }
        } finally {
            iterator.dispose()
            search.dispose()
            searcher.dispose()
        }
        return hits
    }

    private fun suggestions(archive: Archive, query: String, limit: Int): List<ZimHit> {
        val searcher = SuggestionSearcher(archive)
        val search = searcher.suggest(query)
        val iterator = search.getResults(0, limit)
        val hits = ArrayList<ZimHit>()
        try {
            while (iterator.hasNext()) {
                val item = iterator.next()
                hits += ZimHit(item.title, item.path, if (item.hasSnippet()) item.snippet else "")
            }
        } finally {
            iterator.dispose()
            search.dispose()
            searcher.dispose()
        }
        return hits
    }

    override fun close() = lock.write {
        if (!closed) {
            closed = true
            runCatching { archive.dispose() }
            runCatching { descriptor?.close() }
        }
    }

    companion object {
        /** Opens a `content://` (SAF) or `file://` ZIM. Blocking; throws if the file cannot be read. */
        fun open(context: Context, uri: Uri): ZimArchive {
            if (uri.scheme == "file") {
                return ZimArchive(Archive(File(requireNotNull(uri.path)).canonicalPath), null)
            }
            val descriptor = context.contentResolver.openFileDescriptor(uri, "r")
                ?: throw IllegalStateException("Cannot open $uri")
            try {
                return ZimArchive(Archive(descriptor.fileDescriptor), descriptor)
            } catch (_: Exception) {
                // libzim reopens "/dev/fd/<n>"; Android refuses that for shared-storage files granted only through
                // SAF. With all-files access the file can be opened by its real path instead.
                descriptor.close()
            }
            val path = realPath(uri)?.takeIf { it.canRead() } ?: throw NeedsFileAccessException()
            return ZimArchive(Archive(path.canonicalPath), null)
        }

        /** Real path of an external-storage document (`primary:Download/x.zim` -> /storage/emulated/0/Download/x.zim). */
        fun realPath(uri: Uri): File? {
            if (uri.authority != "com.android.externalstorage.documents") return null
            val documentId = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull() ?: return null
            val volume = documentId.substringBefore(':')
            val relative = documentId.substringAfter(':', "")
            val root = if (volume.equals("primary", ignoreCase = true)) Environment.getExternalStorageDirectory() else File("/storage/$volume")
            return File(root, relative)
        }
    }
}

/** The ZIM is in shared storage and the app may not read it directly (see [ZimArchive.open]). */
class NeedsFileAccessException : Exception("This file needs all-files access or a copy in app storage")

internal fun Archive.metadata(key: String): String? = runCatching { getMetadata(key) }.getOrNull()?.takeIf { it.isNotBlank() }
