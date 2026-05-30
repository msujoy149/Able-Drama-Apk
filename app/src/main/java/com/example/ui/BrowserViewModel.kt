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

sealed interface WebViewCommand {
    data object GoBack : WebViewCommand
    data object GoForward : WebViewCommand
    data object Reload : WebViewCommand
    data class LoadUrl(val url: String) : WebViewCommand
}

class BrowserViewModel(private val repository: BrowserRepository) : ViewModel() {

    companion object {
        const val PRIMARY_URL = "https://www.abledrama.top"
        const val SECONDARY_URL = "https://www.abledrama.top"
    }

    // Default configuration URL
    private val _homeUrl = MutableStateFlow(SECONDARY_URL)
    val homeUrl: StateFlow<String> = _homeUrl.asStateFlow()

    // Current page status
    private val _currentUrl = MutableStateFlow(SECONDARY_URL)
    val currentUrl: StateFlow<String> = _currentUrl.asStateFlow()

    private val _currentTitle = MutableStateFlow("Able Drama")
    val currentTitle: StateFlow<String> = _currentTitle.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _progress = MutableStateFlow(0)
    val progress: StateFlow<Int> = _progress.asStateFlow()

    // Web view state signals
    private val _canGoBack = MutableStateFlow(false)
    val canGoBack: StateFlow<Boolean> = _canGoBack.asStateFlow()

    private val _canGoForward = MutableStateFlow(false)
    val canGoForward: StateFlow<Boolean> = _canGoForward.asStateFlow()

    // Commands to send to the WebView Composable
    private val _commands = MutableSharedFlow<WebViewCommand>(extraBufferCapacity = 16)
    val commands: SharedFlow<WebViewCommand> = _commands.asSharedFlow()

    // Local Persistence Streams
    val bookmarks: StateFlow<List<Bookmark>> = repository.bookmarks
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val history: StateFlow<List<HistoryItem>> = repository.history
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Check if current URL is bookmarked
    val isCurrentUrlBookmarked: StateFlow<Boolean> = _currentUrl
        .flatMapLatest { url -> repository.isBookmarkedFlow(url) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    init {
        // Default to a default page on start
        _homeUrl.value = SECONDARY_URL
        _currentUrl.value = SECONDARY_URL
    }

    fun setHomeUrl(url: String) {
        val formattedUrl = formatUrl(url)
        _homeUrl.value = formattedUrl
    }

    fun updateCurrentState(url: String, title: String) {
        _currentUrl.value = url
        if (title.isNotBlank() && !title.startsWith("http")) {
            _currentTitle.value = title
        }
        // Add to history automatically in background
        viewModelScope.launch {
            repository.addHistory(url, title.ifBlank { url })
        }
    }

    fun updateLoadingStatus(loading: Boolean, progressPercent: Int) {
        _isLoading.value = loading
        _progress.value = progressPercent
    }

    fun updateNavigationCapabilities(back: Boolean, forward: Boolean) {
        _canGoBack.value = back
        _canGoForward.value = forward
    }

    // UI actions
    fun loadUrl(url: String) {
        val formattedUrl = formatUrl(url)
        _currentUrl.value = formattedUrl
        _commands.tryEmit(WebViewCommand.LoadUrl(formattedUrl))
    }

    fun goHome() {
        _commands.tryEmit(WebViewCommand.LoadUrl(_homeUrl.value))
    }

    fun goBack() {
        _commands.tryEmit(WebViewCommand.GoBack)
    }

    fun goForward() {
        _commands.tryEmit(WebViewCommand.GoForward)
    }

    fun reload() {
        _commands.tryEmit(WebViewCommand.Reload)
    }

    fun toggleBookmark() {
        val url = _currentUrl.value
        val title = _currentTitle.value
        viewModelScope.launch {
            if (repository.isBookmarked(url)) {
                repository.removeBookmark(url)
            } else {
                repository.addBookmark(url, title)
            }
        }
    }

    fun addCustomBookmark(url: String, title: String) {
        viewModelScope.launch {
            repository.addBookmark(formatUrl(url), title)
        }
    }

    fun removeBookmark(url: String) {
        viewModelScope.launch {
            repository.removeBookmark(url)
        }
    }

    fun deleteHistoryItem(id: Int) {
        viewModelScope.launch {
            repository.deleteHistory(id)
        }
    }

    fun clearHistory() {
        viewModelScope.launch {
            repository.clearAllHistory()
        }
    }

    private fun formatUrl(url: String): String {
        var trimmed = url.trim()
        if (trimmed.isBlank()) return _homeUrl.value
        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
            trimmed = "https://$trimmed"
        }
        return trimmed
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
