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

data class DetectedResource(
    val url: String,
    val title: String,
    val fileType: String, // e.g. "Video", "Audio", "Document", "Archive"
    val quality: String? = null, // e.g. "1080p", "720p", "MP3"
    val fileSize: Long = 0L // if available
)

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

class BrowserViewModel(
    private val repository: BrowserRepository,
    context: android.content.Context
) : ViewModel() {

    private val sharedPreferences = context.applicationContext.getSharedPreferences("able_browser_prefs", android.content.Context.MODE_PRIVATE)

    private fun isAbleDramaUrl(url: String): Boolean {
        val lower = url.lowercase().trim()
        return lower.contains("abledrama.top") || lower.contains("ablesrama.top") || lower.contains("abledrama")
    }

    private fun loadSavedTabs(): List<BrowserTab> {
        val savedJson = sharedPreferences.getString("browser_tabs", null)
        if (!savedJson.isNullOrBlank()) {
            try {
                val jsonArray = org.json.JSONArray(savedJson)
                val list = mutableListOf<BrowserTab>()
                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.getJSONObject(i)
                    val url = obj.getString("url")
                    if (!isAbleDramaUrl(url)) {
                        list.add(
                            BrowserTab(
                                id = obj.getString("id"),
                                url = url,
                                title = obj.optString("title", "Google")
                            )
                        )
                    }
                }
                if (list.isNotEmpty()) return list
            } catch (e: Exception) {
                android.util.Log.e("BrowserViewModel", "Failed to load saved tabs", e)
            }
        }
        return listOf(BrowserTab(id = "browser_initial_tab", url = "browser://home", title = "Home"))
    }

    private fun loadSavedSelectedTabId(restored: List<BrowserTab>): String {
        val savedId = sharedPreferences.getString("browser_selected_tab_id", null)
        if (savedId != null && restored.any { it.id == savedId }) {
            return savedId
        }
        return restored.firstOrNull()?.id ?: "browser_initial_tab"
    }

    private val restoredTabs = loadSavedTabs()
    private val restoredSelectedId = loadSavedSelectedTabId(restoredTabs)

    private val _isDarkTheme = MutableStateFlow(true)
    val isDarkTheme: StateFlow<Boolean> = _isDarkTheme.asStateFlow()

    fun setDarkTheme(isDark: Boolean) {
        _isDarkTheme.value = isDark
    }

    private val _isDesktopModeEnabled = MutableStateFlow(sharedPreferences.getBoolean("browser_desktop_mode", false))
    val isDesktopModeEnabled: StateFlow<Boolean> = _isDesktopModeEnabled.asStateFlow()

    fun setDesktopModeEnabled(enabled: Boolean) {
        _isDesktopModeEnabled.value = enabled
        sharedPreferences.edit().putBoolean("browser_desktop_mode", enabled).apply()
        if (!_isDramaModeActive.value) {
            reload()
        }
    }

    companion object {
        const val PRIMARY_URL = "https://www.abledrama.top"
        const val SECONDARY_URL = "https://www.abledrama.top"
    }

    // List of browser/drama tabs and selected IDs (complete isolation)
    private val _browserTabs = MutableStateFlow<List<BrowserTab>>(restoredTabs)
    val browserTabs: StateFlow<List<BrowserTab>> = _browserTabs.asStateFlow()

    private val _browserSelectedTabId = MutableStateFlow(restoredSelectedId)
    val browserSelectedTabId: StateFlow<String> = _browserSelectedTabId.asStateFlow()

    private val _dramaTabs = MutableStateFlow<List<BrowserTab>>(
        listOf(BrowserTab(id = "drama_initial_tab", url = SECONDARY_URL, title = "Able Drama"))
    )
    val dramaTabs: StateFlow<List<BrowserTab>> = _dramaTabs.asStateFlow()

    private val _dramaSelectedTabId = MutableStateFlow("drama_initial_tab")
    val dramaSelectedTabId: StateFlow<String> = _dramaSelectedTabId.asStateFlow()

    private val _isDramaModeActive = MutableStateFlow(true)
    val isDramaModeActive: StateFlow<Boolean> = _isDramaModeActive.asStateFlow()

    fun setDramaModeActive(active: Boolean) {
        _isDramaModeActive.value = active
        val currentList = _browserTabs.value
        val cleanedList = currentList.filter { !isAbleDramaUrl(it.url) }
        if (cleanedList.size != currentList.size || cleanedList.isEmpty()) {
            if (cleanedList.isEmpty()) {
                val newId = "browser_initial_tab_" + UUID.randomUUID().toString().take(6)
                _browserTabs.value = listOf(BrowserTab(id = newId, url = "browser://home", title = "Home"))
                _browserSelectedTabId.value = newId
            } else {
                _browserTabs.value = cleanedList
                val currentSelectedId = _browserSelectedTabId.value
                if (cleanedList.none { it.id == currentSelectedId }) {
                    _browserSelectedTabId.value = cleanedList.last().id
                }
            }
        }
    }

    // Dynamic tabs and selectedTabId derived from _isDramaModeActive
    val tabs: StateFlow<List<BrowserTab>> = _isDramaModeActive
        .flatMapLatest { isDrama -> if (isDrama) _dramaTabs else _browserTabs }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), listOf(BrowserTab(id = "drama_initial_tab", url = SECONDARY_URL, title = "Able Drama")))

    val selectedTabId: StateFlow<String> = _isDramaModeActive
        .flatMapLatest { isDrama -> if (isDrama) _dramaSelectedTabId else _browserSelectedTabId }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "drama_initial_tab")

    private fun getActiveList(): MutableStateFlow<List<BrowserTab>> {
        return if (_isDramaModeActive.value) _dramaTabs else _browserTabs
    }

    private fun getActiveSelectedId(): MutableStateFlow<String> {
        return if (_isDramaModeActive.value) _dramaSelectedTabId else _browserSelectedTabId
    }

    // Derived states of the selected tab for backward compatibility
    val currentUrl: StateFlow<String> = combine(tabs, selectedTabId) { tabsList, activeId ->
        tabsList.find { it.id == activeId }?.url ?: SECONDARY_URL
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SECONDARY_URL)

    val currentTitle: StateFlow<String> = combine(tabs, selectedTabId) { tabsList, activeId ->
        tabsList.find { it.id == activeId }?.title ?: "Able Drama"
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "Able Drama")

    val isLoading: StateFlow<Boolean> = combine(tabs, selectedTabId) { tabsList, activeId ->
        tabsList.find { it.id == activeId }?.isLoading ?: false
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val progress: StateFlow<Int> = combine(tabs, selectedTabId) { tabsList, activeId ->
        tabsList.find { it.id == activeId }?.progress ?: 0
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val canGoBack: StateFlow<Boolean> = combine(tabs, selectedTabId) { tabsList, activeId ->
        tabsList.find { it.id == activeId }?.canGoBack ?: false
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val canGoForward: StateFlow<Boolean> = combine(tabs, selectedTabId) { tabsList, activeId ->
        tabsList.find { it.id == activeId }?.canGoForward ?: false
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val webThemeColor: StateFlow<String?> = combine(tabs, selectedTabId) { tabsList, activeId ->
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

    private val _triggerReturnToDramaDialog = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val triggerReturnToDramaDialog = _triggerReturnToDramaDialog.asSharedFlow()

    fun triggerReturnToDramaDialog() {
        _triggerReturnToDramaDialog.tryEmit(Unit)
    }

    private val _requestSearchFocus = MutableStateFlow(false)
    val requestSearchFocus: StateFlow<Boolean> = _requestSearchFocus.asStateFlow()

    fun triggerSearchFocus(focus: Boolean) {
        _requestSearchFocus.value = focus
    }

    // Real-time Download Resource Detection State map (tabId -> list of resources)
    private val _detectedResources = MutableStateFlow<Map<String, List<DetectedResource>>>(emptyMap())
    val detectedResources: StateFlow<Map<String, List<DetectedResource>>> = _detectedResources.asStateFlow()

    // Tracks if a video is actively playing on each tab
    private val _isVideoPlayingMap = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val isVideoPlaying: StateFlow<Boolean> = combine(_isVideoPlayingMap, selectedTabId) { map, activeId ->
        map[activeId] ?: false
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    // Tracks the clean extracted video/page title for each tab
    private val _activeVideoTitleMap = MutableStateFlow<Map<String, String>>(emptyMap())
    val activeVideoTitleMap: StateFlow<Map<String, String>> = _activeVideoTitleMap.asStateFlow()

    // Tracks the active video duration for each tab
    private val _activeVideoDurationMap = MutableStateFlow<Map<String, Double>>(emptyMap())
    val activeVideoDurationMap: StateFlow<Map<String, Double>> = _activeVideoDurationMap.asStateFlow()

    val currentDetectedResources: StateFlow<List<DetectedResource>> = combine(
        _detectedResources,
        selectedTabId,
        _isVideoPlayingMap,
        _activeVideoTitleMap
    ) { map, activeId, isPlayingMap, titleMap ->
        val isPlaying = isPlayingMap[activeId] ?: false
        val activeTitle = titleMap[activeId] ?: ""
        
        val list = map[activeId] ?: emptyList()
        val videoResources = list.filter { it.fileType == "Video" && isValidVideoResource(it.url, it.title) }
        
        val activeTab = _browserTabs.value.find { it.id == activeId } ?: _dramaTabs.value.find { it.id == activeId }
        val pageUrl = activeTab?.url ?: currentUrl.value
        
        if (pageUrl.isBlank() || pageUrl.startsWith("browser://") || pageUrl == "about:blank" || !isDownloadablePage(pageUrl)) {
            return@combine emptyList<DetectedResource>()
        }
        
        val currentTitleFallback = when {
            activeTitle.isNotBlank() && !isGenericTitle(activeTitle) -> activeTitle
            !activeTab?.title.isNullOrBlank() -> {
                activeTab.title
                    .replace(" - YouTube", "", ignoreCase = true)
                    .replace(" | Facebook", "", ignoreCase = true)
                    .replace(" - TikTok", "", ignoreCase = true)
                    .replace(" - Instagram", "", ignoreCase = true)
                    .replace(" - bilibili", "", ignoreCase = true)
                    .replace(" - BiliBili", "", ignoreCase = true)
                    .trim()
            }
            else -> "Playable Video Stream"
        }
        
        if (!isPlaying && videoResources.isEmpty()) {
            emptyList()
        } else {
            // Generate fallback resources if video is playing but no resources detected yet
            val baseResources = if (videoResources.isEmpty() && isPlaying) {
                listOf(
                    DetectedResource(
                        url = pageUrl,
                        title = currentTitleFallback,
                        fileType = "Video",
                        quality = "720p",
                        fileSize = 0L
                    )
                )
            } else {
                videoResources
            }
            
            baseResources.map { res ->
                val targetTitle = when {
                    !isGenericTitle(res.title) -> res.title
                    !isGenericTitle(currentTitleFallback) -> currentTitleFallback
                    else -> "Playable Video"
                }.replace(" - YouTube", "", ignoreCase = true)
                 .replace(" | Facebook", "", ignoreCase = true)
                 .replace(" - TikTok", "", ignoreCase = true)
                 .replace(" - Instagram", "", ignoreCase = true)
                 .replace(" - bilibili", "", ignoreCase = true)
                 .replace(" - BiliBili", "", ignoreCase = true)
                 .trim()
                
                res.copy(title = targetTitle)
            }.distinctBy { it.url }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun isGenericTitle(title: String): Boolean {
        val t = title.lowercase().trim()
        val keywords = listOf("collect", "config", "stream", "resource", "blob", "unknown", "videoplayback", "video_stream", "video-cdn")
        if (keywords.any { t == it || t.contains(it) }) return true
        return t.isBlank() ||
               t == "video element" ||
               t == "video stream source" ||
               t == "video link" ||
               t == "video media resource" ||
               t == "video resource" ||
               t == "video" ||
               t == "video.mp4" ||
               t == "stream.m3u8" ||
               t == "resource_001" ||
               t == "unknown_video" ||
               t == "video_001" ||
               t == "resource_xxx"
    }

    fun isValidVideoResource(url: String, title: String): Boolean {
        val urlLower = url.lowercase()
        val titleLower = title.lowercase().trim()

        // 1. If it is a known major video platform CDN or stream URL, it is always a valid video resource!
        val isMajorVideoCdnStream = urlLower.contains("googlevideo.com") ||
                                    urlLower.contains("videoplayback") ||
                                    urlLower.contains("video_stream") ||
                                    urlLower.contains("video-cdn") ||
                                    (urlLower.contains("fbcdn.net") && (urlLower.contains("/v/") || urlLower.contains("_v_") || urlLower.contains(".mp4"))) ||
                                    urlLower.contains("tiktokv.com") ||
                                    urlLower.contains("tiktok.com") ||
                                    urlLower.contains("vimeo") ||
                                    urlLower.contains("dailymotion") ||
                                    urlLower.contains(".m3u8")

        if (isMajorVideoCdnStream) {
            // Keep it! But make sure it's not a generic tracking, logging, analytics, subtitling, or ad url
            val blacklistKeywords = listOf(
                "analytics", "telemetry", "metrics", "collect", "tracker", "logging", "logger", 
                "google-analytics", "doubleclick", "googlesyndication", "/ad", "popads", "popcash", 
                "manifest.json", "manifest.mpd", "hotkeys", "caption", "subtitles", "playlog", "ping", 
                "/v1/logs", "youtubei/v1", "/log_event", "pagead", "favicon.ico"
            )
            if (blacklistKeywords.any { urlLower.contains(it) }) {
                return false
            }
            // For YouTube, filter out audio-only streams
            if (urlLower.contains("googlevideo.com") && urlLower.contains("mime=audio")) {
                return false
            }
            return true
        }

        val forbiddenTitles = listOf("collect", "config", "unknown", "stream", "blob", "resource", "video_001", "resource_xxx")
        if (forbiddenTitles.any { titleLower == it || titleLower.contains(it) }) {
            return false
        }

        val excludeKeywords = listOf(
            "analytics", "telemetry", "metrics", "collect", "tracker", "logging", "logger", 
            "google-analytics", "doubleclick", "googlesyndication", "/ad", "popads", "popcash", 
            "config", "settings", "manifest.json", "manifest.mpd", "hotkeys", "caption", 
            "subtitles", "playlog", "ping", "/v1/logs", "youtubei/v1", "/log_event", "pagead",
            "favicon.ico", "adsystem", "exoclick", "clck.ru", "stat", "beacon", "pixel",
            "/fragment", "-fragment", "_fragment",
            "/chunk", "-chunk", "_chunk",
            "/segment", "-segment", "_segment",
            "m3u8_audio", ".aac", ".ts", ".vtt", "audio-only", "/audio/", "range="
        )

        for (kw in excludeKeywords) {
            if (urlLower.contains(kw)) {
                if (urlLower.contains("videoplayback")) {
                    if (urlLower.contains("mime=audio")) {
                        return false
                    }
                } else {
                    return false
                }
            }
        }

        val extensions = listOf(".mp4", ".mkv", ".webm", ".mov", ".avi", ".flv", ".3gp", ".m4v", ".wmv", ".mpeg", ".m3u8")
        val isVideoExt = extensions.any { urlLower.substringBefore("?").endsWith(it) }
        val isVideoStream = urlLower.contains("videoplayback") || 
                            urlLower.contains("video_stream") || 
                            urlLower.contains("video-cdn") ||
                            urlLower.contains("googlevideo.com") ||
                            urlLower.contains("tiktok") ||
                            urlLower.contains("fbcdn.net") ||
                            (urlLower.contains("/video/") && !urlLower.substringBefore("?").endsWith(".html"))

        return isVideoExt || isVideoStream
    }

    fun isDownloadablePage(url: String): Boolean {
        val cleanUrl = url.lowercase().trim()
        if (cleanUrl.isBlank() || cleanUrl.startsWith("browser://") || cleanUrl == "about:blank") return false
        
        val uri = try { android.net.Uri.parse(cleanUrl) } catch(e: Exception) { null }
        val host = uri?.host ?: ""
        val path = uri?.path ?: ""
        
        if (host.contains("youtube.com") || host.contains("youtu.be")) {
            if (path == "/" || path.isBlank()) return false
            if (path.contains("/results")) return false
            if (path.contains("/feed/")) return false
        }
        
        if (host.contains("facebook.com")) {
            if (path == "/" || path.isBlank()) return false
            if (path.contains("/search")) return false
            if (path.contains("/home") || path.contains("/feed")) return false
        }
        
        if (host.contains("tiktok.com")) {
            if (path == "/" || path.isBlank() || path == "/explore") return false
            if (path.contains("/search")) return false
        }
        
        if (host.contains("instagram.com")) {
            if (path == "/" || path.isBlank() || path == "/explore" || (path.contains("/reels") && path.length < 10)) return false
            if (path.contains("/search") || path.contains("/direct")) return false
        }

        if (host.contains("vimeo.com")) {
            if (path == "/" || path.isBlank() || path == "/watch") return false
            if (path.contains("/search")) return false
        }

        if (host.contains("dailymotion.com")) {
            if (path == "/" || path.isBlank()) return false
            if (path.contains("/search") || path.contains("/library") || path.contains("/news")) return false
        }
        
        if (host.contains("google.com")) {
            if (path.contains("/search") || path == "/" || path.isBlank()) return false
        }

        return true
    }

    fun clearDetectedResources(tabId: String) {
        _detectedResources.update { map ->
            map.toMutableMap().apply {
                remove(tabId)
            }
        }
        _isVideoPlayingMap.update { map ->
            map.toMutableMap().apply {
                remove(tabId)
            }
        }
        _activeVideoTitleMap.update { map ->
            map.toMutableMap().apply {
                remove(tabId)
            }
        }
        _activeVideoDurationMap.update { map ->
            map.toMutableMap().apply {
                remove(tabId)
            }
        }
    }

    fun onVideoPlaybackStateChanged(
        tabId: String, 
        isPlaying: Boolean, 
        activeTitle: String, 
        activeSrc: String, 
        duration: Double = 0.0,
        videoWidth: Int = 0,
        videoHeight: Int = 0
    ) {
        _isVideoPlayingMap.update { map ->
            map.toMutableMap().apply {
                put(tabId, isPlaying)
            }
        }
        if (duration > 0.0) {
            _activeVideoDurationMap.update { map ->
                map.toMutableMap().apply {
                    put(tabId, duration)
                }
            }
        }

        val activeTab = _browserTabs.value.find { it.id == tabId } ?: _dramaTabs.value.find { it.id == tabId }
        val pageUrl = activeTab?.url ?: currentUrl.value
        val isDownloadable = isDownloadablePage(pageUrl)

        val resolvedTitle = when {
            activeTitle.isNotBlank() && !isGenericTitle(activeTitle) -> activeTitle
            !activeTab?.title.isNullOrBlank() -> {
                activeTab.title
                    .replace(" - YouTube", "", ignoreCase = true)
                    .replace(" | Facebook", "", ignoreCase = true)
                    .replace(" - TikTok", "", ignoreCase = true)
                    .replace(" - Instagram", "", ignoreCase = true)
                    .replace(" - bilibili", "", ignoreCase = true)
                    .replace(" - BiliBili", "", ignoreCase = true)
                    .trim()
            }
            else -> "Playable Video Stream"
        }

        _activeVideoTitleMap.update { map ->
            map.toMutableMap().apply {
                put(tabId, resolvedTitle)
            }
        }

        // Update any existing detected resources on this tab
        _detectedResources.update { map ->
            val list = map[tabId] ?: emptyList()
            val updatedList = list.map { res ->
                if (isGenericTitle(res.title) && !isGenericTitle(resolvedTitle)) {
                    res.copy(title = resolvedTitle)
                } else {
                    res
                }
            }
            map.toMutableMap().apply {
                put(tabId, updatedList)
            }
        }

        val mappedQuality = when {
            videoWidth >= 3840 || videoHeight >= 2160 -> "2160p"
            videoWidth >= 2560 || videoHeight >= 1440 -> "1440p"
            videoWidth >= 1920 || videoHeight >= 1080 -> "1080p"
            videoWidth >= 1280 || videoHeight >= 720 -> "720p"
            videoWidth >= 854 || videoHeight >= 480 -> "480p"
            videoWidth >= 640 || videoHeight >= 360 -> "360p"
            videoWidth >= 426 || videoHeight >= 240 -> "240p"
            videoHeight > 0 -> "${videoHeight}p"
            else -> null
        }

        if (isPlaying && isDownloadable) {
            // If activeSrc is blank or uses MSE blobs, synthesize high quality fallback stream using the page URL
            val deservesFallback = activeSrc.isBlank() || activeSrc.startsWith("blob:") || !activeSrc.startsWith("http")
            val targetMediaUrl = if (deservesFallback) pageUrl else activeSrc

            val playRes = DetectedResource(
                url = targetMediaUrl,
                title = resolvedTitle,
                fileType = "Video",
                quality = mappedQuality ?: (if (targetMediaUrl.contains(".m3u8")) "HLS Playlist" else "720p"),
                fileSize = 0L
            )
            addDetectedResource(tabId, playRes)
        } else if (activeSrc.isNotBlank() && activeSrc.startsWith("http")) {
            val playRes = DetectedResource(
                url = activeSrc,
                title = resolvedTitle,
                fileType = "Video",
                quality = mappedQuality ?: (if (activeSrc.contains(".m3u8")) "HLS Playlist" else "720p"),
                fileSize = 0L
            )
            addDetectedResource(tabId, playRes)
        }
    }

    fun isValidAudioResource(url: String, title: String): Boolean {
        val urlLower = url.lowercase()
        val blacklistKeywords = listOf(
            "analytics", "telemetry", "metrics", "collect", "tracker", "logging", "logger", 
            "google-analytics", "doubleclick", "googlesyndication", "/ad", "popads", "popcash", 
            "manifest.json", "manifest.mpd", "hotkeys", "caption", "subtitles", "playlog", "ping", 
            "/v1/logs", "youtubei/v1", "/log_event", "pagead", "favicon.ico"
        )
        if (blacklistKeywords.any { urlLower.contains(it) }) return false
        val audioExtensions = listOf(".mp3", ".wav", ".m4a", ".ogg", ".aac", ".flac", ".wma", ".opus", ".mka", ".m3u", ".m3u8_audio")
        return audioExtensions.any { urlLower.substringBefore("?").endsWith(it) } || urlLower.contains("audioplayback") || urlLower.contains("audio_stream")
    }

    fun addDetectedResource(tabId: String, resource: DetectedResource) {
        val isValid = if (resource.fileType == "Audio") {
            isValidAudioResource(resource.url, resource.title)
        } else {
            isValidVideoResource(resource.url, resource.title)
        }
        if (!isValid) {
            return
        }

        // Log/Register detected resources globally in the download recovery engine
        try {
            com.example.util.DownloadEngine.registerDetectedUrl(resource.title, resource.url)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // If the resource being added has a generic title and we have a non-generic activeTitle for this tab, use it!
        val activeTitle = _activeVideoTitleMap.value[tabId]
        val resolvedResource = if (!activeTitle.isNullOrBlank() && isGenericTitle(resource.title)) {
            resource.copy(title = activeTitle)
        } else {
            resource
        }

        _detectedResources.update { map ->
            val list = map[tabId] ?: emptyList()
            if (list.any { it.url == resolvedResource.url }) {
                val updatedList = list.map {
                    if (it.url == resolvedResource.url && isGenericTitle(it.title) && !isGenericTitle(resolvedResource.title)) {
                        it.copy(title = resolvedResource.title)
                    } else {
                        it
                    }
                }
                map.toMutableMap().apply {
                    put(tabId, updatedList)
                }
            } else {
                map.toMutableMap().apply {
                    put(tabId, list + resolvedResource)
                }
            }
        }
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
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            combine(_browserTabs, _browserSelectedTabId) { tabs, selectedId ->
                Pair(tabs, selectedId)
            }.collect { (tabs, selectedId) ->
                try {
                    val jsonArray = org.json.JSONArray()
                    for (tab in tabs) {
                        val obj = org.json.JSONObject()
                        obj.put("id", tab.id)
                        obj.put("url", tab.url)
                        obj.put("title", tab.title)
                        jsonArray.put(obj)
                    }
                    sharedPreferences.edit()
                        .putString("browser_tabs", jsonArray.toString())
                        .putString("browser_selected_tab_id", selectedId)
                        .apply()
                } catch (e: Exception) {
                    android.util.Log.e("BrowserViewModel", "Failed to save browser session", e)
                }
            }
        }
    }

    fun setHomeUrl(url: String) {
        val formattedUrl = formatUrl(url)
        _homeUrl.value = formattedUrl
    }

    // Direct interface actions to open browser home & pages safely
    fun openBrowserToHome() {
        setDramaModeActive(false)
        val currentList = _browserTabs.value
        val activeId = _browserSelectedTabId.value
        val activeTab = currentList.find { it.id == activeId }
        
        if (activeTab != null) {
            // Tab already active and valid, keep session intact!
            if (activeTab.url == "browser://home") {
                _commands.tryEmit(WebViewCommand.LoadUrl(activeId, "browser://home"))
            }
        } else if (currentList.isNotEmpty()) {
            val lastTab = currentList.last()
            _browserSelectedTabId.value = lastTab.id
            if (lastTab.url == "browser://home") {
                _commands.tryEmit(WebViewCommand.LoadUrl(lastTab.id, "browser://home"))
            }
        } else {
            val newId = "browser_initial_tab_" + UUID.randomUUID().toString().take(6)
            val newTab = BrowserTab(id = newId, url = "browser://home", title = "Home")
            _browserTabs.value = listOf(newTab)
            _browserSelectedTabId.value = newId
            _commands.tryEmit(WebViewCommand.LoadUrl(newId, "browser://home"))
        }
    }

    fun openUrlInBrowser(url: String) {
        setDramaModeActive(false)
        val formattedUrl = formatUrl(url)
        val currentTabs = _browserTabs.value
        val activeId = _browserSelectedTabId.value
        
        val targetTabId: String
        if (currentTabs.isNotEmpty()) {
            val targetTab = currentTabs.find { it.id == activeId } ?: currentTabs.last()
            targetTabId = targetTab.id
            val updatedTabs = currentTabs.map { tab ->
                if (tab.id == targetTabId) {
                    tab.copy(url = formattedUrl, title = url)
                } else {
                    tab
                }
            }
            _browserTabs.value = updatedTabs
            _browserSelectedTabId.value = targetTabId
        } else {
            val newId = UUID.randomUUID().toString()
            targetTabId = newId
            val newTab = BrowserTab(id = newId, url = formattedUrl, title = url)
            _browserTabs.value = listOf(newTab)
            _browserSelectedTabId.value = newId
        }
        
        _commands.tryEmit(WebViewCommand.LoadUrl(targetTabId, formattedUrl))
    }

    // Multi-tab actions
    fun createNewTab(url: String = "browser://home") {
        val newId = UUID.randomUUID().toString()
        val defaultUrl = if (_isDramaModeActive.value) SECONDARY_URL else "browser://home"
        val targetUrl = if (url == "browser://home") defaultUrl else url
        val formattedUrl = if (targetUrl == "browser://home") "browser://home" else formatUrl(targetUrl)
        val rawTitle = if (targetUrl == "browser://home") "Home" else if (targetUrl == SECONDARY_URL) "Able Drama" else "Google"
        val newTab = BrowserTab(id = newId, url = formattedUrl, title = rawTitle)
        val activeList = getActiveList()
        val activeSelectedId = getActiveSelectedId()
        activeList.value = activeList.value + newTab
        activeSelectedId.value = newId
    }

    fun selectTab(tabId: String) {
        val activeList = getActiveList()
        val activeSelectedId = getActiveSelectedId()
        if (activeList.value.any { it.id == tabId }) {
            activeSelectedId.value = tabId
        }
    }

    fun closeTab(tabId: String) {
        val activeList = getActiveList()
        val activeSelectedId = getActiveSelectedId()
        val currentList = activeList.value
        
        if (currentList.size <= 1) {
            val newId = UUID.randomUUID().toString()
            val fallbackUrl = if (_isDramaModeActive.value) SECONDARY_URL else "browser://home"
            val fallbackTitle = if (_isDramaModeActive.value) "Able Drama" else "Home"
            activeList.value = listOf(BrowserTab(id = newId, url = fallbackUrl, title = fallbackTitle))
            activeSelectedId.value = newId
            return
        }

        val remainingTabs = currentList.filter { it.id != tabId }
        activeList.value = remainingTabs

        if (activeSelectedId.value == tabId) {
            activeSelectedId.value = remainingTabs.last().id
        }
    }

    fun updateCurrentState(url: String, title: String) {
        val activeId = if (_isDramaModeActive.value) _dramaSelectedTabId.value else _browserSelectedTabId.value
        updateCurrentState(activeId, url, title)
    }

    fun updateCurrentState(tabId: String, url: String, title: String) {
        updateCurrentStateWithHistory(tabId, url, title, null)
    }

    fun updateCurrentStateWithHistory(tabId: String, url: String, title: String, thumbnailUrl: String? = null) {
        if (_dramaTabs.value.any { it.id == tabId }) {
            _dramaTabs.value = _dramaTabs.value.map { tab ->
                if (tab.id == tabId) {
                    val cleanTitle = if (title.isNotBlank() && !title.startsWith("http")) title else tab.title
                    tab.copy(url = url, title = cleanTitle)
                } else {
                    tab
                }
            }
            if (_dramaSelectedTabId.value == tabId && url.isNotBlank()) {
                viewModelScope.launch {
                    val cleanTitle = title.ifBlank { url }
                    if (shouldRecordDramaHistory(url)) {
                        val dramaTitle = getDramaSectionTitle(url, cleanTitle)
                        repository.addHistory(url, dramaTitle, isBrowser = false, thumbnailUrl = thumbnailUrl)
                    }
                }
            }
        } else if (_browserTabs.value.any { it.id == tabId }) {
            _browserTabs.value = _browserTabs.value.map { tab ->
                if (tab.id == tabId) {
                    val cleanTitle = if (title.isNotBlank() && !title.startsWith("http")) title else tab.title
                    tab.copy(url = url, title = cleanTitle)
                } else {
                    tab
                }
            }
            if (_browserSelectedTabId.value == tabId && url.isNotBlank()) {
                viewModelScope.launch {
                    val cleanTitle = title.ifBlank { url }
                    repository.addHistory(url, cleanTitle, isBrowser = true, thumbnailUrl = thumbnailUrl)
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
        val activeId = if (_isDramaModeActive.value) _dramaSelectedTabId.value else _browserSelectedTabId.value
        updateLoadingStatus(activeId, loading, progressPercent)
    }

    fun updateLoadingStatus(tabId: String, loading: Boolean, progressPercent: Int) {
        if (_dramaTabs.value.any { it.id == tabId }) {
            _dramaTabs.value = _dramaTabs.value.map { tab ->
                if (tab.id == tabId) tab.copy(isLoading = loading, progress = progressPercent) else tab
            }
        } else if (_browserTabs.value.any { it.id == tabId }) {
            _browserTabs.value = _browserTabs.value.map { tab ->
                if (tab.id == tabId) tab.copy(isLoading = loading, progress = progressPercent) else tab
            }
        }
    }

    fun updateWebThemeColor(color: String?) {
        val activeId = if (_isDramaModeActive.value) _dramaSelectedTabId.value else _browserSelectedTabId.value
        updateWebThemeColor(activeId, color)
    }

    fun updateWebThemeColor(tabId: String, color: String?) {
        if (_dramaTabs.value.any { it.id == tabId }) {
            _dramaTabs.value = _dramaTabs.value.map { tab ->
                if (tab.id == tabId) tab.copy(webThemeColor = color) else tab
            }
        } else if (_browserTabs.value.any { it.id == tabId }) {
            _browserTabs.value = _browserTabs.value.map { tab ->
                if (tab.id == tabId) tab.copy(webThemeColor = color) else tab
            }
        }
    }

    fun updateNavigationCapabilities(back: Boolean, forward: Boolean) {
        val activeId = if (_isDramaModeActive.value) _dramaSelectedTabId.value else _browserSelectedTabId.value
        updateNavigationCapabilities(activeId, back, forward)
    }

    fun updateNavigationCapabilities(tabId: String, back: Boolean, forward: Boolean) {
        if (_dramaTabs.value.any { it.id == tabId }) {
            _dramaTabs.value = _dramaTabs.value.map { tab ->
                if (tab.id == tabId) tab.copy(canGoBack = back, canGoForward = forward) else tab
            }
        } else if (_browserTabs.value.any { it.id == tabId }) {
            _browserTabs.value = _browserTabs.value.map { tab ->
                if (tab.id == tabId) tab.copy(canGoBack = back, canGoForward = forward) else tab
            }
        }
    }

    fun updateTabScreenshot(tabId: String, bitmap: android.graphics.Bitmap?) {
        if (_dramaTabs.value.any { it.id == tabId }) {
            _dramaTabs.value = _dramaTabs.value.map { tab ->
                if (tab.id == tabId) tab.copy(screenshot = bitmap) else tab
            }
        } else if (_browserTabs.value.any { it.id == tabId }) {
            _browserTabs.value = _browserTabs.value.map { tab ->
                if (tab.id == tabId) tab.copy(screenshot = bitmap) else tab
            }
        }
    }

    // UI actions
    fun loadUrl(url: String) {
        val trimmed = url.trim()
        val isWebSearch = trimmed.isNotEmpty() && 
                          !trimmed.contains("://") && 
                          !trimmed.startsWith("about:") && 
                          !trimmed.startsWith("browser:") && 
                          (trimmed.contains(" ") || trimmed.contains("\n") || 
                           !(trimmed.contains(".") && !trimmed.startsWith(".") && !trimmed.endsWith(".")))
        
        if (isWebSearch) {
            saveCompletedSearch(trimmed)
        }

        val formattedUrl = formatUrl(url)
        val activeId = if (_isDramaModeActive.value) _dramaSelectedTabId.value else _browserSelectedTabId.value
        if (formattedUrl.startsWith("browser://", ignoreCase = true) || formattedUrl.isBlank() || formattedUrl == "about:blank") {
            clearDetectedResources(activeId)
        }
        val activeList = getActiveList()
        activeList.value = activeList.value.map { tab ->
            if (tab.id == activeId) {
                tab.copy(url = formattedUrl)
            } else {
                tab
            }
        }
        _commands.tryEmit(WebViewCommand.LoadUrl(activeId, formattedUrl))
    }

    fun goHome() {
        val activeId = if (_isDramaModeActive.value) _dramaSelectedTabId.value else _browserSelectedTabId.value
        _commands.tryEmit(WebViewCommand.LoadUrl(activeId, "https://www.abledrama.top"))
    }

    fun goBack() {
        val activeId = if (_isDramaModeActive.value) _dramaSelectedTabId.value else _browserSelectedTabId.value
        _commands.tryEmit(WebViewCommand.GoBack(activeId))
    }

    fun goForward() {
        val activeId = if (_isDramaModeActive.value) _dramaSelectedTabId.value else _browserSelectedTabId.value
        _commands.tryEmit(WebViewCommand.GoForward(activeId))
    }

    fun reload() {
        val activeId = if (_isDramaModeActive.value) _dramaSelectedTabId.value else _browserSelectedTabId.value
        _commands.tryEmit(WebViewCommand.Reload(activeId))
    }

    fun toggleBookmark() {
        val url = currentUrl.value
        val title = currentTitle.value
        val formattedUrl = formatUrl(url)
        val isDrama = _isDramaModeActive.value
        
        if (isDrama) {
            val currentlyBookmarked = isCurrentUrlCustomBookmarked.value
            _optimisticCustomBookmarks.value = _optimisticCustomBookmarks.value + (formattedUrl to !currentlyBookmarked)
            viewModelScope.launch {
                if (currentlyBookmarked) {
                    repository.removeBookmark(formattedUrl, isBrowser = false)
                } else {
                    repository.addBookmark(formattedUrl, title, isBrowser = false)
                }
            }
        } else {
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

    // Search Suggestions and Search History logic
    private val _searchSuggestions = MutableStateFlow<List<SearchSuggestionItem>>(emptyList())
    val searchSuggestions: StateFlow<List<SearchSuggestionItem>> = _searchSuggestions.asStateFlow()

    private val _currentSearchInput = MutableStateFlow("")
    val currentSearchInput: StateFlow<String> = _currentSearchInput.asStateFlow()

    private var searchJob: kotlinx.coroutines.Job? = null

    fun updateSearchInput(query: String) {
        _currentSearchInput.value = query
        searchJob?.cancel()
        if (query.trim().isBlank()) {
            loadRecentHistorySuggestions()
            return
        }
        searchJob = viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            kotlinx.coroutines.delay(80)
            val suggestions = generateSuggestions(query)
            _searchSuggestions.value = suggestions
        }
    }

    fun loadRecentHistorySuggestions() {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val finalSuggestions = mutableListOf<SearchSuggestionItem>()
                val seenTexts = mutableSetOf<String>()

                fun addSuggestion(item: SearchSuggestionItem) {
                    val key = item.text.trim().lowercase()
                    if (key.isNotEmpty() && !seenTexts.contains(key)) {
                        seenTexts.add(key)
                        finalSuggestions.add(item)
                    }
                }

                // 1. Mapped history search (recent 7)
                try {
                    val list = repository.searchHistory.first()
                    list.take(7).forEach {
                        addSuggestion(
                            SearchSuggestionItem(
                                id = it.id,
                                text = it.query,
                                subText = "Recent search",
                                type = SuggestionType.HISTORY_SEARCH
                            )
                        )
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }

                // 2. Mapped popular websites (8 default sites)
                val popularPresets = listOf(
                    Pair("google.com", "Google - Search Engine"),
                    Pair("youtube.com", "YouTube - Videos & Music"),
                    Pair("facebook.com", "Facebook - Social Media"),
                    Pair("wikipedia.org", "Wikipedia - Free Encyclopedia"),
                    Pair("github.com", "GitHub - Developer Platform"),
                    Pair("chatgpt.com", "ChatGPT - AI Assistant"),
                    Pair("toffeelive.com", "Toffee - Live TV & Sports"),
                    Pair("chorki.com", "Chorki - Bengali Drama, Movie, Series")
                )
                popularPresets.forEach { (url, title) ->
                    addSuggestion(
                        SearchSuggestionItem(
                            text = url,
                            subText = title,
                            type = SuggestionType.BROWSER_HISTORY
                        )
                    )
                }

                _searchSuggestions.value = finalSuggestions.take(15)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun saveCompletedSearch(query: String) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val trimmed = query.trim()
            if (trimmed.isNotEmpty() && !trimmed.startsWith("http://") && !trimmed.startsWith("https://") && !trimmed.startsWith("browser://")) {
                repository.addSearchQuery(trimmed)
            }
        }
    }

    fun deleteSearchQuery(id: Int) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            repository.deleteSearchQuery(id)
            if (_currentSearchInput.value.isBlank()) {
                val list = repository.searchHistory.first()
                _searchSuggestions.value = list.map {
                    SearchSuggestionItem(
                        id = it.id,
                        text = it.query,
                        subText = "Recent search",
                        type = SuggestionType.HISTORY_SEARCH
                    )
                }
            } else {
                updateSearchInput(_currentSearchInput.value)
            }
        }
    }

    fun deleteSearchQueryText(query: String) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            repository.deleteSearchQueryText(query)
            if (_currentSearchInput.value.isBlank()) {
                val list = repository.searchHistory.first()
                _searchSuggestions.value = list.map {
                    SearchSuggestionItem(
                        id = it.id,
                        text = it.query,
                        subText = "Recent search",
                        type = SuggestionType.HISTORY_SEARCH
                    )
                }
            } else {
                updateSearchInput(_currentSearchInput.value)
            }
        }
    }

    fun clearSearchQueryHistory() {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            repository.clearSearchQueryHistory()
            _searchSuggestions.value = emptyList()
        }
    }

    private suspend fun generateSuggestions(query: String): List<SearchSuggestionItem> {
        if (query.trim().isBlank()) return emptyList()
        val trimmed = query.trim()
        val trimmedLower = trimmed.lowercase()

        val finalSuggestions = mutableListOf<SearchSuggestionItem>()
        val seenTexts = mutableSetOf<String>()

        fun addSuggestion(item: SearchSuggestionItem) {
            val key = item.text.trim().lowercase()
            if (key.isNotEmpty() && !seenTexts.contains(key)) {
                seenTexts.add(key)
                finalSuggestions.add(item)
            }
        }

        // 1. Direct typed search query suggestion
        addSuggestion(
            SearchSuggestionItem(
                text = trimmed,
                subText = "Search Google for \"$trimmed\"",
                type = SuggestionType.RELATED_SUGGEST
            )
        )

        // 2. Matching search history in database
        try {
            val searchHistorySnapshot = repository.searchHistory.first()
            val matchingHistory = searchHistorySnapshot.filter { 
                it.query.lowercase().contains(trimmedLower) 
            }.sortedWith(compareByDescending<com.example.data.SearchQueryHistory> { it.useCount }
                .thenByDescending { it.timestamp })
            
            matchingHistory.take(5).forEach {
                addSuggestion(
                    SearchSuggestionItem(
                        id = it.id,
                        text = it.query,
                        subText = "History search",
                        type = SuggestionType.HISTORY_SEARCH
                    )
                )
            }
        } catch (e: Exception) {
            android.util.Log.e("BrowserViewModel", "Error matching local searches", e)
        }

        // 3. Matching local browser history Items
        try {
            val browserHistorySnapshot = repository.browserHistory.first()
            val matchingUrls = browserHistorySnapshot.filter {
                it.url.lowercase().contains(trimmedLower) || it.title.lowercase().contains(trimmedLower)
            }.distinctBy { it.url }

            matchingUrls.take(5).forEach {
                addSuggestion(
                    SearchSuggestionItem(
                        text = it.url,
                        subText = it.title,
                        type = SuggestionType.BROWSER_HISTORY
                    )
                )
            }
        } catch (e: Exception) {
            android.util.Log.e("BrowserViewModel", "Error matching local browser history", e)
        }

        // 4. Highlighted popular preset websites matching query (Frequently Visited Websites)
        val popularPresets = listOf(
            Pair("google.com", "Google - Search Engine"),
            Pair("youtube.com", "YouTube - Videos & Music"),
            Pair("facebook.com", "Facebook - Social Media"),
            Pair("wikipedia.org", "Wikipedia - Free Encyclopedia"),
            Pair("github.com", "GitHub - Developer Platform"),
            Pair("chatgpt.com", "ChatGPT - AI Assistant"),
            Pair("gmail.com", "Gmail - Google Mail"),
            Pair("toffeelive.com", "Toffee - Live TV & Sports"),
            Pair("chorki.com", "Chorki - Bengali Drama, Movie, Series"),
            Pair("cricbuzz.com", "Cricbuzz - Live Cricket Scores"),
            Pair("bioscopelive.com", "Bioscope - Live TV & Natok"),
            Pair("prothomalo.com", "Prothom Alo - Bengali News"),
            Pair("yahoo.com", "Yahoo! Search"),
            Pair("netflix.com", "Netflix - Movies & TV Shows"),
            Pair("instagram.com", "Instagram - Social Net"),
            Pair("twitter.com", "Twitter / X")
        )

        popularPresets.filter {
            it.first.lowercase().contains(trimmedLower) || it.second.lowercase().contains(trimmedLower)
        }.take(5).forEach { (url, title) ->
            addSuggestion(
                SearchSuggestionItem(
                    text = url,
                    subText = title,
                    type = SuggestionType.BROWSER_HISTORY
                )
            )
        }

        // 5. Connect and fetch Google Auto-Suggest matches live
        try {
            val urlString = "https://suggestqueries.google.com/complete/search?client=chrome&q=" + java.net.URLEncoder.encode(trimmed, "UTF-8")
            val url = java.net.URL(urlString)
            val conn = url.openConnection() as java.net.HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 800
            conn.readTimeout = 800
            
            val response = conn.inputStream.bufferedReader().use { it.readText() }
            val jsonArray = org.json.JSONArray(response)
            if (jsonArray.length() > 1) {
                val suggestionsArray = jsonArray.getJSONArray(1)
                for (i in 0 until suggestionsArray.length()) {
                    val s = suggestionsArray.getString(i)
                    addSuggestion(
                        SearchSuggestionItem(
                            text = s,
                            subText = "Search suggestion",
                            type = SuggestionType.RELATED_SUGGEST
                        )
                    )
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("BrowserViewModel", "Error fetching live suggestions", e)
        }

        // 6. Local curated Trending list matcher
        val trendingPresets = listOf(
            "Cricket Live Score",
            "Bangladesh Weather Today",
            "Latest Bangla Natok",
            "Best AI Tools",
            "Chorki Web Series",
            "Toffee Drama",
            "AI Video Generator",
            "Free AI Chatbot",
            "Bioscope Drama Live",
            "Today News Bangladesh",
            "YouTube Music",
            "Google Translate",
            "Facebook Login",
            "Cricbuzz Scorecard",
            "Chat GPT Online"
        )

        trendingPresets.filter {
            it.lowercase().contains(trimmedLower)
        }.take(5).forEach { topic ->
            addSuggestion(
                SearchSuggestionItem(
                    text = topic,
                    subText = "Trending topic",
                    type = SuggestionType.RELATED_SUGGEST
                )
            )
        }

        // Mix back-up trending topics if user's list size is still less than 12
        if (finalSuggestions.size < 12) {
            trendingPresets.take(15 - finalSuggestions.size).forEach { topic ->
                addSuggestion(
                    SearchSuggestionItem(
                        text = topic,
                        subText = "Trending search",
                        type = SuggestionType.RELATED_SUGGEST
                    )
                )
            }
        }

        return finalSuggestions.take(15)
    }

    fun getCleanActiveVideoTitle(tabId: String): String {
        var title = _activeVideoTitleMap.value[tabId] ?: ""
        
        if (title.isBlank() || isGenericTitle(title)) {
            val activeTab = _browserTabs.value.find { it.id == tabId } ?: _dramaTabs.value.find { it.id == tabId }
            title = activeTab?.title ?: ""
        }
        
        if (title.isBlank() || isGenericTitle(title)) {
            title = "Video Playback"
        }
        
        return title
            .replace(" - YouTube", "", ignoreCase = true)
            .replace(" | Facebook", "", ignoreCase = true)
            .replace(" - TikTok", "", ignoreCase = true)
            .replace(" - Instagram", "", ignoreCase = true)
            .replace(" - Watch", "", ignoreCase = true)
            .replace(" | Twitter", "", ignoreCase = true)
            .replace(" on X", "", ignoreCase = true)
            .replace("[Facebook]", "", ignoreCase = true)
            .replace("Video Element", "Video", ignoreCase = true)
            .replace("ytd-watch-metadata", "", ignoreCase = true)
            .trim()
    }
}

// Search Suggestions representation data classes
data class SearchSuggestionItem(
    val id: Int = 0,
    val text: String,
    val subText: String? = null,
    val type: SuggestionType
)

enum class SuggestionType {
    HISTORY_SEARCH,  // previous searches
    RELATED_SUGGEST, // from Google complete search api
    BROWSER_HISTORY  // matching actual URLs visited
}

class BrowserViewModelFactory(
    private val repository: BrowserRepository,
    private val context: android.content.Context
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(BrowserViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return BrowserViewModel(repository, context) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
