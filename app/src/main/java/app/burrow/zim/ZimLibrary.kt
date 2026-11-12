package app.burrow.zim

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import app.burrow.data.ZimDao
import app.burrow.data.ZimFileEntity
import app.burrow.data.ZimFolderEntity
import app.burrow.offline.ZimDownloads
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.kiwix.libzim.Archive
import java.io.File

/** Local ZIM library: SAF folders the user picked, scanned recursively for `.zim` files. */
class ZimLibrary(
    private val context: Context,
    private val dao: ZimDao,
    private val ensureNative: () -> Unit,
) {
    val folders = dao.observeFolders()
    val files = dao.observeFiles()

    private val scanMutex = Mutex()
    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning

    suspend fun addFolder(treeUri: Uri) {
        context.contentResolver.takePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val folder = ZimFolderEntity(treeUri.toString(), folderName(treeUri), System.currentTimeMillis())
        dao.insertFolder(folder)
        scan(listOf(folder))
    }

    suspend fun removeFolder(folder: ZimFolderEntity) {
        dao.deleteFilesIn(folder.treeUri)
        dao.deleteFolder(folder.treeUri)
        runCatching {
            context.contentResolver.releasePersistableUriPermission(
                Uri.parse(folder.treeUri),
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
    }

    suspend fun rescanAll() = scan(dao.folders())

    /** Registers a ZIM downloaded into app storage. */
    suspend fun addDownloaded(file: File) = withContext(Dispatchers.IO) {
        readMetadata(Uri.fromFile(file), folderUri = null, lastModified = file.lastModified())?.let { dao.upsertFile(it) }
    }

    suspend fun deleteDownloaded(entity: ZimFileEntity) = withContext(Dispatchers.IO) {
        Uri.parse(entity.uri).path?.let { File(it).delete() }
        dao.deleteFile(entity.uri)
    }

    private suspend fun scan(folders: List<ZimFolderEntity>) = scanMutex.withLock {
        _scanning.value = true
        try {
            withContext(Dispatchers.IO) { folders.forEach { scanFolder(it) } }
        } finally {
            _scanning.value = false
        }
    }

    private suspend fun scanFolder(folder: ZimFolderEntity) {
        val treeUri = Uri.parse(folder.treeUri)
        val found = ArrayList<Document>()
        try {
            walk(treeUri, DocumentsContract.getTreeDocumentId(treeUri), depth = 0, out = found)
        } catch (e: Exception) {
            // Permission revoked or provider gone: keep the folder, report nothing new.
            Log.w(TAG, "Cannot list ${folder.treeUri}", e)
            return
        }
        val known = dao.filesIn(folder.treeUri).associateBy { it.uri }
        for (doc in found) {
            val existing = known[doc.uri.toString()]
            // Unreadable files are retried on every scan: access may have been granted since.
            val unchanged = existing != null && existing.readable &&
                existing.lastModified == doc.lastModified && existing.sizeBytes == doc.size
            if (unchanged) continue
            val entity = try {
                readMetadata(doc.uri, folder.treeUri, doc.lastModified)
            } catch (_: NeedsFileAccessException) {
                placeholder(doc, folder.treeUri)
            }
            entity?.let { dao.upsertFile(it) }
        }
        val foundUris = found.map { it.uri.toString() }.toSet()
        known.keys.filter { it !in foundUris }.forEach { dao.deleteFile(it) }
    }

    private class Document(val uri: Uri, val name: String, val size: Long, val lastModified: Long)

    private fun placeholder(doc: Document, folderUri: String) = ZimFileEntity(
        uri = doc.uri.toString(),
        folderUri = folderUri,
        title = doc.name.removeSuffix(".zim").removeSuffix(".ZIM"),
        language = "",
        articleCount = 0,
        sizeBytes = doc.size,
        zimId = "",
        lastModified = doc.lastModified,
        icon = null,
        readable = false,
    )

    /**
     * Copies a ZIM from shared storage into app storage, where libkiwix can read it, and adds the copy to the
     * library. The original stays where it is.
     */
    suspend fun copyToAppStorage(entity: ZimFileEntity): Unit = withContext(Dispatchers.IO) {
        _scanning.value = true
        try {
            val name = entity.title.replace(Regex("[^A-Za-z0-9._-]"), "_") + ".zim"
            val target = File(ZimDownloads.directory(context), name)
            val part = File(target.path + ".copy")
            context.contentResolver.openInputStream(Uri.parse(entity.uri))?.use { input ->
                part.outputStream().use { input.copyTo(it, 1 shl 20) }
            } ?: throw IllegalStateException("Cannot read ${entity.uri}")
            if (!part.renameTo(target)) throw IllegalStateException("Cannot write $target")
            addDownloaded(target)
        } finally {
            _scanning.value = false
        }
    }

    private fun walk(treeUri: Uri, documentId: String, depth: Int, out: MutableList<Document>) {
        if (depth > MAX_DEPTH) return
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, documentId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
        context.contentResolver.query(children, projection, null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getString(0) ?: continue
                val name = cursor.getString(1).orEmpty()
                val mime = cursor.getString(2).orEmpty()
                if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                    walk(treeUri, id, depth + 1, out)
                } else if (name.endsWith(".zim", ignoreCase = true)) {
                    val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, id)
                    out += Document(uri, name, cursor.getLong(3), cursor.getLong(4))
                }
            }
        }
    }

    private fun readMetadata(uri: Uri, folderUri: String?, lastModified: Long): ZimFileEntity? = try {
        ensureNative()
        ZimArchive.open(context, uri).use { zim ->
            zim.metadataEntity(uri, folderUri, lastModified)
        }
    } catch (e: NeedsFileAccessException) {
        throw e
    } catch (e: Throwable) {
        Log.w(TAG, "Not a readable ZIM: $uri", e)
        null
    }

    private fun folderName(treeUri: Uri): String {
        val docId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull().orEmpty()
        // e.g. "primary:Download/zim" -> "Download/zim"
        return docId.substringAfter(':').ifEmpty { docId.ifEmpty { treeUri.toString() } }
    }

    companion object {
        private const val TAG = "ZimLibrary"
        private const val MAX_DEPTH = 8
    }
}

private fun ZimArchive.metadataEntity(uri: Uri, folderUri: String?, lastModified: Long): ZimFileEntity? =
    withArchive { archive -> archive.toEntity(uri, folderUri, lastModified) }

private fun Archive.toEntity(uri: Uri, folderUri: String?, lastModified: Long) = ZimFileEntity(
    uri = uri.toString(),
    folderUri = folderUri,
    title = metadata("Title") ?: filename.substringAfterLast('/').removeSuffix(".zim"),
    language = metadata("Language").orEmpty(),
    articleCount = articleCount,
    sizeBytes = filesize,
    zimId = uuid,
    lastModified = lastModified,
    icon = runCatching { getIllustrationItem(48).data.data }.getOrNull(),
)
