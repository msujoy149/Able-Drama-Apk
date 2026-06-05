package com.example.data

import kotlinx.coroutines.flow.Flow

class BrowserRepository(private val dao: BrowserDao) {
    val bookmarks: Flow<List<Bookmark>> = dao.getAllBookmarks()
    val history: Flow<List<HistoryItem>> = dao.getRecentHistory()

    val browserBookmarks: Flow<List<Bookmark>> = dao.getBrowserBookmarks()
    val browserHistory: Flow<List<HistoryItem>> = dao.getBrowserHistory()

    suspend fun addBookmark(url: String, title: String, isBrowser: Boolean = false) {
        if (url.isNotBlank()) {
            dao.insertBookmark(Bookmark(url = url, title = title, isBrowser = isBrowser))
        }
    }

    suspend fun removeBookmark(url: String, isBrowser: Boolean = false) {
        if (isBrowser) {
            dao.deleteBrowserBookmarkByUrl(url)
        } else {
            dao.deleteBookmarkByUrl(url)
        }
    }

    fun isBookmarkedFlow(url: String, isBrowser: Boolean = false): Flow<Boolean> {
        return if (isBrowser) dao.isBrowserBookmarkedFlow(url) else dao.isBookmarkedFlow(url)
    }

    suspend fun isBookmarked(url: String, isBrowser: Boolean = false): Boolean {
        return if (isBrowser) dao.isBrowserBookmarkedDirect(url) else dao.isBookmarkedDirect(url)
    }

    suspend fun addHistory(url: String, title: String, isBrowser: Boolean = false) {
        if (url.startsWith("http")) {
            val trimmedUrl = url.trim()
            if (trimmedUrl.isNotBlank()) {
                if (isBrowser) {
                    dao.deleteBrowserHistoryByUrl(trimmedUrl)
                } else {
                    dao.deleteHistoryByUrl(trimmedUrl)
                }
                dao.insertHistory(HistoryItem(url = trimmedUrl, title = title, isBrowser = isBrowser))
            }
        }
    }

    suspend fun deleteHistory(id: Int) {
        dao.deleteHistoryById(id)
    }

    suspend fun clearAllHistory(isBrowser: Boolean = false) {
        if (isBrowser) {
            dao.clearBrowserHistory()
        } else {
            dao.clearHistory()
        }
    }
}

class DownloadRepository(private val dao: DownloadDao) {
    val allDownloads: Flow<List<DownloadItem>> = dao.getAllDownloads()

    suspend fun getDownloadById(id: Long): DownloadItem? = dao.getDownloadById(id)

    suspend fun insertDownload(item: DownloadItem): Long = dao.insertDownload(item)

    suspend fun updateDownload(item: DownloadItem) = dao.updateDownload(item)

    suspend fun deleteDownload(item: DownloadItem) = dao.deleteDownload(item)

    suspend fun deleteDownloadById(id: Long) = dao.deleteDownloadById(id)
}

