@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package com.example.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.Bookmark
import com.example.data.BrowserRepository
import com.example.data.HistoryItem
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.UUID

data class BrowserTab(
    val id: String,
    val url: String,
    val title: String = "Google",
    val canGoBack: Boolean = false,
    val canGoForward: Boolean = false,
    val webThemeColor: String? = null,
    val progress: Int = 0,
    val isLoading: Boolean = false,
    val screenshot: android.graphics.Bitmap? = null
)

sealed interface WebViewCommand {
    val tabId: String
    data class GoBack(override val tabId: String) : WebViewCommand
    data class GoForward(override val tabId: String) : WebViewCommand
    data class Reload(override val tabId: String) : WebViewCommand
    data class LoadUrl(override val tabId: String, val url: String) : WebViewCommand
}

class BrowserViewModel(private val repository: BrowserRepository) : ViewModel() {

    private val _isDarkTheme = MutableStateFlow(true)
    val isDarkTheme: StateFlow<Boolean> = _isDarkTheme.asStateFlow()

    fun setDarkTheme(isDark: Boolean) {
        _isDarkTheme.value = isDark
    }

    companion object {
        const val PRIMARY_URL = "https://www.abledrama.top"
        const val SECONDARY_URL = "https://www.abledrama.top"
    }

    // List of open tabs
    private val _tabs = MutableStateFlow<List<BrowserTab>>(
        listOf(BrowserTab(id = "tab_initial", url = SECONDARY_URL, title = "Able Drama"))
    )
    val tabs: StateFlow<List<BrowserTab>> = _tabs.asStateFlow()

    private val _selectedTabId = MutableStateFlow("tab_initial")
    val selectedTabId: StateFlow<String> = _selectedTabId.asStateFlow()

    // Derived states of the selected tab for backward compatibility
    val currentUrl: StateFlow<String> = combine(_tabs, _selectedTabId) { tabsList, activeId ->
        tabsList.find { it.id == activeId }?.url ?: SECONDARY_URL
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SECONDARY_URL)

    val currentTitle: StateFlow<String> = combine(_tabs, _selectedTabId) { tabsList, activeId ->
        tabsList.find { it.id == activeId }?.title ?: "Able Drama"
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "Able Drama")

