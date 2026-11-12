package app.burrow.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** A folder the user granted through the Storage Access Framework. */
@Entity(tableName = "zim_folders")
data class ZimFolderEntity(
    @PrimaryKey val treeUri: String,
    val displayName: String,
    val addedAt: Long,
)

/**
 * A ZIM file found in a folder (content:// document URI) or downloaded into app storage (file:// URI,
 * [folderUri] = null).
 */
@Entity(tableName = "zim_files", indices = [Index("folderUri")])
data class ZimFileEntity(
    @PrimaryKey val uri: String,
    val folderUri: String?,
    val title: String,
    val language: String,
    val articleCount: Int,
    val sizeBytes: Long,
    val zimId: String,
    val lastModified: Long,
    val icon: ByteArray?,
    /** False when libkiwix may not read the file in place (shared storage without all-files access). */
    val readable: Boolean = true,
)

@Entity(tableName = "servers")
data class ServerEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Normalised base URL without trailing slash, e.g. `http://192.168.1.10:8080`. */
    val baseUrl: String,
    val name: String,
    val username: String?,
    val password: String?,
) {
    val displayName: String get() = name.ifBlank { baseUrl }
}

/** A bookmark inside a ZIM (local or remote). [bookKey] is `zim:<uri>` or `remote:<contentRoot>`. */
@Entity(tableName = "article_bookmarks", indices = [Index(value = ["bookKey", "url"], unique = true)])
data class ArticleBookmarkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookKey: String,
    val url: String,
    val title: String,
    val createdAt: Long,
)

/** A remote article saved for offline reading. Its files live in [directory]. */
@Entity(tableName = "offline_articles", indices = [Index(value = ["url"], unique = true)])
data class OfflineArticleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val url: String,
    val title: String,
    val bookTitle: String,
    val contentRoot: String,
    val serverBaseUrl: String?,
    val directory: String,
    val sizeBytes: Long,
    val savedAt: Long,
)

/** Offline index: one stored response (the page or one of its assets) of an offline article. */
@Entity(
    tableName = "offline_resources",
    primaryKeys = ["articleId", "url"],
    indices = [Index("url")],
    foreignKeys = [
        ForeignKey(
            entity = OfflineArticleEntity::class,
            parentColumns = ["id"],
            childColumns = ["articleId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class OfflineResourceEntity(
    val articleId: Long,
    val url: String,
    val fileName: String,
    val contentType: String?,
)

@Entity(tableName = "books")
data class BookEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val filePath: String,
    val format: String,
    val title: String,
    val author: String,
    val sizeBytes: Long,
    val importedAt: Long,
    /** Remembered reading position: section index and character offset inside that section. */
    val section: Int = 0,
    val sectionOffset: Int = 0,
    val progress: Float = 0f,
    val lastOpenedAt: Long = 0,
)

@Entity(
    tableName = "book_bookmarks",
    indices = [Index("bookId")],
    foreignKeys = [
        ForeignKey(
            entity = BookEntity::class,
            parentColumns = ["id"],
            childColumns = ["bookId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class BookBookmarkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: Long,
    val section: Int,
    val sectionOffset: Int,
    val progress: Float,
    val label: String,
    val createdAt: Long,
)
