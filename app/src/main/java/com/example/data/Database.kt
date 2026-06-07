package com.example.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "bookmarks", indices = [Index(value = ["url", "isBrowser"], unique = true)])
data class Bookmark(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val url: String,
    val title: String,
    val timestamp: Long = System.currentTimeMillis(),
    val isBrowser: Boolean = false
)

@Entity(tableName = "history")
data class HistoryItem(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val url: String,
    val title: String,
    val timestamp: Long = System.currentTimeMillis(),
    val isBrowser: Boolean = false,
    val thumbnailUrl: String? = null
)

@Entity(tableName = "downloads")
data class DownloadItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val url: String,
    val fileName: String,
    val filePath: String,
    val fileSize: Long,
    val bytesDownloaded: Long,
    val isResumeSupported: Boolean,
    val status: String, // "DOWNLOADING", "FINISHED", "PAUSED", "ERROR"
    val progress: Float, // 0.0 - 100.0
    val downloadSpeed: String = "0 KB/s",
    val eta: String = "--",
    val timestamp: Long = System.currentTimeMillis(),
    val useWebpageTitle: Boolean = true,
    val wifiOnly: Boolean = false,
    val retryOnFail: Boolean = true
)

@Dao
interface BrowserDao {
    // Bookmarks entries
    @Query("SELECT * FROM bookmarks WHERE isBrowser = 0 ORDER BY timestamp DESC")
    fun getAllBookmarks(): Flow<List<Bookmark>>

    @Query("SELECT * FROM bookmarks WHERE isBrowser = 1 ORDER BY timestamp DESC")
    fun getBrowserBookmarks(): Flow<List<Bookmark>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBookmark(bookmark: Bookmark)

    @Query("DELETE FROM bookmarks WHERE url = :url AND isBrowser = 0")
    suspend fun deleteBookmarkByUrl(url: String)

    @Query("DELETE FROM bookmarks WHERE url = :url AND isBrowser = 1")
    suspend fun deleteBrowserBookmarkByUrl(url: String)

    @Query("SELECT EXISTS(SELECT 1 FROM bookmarks WHERE url = :url AND isBrowser = 0 LIMIT 1)")
    fun isBookmarkedFlow(url: String): Flow<Boolean>

    @Query("SELECT EXISTS(SELECT 1 FROM bookmarks WHERE url = :url AND isBrowser = 1 LIMIT 1)")
    fun isBrowserBookmarkedFlow(url: String): Flow<Boolean>

    @Query("SELECT EXISTS(SELECT 1 FROM bookmarks WHERE url = :url AND isBrowser = 0 LIMIT 1)")
    suspend fun isBookmarkedDirect(url: String): Boolean

    @Query("SELECT EXISTS(SELECT 1 FROM bookmarks WHERE url = :url AND isBrowser = 1 LIMIT 1)")
    suspend fun isBrowserBookmarkedDirect(url: String): Boolean

    // History entries
    @Query("SELECT * FROM history WHERE isBrowser = 0 ORDER BY timestamp DESC LIMIT 100")
    fun getRecentHistory(): Flow<List<HistoryItem>>

    @Query("SELECT * FROM history WHERE isBrowser = 1 ORDER BY timestamp DESC LIMIT 100")
    fun getBrowserHistory(): Flow<List<HistoryItem>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertHistory(item: HistoryItem)

    @Query("DELETE FROM history WHERE url = :url AND isBrowser = 0")
    suspend fun deleteHistoryByUrl(url: String)

    @Query("DELETE FROM history WHERE url = :url AND isBrowser = 1")
    suspend fun deleteBrowserHistoryByUrl(url: String)

    @Query("DELETE FROM history WHERE id = :id")
    suspend fun deleteHistoryById(id: Int)

    @Query("DELETE FROM history WHERE isBrowser = 0")
    suspend fun clearHistory()

    @Query("DELETE FROM history WHERE isBrowser = 1")
    suspend fun clearBrowserHistory()
}

@Dao
interface DownloadDao {
    @Query("SELECT * FROM downloads ORDER BY timestamp DESC")
    fun getAllDownloads(): Flow<List<DownloadItem>>

    @Query("SELECT * FROM downloads WHERE id = :id")
    suspend fun getDownloadById(id: Long): DownloadItem?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDownload(item: DownloadItem): Long

    @Update
    suspend fun updateDownload(item: DownloadItem)

    @Delete
    suspend fun deleteDownload(item: DownloadItem)

    @Query("DELETE FROM downloads WHERE id = :id")
    suspend fun deleteDownloadById(id: Long)
}

@Database(entities = [Bookmark::class, HistoryItem::class, DownloadItem::class], version = 4, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun browserDao(): BrowserDao
    abstract fun downloadDao(): DownloadDao
}