    val isLoading: StateFlow<Boolean> = combine(_tabs, _selectedTabId) { tabsList, activeId ->
        tabsList.find { it.id == activeId }?.isLoading ?: false
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val progress: StateFlow<Int> = combine(_tabs, _selectedTabId) { tabsList, activeId ->
        tabsList.find { it.id == activeId }?.progress ?: 0
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val canGoBack: StateFlow<Boolean> = combine(_tabs, _selectedTabId) { tabsList, activeId ->
        tabsList.find { it.id == activeId }?.canGoBack ?: false
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val canGoForward: StateFlow<Boolean> = combine(_tabs, _selectedTabId) { tabsList, activeId ->
        tabsList.find { it.id == activeId }?.canGoForward ?: false
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val webThemeColor: StateFlow<String?> = combine(_tabs, _selectedTabId) { tabsList, activeId ->
        tabsList.find { it.id == activeId }?.webThemeColor
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    // Default configuration URL
    private val _homeUrl = MutableStateFlow(SECONDARY_URL)
    val homeUrl: StateFlow<String> = _homeUrl.asStateFlow()

    // Commands to send to the WebView Composable
    private val _commands = MutableSharedFlow<WebViewCommand>(extraBufferCapacity = 16)
    val commands: SharedFlow<WebViewCommand> = _commands.asSharedFlow()

    private val _openBrowserTrigger = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val openBrowserTrigger = _openBrowserTrigger.asSharedFlow()

    fun triggerOpenBrowser() {
        _openBrowserTrigger.tryEmit(Unit)
    }

    private val _requestSearchFocus = MutableStateFlow(false)
    val requestSearchFocus: StateFlow<Boolean> = _requestSearchFocus.asStateFlow()

    fun triggerSearchFocus(focus: Boolean) {
        _requestSearchFocus.value = focus
    }

    // Local Persistence Streams
    val bookmarks: StateFlow<List<Bookmark>> = repository.bookmarks
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val history: StateFlow<List<HistoryItem>> = repository.history
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val browserBookmarks: StateFlow<List<Bookmark>> = repository.browserBookmarks
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val browserHistory: StateFlow<List<HistoryItem>> = repository.browserHistory
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _optimisticCustomBookmarks = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    private val _optimisticBrowserBookmarks = MutableStateFlow<Map<String, Boolean>>(emptyMap())

    // Check if current URL is bookmarked as a browser bookmark
    val isCurrentUrlBookmarked: StateFlow<Boolean> = currentUrl
        .flatMapLatest { url ->
            val formattedUrl = formatUrl(url)
            repository.isBookmarkedFlow(formattedUrl, isBrowser = true).combine(_optimisticBrowserBookmarks) { dbVal, optMap ->
                optMap[formattedUrl] ?: dbVal
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    // Check if current URL is bookmarked as a custom/post bookmark
    val isCurrentUrlCustomBookmarked: StateFlow<Boolean> = currentUrl
        .flatMapLatest { url ->
            val formattedUrl = formatUrl(url)
            repository.isBookmarkedFlow(formattedUrl, isBrowser = false).combine(_optimisticCustomBookmarks) { dbVal, optMap ->
                optMap[formattedUrl] ?: dbVal
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    init {
        _homeUrl.value = SECONDARY_URL
    }

    fun setHomeUrl(url: String) {
        val formattedUrl = formatUrl(url)
        _homeUrl.value = formattedUrl
    }

    // Multi-tab actions
    fun createNewTab(url: String = "browser://home") {
        val newId = UUID.randomUUID().toString()
        val formattedUrl = if (url == "browser://home") "browser://home" else formatUrl(url)
        val newTab = BrowserTab(id = newId, url = formattedUrl, title = if (url == "browser://home") "Home" else "Google")
        _tabs.value = _tabs.value + newTab
        _selectedTabId.value = newId
    }

    fun selectTab(tabId: String) {
        if (_tabs.value.any { it.id == tabId }) {
            _selectedTabId.value = tabId
        }
    }

    fun closeTab(tabId: String) {
        val currentList = _tabs.value
        if (currentList.size <= 1) {
            val newId = UUID.randomUUID().toString()
            _tabs.value = listOf(BrowserTab(id = newId, url = "browser://home", title = "Home"))
            _selectedTabId.value = newId
            return
        }

        val remainingTabs = currentList.filter { it.id != tabId }
        _tabs.value = remainingTabs

        if (_selectedTabId.value == tabId) {
            _selectedTabId.value = remainingTabs.last().id
        }
    }

    fun updateCurrentState(url: String, title: String) {
        updateCurrentState(_selectedTabId.value, url, title)
    }

    fun updateCurrentState(tabId: String, url: String, title: String) {
        updateCurrentStateWithHistory(tabId, url, title, null)
    }

    fun updateCurrentStateWithHistory(tabId: String, url: String, title: String, thumbnailUrl: String? = null) {
        _tabs.value = _tabs.value.map { tab ->
            if (tab.id == tabId) {
                val cleanTitle = if (title.isNotBlank() && !title.startsWith("http")) title else tab.title
                tab.copy(url = url, title = cleanTitle)
            } else {
                tab
            }
        }
        if (_selectedTabId.value == tabId && url.isNotBlank()) {
            viewModelScope.launch {
                val cleanTitle = title.ifBlank { url }
                repository.addHistory(url, cleanTitle, isBrowser = true, thumbnailUrl = thumbnailUrl)
                
                if (shouldRecordDramaHistory(url)) {
                    val dramaTitle = getDramaSectionTitle(url, cleanTitle)
                    repository.addHistory(url, dramaTitle, isBrowser = false, thumbnailUrl = thumbnailUrl)
                }
            }
        }
    }

    fun shouldRecordDramaHistory(url: String): Boolean {
        val u = url.trim().lowercase()
        if (!u.contains("abledrama.top") && !u.contains("ablesrama.top")) return false
        if (u.contains("abledrama.top/history") || u.contains("ablesrama.top/history") || u.contains("/history")) return false
        
        // Match Movies, Drama, Anime, Short Drama, Web Series and portals
        if (u.contains("search/label/movies") || u.contains("category/movies")) return true
        if (u.contains("search/label/drama") || u.contains("category/drama") || u.contains("category/k-drama") || u.contains("category/bengali")) return true
        if (u.contains("search/label/anime") || u.contains("category/anime")) return true
        if (u.contains("search/label/short") || u.contains("category/short")) return true
        if (u.contains("search/label/web") || u.contains("category/web")) return true
        if (u.contains("search/label/ongoin")) return true
        if (u.endsWith("/search") || u.endsWith("/search/") || u.contains("abledrama.top/search?") || u.contains("ablesrama.top/search?")) return true
        if (u.contains("/p/")) return true
        
        // Post pages
        if (u.contains(".html") || u.contains("/20")) return true
        return false
    }

    private fun getDramaSectionTitle(url: String, currentTitle: String): String {
        val u = url.trim().lowercase()
        return when {
            u.contains("search/label/movies") || u.contains("category/movies") -> "Movies Portal"
            u.contains("search/label/drama") || u.contains("category/drama") -> "Drama Portal"
            u.contains("category/k-drama") -> "Korean Drama Portal"
            u.contains("category/bengali") -> "Bengali Drama Portal"
            u.contains("search/label/anime") || u.contains("category/anime") -> "Anime Portal"
            u.contains("search/label/short") || u.contains("category/short") -> "Short Drama Portal"
            u.contains("search/label/web") || u.contains("category/web") -> "Web Series Portal"
            u.contains("search/label/ongoin") -> "Ongoing Uploads Portal"
            u.endsWith("/search") || u.endsWith("/search/") || u.contains("abledrama.top/search?") || u.contains("ablesrama.top/search?") -> "Recent Uploads Portal"
            u.contains("/p/how-to-download.html") -> "How to Download Guide"
            u.contains("/p/request-file-form.html") -> "Request Movie/Drama Form"
            u.contains("/p/dmca-remove-your-file.html") -> "DMCA Takedown Form"
            else -> currentTitle
        }
    }

    fun updateLoadingStatus(loading: Boolean, progressPercent: Int) {
        updateLoadingStatus(_selectedTabId.value, loading, progressPercent)
    }

    fun updateLoadingStatus(tabId: String, loading: Boolean, progressPercent: Int) {
        _tabs.value = _tabs.value.map { tab ->
            if (tab.id == tabId) {
                tab.copy(isLoading = loading, progress = progressPercent)
            } else {
                tab
            }
        }
    }

    fun updateWebThemeColor(color: String?) {
        updateWebThemeColor(_selectedTabId.value, color)
    }

    fun updateWebThemeColor(tabId: String, color: String?) {
        _tabs.value = _tabs.value.map { tab ->
            if (tab.id == tabId) {
                tab.copy(webThemeColor = color)
            } else {
                tab
            }
        }
    }

    fun updateNavigationCapabilities(back: Boolean, forward: Boolean) {
        updateNavigationCapabilities(_selectedTabId.value, back, forward)
    }

    fun updateNavigationCapabilities(tabId: String, back: Boolean, forward: Boolean) {
        _tabs.value = _tabs.value.map { tab ->
            if (tab.id == tabId) {
                tab.copy(canGoBack = back, canGoForward = forward)
            } else {
                tab
            }
        }
    }

    fun updateTabScreenshot(tabId: String, bitmap: android.graphics.Bitmap?) {
        _tabs.value = _tabs.value.map { tab ->
            if (tab.id == tabId) {
                tab.copy(screenshot = bitmap)
            } else {
                tab
            }
        }
    }

    // UI actions
    fun loadUrl(url: String) {
        val formattedUrl = formatUrl(url)
        val activeId = _selectedTabId.value
        _tabs.value = _tabs.value.map { tab ->
            if (tab.id == activeId) {
                tab.copy(url = formattedUrl)
            } else {
                tab
            }
        }
        _commands.tryEmit(WebViewCommand.LoadUrl(activeId, formattedUrl))
    }

    fun goHome() {
        val activeId = _selectedTabId.value
        _commands.tryEmit(WebViewCommand.LoadUrl(activeId, "https://www.abledrama.top"))
    }

    fun goBack() {
        _commands.tryEmit(WebViewCommand.GoBack(_selectedTabId.value))
    }

    fun goForward() {
        _commands.tryEmit(WebViewCommand.GoForward(_selectedTabId.value))
    }

    fun reload() {
        _commands.tryEmit(WebViewCommand.Reload(_selectedTabId.value))
    }

    fun toggleBookmark() {
        val url = currentUrl.value
        val title = currentTitle.value
        val formattedUrl = formatUrl(url)
        val currentlyBookmarked = isCurrentUrlBookmarked.value
        _optimisticBrowserBookmarks.value = _optimisticBrowserBookmarks.value + (formattedUrl to !currentlyBookmarked)
        viewModelScope.launch {
            if (currentlyBookmarked) {
                repository.removeBookmark(formattedUrl, isBrowser = true)
            } else {
                repository.addBookmark(formattedUrl, title, isBrowser = true)
            }
        }
    }

    fun addCustomBookmark(url: String, title: String) {
        val formattedUrl = formatUrl(url)
        _optimisticCustomBookmarks.value = _optimisticCustomBookmarks.value + (formattedUrl to true)
        viewModelScope.launch {
            repository.addBookmark(formattedUrl, title, isBrowser = false)
        }
    }

    fun removeBookmark(url: String) {
        val formattedUrl = formatUrl(url)
        _optimisticCustomBookmarks.value = _optimisticCustomBookmarks.value + (formattedUrl to false)
        viewModelScope.launch {
            repository.removeBookmark(formattedUrl, isBrowser = false)
        }
    }

    fun addBrowserBookmark(url: String, title: String) {
        val formattedUrl = formatUrl(url)
        _optimisticBrowserBookmarks.value = _optimisticBrowserBookmarks.value + (formattedUrl to true)
        viewModelScope.launch {
            repository.addBookmark(formattedUrl, title, isBrowser = true)
        }
    }

    fun removeBrowserBookmark(url: String) {
        val formattedUrl = formatUrl(url)
        _optimisticBrowserBookmarks.value = _optimisticBrowserBookmarks.value + (formattedUrl to false)
        viewModelScope.launch {
            repository.removeBookmark(formattedUrl, isBrowser = true)
        }
    }

    fun deleteHistoryItem(id: Int) {
        viewModelScope.launch {
            repository.deleteHistory(id)
        }
    }

    fun clearHistory() {
        viewModelScope.launch {
            repository.clearAllHistory(isBrowser = false)
        }
    }

    fun clearBrowserHistory() {
        viewModelScope.launch {
            repository.clearAllHistory(isBrowser = true)
        }
    }

    fun formatUrl(url: String): String {
        val trimmed = url.trim()
        if (trimmed.isBlank()) return _homeUrl.value

        // If it already starts with http:// or https://, return it as is
        if (trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true)) {
            return trimmed
        }

        // Other standard browser schemes/protocols
        if (trimmed.startsWith("file://", ignoreCase = true) ||
            trimmed.startsWith("ftp://", ignoreCase = true) ||
            trimmed.startsWith("about:", ignoreCase = true) ||
            trimmed.startsWith("browser://", ignoreCase = true) ||
            trimmed.startsWith("javascript:", ignoreCase = true)) {
            return trimmed
        }

        // Check if there are spaces or newlines. If so, it is definitely a search.
        if (trimmed.contains(" ") || trimmed.contains("\n") || trimmed.contains("\t")) {
            val encodedQuery = try {
                java.net.URLEncoder.encode(trimmed, "UTF-8")
            } catch (e: Exception) {
                trimmed
            }
            return if (_isDarkTheme.value) {
                "https://www.google.com/search?q=$encodedQuery&cs=1"
            } else {
                "https://www.google.com/search?q=$encodedQuery&cs=0"
            }
        }

        // Check if matches standard WEB_URL pattern OR lighter check for standard domains like abledrama.com
        val webUrlPattern = android.util.Patterns.WEB_URL
        val matchesWebUrl = webUrlPattern.matcher(trimmed).matches()
        val hasDot = trimmed.contains(".") && !trimmed.startsWith(".") && !trimmed.endsWith(".")

        if (matchesWebUrl || hasDot) {
            val lastDot = trimmed.lastIndexOf('.')
            if (lastDot > 0 && lastDot < trimmed.length - 1) {
                val partAfterDot = trimmed.substring(lastDot + 1).split('/')[0]
                val isTldValid = partAfterDot.isNotEmpty() && partAfterDot.all { it.isLetter() || it.isDigit() } && partAfterDot.length >= 2
                if (isTldValid) {
                    return "https://$trimmed"
                }
            }
        }

        // Otherwise, fallback to Google Search
        val encodedQuery = try {
            java.net.URLEncoder.encode(trimmed, "UTF-8")
        } catch (e: Exception) {
            trimmed
        }
        return if (_isDarkTheme.value) {
            "https://www.google.com/search?q=$encodedQuery&cs=1"
        } else {
            "https://www.google.com/search?q=$encodedQuery&cs=0"
        }
    }
}

class BrowserViewModelFactory(private val repository: BrowserRepository) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(BrowserViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return BrowserViewModel(repository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
