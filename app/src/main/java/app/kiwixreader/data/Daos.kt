package app.kiwixreader.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface ZimDao {
    @Query("SELECT * FROM zim_folders ORDER BY displayName")
    fun observeFolders(): Flow<List<ZimFolderEntity>>

    @Query("SELECT * FROM zim_folders")
    suspend fun folders(): List<ZimFolderEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFolder(folder: ZimFolderEntity)

    @Query("DELETE FROM zim_folders WHERE treeUri = :treeUri")
    suspend fun deleteFolder(treeUri: String)

    @Query("SELECT * FROM zim_files ORDER BY title COLLATE NOCASE")
    fun observeFiles(): Flow<List<ZimFileEntity>>

    @Query("SELECT * FROM zim_files WHERE folderUri = :folderUri")
    suspend fun filesIn(folderUri: String): List<ZimFileEntity>

    @Upsert
    suspend fun upsertFile(file: ZimFileEntity)

    @Query("DELETE FROM zim_files WHERE uri = :uri")
    suspend fun deleteFile(uri: String)

    @Query("DELETE FROM zim_files WHERE folderUri = :folderUri")
    suspend fun deleteFilesIn(folderUri: String)
}

@Dao
interface ServerDao {
    @Query("SELECT * FROM servers ORDER BY id")
    fun observeAll(): Flow<List<ServerEntity>>

    @Query("SELECT * FROM servers ORDER BY id")
    suspend fun all(): List<ServerEntity>

    @Query("SELECT * FROM servers WHERE id = :id")
    suspend fun get(id: Long): ServerEntity?

    @Upsert
    suspend fun upsert(server: ServerEntity): Long

    @Delete
    suspend fun delete(server: ServerEntity)
}

@Dao
interface ArticleBookmarkDao {
    @Query("SELECT * FROM article_bookmarks WHERE bookKey = :bookKey ORDER BY createdAt DESC")
    fun observe(bookKey: String): Flow<List<ArticleBookmarkEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(bookmark: ArticleBookmarkEntity)

    @Query("DELETE FROM article_bookmarks WHERE bookKey = :bookKey AND url = :url")
    suspend fun delete(bookKey: String, url: String)
}

@Dao
interface OfflineDao {
    @Query("SELECT * FROM offline_articles ORDER BY savedAt DESC")
    fun observeArticles(): Flow<List<OfflineArticleEntity>>

    @Query("SELECT * FROM offline_articles WHERE url = :url")
    suspend fun articleByUrl(url: String): OfflineArticleEntity?

    @Query("SELECT * FROM offline_articles WHERE id = :id")
    suspend fun article(id: Long): OfflineArticleEntity?

    @Insert
    suspend fun insertArticle(article: OfflineArticleEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertResources(resources: List<OfflineResourceEntity>)

    @Query("DELETE FROM offline_articles WHERE id = :id")
    suspend fun deleteArticle(id: Long)

    /** Looks a URL up in the offline index (blocking: called from WebView request threads). */
    @Query(
        "SELECT r.fileName AS fileName, r.contentType AS contentType, a.directory AS directory " +
            "FROM offline_resources r JOIN offline_articles a ON a.id = r.articleId WHERE r.url = :url LIMIT 1",
    )
    fun findResourceBlocking(url: String): StoredResource?
}

data class StoredResource(val fileName: String, val contentType: String?, val directory: String)

@Dao
interface BookDao {
    @Query("SELECT * FROM books ORDER BY lastOpenedAt DESC, importedAt DESC")
    fun observeAll(): Flow<List<BookEntity>>

    @Query("SELECT * FROM books WHERE id = :id")
    suspend fun get(id: Long): BookEntity?

    @Insert
    suspend fun insert(book: BookEntity): Long

    @Delete
    suspend fun delete(book: BookEntity)

    @Query(
        "UPDATE books SET section = :section, sectionOffset = :offset, progress = :progress, " +
            "lastOpenedAt = :now WHERE id = :id",
    )
    suspend fun savePosition(id: Long, section: Int, offset: Int, progress: Float, now: Long)

    @Query("SELECT * FROM book_bookmarks WHERE bookId = :bookId ORDER BY section, sectionOffset")
    fun observeBookmarks(bookId: Long): Flow<List<BookBookmarkEntity>>

    @Insert
    suspend fun insertBookmark(bookmark: BookBookmarkEntity)

    @Query("DELETE FROM book_bookmarks WHERE id = :id")
    suspend fun deleteBookmark(id: Long)
}
