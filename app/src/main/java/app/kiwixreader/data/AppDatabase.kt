package app.kiwixreader.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        ZimFolderEntity::class,
        ZimFileEntity::class,
        ServerEntity::class,
        ArticleBookmarkEntity::class,
        OfflineArticleEntity::class,
        OfflineResourceEntity::class,
        BookEntity::class,
        BookBookmarkEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun zimDao(): ZimDao
    abstract fun serverDao(): ServerDao
    abstract fun articleBookmarkDao(): ArticleBookmarkDao
    abstract fun offlineDao(): OfflineDao
    abstract fun bookDao(): BookDao

    companion object {
        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "kiwixreader.db").build()
    }
}
