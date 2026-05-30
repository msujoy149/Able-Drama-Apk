package com.example.data

import kotlinx.coroutines.flow.Flow

class BrowserRepository(private val dao: BrowserDao) {
    val bookmarks: Flow<List<Bookmark>> = dao.getAllBookmarks()
    val history: Flow<List<HistoryItem>> = dao.getRecentHistory()

    suspend fun addBookmark(url: String, title: String) {
        if (url.isNotBlank()) {
            dao.insertBookmark(Bookmark(url = url, title = title))
        }
    }

    suspend fun removeBookmark(url: String) {
        dao.deleteBookmarkByUrl(url)
    }

    fun isBookmarkedFlow(url: String): Flow<Boolean> = dao.isBookmarkedFlow(url)

    suspend fun isBookmarked(url: String): Boolean = dao.isBookmarkedDirect(url)

    suspend fun addHistory(url: String, title: String) {
        if (url.startsWith("http")) {
            val trimmedUrl = url.trim()
            if (trimmedUrl.isNotBlank()) {
                dao.deleteHistoryByUrl(trimmedUrl)
                dao.insertHistory(HistoryItem(url = trimmedUrl, title = title))
            }
        }
    }

    suspend fun deleteHistory(id: Int) {
        dao.deleteHistoryById(id)
    }

    suspend fun clearAllHistory() {
        dao.clearHistory()
    }
}
