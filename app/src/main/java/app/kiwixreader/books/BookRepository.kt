package app.kiwixreader.books

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import app.kiwixreader.data.BookBookmarkEntity
import app.kiwixreader.data.BookDao
import app.kiwixreader.data.BookEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import java.util.zip.ZipFile

class UnsupportedBookException(name: String) : Exception("Unsupported file: $name")

/** Books tab storage: imported files are copied into app storage so they stay readable. */
class BookRepository(private val context: Context, private val dao: BookDao) {
    val books = dao.observeAll()
    private val directory = File(context.filesDir, "books")

    suspend fun get(id: Long) = dao.get(id)

    suspend fun import(uri: Uri): BookEntity = withContext(Dispatchers.IO) {
        val name = displayName(uri) ?: uri.lastPathSegment ?: "book"
        directory.mkdirs()
        val id = UUID.randomUUID().toString()
        val temp = File(directory, "$id.tmp")
        try {
            context.contentResolver.openInputStream(uri)?.use { input -> temp.outputStream().use { input.copyTo(it) } }
                ?: throw UnsupportedBookException(name)
            val format = detectFormat(name, temp) ?: throw UnsupportedBookException(name)
            val file = File(directory, "$id.${format.extension}")
            if (!temp.renameTo(file)) throw IllegalStateException("Cannot store $name")
            val metadata = runCatching { BookParser.metadata(file, format, name.substringBeforeLast('.')) }
                .getOrElse { file.delete(); throw UnsupportedBookException(name) }
            val book = BookEntity(
                filePath = file.absolutePath,
                format = format.name,
                title = metadata.title,
                author = metadata.author,
                sizeBytes = file.length(),
                importedAt = System.currentTimeMillis(),
            )
            book.copy(id = dao.insert(book))
        } finally {
            temp.delete()
        }
    }

    suspend fun delete(book: BookEntity) = withContext(Dispatchers.IO) {
        File(book.filePath).delete()
        dao.delete(book)
    }

    suspend fun load(book: BookEntity): BookContent = withContext(Dispatchers.Default) {
        BookParser.load(File(book.filePath), BookFormat.valueOf(book.format))
    }

    suspend fun savePosition(bookId: Long, section: Int, offset: Int, progress: Float) =
        dao.savePosition(bookId, section, offset, progress, System.currentTimeMillis())

    fun bookmarks(bookId: Long) = dao.observeBookmarks(bookId)

    suspend fun addBookmark(bookmark: BookBookmarkEntity) = dao.insertBookmark(bookmark)

    suspend fun deleteBookmark(id: Long) = dao.deleteBookmark(id)

    private fun displayName(uri: Uri): String? =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }

    private fun detectFormat(name: String, file: File): BookFormat? {
        val extension = name.substringAfterLast('.', "").lowercase()
        val head = file.inputStream().use { input -> ByteArray(512).let { it.copyOf(input.read(it).coerceAtLeast(0)) } }
        val isZip = head.size >= 4 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte()
        if (isZip) {
            val isEpub = runCatching { ZipFile(file).use { it.getEntry("META-INF/container.xml") != null } }.getOrDefault(false)
            return if (isEpub) BookFormat.EPUB else null
        }
        val start = String(head, Charsets.ISO_8859_1).trimStart('﻿', ' ', '\n', '\r', '\t').lowercase()
        return when {
            extension in setOf("html", "htm", "xhtml") -> BookFormat.HTML
            start.startsWith("<!doctype html") || start.startsWith("<html") || start.startsWith("<?xml") -> BookFormat.HTML
            extension == "txt" || head.none { it == 0.toByte() } -> BookFormat.TXT
            else -> null
        }
    }
}
