package com.example

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import kotlinx.coroutines.delay
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.Manifest
import android.os.Build
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import coil.compose.AsyncImage
import androidx.compose.animation.*
import androidx.compose.foundation.Image
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.zIndex
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.room.Room
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.graphics.asImageBitmap
import com.example.data.AppDatabase
import com.example.data.Bookmark
import com.example.data.BrowserRepository
import com.example.data.HistoryItem
import com.example.data.DownloadRepository
import com.example.ui.BrowserViewModel
import com.example.ui.BrowserViewModelFactory
import com.example.ui.BrowserTab
import com.example.ui.components.AdvancedWebView
import com.example.ui.components.DownloadFileDialog
import com.example.ui.components.DownloadManagerDialog
import com.example.util.DownloadEngine
import androidx.compose.ui.text.TextStyle
import com.example.ui.theme.*
import androidx.compose.ui.graphics.graphicsLayer
import com.example.util.NetworkMonitor
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun Context.getClipboardManager(): ClipboardManager? {
    val attributionContext = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
        try {
            this.createAttributionContext("abledrama-attribution")
        } catch (t: Throwable) {
            this
        }
    } else {
        this
    }
    return attributionContext.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
}

class MainActivity : ComponentActivity() {

    override fun getAttributionTag(): String? {
        return "abledrama-attribution"
    }

    private var clipboardObserver: ClipboardObserver? = null

    companion object {
        var isAbleDramaActive: Boolean = true
    }

    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private var fullscreenContainer: FrameLayout? = null
    private var browserViewModel: BrowserViewModel? = null

    // Clean, robust, lifecycle-aware element responsible for background/active clipboard change redirects
    class ClipboardObserver(
        private val activity: MainActivity,
        private val onClipboardChanged: (String) -> Unit
    ) : LifecycleEventObserver {
        private val handler = android.os.Handler(android.os.Looper.getMainLooper())
        private var isResumed = false

        private val checkClipboardRunnable = Runnable { checkClipboard() }

        private val listener = ClipboardManager.OnPrimaryClipChangedListener {
            handler.removeCallbacks(checkClipboardRunnable)
            handler.postDelayed(checkClipboardRunnable, 500)
        }

        fun triggerCheck(delayMs: Long = 500) {
            handler.removeCallbacks(checkClipboardRunnable)
            if (delayMs > 0) {
                handler.postDelayed(checkClipboardRunnable, delayMs)
            } else {
                handler.post(checkClipboardRunnable)
            }
        }

        override fun onStateChanged(source: LifecycleOwner, event: Lifecycle.Event) {
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    isResumed = true
                    try {
                        val clipboard = activity.getClipboardManager()
                        clipboard?.addPrimaryClipChangedListener(listener)
                    } catch (t: Throwable) {
                        android.util.Log.e("ClipboardObserver", "Failed to add primary clip listener", t)
                    }
                }
                Lifecycle.Event.ON_PAUSE -> {
                    isResumed = false
                    try {
                        val clipboard = activity.getClipboardManager()
                        clipboard?.removePrimaryClipChangedListener(listener)
                        handler.removeCallbacks(checkClipboardRunnable)
                    } catch (t: Throwable) {
                        android.util.Log.e("ClipboardObserver", "Failed to remove primary clip listener", t)
                    }
                }
                Lifecycle.Event.ON_DESTROY -> {
                    handler.removeCallbacks(checkClipboardRunnable)
                    source.lifecycle.removeObserver(this)
                }
                else -> {}
            }
        }

        private fun checkClipboard() {
            if (!isResumed) return
            if (!activity.hasWindowFocus()) return
            if (!MainActivity.isAbleDramaActive) return

            try {
                val clipboard = activity.getClipboardManager() ?: return
                if (!clipboard.hasPrimaryClip()) return
                val clipData = try {
                    clipboard.primaryClip
                } catch (t: Throwable) {
                    null
                } ?: return

                if (clipData.itemCount > 0) {
                    val firstItem = try {
                        clipData.getItemAt(0)
                    } catch (t: Throwable) {
                        null
                    }
                    val copiedText = firstItem?.text?.toString()?.trim()
                    if (!copiedText.isNullOrEmpty()) {
                        val urlCandidate = activity.extractUrl(copiedText)
                        if (urlCandidate != null) {
                            onClipboardChanged(urlCandidate)
                        }
                    }
                }
            } catch (t: Throwable) {
                android.util.Log.e("ClipboardObserver", "Error in clipboard check", t)
            }
        }
    }

    fun extractUrl(text: String): String? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null

        // 1. Check for standard HTTP/HTTPS URLs anywhere in the text using a strict regex
        val httpRegex = "(?i)\\bhttps?://\\S+".toRegex()
        val matchHttp = httpRegex.find(trimmed)
        if (matchHttp != null) {
            val matched = matchHttp.value
            val clean = matched.removeSuffix(".").removeSuffix(",").removeSuffix("?").removeSuffix("!").removeSuffix(")")
            return clean
        }

        // 2. Check for www. domains anywhere in the text
        val wwwRegex = "(?i)\\bwww\\.\\S+".toRegex()
        val matchWww = wwwRegex.find(trimmed)
        if (matchWww != null) {
            val matched = matchWww.value
            val clean = matched.removeSuffix(".").removeSuffix(",").removeSuffix("?").removeSuffix("!").removeSuffix(")")
            return "https://$clean"
        }

        // 3. Scan each word to find plain domain names without protocol, e.g. abledrama.top, google.com
        val words = trimmed.split("\\s+".toRegex())
        val domainRegex = "^(?i)[a-zA-Z0-9][-a-zA-Z0-9._]*\\.[a-zA-Z]{2,6}(/\\S*)?$".toRegex()
        val invalidExtensions = setOf("txt", "png", "jpg", "jpeg", "gif", "mp4", "mp3", "pdf", "zip", "apk", "xml", "json")
        for (word in words) {
            val cleanWord = word.removeSuffix(".").removeSuffix(",").removeSuffix("?").removeSuffix("!").removeSuffix(")").trim()
            if (domainRegex.matches(cleanWord)) {
                val suffix = cleanWord.substringBefore("/").substringAfterLast(".").lowercase()
                if (suffix in invalidExtensions) continue
                
                val partBeforeDot = cleanWord.substringBefore(".")
                if (partBeforeDot.all { it.isDigit() } && suffix.all { it.isDigit() }) {
                    continue
                }
                
                return "https://$cleanWord"
            }
        }
        
        return null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Pre-create WebView cache/code cache directories to prevent Chromium readdir/file info errors
        try {
            val webViewCacheJs = java.io.File(applicationContext.cacheDir, "WebView/Default/HTTP Cache/Code Cache/js")
            if (!webViewCacheJs.exists()) {
                webViewCacheJs.mkdirs()
            }
            val webViewCacheWasm = java.io.File(applicationContext.cacheDir, "WebView/Default/HTTP Cache/Code Cache/wasm")
            if (!webViewCacheWasm.exists()) {
                webViewCacheWasm.mkdirs()
            }
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "Failed to pre-create WebView cache directories", e)
        }

        // Build Local Room database instance
        val db = AppDatabase.getDatabase(applicationContext)

        val repository = BrowserRepository(db.browserDao())
        val downloadRepository = DownloadRepository(db.downloadDao())
        DownloadEngine.init(applicationContext, downloadRepository)
        val networkMonitor = NetworkMonitor(applicationContext)

        // Pre-create/retrieve the BrowserViewModel so that the Activity can access it for clipboard redirection
        val viewModelFactory = BrowserViewModelFactory(repository, applicationContext)
        browserViewModel = androidx.lifecycle.ViewModelProvider(this, viewModelFactory)[BrowserViewModel::class.java]

        // Register our specialized lifecycle-aware ClipboardObserver for redirection
        val observer = ClipboardObserver(this) { urlCandidate ->
            android.util.Log.d("ClipboardRedirection", "Redirecting internally to URL: $urlCandidate")
            runOnUiThread {
                Toast.makeText(this@MainActivity, "Opening copied link...", Toast.LENGTH_SHORT).show()
                browserViewModel?.openUrlInBrowser(urlCandidate)
                browserViewModel?.triggerOpenBrowser()
            }
        }
        clipboardObserver = observer
        lifecycle.addObserver(observer)

        setContent {
            val context = LocalContext.current
            val prefs = remember { context.getSharedPreferences("abledrama_prefs", Context.MODE_PRIVATE) }

            // Notification Permission Handling
            val permissionLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.RequestPermission()
            ) { isGranted ->
                prefs.edit().putBoolean("asked_notification_permission", true).apply()
            }

            var showPermissionPrompt by remember {
                mutableStateOf(
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    !prefs.getBoolean("asked_notification_permission", false) &&
                    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                )
            }

            if (showPermissionPrompt) {
                AlertDialog(
                    onDismissRequest = {
                        prefs.edit().putBoolean("asked_notification_permission", true).apply()
                        showPermissionPrompt = false
                    },
                    title = { Text("Notification Permission") },
                    text = { Text("Allow notifications for download progress and background downloads?") },
                    confirmButton = {
                        Button(
                            onClick = {
                                prefs.edit().putBoolean("asked_notification_permission", true).apply()
                                showPermissionPrompt = false
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                    permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                }
                            }
                        ) {
                            Text("Allow")
                        }
                    },
                    dismissButton = {
                        TextButton(
                            onClick = {
                                prefs.edit().putBoolean("asked_notification_permission", true).apply()
                                showPermissionPrompt = false
                            }
                        ) {
                            Text("Deny")
                        }
                    }
                )
            }

            var isDarkTheme by remember {
                mutableStateOf(prefs.getBoolean("is_dark_theme", true))
            }

            LaunchedEffect(isDarkTheme) {
                browserViewModel?.setDarkTheme(isDarkTheme)
            }

            MyApplicationTheme(darkTheme = isDarkTheme) {
                var showSplashScreen by remember { mutableStateOf(true) }

                LaunchedEffect(Unit) {
                    delay(1500L) // Elegant 1.5s splash loading screen
                    showSplashScreen = false
                }

                Crossfade(targetState = showSplashScreen, label = "SplashTransition") { isSplash ->
                    if (isSplash) {
                        SplashScreen(isDarkTheme = isDarkTheme)
                    } else {
                        // Track FullScreen video client components
                        var isHtmlVideoFullscreen by remember { mutableStateOf(false) }

                        Surface(
                            modifier = Modifier.fillMaxSize(),
                            color = MaterialTheme.colorScheme.background
                        ) {
                            if (isHtmlVideoFullscreen) {
                                // Display native player container taking full screens
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(Color.Black)
                                ) {
                                    AndroidView(
                                        factory = { ctx ->
                                            FrameLayout(ctx).apply {
                                                fullscreenContainer = this
                                                customView?.let { view ->
                                                    (view.parent as? ViewGroup)?.removeView(view)
                                                    addView(view)
                                                }
                                            }
                                        },
                                        modifier = Modifier.fillMaxSize()
                                    )
                                    
                                    // Exit fullscreen button floating top bar
                                    IconButton(
                                        onClick = {
                                            hideVideoFullscreen()
                                            isHtmlVideoFullscreen = false
                                        },
                                        modifier = Modifier
                                            .padding(safeDrawingPadding())
                                            .align(Alignment.TopEnd)
                                            .background(Color.Black.copy(alpha = 0.6f), CircleShape)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.FullscreenExit,
                                            contentDescription = "Exit Fullscreen",
                                            tint = Color.White
                                        )
                                    }
                                }
                            } else {
                                // Regular interactive view layers
                                MainAppContent(
                                    viewModel = browserViewModel!!,
                                    downloadRepository = downloadRepository,
                                    networkMonitor = networkMonitor,
                                    isDarkTheme = isDarkTheme,
                                    onToggleDarkTheme = {
                                        val nextTheme = !isDarkTheme
                                        isDarkTheme = nextTheme
                                        prefs.edit().putBoolean("is_dark_theme", nextTheme).apply()
                                    },
                                    onShowCustomView = { view, callback ->
                                        customView = view
                                        customViewCallback = callback
                                        isHtmlVideoFullscreen = true
                                    },
                                    onHideCustomView = {
                                        hideVideoFullscreen()
                                        isHtmlVideoFullscreen = false
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    private fun hideVideoFullscreen() {
        customView?.let {
            fullscreenContainer?.removeView(it)
        }
        customViewCallback?.onCustomViewHidden()
        customView = null
        customViewCallback = null
        fullscreenContainer = null
    }

    override fun onDestroy() {
        super.onDestroy()
        hideVideoFullscreen()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            clipboardObserver?.triggerCheck(500)
        }
    }
}

enum class AppTab {
    BROWSER,
    ACCOUNT
}

data class NavigationHistoryEntry(
    val tab: AppTab,
    val url: String = "",
    val showDownloadManagerDialog: Boolean = false,
    val showBrowserHistoryDialog: Boolean = false,
    val showAboutBrowserDialog: Boolean = false,
    val isDramaModeActive: Boolean = true
)

data class AbleDramaSavedState(
    val url: String,
    val title: String,
    val isPost: Boolean,
    val isCategory: Boolean,
    val isHome: Boolean
)

enum class BottomNavItem {
    MOVIES,
    DRAMA,
    WEB_SERIES,
    SHORT_DRAMA,
    ANIME,
    ACCOUNT
}

@Composable
fun MainAppContent(
    viewModel: BrowserViewModel,
    downloadRepository: DownloadRepository,
    networkMonitor: NetworkMonitor,
    isDarkTheme: Boolean,
    onToggleDarkTheme: () -> Unit,
    onShowCustomView: (View, WebChromeClient.CustomViewCallback) -> Unit,
    onHideCustomView: () -> Unit
) {
    val context = LocalContext.current

    // Navigation and history state list
    val navHistory = remember { mutableStateListOf<NavigationHistoryEntry>() }
    val dramaSaveStack = remember { mutableStateListOf<AbleDramaSavedState>() }
    var lastDramaUrl by remember { mutableStateOf("https://www.abledrama.top") }
    var lastDramaTitle by remember { mutableStateOf("Able Drama") }

    // Collect app states dynamically
    var currentTab by remember { mutableStateOf(AppTab.BROWSER) }
    var selectedBottomItem by remember { mutableStateOf<BottomNavItem?>(null) }
    var showUrlBar by remember { mutableStateOf(false) }
    val isOnline by networkMonitor.isOnline.collectAsStateWithLifecycle(initialValue = true)
    
    val currentUrl by viewModel.currentUrl.collectAsStateWithLifecycle()
    val currentTitle by viewModel.currentTitle.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val loadProgress by viewModel.progress.collectAsStateWithLifecycle()
    val isBookmarked by viewModel.isCurrentUrlBookmarked.collectAsStateWithLifecycle()
    val isCustomBookmarked by viewModel.isCurrentUrlCustomBookmarked.collectAsStateWithLifecycle()
    val canGoBack by viewModel.canGoBack.collectAsStateWithLifecycle()
    val canGoForward by viewModel.canGoForward.collectAsStateWithLifecycle()

    val bookmarks by viewModel.bookmarks.collectAsStateWithLifecycle()
    val history by viewModel.history.collectAsStateWithLifecycle()
    val browserBookmarks by viewModel.browserBookmarks.collectAsStateWithLifecycle()
    val browserHistory by viewModel.browserHistory.collectAsStateWithLifecycle()
    val tabsList by viewModel.tabs.collectAsStateWithLifecycle()
    val selectedTabId by viewModel.selectedTabId.collectAsStateWithLifecycle()
    val isDramaModeActive by viewModel.isDramaModeActive.collectAsStateWithLifecycle()
    val browserTabsList by viewModel.browserTabs.collectAsStateWithLifecycle()
    val browserSelectedTabId by viewModel.browserSelectedTabId.collectAsStateWithLifecycle()
    val dramaTabsList by viewModel.dramaTabs.collectAsStateWithLifecycle()
    val dramaSelectedTabId by viewModel.dramaSelectedTabId.collectAsStateWithLifecycle()
    val requestSearchFocus by viewModel.requestSearchFocus.collectAsStateWithLifecycle()
    val isDesktopModeEnabled by viewModel.isDesktopModeEnabled.collectAsStateWithLifecycle()
    val urlBarFocusRequester = remember { FocusRequester() }

    var isTabGridVisible by remember { mutableStateOf(false) }

    var inputUrl by remember { mutableStateOf(currentUrl) }
    val focusManager = LocalFocusManager.current

    val coroutineScope = rememberCoroutineScope()

    var showDownloadFileDialog by remember { mutableStateOf(false) }
    var downloadPendingUrl by remember { mutableStateOf("") }
    var downloadPendingName by remember { mutableStateOf("") }
    var showDownloadManagerDialog by remember { mutableStateOf(false) }
    var showBrowserHistoryDialog by remember { mutableStateOf(false) }
    var showAboutBrowserDialog by remember { mutableStateOf(false) }
    var showTelegramDialog by remember { mutableStateOf(false) }
    var showExitDialog by remember { mutableStateOf(false) }
    val currentDetectedResources by viewModel.currentDetectedResources.collectAsStateWithLifecycle()
    val isVideoPlaying by viewModel.isVideoPlaying.collectAsStateWithLifecycle()
    val activeVideoDurationMap by viewModel.activeVideoDurationMap.collectAsStateWithLifecycle()
    val activeDuration = activeVideoDurationMap[selectedTabId] ?: 0.0
    var showDetectedResourcesSheet by remember { mutableStateOf(false) }

    fun pushToHistory() {
        val entry = NavigationHistoryEntry(
            tab = currentTab,
            url = currentUrl,
            showDownloadManagerDialog = showDownloadManagerDialog,
            showBrowserHistoryDialog = showBrowserHistoryDialog,
            showAboutBrowserDialog = showAboutBrowserDialog,
            isDramaModeActive = viewModel.isDramaModeActive.value
        )
        val last = navHistory.lastOrNull()
        if (last == null || last != entry) {
            navHistory.add(entry)
            android.util.Log.d("NavHistory", "Pushed state; stack size: ${navHistory.size}, entry: $entry")
        }
    }

    fun popHistory(): Boolean {
        if (navHistory.isNotEmpty()) {
            val entry = navHistory.removeAt(navHistory.size - 1)
            android.util.Log.d("NavHistory", "Popping state; stack size: ${navHistory.size}, restoring entry: $entry")
            
            // If the popped or restored state is Drama mode, force AppTab.BROWSER to completely bypass Main Menu
            if (entry.isDramaModeActive) {
                currentTab = AppTab.BROWSER
                viewModel.setDramaModeActive(true)
                showUrlBar = false
                
                val restoredState = if (dramaSaveStack.isNotEmpty()) {
                    dramaSaveStack.removeAt(dramaSaveStack.size - 1)
                } else {
                    null
                }
                
                val targetUrl = restoredState?.url ?: entry.url.ifEmpty { lastDramaUrl }
                if (targetUrl.isNotEmpty() && targetUrl != currentUrl) {
                    viewModel.loadUrl(targetUrl)
                }
            } else {
                currentTab = entry.tab
                viewModel.setDramaModeActive(entry.isDramaModeActive)
                if (entry.tab == AppTab.BROWSER && entry.url.isNotEmpty() && entry.url != currentUrl) {
                    viewModel.loadUrl(entry.url)
                }
            }
            
            showDownloadManagerDialog = entry.showDownloadManagerDialog
            showBrowserHistoryDialog = entry.showBrowserHistoryDialog
            showAboutBrowserDialog = entry.showAboutBrowserDialog
            return true
        }
        return false
    }

    fun exitAndBackToHome() {
        val restoredState = if (dramaSaveStack.isNotEmpty()) {
            dramaSaveStack.removeAt(dramaSaveStack.size - 1)
        } else {
            null
        }
        
        android.util.Log.d("NavHistory", "Exit to home; restored drama state: $restoredState")
        
        // Enforce the Main Menu is completely bypassed on return
        currentTab = AppTab.BROWSER
        viewModel.setDramaModeActive(true)
        showUrlBar = false
        
        // Clear all Main Menu entries from standard navigation history so back clicks bypass it too
        navHistory.removeAll { it.tab == AppTab.ACCOUNT }
        
        // Return directly with zero reload and zero scroll resetting if it's already on that URL
        val targetUrl = restoredState?.url ?: lastDramaUrl
        if (targetUrl.isNotEmpty() && targetUrl != currentUrl) {
            viewModel.loadUrl(targetUrl)
        }
        
        showDownloadManagerDialog = false
        showBrowserHistoryDialog = false
        showAboutBrowserDialog = false
    }

    // Graceful back handling for all tabs, views, and overlays
    androidx.activity.compose.BackHandler(enabled = true) {
        when {
            showDownloadFileDialog -> {
                showDownloadFileDialog = false
            }
            showDownloadManagerDialog -> {
                if (!popHistory()) {
                    showDownloadManagerDialog = false
                }
            }
            showBrowserHistoryDialog -> {
                if (!popHistory()) {
                    showBrowserHistoryDialog = false
                }
            }
            showAboutBrowserDialog -> {
                if (!popHistory()) {
                    showAboutBrowserDialog = false
                }
            }
            showTelegramDialog -> {
                showTelegramDialog = false
            }
            currentTab == AppTab.BROWSER && canGoBack -> {
                viewModel.goBack()
            }
            else -> {
                if (!popHistory()) {
                    val isAtHome = (currentUrl == "browser://home" || 
                                    currentUrl.isBlank() || 
                                    currentUrl.trim().trimEnd('/') == "https://www.abledrama.top" || 
                                    currentUrl.trim().trimEnd('/') == "https://abledrama.top")
                    
                    if (currentTab != AppTab.BROWSER) {
                        pushToHistory()
                        currentTab = AppTab.BROWSER
                        showUrlBar = false
                    } else if (!isAtHome) {
                        viewModel.loadUrl("browser://home")
                        showUrlBar = false
                    } else {
                        showExitDialog = true
                    }
                }
            }
        }
    }

    LaunchedEffect(currentTab, showUrlBar) {
        MainActivity.isAbleDramaActive = (showUrlBar == false || currentTab == AppTab.ACCOUNT)
        android.util.Log.d("ClipboardRedirection", "Updated isAbleDramaActive: ${MainActivity.isAbleDramaActive}")
    }

    // Bookmark overlay state and auto-hide timer for post URLs
    var isBookmarkOverlayVisible by remember { mutableStateOf(false) }
    var bookmarkTimerTrigger by remember { mutableIntStateOf(0) }
    val isCurrentUrlAPost = remember(currentUrl, isDramaModeActive) { isPostUrl(currentUrl) && isDramaModeActive }

    LaunchedEffect(currentUrl, isDramaModeActive) {
        if (isDramaModeActive && isPostUrl(currentUrl)) {
            isBookmarkOverlayVisible = true
            bookmarkTimerTrigger++
        } else {
            isBookmarkOverlayVisible = false
        }
    }

    LaunchedEffect(isBookmarkOverlayVisible, bookmarkTimerTrigger) {
        if (isBookmarkOverlayVisible) {
            delay(10000L) // Auto hide after 10 seconds
            isBookmarkOverlayVisible = false
        }
    }


    LaunchedEffect(viewModel) {
        viewModel.openBrowserTrigger.collect {
            if (viewModel.isDramaModeActive.value || currentTab == AppTab.ACCOUNT) {
                pushToHistory()
            }
            currentTab = AppTab.BROWSER
            showUrlBar = true
        }
    }

    // Telegram VIP promotional campaign pop-up manager (triggers once per app launch session)
    var hasShownTelegramThisSession by rememberSaveable { mutableStateOf(false) }
    var telegramCountdown by remember { mutableIntStateOf(5) }

    LaunchedEffect(Unit) {
        if (!hasShownTelegramThisSession) {
            showTelegramDialog = true
            hasShownTelegramThisSession = true
        }
    }

    LaunchedEffect(showTelegramDialog) {
        if (showTelegramDialog) {
            telegramCountdown = 5
            while (telegramCountdown > 0) {
                delay(1000L)
                telegramCountdown--
            }
            showTelegramDialog = false
        }
    }

    // Keep bottom search bar in sync on web loads
    LaunchedEffect(currentUrl) {
        inputUrl = currentUrl
    }

    // Sync last drama URL and title when in Drama Mode
    LaunchedEffect(currentUrl, currentTitle, isDramaModeActive) {
        if (isDramaModeActive) {
            val u = currentUrl.trim()
            if (u.isNotEmpty() && (u.contains("abledrama.top") || u.contains("ablesrama.top"))) {
                lastDramaUrl = u
                lastDramaTitle = currentTitle
            }
        }
    }

    // Capture and save the exact Drama state before entering Able Browser
    var prevDramaModeActive by remember { mutableStateOf(true) }
    LaunchedEffect(isDramaModeActive) {
        if (!isDramaModeActive && prevDramaModeActive) {
            val u = lastDramaUrl
            val isPost = isPostUrl(u)
            val isCategory = u.contains("/search") || u.contains("/category/") || u.contains("/p/") || u.contains("/search/label/")
            val isHome = !isPost && !isCategory
            
            val state = AbleDramaSavedState(
                url = u,
                title = lastDramaTitle,
                isPost = isPost,
                isCategory = isCategory,
                isHome = isHome
            )
            dramaSaveStack.add(state)
            android.util.Log.d("DramaSavedState", "Context-Aware Saved State before Browser: $state")
        }
        prevDramaModeActive = isDramaModeActive
        showUrlBar = !isDramaModeActive
    }

    // Dynamic sync of highlighted tab depending on web page location
    LaunchedEffect(currentUrl, currentTab) {
        if (currentTab == AppTab.BROWSER) {
            val lowercaseUrl = currentUrl.lowercase()
            when {
                lowercaseUrl.contains("/label/movies") || lowercaseUrl.contains("/category/movies") -> selectedBottomItem = BottomNavItem.MOVIES
                lowercaseUrl.contains("/label/drama") || lowercaseUrl.contains("/category/bengali") || lowercaseUrl.contains("/category/k-drama") || lowercaseUrl.contains("/category/drama") -> selectedBottomItem = BottomNavItem.DRAMA
                lowercaseUrl.contains("/label/web%20series") || lowercaseUrl.contains("/label/web_series") || lowercaseUrl.contains("/category/web-series") || lowercaseUrl.contains("/search/label/web") -> selectedBottomItem = BottomNavItem.WEB_SERIES
                lowercaseUrl.contains("/label/short%20drama") || lowercaseUrl.contains("/label/short_drama") || lowercaseUrl.contains("/category/short-drama") -> selectedBottomItem = BottomNavItem.SHORT_DRAMA
                lowercaseUrl.contains("/label/anime") || lowercaseUrl.contains("/category/anime") -> selectedBottomItem = BottomNavItem.ANIME
                else -> {
                    selectedBottomItem = null
                }
            }
        } else {
            selectedBottomItem = BottomNavItem.ACCOUNT
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            bottomBar = {
                if (currentTab != AppTab.BROWSER || isDramaModeActive) {
                    CustomBottomNavBar(
                        selectedItem = selectedBottomItem,
                        onItemSelected = { item ->
                            when (item) {
                            BottomNavItem.ACCOUNT -> {
                                if (currentTab == AppTab.ACCOUNT) {
                                    // Tap Main Menu Again -> Close & Return to previous state
                                    popHistory()
                                } else {
                                    pushToHistory()
                                    currentTab = AppTab.ACCOUNT
                                }
                            }
                            else -> {
                                pushToHistory()
                                viewModel.setDramaModeActive(true)
                                currentTab = AppTab.BROWSER
                                showUrlBar = false
                                when (item) {
                                    BottomNavItem.MOVIES -> {
                                        viewModel.loadUrl("https://www.abledrama.top/search/label/Movies")
                                    }
                                    BottomNavItem.DRAMA -> {
                                        viewModel.loadUrl("https://www.abledrama.top/search/label/Drama")
                                    }
                                    BottomNavItem.WEB_SERIES -> {
                                        viewModel.loadUrl("https://www.abledrama.top/search/label/Web%20Series")
                                    }
                                    BottomNavItem.SHORT_DRAMA -> {
                                        viewModel.loadUrl("https://www.abledrama.top/search/label/Short%20Drama")
                                    }
                                    BottomNavItem.ANIME -> {
                                        viewModel.loadUrl("https://www.abledrama.top/search/label/Anime")
                                    }
                                    else -> {}
                                }
                            }
                        }
                    }
                )
                }
            }
        ) { paddingValues ->
            Box(
                modifier = Modifier
                    .padding(paddingValues)
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    // Progressive load line
                    if (isLoading) {
                        LinearProgressIndicator(
                            progress = { loadProgress / 100f },
                            modifier = Modifier.fillMaxWidth().height(4.dp).testTag("web_load_progress"),
                            color = Color.Red,
                            trackColor = Color.Transparent
                        )
                    }

                    // If internet goes offline, show interactive toast alert
                    if (!isOnline) {
                        OfflineAlertBanner()
                    }

                    LaunchedEffect(requestSearchFocus) {
                        if (requestSearchFocus) {
                            currentTab = AppTab.BROWSER
                            showUrlBar = true
                        }
                    }

                    if (currentTab == AppTab.BROWSER && showUrlBar && !isDramaModeActive) {
                        val webThemeColor by viewModel.webThemeColor.collectAsStateWithLifecycle()
                        BrowserToolbar(
                            viewModel = viewModel,
                            currentUrl = currentUrl,
                            currentTitle = currentTitle,
                            canGoBack = canGoBack,
                            canGoForward = canGoForward,
                            isBookmarked = isBookmarked,
                            themeColorHex = webThemeColor,
                            tabCount = tabsList.size,
                            isDarkTheme = isDarkTheme,
                            onToggleDarkTheme = onToggleDarkTheme,
                            onUrlSubmit = { url -> viewModel.loadUrl(url) },
                            onBookmarkToggle = {
                                viewModel.toggleBookmark()
                            },
                            onShareAction = {
                                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_TEXT, currentUrl)
                                }
                                context.startActivity(Intent.createChooser(shareIntent, "Share URL Option"))
                            },
                            onGoBack = { viewModel.goBack() },
                            onGoForward = { viewModel.goForward() },
                            onReload = { viewModel.reload() },
                            onHomeClick = {
                                viewModel.loadUrl("browser://home")
                                showUrlBar = false
                            },
                            onPlusClick = {
                                viewModel.createNewTab("browser://home")
                            },
                            onTabListClick = {
                                isTabGridVisible = true
                            },
                            onExitBrowser = {
                                exitAndBackToHome()
                            },
                            onHistoryClick = {
                                pushToHistory()
                                showBrowserHistoryDialog = true
                            },
                            onDownloadClick = {
                                pushToHistory()
                                showDownloadManagerDialog = true
                            },
                            onAboutClick = {
                                pushToHistory()
                                showAboutBrowserDialog = true
                            },
                            focusRequester = urlBarFocusRequester,
                            shouldFocusImmediately = requestSearchFocus,
                            onFocusTriggeredHandled = { viewModel.triggerSearchFocus(false) }
                        )
                    }

                    // Tab Content Render
                    Box(modifier = Modifier.weight(1f)) {
                        // Always keep AdvancedWebViews in composition to preserve state & receive commands instantly
                        // 1. Able Drama WebViews (completely isolated container)
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer {
                                    alpha = if (currentTab == AppTab.BROWSER && isDramaModeActive) 1f else 0f
                                    translationX = if (currentTab == AppTab.BROWSER && isDramaModeActive) 0f else 20000f
                                }
                        ) {
                            dramaTabsList.forEach { tab ->
                                key(tab.id) {
                                    val isThisTabVisible = currentTab == AppTab.BROWSER && isDramaModeActive && dramaSelectedTabId == tab.id
                                    androidx.compose.foundation.layout.Box(modifier = Modifier.fillMaxSize()) {
                                        AdvancedWebView(
                                            viewModel = viewModel,
                                            tabId = tab.id,
                                            isVisible = isThisTabVisible,
                                            isDarkTheme = isDarkTheme,
                                            onShowCustomView = onShowCustomView,
                                            onHideCustomView = onHideCustomView,
                                            modifier = Modifier.fillMaxSize().testTag("movie_web_view_${tab.id}"),
                                            onSingleTap = {
                                                if (isCurrentUrlAPost) {
                                                    isBookmarkOverlayVisible = !isBookmarkOverlayVisible
                                                    if (isBookmarkOverlayVisible) {
                                                        bookmarkTimerTrigger++
                                                    }
                                                }
                                            },
                                            onDownloadRequested = { url, contentDisposition, mimeType, contentLength ->
                                                pushToHistory()
                                                downloadPendingUrl = url
                                                showDownloadFileDialog = true
                                            }
                                        )
                                    }
                                }
                            }
                        }

                        // 2. Able Browser WebViews (completely isolated container)
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer {
                                    alpha = if (currentTab == AppTab.BROWSER && !isDramaModeActive) 1f else 0f
                                    translationX = if (currentTab == AppTab.BROWSER && !isDramaModeActive) 0f else 20000f
                                }
                        ) {
                            browserTabsList.forEach { tab ->
                                key(tab.id) {
                                    val isThisTabVisible = currentTab == AppTab.BROWSER && !isDramaModeActive && browserSelectedTabId == tab.id
                                    androidx.compose.foundation.layout.Box(modifier = Modifier.fillMaxSize()) {
                                        if (tab.url == "browser://home" || tab.url.isBlank()) {
                                            if (isThisTabVisible) {
                                                BrowserHomepage(
                                                    viewModel = viewModel,
                                                    tabId = tab.id,
                                                    isDarkTheme = isDarkTheme,
                                                    onUrlFocusTrigger = {
                                                        viewModel.triggerSearchFocus(true)
                                                    },
                                                    onHistoryClick = {
                                                        pushToHistory()
                                                        showBrowserHistoryDialog = true
                                                    },
                                                    onDownloadsClick = {
                                                        pushToHistory()
                                                        showDownloadManagerDialog = true
                                                    },
                                                    onExitClick = {
                                                        exitAndBackToHome()
                                                    },
                                                    modifier = Modifier.fillMaxSize()
                                                )
                                            }
                                        } else {
                                            AdvancedWebView(
                                                viewModel = viewModel,
                                                tabId = tab.id,
                                                isVisible = isThisTabVisible,
                                                isDarkTheme = isDarkTheme,
                                                isDesktopModeAllowed = true,
                                                onShowCustomView = onShowCustomView,
                                                onHideCustomView = onHideCustomView,
                                                modifier = Modifier.fillMaxSize().testTag("browser_web_view_${tab.id}"),
                                                onSingleTap = {},
                                                onDownloadRequested = { url, contentDisposition, mimeType, contentLength ->
                                                    pushToHistory()
                                                    downloadPendingUrl = url
                                                    showDownloadFileDialog = true
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        // Overlay container for back-compatibility overlay items
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer {
                                    alpha = if (currentTab == AppTab.BROWSER) 1f else 0f
                                    translationX = if (currentTab == AppTab.BROWSER) 0f else 20000f
                                }
                        ) {

                            // Floating Bookmark Icon on the right vertical edge overlay
                            androidx.compose.animation.AnimatedVisibility(
                                visible = isBookmarkOverlayVisible && isCurrentUrlAPost,
                                enter = fadeIn() + scaleIn(),
                                exit = fadeOut() + scaleOut(),
                                modifier = Modifier
                                    .align(Alignment.CenterEnd)
                                    .padding(end = 16.dp)
                            ) {
                                val heartScale by animateFloatAsState(
                                    targetValue = if (isCustomBookmarked) 1.25f else 1.0f,
                                    animationSpec = spring(
                                        dampingRatio = Spring.DampingRatioHighBouncy,
                                        stiffness = Spring.StiffnessMedium
                                    ),
                                    label = "HeartAnimation"
                                )

                                Card(
                                    modifier = Modifier
                                        .size(52.dp)
                                        .clickable {
                                            if (isCustomBookmarked) {
                                                viewModel.removeBookmark(currentUrl)
                                                Toast.makeText(context, "Bookmark removed", Toast.LENGTH_SHORT).show()
                                            } else {
                                                viewModel.addCustomBookmark(currentUrl, currentTitle)
                                                Toast.makeText(context, "Post bookmarked!", Toast.LENGTH_SHORT).show()
                                            }
                                            bookmarkTimerTrigger++ // reset timer so user sees transition clearly
                                        }
                                        .testTag("floating_post_bookmark_btn"),
                                    shape = CircleShape,
                                    colors = CardDefaults.cardColors(
                                        containerColor = Color.Black.copy(alpha = 0.25f)
                                    ),
                                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                                ) {
                                    Box(
                                        modifier = Modifier.fillMaxSize(),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = if (isCustomBookmarked) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                                            contentDescription = "Bookmark",
                                            tint = if (isCustomBookmarked) Color(0xFFE50914) else Color.White,
                                            modifier = Modifier
                                                .size(26.dp)
                                                .scale(heartScale)
                                        )
                                    }
                                }
                            }

                            // Floating Download Button on the right-middle side of the screen
                            androidx.compose.animation.AnimatedVisibility(
                                visible = isVideoPlaying && currentDetectedResources.isNotEmpty() && viewModel.isDownloadablePage(currentUrl),
                                enter = fadeIn() + scaleIn(),
                                exit = fadeOut() + scaleOut(),
                                modifier = Modifier
                                    .align(Alignment.CenterEnd)
                                    .offset(y = 80.dp)
                                    .padding(end = 16.dp)
                            ) {
                                Card(
                                    modifier = Modifier
                                        .size(56.dp)
                                        .clickable {
                                            showDetectedResourcesSheet = true
                                        }
                                        .testTag("floating_download_resources_btn"),
                                    shape = CircleShape,
                                    colors = CardDefaults.cardColors(
                                        containerColor = Color(0xFFD0BCFF)
                                    ),
                                    elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
                                ) {
                                    Box(
                                        modifier = Modifier.fillMaxSize(),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.CloudUpload,
                                            contentDescription = "Detected Downloads",
                                            tint = Color(0xFF131317),
                                            modifier = Modifier.size(28.dp)
                                        )
                                    }
                                }
                            }
                        }

                        androidx.compose.animation.AnimatedVisibility(
                            visible = currentTab == AppTab.ACCOUNT,
                            enter = slideInHorizontally(
                                initialOffsetX = { fullWidth -> fullWidth },
                                animationSpec = androidx.compose.animation.core.tween(durationMillis = 300, easing = androidx.compose.animation.core.FastOutSlowInEasing)
                            ) + fadeIn(animationSpec = androidx.compose.animation.core.tween(300)),
                            exit = slideOutHorizontally(
                                targetOffsetX = { fullWidth -> fullWidth },
                                animationSpec = androidx.compose.animation.core.tween(durationMillis = 300, easing = androidx.compose.animation.core.FastOutSlowInEasing)
                            ) + fadeOut(animationSpec = androidx.compose.animation.core.tween(300)),
                            modifier = Modifier.fillMaxSize()
                        ) {
                            MyAccountTab(
                                bookmarks = bookmarks,
                                history = history,
                                isDarkTheme = isDarkTheme,
                                onToggleDarkTheme = onToggleDarkTheme,
                                onSectionClick = { targetUrl ->
                                    pushToHistory()
                                    viewModel.setDramaModeActive(true)
                                    viewModel.loadUrl(targetUrl)
                                    currentTab = AppTab.BROWSER
                                    showUrlBar = false
                                },
                                onDeleteBookmark = { url -> viewModel.removeBookmark(url) },
                                onDeleteHistory = { id -> viewModel.deleteHistoryItem(id) },
                                onClearHistory = { viewModel.clearHistory() },
                                onEmailFeedback = {
                                    val intent = Intent(Intent.ACTION_SENDTO).apply {
                                        data = Uri.parse("mailto:mondalsujoy1147@gmail.com")
                                        putExtra(Intent.EXTRA_SUBJECT, "Able Drama Android VIP Feedback")
                                    }
                                    try {
                                        context.startActivity(intent)
                                    } catch (e: Exception) {
                                        Toast.makeText(context, "Email application not found on this device.", Toast.LENGTH_SHORT).show()
                                    }
                                },
                                onDownloadClick = {
                                    pushToHistory()
                                    showDownloadManagerDialog = true
                                },
                                onBrowserToggleClick = {
                                    pushToHistory()
                                    viewModel.openBrowserToHome()
                                    currentTab = AppTab.BROWSER
                                },
                                onUrlPasteGoClick = { targetUrl ->
                                    pushToHistory()
                                    viewModel.openUrlInBrowser(targetUrl)
                                    currentTab = AppTab.BROWSER
                                }
                            )
                        }
                    }
                }
            }
        }

        // Custom Downloader Dialogs
        if (showDownloadFileDialog) {
            DownloadFileDialog(
                initialUrl = downloadPendingUrl,
                initialReferrerUrl = currentUrl,
                initialFileName = downloadPendingName,
                onDismissRequest = { showDownloadFileDialog = false },
                downloadRepository = downloadRepository,
                coroutineScope = coroutineScope
            )
        }

        if (showDetectedResourcesSheet && currentDetectedResources.isNotEmpty()) {
            Dialog(
                onDismissRequest = { showDetectedResourcesSheet = false },
                properties = DialogProperties(usePlatformDefaultWidth = false)
            ) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp)
                        .clip(RoundedCornerShape(24.dp)),
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 8.dp
                ) {
                    val analyzedVideoQualities = remember { mutableStateMapOf<String, List<com.example.util.VideoQualityOption>>() }
                    var isAnalyzingVideo by remember { mutableStateOf(false) }

                    LaunchedEffect(currentDetectedResources, activeDuration) {
                        isAnalyzingVideo = true
                        val grouped = currentDetectedResources.groupBy { it.title }
                        for ((title, resources) in grouped) {
                            val options = com.example.util.VideoAnalyzer.analyze(resources, activeDuration)
                            analyzedVideoQualities[title] = options
                        }
                        isAnalyzingVideo = false
                    }

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp)
                    ) {
                        // Title row
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .background(
                                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
                                        shape = RoundedCornerShape(12.dp)
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.CloudUpload,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = "Video Ready To Download",
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = "Smart Quality Detection",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                                )
                            }
                            Spacer(modifier = Modifier.weight(1f))
                            IconButton(onClick = { showDetectedResourcesSheet = false }) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Close",
                                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                )
                            }
                        }

                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                            modifier = Modifier.padding(vertical = 16.dp)
                        )

                        val groupedVideos = remember(currentDetectedResources) {
                            currentDetectedResources.groupBy { it.title }
                        }

                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 420.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            items(groupedVideos.keys.toList()) { videoTitle ->
                                val resourcesInGroup = groupedVideos[videoTitle] ?: emptyList()
                                val qualities = analyzedVideoQualities[videoTitle] ?: emptyList()

                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)
                                    ),
                                    shape = RoundedCornerShape(16.dp),
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))
                                ) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(16.dp)
                                    ) {
                                        Text(
                                            text = "VIDEO",
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.primary,
                                            letterSpacing = 1.2.sp
                                        )
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            text = videoTitle,
                                            fontSize = 15.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            maxLines = 3,
                                            overflow = TextOverflow.Ellipsis,
                                            lineHeight = 20.sp
                                        )

                                        Spacer(modifier = Modifier.height(16.dp))

                                        if (isAnalyzingVideo && qualities.isEmpty()) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.Center
                                            ) {
                                                CircularProgressIndicator(
                                                    modifier = Modifier.size(20.dp),
                                                    strokeWidth = 2.dp,
                                                    color = MaterialTheme.colorScheme.primary
                                                )
                                                Spacer(modifier = Modifier.width(12.dp))
                                                Text(
                                                    text = "Analyzing video qualities & sizes...",
                                                    fontSize = 13.sp,
                                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                                )
                                            }
                                        } else if (qualities.isEmpty()) {
                                            // Handle case if parsing yielded no streams (should fallback to basic resource)
                                            val firstRes = resourcesInGroup.firstOrNull()
                                            if (firstRes != null) {
                                                Button(
                                                    onClick = {
                                                        com.example.util.VideoAnalyzer.startDirectDownload(
                                                            context = context,
                                                            url = firstRes.url,
                                                            title = videoTitle,
                                                            resolution = "Original",
                                                            estimatedSize = 0L,
                                                            downloadRepository = downloadRepository,
                                                            scope = coroutineScope
                                                        )
                                                        showDetectedResourcesSheet = false
                                                    },
                                                    modifier = Modifier.fillMaxWidth(),
                                                    shape = RoundedCornerShape(12.dp)
                                                ) {
                                                    Icon(imageVector = Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                                                    Spacer(modifier = Modifier.width(8.dp))
                                                    Text("Download Video")
                                                }
                                            } else {
                                                Text(
                                                    text = "No compatible streams detected.",
                                                    color = MaterialTheme.colorScheme.error,
                                                    fontSize = 13.sp
                                                )
                                            }
                                        } else if (qualities.size == 1) {
                                            // SINGLE QUALITY VIDEOS
                                            val option = qualities.first()
                                            Column(modifier = Modifier.fillMaxWidth()) {
                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    modifier = Modifier.padding(bottom = 12.dp)
                                                ) {
                                                    Text(
                                                        text = "Estimated Size:",
                                                        fontSize = 13.sp,
                                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                                                    )
                                                    Spacer(modifier = Modifier.width(6.dp))
                                                    Text(
                                                        text = option.displaySize,
                                                        fontSize = 14.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        color = MaterialTheme.colorScheme.onSurface
                                                    )
                                                    if (option.format.isNotEmpty()) {
                                                        Spacer(modifier = Modifier.width(8.dp))
                                                        Surface(
                                                            color = MaterialTheme.colorScheme.primaryContainer,
                                                            shape = RoundedCornerShape(4.dp)
                                                        ) {
                                                            Text(
                                                                text = option.format.uppercase(),
                                                                fontSize = 9.sp,
                                                                fontWeight = FontWeight.Bold,
                                                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                                                            )
                                                        }
                                                    }
                                                }

                                                Button(
                                                    onClick = {
                                                        com.example.util.VideoAnalyzer.startDirectDownload(
                                                            context = context,
                                                            url = option.url,
                                                            title = videoTitle,
                                                            resolution = option.resolution,
                                                            estimatedSize = option.sizeBytes,
                                                            downloadRepository = downloadRepository,
                                                            scope = coroutineScope
                                                        )
                                                        showDetectedResourcesSheet = false
                                                    },
                                                    modifier = Modifier.fillMaxWidth(),
                                                    colors = ButtonDefaults.buttonColors(
                                                        containerColor = MaterialTheme.colorScheme.primary
                                                    ),
                                                    shape = RoundedCornerShape(12.dp)
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Default.Download,
                                                        contentDescription = null,
                                                        modifier = Modifier.size(18.dp)
                                                    )
                                                    Spacer(modifier = Modifier.width(8.dp))
                                                    Text(
                                                        text = "Download (${option.displaySize})",
                                                        fontWeight = FontWeight.Bold,
                                                        fontSize = 14.sp
                                                    )
                                                }
                                            }
                                        } else {
                                            // MULTI QUALITY VIDEOS
                                            Text(
                                                text = "AVAILABLE QUALITIES",
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                                                letterSpacing = 1.2.sp,
                                                modifier = Modifier.padding(bottom = 8.dp)
                                            )
                                            
                                            Column(
                                                modifier = Modifier.fillMaxWidth(),
                                                verticalArrangement = Arrangement.spacedBy(8.dp)
                                            ) {
                                                qualities.forEach { option ->
                                                    Surface(
                                                        modifier = Modifier
                                                            .fillMaxWidth()
                                                            .clickable {
                                                                com.example.util.VideoAnalyzer.startDirectDownload(
                                                                    context = context,
                                                                    url = option.url,
                                                                    title = videoTitle,
                                                                    resolution = option.resolution,
                                                                    estimatedSize = option.sizeBytes,
                                                                    downloadRepository = downloadRepository,
                                                                    scope = coroutineScope
                                                                )
                                                                showDetectedResourcesSheet = false
                                                            },
                                                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                                                        shape = RoundedCornerShape(12.dp),
                                                        border = BorderStroke(
                                                            width = 1.dp,
                                                            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.1f)
                                                        )
                                                    ) {
                                                        Row(
                                                            modifier = Modifier
                                                                .fillMaxWidth()
                                                                .padding(horizontal = 16.dp, vertical = 12.dp),
                                                            verticalAlignment = Alignment.CenterVertically
                                                        ) {
                                                            Icon(
                                                                imageVector = Icons.Default.Download,
                                                                contentDescription = null,
                                                                tint = MaterialTheme.colorScheme.primary,
                                                                modifier = Modifier.size(16.dp)
                                                            )
                                                            Spacer(modifier = Modifier.width(12.dp))
                                                            Text(
                                                                text = option.resolution,
                                                                fontSize = 14.sp,
                                                                fontWeight = FontWeight.Bold,
                                                                color = MaterialTheme.colorScheme.onSurface
                                                            )
                                                            Spacer(modifier = Modifier.width(6.dp))
                                                            Text(
                                                                text = "•  ${option.displaySize}",
                                                                fontSize = 13.sp,
                                                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                                            )
                                                            Spacer(modifier = Modifier.weight(1f))
                                                            Surface(
                                                                color = MaterialTheme.colorScheme.secondaryContainer,
                                                                shape = RoundedCornerShape(4.dp)
                                                            ) {
                                                                Text(
                                                                    text = option.format.uppercase(),
                                                                    fontSize = 9.sp,
                                                                    fontWeight = FontWeight.Bold,
                                                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                                                                )
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        // Bottom Actions Row
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            TextButton(
                                onClick = { showDetectedResourcesSheet = false }
                            ) {
                                Text(
                                    text = "CANCEL", 
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f), 
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }
        }

        if (showDownloadManagerDialog) {
            DownloadManagerDialog(
                onDismissRequest = { showDownloadManagerDialog = false },
                downloadRepository = downloadRepository,
                coroutineScope = coroutineScope,
                onExitClick = {
                    showDownloadManagerDialog = false
                    exitAndBackToHome()
                }
            )
        }

        if (showExitDialog) {
            AlertDialog(
                onDismissRequest = { showExitDialog = false },
                title = { Text("Exit App", fontWeight = FontWeight.Bold) },
                text = { Text("Are you sure you want to exit the app?") },
                confirmButton = {
                    TextButton(
                        onClick = {
                            showExitDialog = false
                            (context as? android.app.Activity)?.finish()
                        }
                    ) {
                        Text("Exit", color = Color.Red, fontWeight = FontWeight.SemiBold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showExitDialog = false }) {
                        Text("Cancel", fontWeight = FontWeight.SemiBold)
                    }
                }
            )
        }

        if (showBrowserHistoryDialog) {
            BrowserHistoryBookmarksDialog(
                browserBookmarks = browserBookmarks,
                browserHistory = browserHistory,
                isDarkTheme = isDarkTheme,
                onDismissRequest = { showBrowserHistoryDialog = false },
                onUrlClick = { url ->
                    showBrowserHistoryDialog = false
                    viewModel.loadUrl(url)
                },
                onDeleteBookmark = { url ->
                    viewModel.removeBrowserBookmark(url)
                },
                onDeleteHistory = { id ->
                    viewModel.deleteHistoryItem(id)
                },
                onClearAllHistory = {
                    viewModel.clearBrowserHistory()
                },
                onExitClick = {
                    showBrowserHistoryDialog = false
                    exitAndBackToHome()
                }
            )
        }

        if (showAboutBrowserDialog) {
            AboutBrowserDialog(
                isDarkTheme = isDarkTheme,
                onDismissRequest = { showAboutBrowserDialog = false }
            )
        }

        // Telegram Community Join Promo Dialog (Inline overlay to bypass GPU sub-window driver issues and guarantee 100% crash-free responsive execution)
        if (showTelegramDialog) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.7f))
                    .clickable(enabled = true, onClick = { showTelegramDialog = false }),
                contentAlignment = Alignment.Center
            ) {
                Card(
                    modifier = Modifier
                        .widthIn(max = 420.dp)
                        .fillMaxWidth()
                        .padding(24.dp)
                        .clickable(enabled = true, onClick = { /* Prevent clicks propagating to parent dismiss */ })
                        .testTag("telegram_promo_dialog"),
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = DarkVacuum),
                    border = BorderStroke(1.2.dp, CinemaRed.copy(alpha = 0.5f))
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Box(
                            modifier = Modifier
                                .size(56.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF24A1DE)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Send,
                                contentDescription = "Telegram Community",
                                tint = Color.White,
                                modifier = Modifier.size(28.dp)
                            )
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        Text(
                            text = "Join Our Official Telegram",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        Text(
                            text = "Join our official Telegram channel now to get instant movie requests, direct high-speed links, series updates, and developer support!",
                            color = Color.LightGray,
                            fontSize = 13.sp,
                            textAlign = TextAlign.Center,
                            lineHeight = 18.sp
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        Text(
                            text = "Auto-closing in ${telegramCountdown}s...",
                            color = CinemaGold,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )

                        Spacer(modifier = Modifier.height(24.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            TextButton(
                                onClick = { showTelegramDialog = false },
                                colors = ButtonDefaults.textButtonColors(contentColor = Color.LightGray)
                            ) {
                                Text("Dismiss", fontWeight = FontWeight.SemiBold)
                            }

                            Button(
                                onClick = {
                                    showTelegramDialog = false
                                    try {
                                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/+Cg68mwS78D8yNzRl"))
                                        context.startActivity(intent)
                                    } catch (e: Exception) {
                                        Toast.makeText(context, "Cannot open Telegram link", Toast.LENGTH_SHORT).show()
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF24A1DE)),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Text("Join Now", color = Color.White, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }

        if (isTabGridVisible) {
            TabGridOverlay(
                tabs = tabsList,
                selectedTabId = selectedTabId,
                isDarkTheme = isDarkTheme,
                onTabSelect = { tabId ->
                    viewModel.selectTab(tabId)
                    isTabGridVisible = false
                },
                onTabClose = { tabId ->
                    viewModel.closeTab(tabId)
                },
                onNewTab = {
                    viewModel.createNewTab("https://www.google.com")
                    isTabGridVisible = false
                },
                onCloseGrid = {
                    isTabGridVisible = false
                }
            )
        }
    }
}

@Composable
fun HeaderBrandBar(
    title: String,
    showActions: Boolean,
    isBookmarked: Boolean,
    onBookmarkToggle: () -> Unit,
    onShareAction: () -> Unit
) {
    Surface(
        color = SurfaceSlate,
        tonalElevation = 4.dp,
        modifier = Modifier.fillMaxWidth().testTag("app_header")
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .windowInsetsPadding(WindowInsets.statusBars),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Elegant Film Red Logo
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(CinemaRed),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(24.dp)
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "ABLE DRAMA",
                    color = CinemaRed,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.SansSerif,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 2.sp
                )
                Text(
                    text = title.trim(),
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (showActions) {
                IconButton(onClick = onBookmarkToggle, modifier = Modifier.testTag("bookmark_toggle_btn")) {
                    Icon(
                        imageVector = if (isBookmarked) Icons.Filled.Bookmark else Icons.Outlined.Bookmark,
                        contentDescription = "Bookmark present page",
                        tint = if (isBookmarked) Color(0xFFE50914) else Color.White
                    )
                }

                IconButton(onClick = onShareAction, modifier = Modifier.testTag("share_page_btn")) {
                    Icon(
                        imageVector = Icons.Default.Share,
                        contentDescription = "Share Url Link",
                        tint = Color.White
                    )
                }
            }
        }
    }
}

@Composable
fun OfflineAlertBanner() {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(12.dp),
        colors = CardDefaults.cardColors(containerColor = CinemaRed.copy(alpha = 0.9f)),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = 16.dp, vertical = 10.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.SignalWifiOff,
                contentDescription = "Offline Mode",
                tint = Color.White,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = "You are currently offline. Please check your network connection.",
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
fun BrowserToolbar(
    viewModel: com.example.ui.BrowserViewModel,
    currentUrl: String,
    currentTitle: String,
    canGoBack: Boolean,
    canGoForward: Boolean,
    isBookmarked: Boolean,
    themeColorHex: String?,
    isDarkTheme: Boolean,
    onToggleDarkTheme: () -> Unit,
    tabCount: Int,
    onBookmarkToggle: () -> Unit,
    onShareAction: () -> Unit,
    onGoBack: () -> Unit,
    onGoForward: () -> Unit,
    onReload: () -> Unit,
    onHomeClick: () -> Unit,
    onPlusClick: () -> Unit,
    onTabListClick: () -> Unit,
    onExitBrowser: () -> Unit,
    onHistoryClick: () -> Unit = {},
    onDownloadClick: () -> Unit = {},
    onUrlSubmit: (String) -> Unit = {},
    onAboutClick: () -> Unit = {},
    focusRequester: FocusRequester = remember { FocusRequester() },
    shouldFocusImmediately: Boolean = false,
    onFocusTriggeredHandled: () -> Unit = {}
) {
    val parsedColor = remember(themeColorHex) {
        if (themeColorHex != null) {
            try {
                val colorString = if (themeColorHex.startsWith("#")) themeColorHex else "#$themeColorHex"
                val expandedColorString = if (colorString.length == 4) {
                    "#" + colorString[1] + colorString[1] + colorString[2] + colorString[2] + colorString[3] + colorString[3]
                } else {
                    colorString
                }
                Color(android.graphics.Color.parseColor(expandedColorString))
            } catch (e: Exception) {
                null
            }
        } else {
            null
        }
    }

    val isDesktopModeEnabled by viewModel.isDesktopModeEnabled.collectAsStateWithLifecycle()

    val finalBarColor = remember(parsedColor, currentUrl, isDarkTheme) {
        if (parsedColor != null) {
            parsedColor
        } else {
            val lowercaseUrl = currentUrl.lowercase()
            when {
                lowercaseUrl.contains("abledrama.top") || lowercaseUrl.contains("abledrama") -> Color(0xFF0F081C)
                lowercaseUrl.contains("google.com") -> if (isDarkTheme) Color(0xFF303134) else Color(0xFFF2F2F2)
                lowercaseUrl.contains("facebook.com") -> Color(0xFF1877F2)
                lowercaseUrl.contains("youtube.com") -> Color(0xFFE62117)
                lowercaseUrl.contains("telegram.org") -> Color(0xFF24A1DE)
                lowercaseUrl.contains("wikipedia.org") -> if (isDarkTheme) Color(0xFF1E1E1E) else Color(0xFFF6F6F6)
                lowercaseUrl.contains("github.com") -> Color(0xFF1F2328)
                else -> {
                    if (isDarkTheme) Color(0xFF08080A) else Color.White
                }
            }
        }
    }

    val isLightColor = remember(finalBarColor) {
        try {
            finalBarColor.luminance() > 0.45f
        } catch (e: Exception) {
            true
        }
    }
    val contentColor = if (isLightColor) Color(0xFF3C4043) else Color.White

    Surface(
        color = finalBarColor,
        tonalElevation = 6.dp,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("browser_controls_bar")
    ) {
        var isEditing by remember { mutableStateOf(false) }
        var editUrlText by remember { mutableStateOf(currentUrl) }

        LaunchedEffect(currentUrl) {
            if (!isEditing) {
                editUrlText = currentUrl
            }
        }

        LaunchedEffect(shouldFocusImmediately) {
            if (shouldFocusImmediately) {
                try {
                    focusRequester.requestFocus()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                onFocusTriggeredHandled()
            }
        }

        val focusManager = LocalFocusManager.current

        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 6.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
            if (!isEditing) {
                // Home button (Leftmost, visible when not editing URL)
                IconButton(
                    onClick = onHomeClick,
                    modifier = Modifier.size(38.dp).testTag("web_home_btn")
                ) {
                    Icon(
                        imageVector = Icons.Default.Home,
                        contentDescription = "Home",
                        tint = contentColor,
                        modifier = Modifier.size(22.dp)
                    )
                }
            } else {
                // Beautiful editing cancel button (Arrow back, visible when editing URL)
                IconButton(
                    onClick = { focusManager.clearFocus() },
                    modifier = Modifier.size(38.dp).testTag("web_edit_back_btn")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Cancel edit",
                        tint = contentColor,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }

            // Beautiful rounded URL address bar container with typing capabilities
            if (currentUrl == "browser://home") {
                // On home page, hide top URL/address bar and replace with empty space
                Spacer(modifier = Modifier.weight(1f))
            } else {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .height(38.dp)
                        .padding(horizontal = 4.dp)
                        .background(
                            color = if (isLightColor) Color(0xFFF1F3F4) else Color.White.copy(alpha = 0.15f),
                            shape = RoundedCornerShape(19.dp)
                        )
                        .padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Dynamic header branding / Lock / Search
                    if (!isEditing && currentUrl == "browser://home") {
                        Icon(
                            imageVector = Icons.Default.Language,
                            contentDescription = "Browser Header Logo",
                            tint = contentColor.copy(alpha = 0.7f),
                            modifier = Modifier.size(16.dp)
                        )
                    } else {
                        Icon(
                            imageVector = if (isEditing) Icons.Default.Search else Icons.Default.Lock,
                            contentDescription = if (isEditing) "Search" else "SSL Secure",
                            tint = contentColor.copy(alpha = 0.7f),
                            modifier = Modifier.size(15.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(6.dp))

                    androidx.compose.foundation.text.BasicTextField(
                        value = if (isEditing) editUrlText else {
                            if (currentUrl == "browser://home") {
                                ""
                            } else {
                                try {
                                    val uri = Uri.parse(currentUrl)
                                    val host = uri.host ?: ""
                                    val path = uri.path ?: ""
                                    val cleanHost = host.removePrefix("www.")
                                    if (path.length > 1) {
                                        cleanHost + path
                                    } else {
                                        cleanHost
                                    }
                                } catch (e: Exception) {
                                    currentUrl.removePrefix("https://").removePrefix("http://").removePrefix("www.")
                                }
                            }
                        },
                        onValueChange = { newValue ->
                            if (isEditing) {
                                editUrlText = newValue
                                viewModel.updateSearchInput(newValue)
                            }
                        },
                        modifier = Modifier
                            .weight(1f)
                            .focusRequester(focusRequester)
                            .onFocusChanged { focusState ->
                                isEditing = focusState.isFocused
                                if (focusState.isFocused) {
                                    val trimmed = currentUrl.trim().lowercase().removeSuffix("/")
                                    val isGoogleHome = trimmed == "https://www.google.com" || 
                                                       trimmed == "https://google.com" || 
                                                       trimmed == "http://www.google.com" || 
                                                       trimmed == "http://google.com"
                                    val isAbleDramaHome = trimmed == "https://www.abledrama.top" || 
                                                          trimmed == "https://abledrama.top" ||
                                                          trimmed == "http://www.abledrama.top" || 
                                                          trimmed == "http://abledrama.top"
                                    val isBrowserHome = trimmed == "browser://home"
                                    if (isGoogleHome || isAbleDramaHome || isBrowserHome) {
                                        editUrlText = ""
                                    } else {
                                        editUrlText = currentUrl
                                    }
                                    viewModel.updateSearchInput(editUrlText)
                                }
                            }
                            .testTag("browser_url_input"),
                        textStyle = androidx.compose.ui.text.TextStyle(
                            color = contentColor,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium
                        ),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Uri,
                            imeAction = ImeAction.Go
                        ),
                        keyboardActions = KeyboardActions(
                            onGo = {
                                val target = editUrlText.trim()
                                if (target.isNotEmpty()) {
                                    onUrlSubmit(target)
                                }
                                focusManager.clearFocus()
                            }
                        ),
                        decorationBox = { innerTextField ->
                            Box(modifier = Modifier.fillMaxWidth()) {
                                if ((isEditing && editUrlText.isEmpty()) || (!isEditing && currentUrl == "browser://home")) {
                                    Text(
                                        text = "Search or type URL",
                                        color = contentColor.copy(alpha = 0.5f),
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                                innerTextField()
                            }
                        }
                    )

                    if (isEditing && editUrlText.isNotEmpty()) {
                        IconButton(
                            onClick = { 
                                editUrlText = "" 
                                viewModel.updateSearchInput("")
                            },
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Clear address bar",
                                tint = contentColor.copy(alpha = 0.6f),
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }
                }
            }

            if (!isEditing) {
                // Plus icon ("+") - Hidden on home page, and hidden during focus
                if (currentUrl != "browser://home") {
                    IconButton(
                        onClick = onPlusClick,
                        modifier = Modifier.size(38.dp).testTag("web_plus_btn")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = "New Tab",
                            tint = contentColor,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }

                // Tab switcher rounded-corner box - Hidden during focus
                Box(
                    modifier = Modifier
                        .padding(horizontal = 4.dp)
                        .size(24.dp)
                        .clickable { onTabListClick() }
                        .border(
                            width = 1.8.dp,
                            color = contentColor,
                            shape = RoundedCornerShape(5.dp)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = tabCount.toString(),
                        color = contentColor,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                // Vertical dots menu icon - Hidden during focus
                var menuExpanded by remember { mutableStateOf(false) }

                Box {
                    IconButton(
                        onClick = { menuExpanded = true },
                        modifier = Modifier.size(38.dp).testTag("web_menu_btn")
                    ) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = "Menu Options",
                            tint = contentColor,
                            modifier = Modifier.size(22.dp)
                        )
                    }

                    if (menuExpanded) {
                        BrowserActionMenuPopup(
                            isBookmarked = isBookmarked,
                            canGoBack = canGoBack,
                            canGoForward = canGoForward,
                            onDismiss = { menuExpanded = false },
                            onReload = onReload,
                            onBookmarkToggle = onBookmarkToggle,
                            onShareAction = onShareAction,
                            onGoBack = onGoBack,
                            onGoForward = onGoForward,
                            onHistoryClick = onHistoryClick,
                            onDownloadClick = onDownloadClick,
                            onExitBrowser = onExitBrowser,
                            isDarkTheme = isDarkTheme,
                            onToggleDarkTheme = onToggleDarkTheme,
                            onNewTabClick = onPlusClick,
                            onAboutClick = onAboutClick,
                            onTranslateClick = {
                                val encodedUrl = try {
                                    java.net.URLEncoder.encode(currentUrl, "UTF-8")
                                } catch (e: java.io.UnsupportedEncodingException) {
                                    currentUrl
                                }
                                val translateUrl = "https://translate.google.com/translate?sl=auto&tl=en&u=$encodedUrl"
                                onUrlSubmit(translateUrl)
                            },
                            isDesktopModeEnabled = isDesktopModeEnabled,
                            onDesktopModeToggle = {
                                viewModel.setDesktopModeEnabled(!isDesktopModeEnabled)
                            }
                        )
                    }
                }
            }
        }

        if (isEditing) {
                val suggestions by viewModel.searchSuggestions.collectAsStateWithLifecycle()
                if (suggestions.isNotEmpty()) {
                    var lastFilledText by remember { mutableStateOf("") }
                    SearchSuggestionsDropdown(
                        suggestions = suggestions,
                        isDarkTheme = isDarkTheme,
                        onSuggestionClick = { item ->
                            if (editUrlText.trim().lowercase() == item.text.trim().lowercase() || item.text.trim().lowercase() == lastFilledText.trim().lowercase()) {
                                onUrlSubmit(item.text)
                                focusManager.clearFocus()
                            } else {
                                editUrlText = item.text
                                lastFilledText = item.text
                                viewModel.updateSearchInput(item.text)
                            }
                        },
                        onDeleteSearchClick = { item ->
                            viewModel.deleteSearchQuery(item.id)
                        },
                        onClearAllClick = {
                            viewModel.clearSearchQueryHistory()
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun TabGridOverlay(
    tabs: List<BrowserTab>,
    selectedTabId: String,
    isDarkTheme: Boolean,
    onTabSelect: (String) -> Unit,
    onTabClose: (String) -> Unit,
    onNewTab: () -> Unit,
    onCloseGrid: () -> Unit
) {
    val backdropColor = if (isDarkTheme) Color(0xFF08080A) else Color(0xFFFAFAFC)
    val textColor = if (isDarkTheme) Color.White else Color(0xFF131317)
    val cardActiveBorder = if (isDarkTheme) CinemaGold else CinemaRed
    val cardBorder = if (isDarkTheme) Color(0xFF1F1F28) else Color(0xFFE0E0E0)
    val metadataBg = if (isDarkTheme) Color(0xFF121217) else Color.White
    val subTextColor = if (isDarkTheme) Color.LightGray else Color(0xFF5F6368)

    Surface(
        color = backdropColor,
        modifier = Modifier.fillMaxSize().testTag("tab_grid_overlay")
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Elegant top control bar for tabs list
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onCloseGrid) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back to Browser",
                        tint = textColor
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                Text(
                    text = "Tabs",
                    color = textColor,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )

                // Plus action on tab management header
                TextButton(
                    onClick = onNewTab,
                    colors = ButtonDefaults.textButtonColors(contentColor = if (isDarkTheme) CinemaGold else CinemaRed)
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = "New Tab",
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("New Tab", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                }
            }

            HorizontalDivider(color = if (isDarkTheme) Color.White.copy(alpha = 0.08f) else Color.Black.copy(alpha = 0.06f))

            if (tabs.isEmpty()) {
                Box(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Text("No tabs open. Touch '+' to add one.", color = Color.Gray, fontSize = 14.sp)
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    contentPadding = PaddingValues(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    gridItems(tabs, key = { it.id }) { tab ->
                        val isActive = tab.id == selectedTabId
                        val activeContainerColor = if (isDarkTheme) Color(0xFF121217) else Color.White
                        val inactiveContainerColor = if (isDarkTheme) Color(0xFF08080A) else Color(0xFFF1F3F4)
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(180.dp) // Enlarged height to make room for full previews!
                                .clickable { onTabSelect(tab.id) },
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(
                                width = if (isActive) 1.8.dp else 1.dp,
                                color = if (isActive) cardActiveBorder else cardBorder
                            ),
                            colors = CardDefaults.cardColors(
                                containerColor = if (isActive) activeContainerColor else inactiveContainerColor
                            )
                        ) {
                            Column(modifier = Modifier.fillMaxSize()) {
                                // 1. Captured Screenshot Preview Area with Overlay Close/Active Action Items
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxWidth()
                                        .background(if (isDarkTheme) Color(0xFF131318) else Color(0xFFFAFAFC))
                                ) {
                                    if (tab.screenshot != null) {
                                        Image(
                                            bitmap = tab.screenshot.asImageBitmap(),
                                            contentDescription = "Preview of ${tab.title}",
                                            modifier = Modifier.fillMaxSize(),
                                            contentScale = ContentScale.Crop,
                                            alignment = Alignment.TopCenter
                                        )
                                    } else {
                                        // Stylized Fallback: elegant gradient card matching the screen visual style
                                        Box(
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .background(
                                                    Brush.verticalGradient(
                                                        colors = if (isDarkTheme) {
                                                            listOf(Color(0xFF2C133B), Color(0xFF110A1C))
                                                        } else {
                                                            listOf(Color(0xFFFFECF0), Color(0xFFEAEFFB))
                                                        }
                                                    )
                                                ),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .size(34.dp)
                                                    .background(if (isDarkTheme) Color.White.copy(alpha = 0.08f) else Color.Black.copy(alpha = 0.05f), CircleShape),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                val domainInitial = try {
                                                    val parsed = Uri.parse(tab.url)
                                                    val host = parsed.host ?: "Google"
                                                    val clean = host.removePrefix("www.").substring(0, 1).uppercase()
                                                    clean
                                                } catch (e: Exception) {
                                                    "G"
                                                }
                                                Text(
                                                    text = domainInitial,
                                                    color = if (isDarkTheme) CinemaGold else CinemaRed,
                                                    fontSize = 15.sp,
                                                    fontWeight = FontWeight.Bold
                                                )
                                            }
                                        }
                                    }

                                    // Top Row Overlay: Close Button + Active Label Badge
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(6.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        if (isActive) {
                                            Box(
                                                modifier = Modifier
                                                    .background(
                                                        color = if (isDarkTheme) CinemaGold else CinemaRed,
                                                        shape = RoundedCornerShape(4.dp)
                                                    )
                                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                                            ) {
                                                Text(
                                                    text = "ACTIVE",
                                                    color = if (isDarkTheme) Color.Black else Color.White,
                                                    fontSize = 8.sp,
                                                    fontWeight = FontWeight.Bold
                                                )
                                            }
                                        } else {
                                            Spacer(modifier = Modifier.width(1.dp))
                                        }

                                        // Close tab button
                                        IconButton(
                                            onClick = { onTabClose(tab.id) },
                                            modifier = Modifier
                                                .size(22.dp)
                                                .background(Color.Black.copy(alpha = 0.5f), CircleShape)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Close,
                                                contentDescription = "Close Tab",
                                                tint = Color.White,
                                                modifier = Modifier.size(12.dp)
                                            )
                                        }
                                    }
                                }

                                // 2. Metadata details header area: Title & Host Url
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(metadataBg)
                                        .padding(8.dp)
                                ) {
                                    Text(
                                        text = tab.title.ifBlank { "Untitled" },
                                        color = textColor,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    val shortDomain = remember(tab.url) {
                                        try {
                                            val parsed = Uri.parse(tab.url)
                                            parsed.host?.removePrefix("www.") ?: tab.url
                                        } catch (e: Exception) {
                                            tab.url
                                        }
                                    }
                                    Text(
                                        text = shortDomain,
                                        color = subTextColor,
                                        fontSize = 9.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun CustomBottomNavBar(
    selectedItem: BottomNavItem?,
    onItemSelected: (BottomNavItem) -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 8.dp,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)),
        modifier = Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.navigationBars)
            .testTag("vibrant_bottom_nav_bar")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 10.dp, horizontal = 4.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            val navItems = listOf(
                BottomNavItem.MOVIES to Pair("Movies", Icons.Default.Movie),
                BottomNavItem.DRAMA to Pair("Drama", Icons.Default.Favorite),
                BottomNavItem.WEB_SERIES to Pair("Web Series", Icons.Default.VideoLibrary),
                BottomNavItem.SHORT_DRAMA to Pair("Short Drama", Icons.Default.FlashOn),
                BottomNavItem.ANIME to Pair("Anime", Icons.Default.LiveTv),
                BottomNavItem.ACCOUNT to Pair("Main Menu", Icons.Default.Menu)
            )

            navItems.forEach { (item, pair) ->
                val (label, icon) = pair
                val isSelected = selectedItem == item

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { onItemSelected(item) }
                        .padding(vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (isSelected) CinemaRed else Color.Transparent)
                            .padding(horizontal = 14.dp, vertical = 4.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = icon,
                            contentDescription = label,
                            modifier = Modifier.size(20.dp),
                            tint = if (isSelected) MaterialTheme.colorScheme.background else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                        )
                    }
                    Spacer(modifier = Modifier.height(3.dp))
                    Text(
                        text = label,
                        color = if (isSelected) CinemaRed else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        fontSize = 9.sp,
                        fontWeight = if (isSelected) FontWeight.ExtraBold else FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
fun MyAccountTab(
    bookmarks: List<Bookmark>,
    history: List<HistoryItem>,
    isDarkTheme: Boolean,
    onToggleDarkTheme: () -> Unit,
    onSectionClick: (String) -> Unit,
    onDeleteBookmark: (String) -> Unit,
    onDeleteHistory: (Int) -> Unit,
    onClearHistory: () -> Unit,
    onEmailFeedback: () -> Unit,
    onDownloadClick: () -> Unit = {},
    onBrowserToggleClick: () -> Unit = {},
    onUrlPasteGoClick: (String) -> Unit = {}
) {
    var showBookmarksDialog by remember { mutableStateOf(false) }
    var showHistoryDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current
    var urlInput by remember { mutableStateOf("") }

    androidx.activity.compose.BackHandler(enabled = showHistoryDialog) {
        showHistoryDialog = false
    }
    androidx.activity.compose.BackHandler(enabled = showBookmarksDialog) {
        showBookmarksDialog = false
    }

    if (showHistoryDialog) {
        var searchQuery by remember { mutableStateOf("") }
        var isSearching by remember { mutableStateOf(false) }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        ) {
            // Header Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                if (isSearching) {
                    TextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = { Text("Search history...", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)) },
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 8.dp),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            disabledContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent
                        ),
                        singleLine = true,
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.Search,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            )
                        },
                        trailingIcon = {
                            if (searchQuery.isNotEmpty()) {
                                IconButton(onClick = { searchQuery = "" }) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "Clear search",
                                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                    )
                                }
                            }
                        }
                    )
                    IconButton(onClick = {
                        isSearching = false
                        searchQuery = ""
                    }) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Cancel search",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                } else {
                    Text(
                        text = "History & Visited Logs",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val infoContext = LocalContext.current
                        IconButton(onClick = {
                            Toast.makeText(infoContext, "Your browsing history is saved locally on this device.", Toast.LENGTH_SHORT).show()
                        }) {
                            Icon(
                                imageVector = Icons.Default.Info,
                                contentDescription = "Info",
                                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                            )
                        }
                        IconButton(onClick = { isSearching = true }) {
                            Icon(
                                imageVector = Icons.Default.Search,
                                contentDescription = "Search",
                                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                            )
                        }
                        IconButton(onClick = { showHistoryDialog = false }) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Close",
                                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                            )
                        }
                    }
                }
            }

            HorizontalDivider(
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                thickness = 1.dp
            )

            val filteredHistory = remember(history, searchQuery) {
                if (searchQuery.isEmpty()) {
                    history
                } else {
                    history.filter {
                        it.title.contains(searchQuery, ignoreCase = true) ||
                                it.url.contains(searchQuery, ignoreCase = true)
                    }
                }
            }

            if (filteredHistory.isEmpty()) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                        modifier = Modifier.padding(24.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.History,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = if (searchQuery.isEmpty()) "No history records" else "No matching items found",
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    }
                }
            } else {
                val groupedHistory = remember(filteredHistory) {
                    filteredHistory.groupBy { item ->
                        getGroupDateString(item.timestamp)
                    }
                }

                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentPadding = PaddingValues(bottom = 16.dp)
                ) {
                    groupedHistory.forEach { (dateGroupHeader, historyList) ->
                        item(key = "header_$dateGroupHeader") {
                            Text(
                                text = dateGroupHeader,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp)
                            )
                        }

                        items(historyList, key = { "item_${it.id}" }) { item ->
                            val uri = try { Uri.parse(item.url) } catch (e: Exception) { null }
                            val host = uri?.host ?: ""

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        onSectionClick(item.url)
                                        showHistoryDialog = false
                                    }
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                val isGoogle = host.contains("google.com", ignoreCase = true)
                                val isAbleDrama = host.contains("abledrama.top", ignoreCase = true)

                                val circleColor = if (isGoogle) {
                                    if (isDarkTheme) Color(0xFF3C4043) else Color(0xFFF1F3F4)
                                } else if (isAbleDrama) {
                                    CinemaRed.copy(alpha = 0.12f)
                                } else {
                                    val letters = host.replace("www.", "")
                                    val firstChar = if (letters.isNotEmpty()) letters.first().uppercaseChar() else 'W'
                                    val hue = (firstChar.code * 31) % 360
                                    Color.hsl(hue.toFloat(), 0.7f, if (isDarkTheme) 0.35f else 0.93f)
                                }

                                if (item.thumbnailUrl != null && item.thumbnailUrl.isNotBlank() && item.thumbnailUrl.startsWith("http")) {
                                    AsyncImage(
                                        model = item.thumbnailUrl,
                                        contentDescription = "Thumbnail",
                                        modifier = Modifier
                                            .size(40.dp)
                                            .clip(CircleShape),
                                        contentScale = ContentScale.Crop
                                    )
                                } else {
                                    Box(
                                        modifier = Modifier
                                            .size(40.dp)
                                            .clip(CircleShape)
                                            .background(circleColor),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        if (isGoogle) {
                                            Text(
                                                text = "G",
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 18.sp,
                                                color = if (isDarkTheme) Color.White else Color(0xFF4285F4)
                                            )
                                        } else if (isAbleDrama) {
                                            Icon(
                                                imageVector = Icons.Default.MovieFilter,
                                                contentDescription = null,
                                                tint = CinemaRed,
                                                modifier = Modifier.size(20.dp)
                                            )
                                        } else {
                                            val letters = host.replace("www.", "")
                                            val firstLetter = if (letters.isNotEmpty()) letters.first().uppercase() else "W"
                                            Text(
                                                text = firstLetter,
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 16.sp,
                                                color = if (isDarkTheme) Color.White else Color.DarkGray
                                            )
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.width(16.dp))

                                Column(
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text(
                                        text = item.title.ifBlank { "Untitled" },
                                        color = MaterialTheme.colorScheme.onSurface,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = host.ifBlank { item.url },
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                                        fontSize = 11.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }

                                Spacer(modifier = Modifier.width(8.dp))

                                IconButton(
                                    onClick = { onDeleteHistory(item.id) },
                                    modifier = Modifier.size(36.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "Delete item",
                                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }

                    // Clear all button at bottom of list
                    item(key = "clear_all_button_item") {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 24.dp, bottom = 12.dp),
                            horizontalArrangement = Arrangement.Center
                        ) {
                            TextButton(
                                onClick = onClearHistory,
                                colors = ButtonDefaults.textButtonColors(contentColor = CinemaRed)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.DeleteSweep,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Clear All History", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
    } else {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(16.dp)
                .testTag("my_account_view"),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
        // Vibrant Profile Card Banner
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("account_profile_card"),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, CinemaRed.copy(alpha = 0.2f))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            Brush.linearGradient(
                                listOf(
                                    MaterialTheme.colorScheme.surface,
                                    CinemaRed.copy(alpha = 0.08f)
                                )
                            )
                        )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        // Profile compact header Row
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(60.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(MaterialTheme.colorScheme.surface)
                                    .padding(2.dp)
                            ) {
                                Image(
                                    painter = painterResource(id = R.drawable.img_app_logo_1780217782245),
                                    contentDescription = "App Logo",
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .clip(RoundedCornerShape(8.dp))
                                )
                            }

                            Column(
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(
                                    text = "AbleDrama.top",
                                    color = MaterialTheme.colorScheme.onSurface,
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.ExtraBold
                                )
                            }

                            // Dark / Light Mode Toggle button (Night Mode Toggle Button moved to the left as per red mark)
                            IconButton(
                                onClick = onToggleDarkTheme,
                                modifier = Modifier
                                    .size(38.dp)
                                    .background(
                                        color = if (isDarkTheme) Color.White.copy(alpha = 0.1f) else CinemaRed.copy(alpha = 0.12f),
                                        shape = CircleShape
                                    )
                                    .testTag("theme_toggle_button")
                            ) {
                                Icon(
                                    imageVector = if (isDarkTheme) Icons.Default.LightMode else Icons.Default.DarkMode,
                                    contentDescription = "Toggle Theme Mode",
                                    tint = if (isDarkTheme) CinemaGold else CinemaRed,
                                    modifier = Modifier.size(20.dp)
                                )
                            }

                            // Browser Toggle Button (Original Night Mode position: navigates immediately to google.com)
                            IconButton(
                                onClick = { onBrowserToggleClick() },
                                modifier = Modifier
                                    .size(38.dp)
                                    .background(
                                        color = if (isDarkTheme) Color.White.copy(alpha = 0.1f) else CinemaRed.copy(alpha = 0.12f),
                                        shape = CircleShape
                                    )
                                    .testTag("browser_toggle_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Language,
                                    contentDescription = "Open google.com",
                                    tint = if (isDarkTheme) CinemaGold else CinemaRed,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }

                        // Custom URL Field & Go Button inside the Column as pictured
                        Spacer(modifier = Modifier.height(16.dp))
                        
                        OutlinedTextField(
                            value = urlInput,
                            onValueChange = { urlInput = it },
                            placeholder = {
                                Text("Paste URL or Search", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("paste_url_or_search_input"),
                            shape = RoundedCornerShape(12.dp),
                            singleLine = true,
                            trailingIcon = {
                                IconButton(
                                    onClick = {
                                        try {
                                            val clipboardManager = context.getClipboardManager()
                                            if (clipboardManager != null && clipboardManager.hasPrimaryClip()) {
                                                val primaryClipText = clipboardManager.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
                                                if (primaryClipText.isNotBlank()) {
                                                    urlInput = primaryClipText
                                                    Toast.makeText(context, "URL pasted!", Toast.LENGTH_SHORT).show()
                                                } else {
                                                    Toast.makeText(context, "Clipboard is empty", Toast.LENGTH_SHORT).show()
                                                }
                                            } else {
                                                Toast.makeText(context, "Clipboard is empty", Toast.LENGTH_SHORT).show()
                                            }
                                        } catch (e: Exception) {
                                            e.printStackTrace()
                                        }
                                    }
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.ContentPaste,
                                        contentDescription = "Paste from Clipboard",
                                        tint = CinemaGold
                                    )
                                }
                            },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f),
                                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.1f),
                                focusedBorderColor = CinemaGold,
                                unfocusedBorderColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
                                focusedLabelColor = CinemaGold,
                                unfocusedLabelColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                focusedTextColor = MaterialTheme.colorScheme.onSurface,
                                unfocusedTextColor = MaterialTheme.colorScheme.onSurface
                            )
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        Button(
                            onClick = {
                                if (urlInput.isNotBlank()) {
                                    onUrlPasteGoClick(urlInput)
                                } else {
                                    Toast.makeText(context, "Please enter a URL or search query first", Toast.LENGTH_SHORT).show()
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("paste_go_button"),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = CinemaGold,
                                contentColor = Color.Black
                            )
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Send,
                                    contentDescription = null,
                                    tint = Color.Black,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Go",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 15.sp,
                                    color = Color.Black
                                )
                            }
                        }
                    }
                }
            }
        }

        // Premium Highlighted Download Manager Section (High-conspicuous, as drawn in user's image)
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onDownloadClick() }
                    .testTag("portal_download_manager_card"),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.2.dp, CinemaGold.copy(alpha = 0.5f))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(46.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(CinemaGold.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Download,
                            contentDescription = "Download Manager",
                            tint = CinemaGold,
                            modifier = Modifier.size(26.dp)
                        )
                    }
                    
                    Spacer(modifier = Modifier.width(16.dp))
                    
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Download Manager",
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Icon(
                        imageVector = Icons.Default.ChevronRight,
                        contentDescription = "Navigate to downloads",
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }

        // Section Title: categories and support
        item {
            Text(
                text = "Important Website Portals",
                color = MaterialTheme.colorScheme.onBackground,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 4.dp)
            )
        }

        // Beautiful 6 Lists
        val accountSections = listOf(
            AccountSectionItem(
                titleBengali = "সাম্প্রতিক আপলোডসমূহ",
                titleEnglish = "Recent Uploads",
                url = "https://www.abledrama.top/search",
                icon = Icons.Default.NewReleases,
                badgeText = "NEW"
            ),
            AccountSectionItem(
                titleBengali = "চলমান আপলোডসমূহ",
                titleEnglish = "Ongoing Uploads",
                url = "https://www.abledrama.top/search/label/Ongoin",
                icon = Icons.Default.CloudUpload,
                badgeText = "ONLINE"
            ),
            AccountSectionItem(
                titleBengali = "ব্রাউজিং হিস্টোরি",
                titleEnglish = "History",
                url = "https://www.abledrama.top/history",
                icon = Icons.Default.History,
                badgeText = null
            ),
            AccountSectionItem(
                titleBengali = "ডাউনলোড করার নিয়ম",
                titleEnglish = "How to Download",
                url = "https://www.abledrama.top/p/how-to-download.html",
                icon = Icons.Default.Download,
                badgeText = "GUIDE"
            ),
            AccountSectionItem(
                titleBengali = "মুভি বা নাটকের অনুরোধ",
                titleEnglish = "Request A File",
                url = "https://www.abledrama.top/p/request-file-form.html",
                icon = Icons.AutoMirrored.Filled.Message,
                badgeText = "ASK"
            ),
            AccountSectionItem(
                titleBengali = "ডিএমসিএ পলিসি",
                titleEnglish = "DMCA Disclaimer",
                url = "https://www.abledrama.top/p/dmca-remove-your-file.html",
                icon = Icons.Default.Gavel,
                badgeText = null
            )
        )

        items(accountSections, key = { it.titleEnglish }) { sec ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        if (sec.titleEnglish == "History") {
                            showHistoryDialog = true
                        } else if (sec.titleEnglish == "Download") {
                            onDownloadClick()
                        } else {
                            onSectionClick(sec.url)
                        }
                    }
                    .testTag("sec_item_${sec.titleEnglish.replace(" ", "_")}"),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(CinemaRed.copy(alpha = 0.12f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = sec.icon,
                                contentDescription = sec.titleEnglish,
                                tint = CinemaRed,
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        Spacer(modifier = Modifier.width(14.dp))

                        Column {
                            Text(
                                text = sec.titleEnglish,
                                color = MaterialTheme.colorScheme.onSurface,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = if (sec.titleEnglish == "History") {
                                    val lastItem = history.firstOrNull()
                                    if (lastItem != null) "Last: ${lastItem.title}" else "Open last browsed page"
                                } else {
                                    "Navigate to official portal"
                                },
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (sec.badgeText != null) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(CinemaGold.copy(alpha = 0.2f))
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = sec.badgeText,
                                    color = CinemaGold,
                                    fontSize = 8.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                        Icon(
                            imageVector = Icons.Default.ChevronRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }

        // Section Title: Local utility storage options
        item {
            Text(
                text = "Local Browser Tools",
                color = MaterialTheme.colorScheme.onBackground,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 8.dp)
            )
        }

        // Two offline action buttons inside cards
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Local Bookmarks Dialog Trigger
                Card(
                    modifier = Modifier
                        .weight(1f)
                        .clickable { showBookmarksDialog = true }
                        .testTag("local_bookmarks_trigger"),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Icon(
                            imageVector = Icons.Default.Bookmark,
                            contentDescription = "Bookmarks",
                            tint = if (bookmarks.isNotEmpty()) Color(0xFFE50914) else CinemaGold,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Bookmarks",
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "${bookmarks.size} items bookmarked",
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                            fontSize = 10.sp
                        )
                    }
                }

                // Go to Home trigger
                Card(
                    modifier = Modifier
                        .weight(1f)
                        .clickable { onSectionClick("https://www.abledrama.top") }
                        .testTag("local_history_trigger"),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Icon(
                            imageVector = Icons.Default.Home,
                            contentDescription = "Go to Home",
                            tint = CinemaGold,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Go to Home",
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "abledrama.top",
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                            fontSize = 10.sp
                        )
                    }
                }
            }
        }

        // Support Contact Options
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 24.dp),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Help & Developer Support",
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "If any drama or movie link fails to open or you encounter a download manager issue, please report it to our developer support team directly.",
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        fontSize = 11.sp,
                        lineHeight = 16.sp
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(
                        onClick = onEmailFeedback,
                        colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent),
                        border = BorderStroke(1.dp, CinemaRed),
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(imageVector = Icons.Default.Email, contentDescription = null, tint = CinemaGold)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Email Support Ticket", color = MaterialTheme.colorScheme.onSurface, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

    // Bookmarks Dialog
    if (showBookmarksDialog) {
        AlertDialog(
            onDismissRequest = { showBookmarksDialog = false },
            containerColor = MaterialTheme.colorScheme.surface,
            title = {
                Text(
                    text = "Saved Bookmarks",
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )
            },
            text = {
                Box(modifier = Modifier.heightIn(max = 400.dp)) {
                    if (bookmarks.isEmpty()) {
                        Text(
                            text = "No saved bookmarks. Click on the bookmark icon inside address bar while browsing to add pages.",
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                            fontSize = 13.sp
                        )
                    } else {
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            items(bookmarks, key = { "bookmark_${it.url}" }) { item ->
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            onSectionClick(item.url)
                                            showBookmarksDialog = false
                                        },
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.background),
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                                ) {
                                    Row(
                                        modifier = Modifier.padding(12.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.MovieFilter,
                                            contentDescription = null,
                                            tint = CinemaRed,
                                            modifier = Modifier.size(20.dp)
                                        )
                                        Spacer(modifier = Modifier.width(10.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = item.title,
                                                color = MaterialTheme.colorScheme.onSurface,
                                                fontSize = 13.sp,
                                                fontWeight = FontWeight.Bold,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Text(
                                                text = item.url,
                                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                                fontSize = 10.sp,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                        IconButton(onClick = { onDeleteBookmark(item.url) }) {
                                            Icon(
                                                imageVector = Icons.Default.Delete,
                                                contentDescription = "Delete",
                                                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showBookmarksDialog = false }) {
                    Text("Dismiss", color = CinemaRed)
                }
            }
        )
    }

    // History Dialog (Refactored Inline)
    if (false) {
        Dialog(
            onDismissRequest = { showHistoryDialog = false },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background
            ) {
                var searchQuery by remember { mutableStateOf("") }
                var isSearching by remember { mutableStateOf(false) }

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .statusBarsPadding()
                ) {
                    // Header Row
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        if (isSearching) {
                            TextField(
                                value = searchQuery,
                                onValueChange = { searchQuery = it },
                                placeholder = { Text("Search history...", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)) },
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(end = 8.dp),
                                colors = TextFieldDefaults.colors(
                                    focusedContainerColor = Color.Transparent,
                                    unfocusedContainerColor = Color.Transparent,
                                    disabledContainerColor = Color.Transparent,
                                    focusedIndicatorColor = Color.Transparent,
                                    unfocusedIndicatorColor = Color.Transparent
                                ),
                                singleLine = true,
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Default.Search,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                    )
                                },
                                trailingIcon = {
                                    if (searchQuery.isNotEmpty()) {
                                        IconButton(onClick = { searchQuery = "" }) {
                                            Icon(
                                                imageVector = Icons.Default.Close,
                                                contentDescription = "Clear search",
                                                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                            )
                                        }
                                    }
                                }
                            )
                            IconButton(onClick = {
                                isSearching = false
                                searchQuery = ""
                            }) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Cancel search",
                                    tint = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        } else {
                            Text(
                                text = "History",
                                fontSize = 22.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Row(
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                val infoContext = LocalContext.current
                                IconButton(onClick = {
                                    Toast.makeText(infoContext, "Your browsing history is saved locally on this device.", Toast.LENGTH_SHORT).show()
                                }) {
                                    Icon(
                                        imageVector = Icons.Default.Info,
                                        contentDescription = "Info",
                                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                                    )
                                }
                                IconButton(onClick = { isSearching = true }) {
                                    Icon(
                                        imageVector = Icons.Default.Search,
                                        contentDescription = "Search",
                                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                                    )
                                }
                                IconButton(onClick = { showHistoryDialog = false }) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "Close",
                                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                                    )
                                }
                            }
                        }
                    }

                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                        thickness = 1.dp
                    )

                    val filteredHistory = remember(history, searchQuery) {
                        if (searchQuery.isEmpty()) {
                            history
                        } else {
                            history.filter {
                                it.title.contains(searchQuery, ignoreCase = true) ||
                                        it.url.contains(searchQuery, ignoreCase = true)
                            }
                        }
                    }

                    if (filteredHistory.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center,
                                modifier = Modifier.padding(24.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.History,
                                    contentDescription = null,
                                    modifier = Modifier.size(64.dp),
                                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                                )
                                Spacer(modifier = Modifier.height(16.dp))
                                Text(
                                    text = if (searchQuery.isEmpty()) "No history records" else "No matching items found",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 16.sp,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                )
                            }
                        }
                    } else {
                        val groupedHistory = remember(filteredHistory) {
                            filteredHistory.groupBy { item ->
                                getGroupDateString(item.timestamp)
                            }
                        }

                        LazyColumn(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                            contentPadding = PaddingValues(bottom = 16.dp)
                        ) {
                            groupedHistory.forEach { (dateGroupHeader, historyList) ->
                                item(key = "header_$dateGroupHeader") {
                                    Text(
                                        text = dateGroupHeader,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp)
                                    )
                                }

                                items(historyList, key = { "item_${it.id}" }) { item ->
                                    val uri = try { Uri.parse(item.url) } catch (e: Exception) { null }
                                    val host = uri?.host ?: ""

                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                onSectionClick(item.url)
                                                showHistoryDialog = false
                                            }
                                            .padding(horizontal = 16.dp, vertical = 12.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        val isGoogle = host.contains("google.com", ignoreCase = true)
                                        val isAbleDrama = host.contains("abledrama.top", ignoreCase = true)

                                        val circleColor = if (isGoogle) {
                                            if (isDarkTheme) Color(0xFF3C4043) else Color(0xFFF1F3F4)
                                        } else if (isAbleDrama) {
                                            CinemaRed.copy(alpha = 0.12f)
                                        } else {
                                            val letters = host.replace("www.", "")
                                            val firstChar = if (letters.isNotEmpty()) letters.first().uppercaseChar() else 'W'
                                            val hue = (firstChar.code * 31) % 360
                                            Color.hsl(hue.toFloat(), 0.7f, if (isDarkTheme) 0.35f else 0.93f)
                                        }

                                        Box(
                                            modifier = Modifier
                                                .size(40.dp)
                                                .clip(CircleShape)
                                                .background(circleColor),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            if (isGoogle) {
                                                Text(
                                                    text = "G",
                                                    fontWeight = FontWeight.Bold,
                                                    fontSize = 18.sp,
                                                    color = if (isDarkTheme) Color.White else Color(0xFF4285F4)
                                                )
                                            } else if (isAbleDrama) {
                                                Icon(
                                                    imageVector = Icons.Default.MovieFilter,
                                                    contentDescription = null,
                                                    tint = CinemaRed,
                                                    modifier = Modifier.size(20.dp)
                                                )
                                            } else {
                                                val letters = host.replace("www.", "")
                                                val firstLetter = if (letters.isNotEmpty()) letters.first().uppercase() else "W"
                                                Text(
                                                    text = firstLetter,
                                                    fontWeight = FontWeight.Bold,
                                                    fontSize = 16.sp,
                                                    color = if (isDarkTheme) Color.White else Color.DarkGray
                                                )
                                            }
                                        }

                                        Spacer(modifier = Modifier.width(16.dp))

                                        Column(
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Text(
                                                text = item.title.ifBlank { "Untitled" },
                                                color = MaterialTheme.colorScheme.onSurface,
                                                fontSize = 14.sp,
                                                fontWeight = FontWeight.Medium,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Spacer(modifier = Modifier.height(2.dp))
                                            Text(
                                                text = host.ifBlank { item.url },
                                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                                                fontSize = 11.sp,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }

                                        Spacer(modifier = Modifier.width(8.dp))

                                        IconButton(
                                            onClick = { onDeleteHistory(item.id) },
                                            modifier = Modifier.size(36.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Close,
                                                contentDescription = "Delete item",
                                                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    }
                                }
                            }

                            // Clear all button at bottom of list
                            item(key = "clear_all_button_item") {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 24.dp, bottom = 12.dp),
                                    horizontalArrangement = Arrangement.Center
                                ) {
                                    TextButton(
                                        onClick = onClearHistory,
                                        colors = ButtonDefaults.textButtonColors(contentColor = CinemaRed)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.DeleteSweep,
                                            contentDescription = null,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("Clear All History", fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

data class AccountSectionItem(
    val titleBengali: String,
    val titleEnglish: String,
    val url: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val badgeText: String?
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BookmarksHistoryTab(
    bookmarks: List<Bookmark>,
    history: List<HistoryItem>,
    onBookmarkClick: (String) -> Unit,
    onHistoryClick: (String) -> Unit,
    onDeleteBookmark: (String) -> Unit,
    onDeleteHistory: (Int) -> Unit,
    onClearHistory: () -> Unit
) {
    var subTabState by remember { mutableStateOf(0) } // 0 = Bookmarks, 1 = History

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // Double Switch tab
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(SurfaceSlate)
                .padding(4.dp)
        ) {
            Button(
                onClick = { subTabState = 0 },
                modifier = Modifier.weight(1f).testTag("sub_tab_bookmarks"),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (subTabState == 0) CinemaRed else Color.Transparent,
                    contentColor = Color.White
                ),
                shape = RoundedCornerShape(10.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Bookmark,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text("Bookmarks (${bookmarks.size})", fontWeight = FontWeight.SemiBold)
            }

            Button(
                onClick = { subTabState = 1 },
                modifier = Modifier.weight(1f).testTag("sub_tab_history"),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (subTabState == 1) CinemaRed else Color.Transparent,
                    contentColor = Color.White
                ),
                shape = RoundedCornerShape(10.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.History,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text("Visited Logs", fontWeight = FontWeight.SemiBold)
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (subTabState == 0) {
            // Bookmarks Layout View
            if (bookmarks.isEmpty()) {
                EmptyStateCard(
                    title = "No Bookmarks",
                    subtitle = "Bookmark your favorite movies or channels to visit them quickly next time.",
                    icon = Icons.Outlined.BookmarkAdd
                )
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f).testTag("bookmarks_list"),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(bookmarks, key = { it.id }) { item ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .combinedClickable(
                                    onClick = { onBookmarkClick(item.url) },
                                    onLongClick = { onDeleteBookmark(item.url) }
                                )
                                .testTag("bookmark_item_${item.id}"),
                            colors = CardDefaults.cardColors(containerColor = SurfaceSlate),
                            border = CardDefaults.outlinedCardBorder().copy(brush = Brush.linearGradient(listOf(BorderGrey, BorderGrey)))
                        ) {
                            Row(
                                modifier = Modifier
                                    .padding(14.dp)
                                    .fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(40.dp)
                                        .clip(CircleShape)
                                        .background(CinemaRed.copy(alpha = 0.15f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.MovieFilter,
                                        contentDescription = null,
                                        tint = CinemaRed,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }

                                Spacer(modifier = Modifier.width(14.dp))

                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = item.title,
                                        color = Color.White,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = item.url,
                                        color = Color.LightGray,
                                        fontSize = 11.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }

                                IconButton(onClick = { onDeleteBookmark(item.url) }, modifier = Modifier.testTag("delete_bookmark_${item.id}")) {
                                    Icon(
                                        imageVector = Icons.Default.BookmarkRemove,
                                        contentDescription = "Remove Bookmark",
                                        tint = Color.Gray
                                    )
                                }
                            }
                        }
                    }
                }
            }
        } else {
            // History Logs View
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Recent Browsing History",
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold
                )
                if (history.isNotEmpty()) {
                    TextButton(
                        onClick = onClearHistory,
                        colors = ButtonDefaults.textButtonColors(contentColor = CinemaRed),
                        modifier = Modifier.testTag("clear_history_btn")
                    ) {
                        Icon(imageVector = Icons.Default.DeleteSweep, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Clear All")
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            if (history.isEmpty()) {
                EmptyStateCard(
                    title = "History Log is Empty",
                    subtitle = "The websites and pages you visit using this companion browser will appear here.",
                    icon = Icons.Outlined.History
                )
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f).testTag("history_list"),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(history, key = { it.id }) { item ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onHistoryClick(item.url) }
                                .testTag("history_item_${item.id}"),
                            colors = CardDefaults.cardColors(containerColor = SurfaceSlate)
                        ) {
                            Row(
                                modifier = Modifier
                                    .padding(12.dp)
                                    .fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = item.title,
                                        color = Color.White,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = formatTimestamp(item.timestamp),
                                            color = Color.Gray,
                                            fontSize = 9.sp
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = "•",
                                            color = Color.Gray,
                                            fontSize = 9.sp
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = item.url,
                                            color = CinemaGold.copy(alpha = 0.8f),
                                            fontSize = 10.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f)
                                        )
                                    }
                                }

                                IconButton(onClick = { onDeleteHistory(item.id) }, modifier = Modifier.testTag("delete_history_${item.id}")) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "Delete item",
                                        tint = Color.Gray,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun HelpInstructionsTab(onNavigateToMirror: (String) -> Unit) {
    val context = LocalContext.current
    
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .testTag("faq_help_view"),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // App Identity Header Card
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = SurfaceSlate),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier
                            .size(64.dp)
                            .clip(CircleShape)
                            .background(CinemaRed),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Movie,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(36.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Text(
                        text = "Able Drama Companion",
                        color = Color.White,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                    
                    Text(
                        text = "Version 2.0.0",
                        color = CinemaGold,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    
                    Spacer(modifier = Modifier.height(2.dp))
                    
                    Text(
                        text = "Required Android 4.4 up",
                        color = CinemaGold.copy(alpha = 0.8f),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = "This is a dedicated secure client browser designed for Able Drama enthusiasts, making it remarkably convenient to filter, trace, stream, and download favorite films or TV soaps safely.",
                        color = Color.LightGray,
                        textAlign = TextAlign.Center,
                        fontSize = 12.sp,
                        lineHeight = 18.sp
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        Button(
                            onClick = { onNavigateToMirror("https://www.ablesrama.top") },
                            colors = ButtonDefaults.buttonColors(containerColor = CinemaRed),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("Mirror 1", fontWeight = FontWeight.Bold)
                        }

                        Button(
                            onClick = { onNavigateToMirror("https://www.abledrama.top") },
                            colors = ButtonDefaults.buttonColors(containerColor = SurfaceSlate),
                            shape = RoundedCornerShape(8.dp),
                            border = ButtonDefaults.outlinedButtonBorder()
                        ) {
                            Text("Mirror 2 (Stable)", color = Color.White, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        // Guide Instructions Card
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = SurfaceSlate)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "How to Download Movies & Series",
                        color = CinemaGold,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
                    
                    Spacer(modifier = Modifier.height(12.dp))

                    val steps = listOf(
                        "1. Select your target movie, trailer, or serial from **Main Cinema Feed** or custom categories tab.",
                        "2. Navigate to that show's details and tap on **Download Now** trigger buttons.",
                        "3. Simply use one-tap back actions to cancel out aggressive redirection / advertising pop-ups.",
                        "4. Once on the stream hosting directory, click download to prompt system's background **Download Manager**.",
                        "5. Observe real-time progress of downloading media within standard system notification tray."
                    )

                    steps.forEach { step ->
                        Text(
                            text = step,
                            color = Color.White,
                            fontSize = 13.sp,
                            lineHeight = 20.sp,
                            modifier = Modifier.padding(vertical = 4.dp)
                        )
                    }
                }
            }
        }

        // Help Actions Card
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = SurfaceSlate)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Support & Feedback",
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                    
                    Spacer(modifier = Modifier.height(10.dp))

                    Text(
                        text = "If you encounter any specific web redirection loops, bookmark crashes, or audio issues during downloads, feel free to drop an email to developer help-desk.",
                        color = Color.LightGray,
                        fontSize = 12.sp,
                        lineHeight = 18.sp
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Button(
                        onClick = {
                            val intent = Intent(Intent.ACTION_SENDTO).apply {
                                data = Uri.parse("mailto:mondalsujoy1147@gmail.com")
                                putExtra(Intent.EXTRA_SUBJECT, "Able Drama Android App Feedback")
                            }
                            try {
                                context.startActivity(intent)
                            } catch (e: Exception) {
                                Toast.makeText(context, "Email client application not found.", Toast.LENGTH_SHORT).show()
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent),
                        border = ButtonDefaults.outlinedButtonBorder(),
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(imageVector = Icons.Default.Email, contentDescription = null, tint = CinemaGold)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Email Developer Feedback", color = Color.White)
                    }
                }
            }
        }
    }
}

@Composable
fun EmptyStateCard(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 32.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceSlate.copy(alpha = 0.5f)),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier
                .padding(24.dp)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = Color.Gray,
                modifier = Modifier.size(48.dp)
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = title,
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = subtitle,
                color = Color.Gray,
                fontSize = 12.sp,
                lineHeight = 18.sp,
                textAlign = TextAlign.Center
            )
        }
    }
}

// Check if a URL represents an active post/article
fun isPostUrl(url: String?): Boolean {
    if (url.isNullOrBlank()) return false
    val u = url.lowercase().trim()
    
    // Must be on the domain
    if (!u.contains("abledrama.top") && !u.contains("ablesrama.top")) return false
    
    // Must NOT be search, label categories, root index, history, etc.
    if (u.contains("/search") ||
        u.contains("/category/") ||
        u.contains("/p/") ||
        u.endsWith(".top") ||
        u.endsWith(".top/") ||
        u.substringAfter(".top").isBlank() ||
        u.substringAfter(".top/").isBlank() ||
        u.substringAfter(".top/").startsWith("?m=") ||
        u.contains("abledrama.top/history") ||
        u.contains("ablesrama.top/history")
    ) {
        return false
    }
    
    return u.contains(".html") || u.contains("/20")
}

// Convert system time to clock format
fun formatTimestamp(timestamp: Long): String {
    return try {
        val sdf = SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault())
        sdf.format(Date(timestamp))
    } catch (e: Exception) {
        ""
    }
}

// Group history entries by day
fun getGroupDateString(timestamp: Long): String {
    return try {
        val sdfDay = SimpleDateFormat("dd MMMM yyyy", Locale.getDefault())
        val itemDateStr = sdfDay.format(Date(timestamp))
        val todayStr = sdfDay.format(Date())
        val yesterdayStr = sdfDay.format(Date(System.currentTimeMillis() - 24 * 60 * 60 * 1000))
        when (itemDateStr) {
            todayStr -> "Today - $itemDateStr"
            yesterdayStr -> "Yesterday - $itemDateStr"
            else -> itemDateStr
        }
    } catch (e: Exception) {
        "Recent Browsing History"
    }
}

// Inline extension padding for notches
@Composable
fun safeDrawingPadding(): PaddingValues {
    return WindowInsets.safeDrawing.asPaddingValues()
}

@Composable
fun CategorySelectionRow(
    currentUrl: String,
    onCategorySelected: (String) -> Unit
) {
    val categories = remember {
        listOf(
            CategoryItem("K-Drama", "k-drama", "https://www.abledrama.top/category/k-drama"),
            CategoryItem("Bengali", "bengali", "https://www.abledrama.top/category/bengali"),
            CategoryItem("Web Series", "web series", "https://www.abledrama.top/category/web-series"),
            CategoryItem("Movies", "movies", "https://www.abledrama.top/category/movies")
        )
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(DarkVacuum)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        categories.forEach { cat ->
            // Highlight chip if the user is currently browsing this section
            val isSelected = currentUrl.contains(cat.slug, ignoreCase = true)
            
            Surface(
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { onCategorySelected(cat.url) }
                    .testTag("cat_chip_${cat.slug}"),
                color = if (isSelected) CinemaRed else SurfaceSlate,
                border = BorderStroke(1.dp, if (isSelected) Color.Transparent else BorderGrey)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = when(cat.slug) {
                            "k-drama" -> Icons.Default.Favorite
                            "bengali" -> Icons.Default.FilterVintage
                            "web-series" -> Icons.Default.Style
                            else -> Icons.Default.LocalPlay
                        },
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = if (isSelected) DarkVacuum else CinemaRed
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = cat.banglaName,
                        color = if (isSelected) DarkVacuum else Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

data class CategoryItem(
    val banglaName: String,
    val englishName: String,
    val url: String
) {
    val slug: String
        get() = when(englishName.lowercase()) {
            "k-drama" -> "k-drama"
            "bengali" -> "bengali"
            "web series" -> "web-series"
            else -> "movies"
        }
}

@Composable
fun SplashScreen(isDarkTheme: Boolean) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(if (isDarkTheme) Color(0xFF15121F) else Color(0xFFF9F9FB)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Perfect beautiful circle app logo
            Box(
                modifier = Modifier
                    .size(140.dp)
                    .clip(CircleShape)
                    .background(if (isDarkTheme) Color(0xFF1E1A30) else Color(0xFFEEEEF2))
                    .padding(4.dp)
            ) {
                Image(
                    painter = painterResource(id = R.drawable.img_app_logo_1780217782245),
                    contentDescription = "App Logo",
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(CircleShape),
                    contentScale = ContentScale.Crop
                )
            }
            
            Spacer(modifier = Modifier.height(24.dp))
            
            Text(
                text = "ABLE DRAMA",
                color = CinemaRed,
                fontSize = 24.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = 4.sp
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Text(
                text = "Your Ultimate Entertainment",
                color = if (isDarkTheme) Color.LightGray else Color.DarkGray,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
fun BrowserMenuItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    testTag: String,
    isDarkTheme: Boolean,
    trailingContent: @Composable (() -> Unit)? = null,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .testTag(testTag)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = if (isDarkTheme) Color(0xFFE6E1E5) else Color(0xFF5F6368),
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.width(16.dp))
        Text(
            text = label,
            color = if (isDarkTheme) Color.White else Color(0xFF202124),
            fontSize = 14.sp,
            fontWeight = FontWeight.Normal,
            modifier = Modifier.weight(1f)
        )
        if (trailingContent != null) {
            trailingContent()
        }
    }
}

@Composable
fun BrowserActionMenuPopup(
    isBookmarked: Boolean,
    canGoBack: Boolean,
    canGoForward: Boolean,
    onDismiss: () -> Unit,
    onReload: () -> Unit,
    onBookmarkToggle: () -> Unit,
    onShareAction: () -> Unit,
    onGoBack: () -> Unit,
    onGoForward: () -> Unit,
    onHistoryClick: () -> Unit,
    onDownloadClick: () -> Unit,
    onExitBrowser: () -> Unit,
    isDarkTheme: Boolean,
    onToggleDarkTheme: () -> Unit,
    onNewTabClick: () -> Unit = {},
    onTranslateClick: () -> Unit = {},
    onAboutClick: () -> Unit = {},
    isDesktopModeEnabled: Boolean = false,
    onDesktopModeToggle: () -> Unit = {}
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(onClick = onDismiss)
                .background(Color.Black.copy(alpha = 0.25f)),
            contentAlignment = Alignment.TopEnd
        ) {
            Card(
                modifier = Modifier
                    .width(260.dp)
                    .padding(top = 52.dp, end = 12.dp)
                    .clickable(enabled = false, onClick = {}),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = if (isDarkTheme) Color(0xFF121217) else Color.White),
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
                border = BorderStroke(0.5.dp, if (isDarkTheme) Color(0xFF1F1F28) else Color(0xFFE0E0E0))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                ) {
                    // Top horizontal action row
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val iconBg = if (isDarkTheme) Color(0xFF1F1F28) else Color(0xFFF1F3F4)
                        val iconTint = if (isDarkTheme) Color.White else Color(0xFF202124)
                        val iconDisabledTint = if (isDarkTheme) Color(0xFF49454F) else Color(0xFFC4C7C5)

                        // Previous Arrow (Back) Button
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(iconBg)
                                .clickable(enabled = canGoBack) {
                                    onDismiss()
                                    onGoBack()
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back",
                                tint = if (canGoBack) iconTint else iconDisabledTint,
                                modifier = Modifier.size(18.dp)
                            )
                        }

                        // Next Arrow (Forward) Button
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(iconBg)
                                .clickable(enabled = canGoForward) {
                                    onDismiss()
                                    onGoForward()
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                                contentDescription = "Forward",
                                tint = if (canGoForward) iconTint else iconDisabledTint,
                                modifier = Modifier.size(18.dp)
                            )
                        }

                        // Bookmark Button (Star)
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(iconBg)
                                .clickable {
                                    onDismiss()
                                    onBookmarkToggle()
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = if (isBookmarked) Icons.Default.Star else Icons.Default.StarBorder,
                                contentDescription = "Bookmark",
                                tint = if (isBookmarked) (if (isDarkTheme) Color(0xFFFFB300) else Color(0xFF1A73E8)) else (if (isDarkTheme) Color(0xFFE6E1E5) else Color(0xFF5F6368)),
                                modifier = Modifier.size(18.dp)
                            )
                        }

                        // Download Button
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(iconBg)
                                .clickable {
                                    onDismiss()
                                    onDownloadClick()
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Download,
                                contentDescription = "Downloads",
                                tint = if (isDarkTheme) Color(0xFFE6E1E5) else Color(0xFF5F6368),
                                modifier = Modifier.size(18.dp)
                            )
                        }

                        // Reload Button
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(iconBg)
                                .clickable {
                                    onDismiss()
                                    onReload()
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "Reload",
                                tint = if (isDarkTheme) Color(0xFFE6E1E5) else Color(0xFF5F6368),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }

                    val dividerColor = if (isDarkTheme) Color(0xFF1F1F28) else Color(0xFFE0E0E0)
                    HorizontalDivider(color = dividerColor, thickness = 0.5.dp, modifier = Modifier.padding(vertical = 4.dp))

                    // Menu List Items
                    BrowserMenuItem(
                        icon = Icons.Outlined.AddBox,
                        label = "New tab",
                        testTag = "browser_menu_new_tab",
                        isDarkTheme = isDarkTheme,
                        onClick = {
                            onDismiss()
                            onNewTabClick()
                        }
                    )

                    BrowserMenuItem(
                        icon = Icons.Default.History,
                        label = "History",
                        testTag = "browser_menu_history",
                        isDarkTheme = isDarkTheme,
                        onClick = {
                            onDismiss()
                            onHistoryClick()
                        }
                    )

                    BrowserMenuItem(
                        icon = Icons.Default.Download,
                        label = "Downloads",
                        testTag = "browser_menu_downloads",
                        isDarkTheme = isDarkTheme,
                        onClick = {
                            onDismiss()
                            onDownloadClick()
                        }
                    )

                    BrowserMenuItem(
                        icon = Icons.Default.Bookmark,
                        label = "Bookmarks",
                        testTag = "browser_menu_bookmarks",
                        isDarkTheme = isDarkTheme,
                        onClick = {
                            onDismiss()
                            onHistoryClick()
                        }
                    )

                    HorizontalDivider(color = dividerColor, thickness = 0.5.dp, modifier = Modifier.padding(vertical = 4.dp))

                    BrowserMenuItem(
                        icon = Icons.Default.Share,
                        label = "Share...",
                        testTag = "browser_menu_share",
                        isDarkTheme = isDarkTheme,
                        onClick = {
                            onDismiss()
                            onShareAction()
                        }
                    )

                    BrowserMenuItem(
                        icon = Icons.Default.Translate,
                        label = "Translate",
                        testTag = "browser_menu_translate",
                        isDarkTheme = isDarkTheme,
                        onClick = {
                            onDismiss()
                            onTranslateClick()
                        }
                    )

                    BrowserMenuItem(
                        icon = Icons.Default.Laptop,
                        label = "Desktop Mode",
                        testTag = "browser_menu_desktop_mode",
                        isDarkTheme = isDarkTheme,
                        trailingContent = {
                            Checkbox(
                                checked = isDesktopModeEnabled,
                                onCheckedChange = null,
                                colors = CheckboxDefaults.colors(
                                    checkedColor = if (isDarkTheme) CinemaRed else MaterialTheme.colorScheme.primary,
                                    uncheckedColor = if (isDarkTheme) Color(0xFFE6E1E5) else Color(0xFF5F6368)
                                ),
                                modifier = Modifier.testTag("browser_menu_desktop_mode_checkbox")
                            )
                        },
                        onClick = {
                            onDismiss()
                            onDesktopModeToggle()
                        }
                    )

                    BrowserMenuItem(
                        icon = Icons.Default.Delete,
                        label = "Delete browsing data",
                        testTag = "browser_menu_delete_data",
                        isDarkTheme = isDarkTheme,
                        onClick = {
                            onDismiss()
                            onHistoryClick()
                        }
                    )

                    BrowserMenuItem(
                        icon = Icons.Default.Info,
                        label = "About Browser",
                        testTag = "browser_menu_about",
                        isDarkTheme = isDarkTheme,
                        onClick = {
                            onDismiss()
                            onAboutClick()
                        }
                    )

                    HorizontalDivider(color = dividerColor, thickness = 0.5.dp, modifier = Modifier.padding(vertical = 4.dp))

                    BrowserMenuItem(
                        icon = Icons.Default.Close,
                        label = "Exit & Back to Home",
                        testTag = "browser_menu_exit",
                        isDarkTheme = isDarkTheme,
                        onClick = {
                            onDismiss()
                            onExitBrowser()
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun BrowserHistoryBookmarksDialog(
    browserBookmarks: List<Bookmark>,
    browserHistory: List<HistoryItem>,
    isDarkTheme: Boolean,
    onDismissRequest: () -> Unit,
    onUrlClick: (String) -> Unit,
    onDeleteBookmark: (String) -> Unit,
    onDeleteHistory: (Int) -> Unit,
    onClearAllHistory: () -> Unit,
    onExitClick: () -> Unit
) {
    var subTabState by remember { mutableStateOf(0) } // 0 = Bookmarks, 1 = History
    var searchQuery by remember { mutableStateOf("") }

    val bgColor = if (isDarkTheme) Color(0xFF08080A) else Color(0xFFFAFAFC)
    val headerTextColor = if (isDarkTheme) Color.White else Color(0xFF131317)
    val closeBtnBg = if (isDarkTheme) Color(0xFF121217) else Color(0xFFF1F3F4)
    val closeBtnTint = if (isDarkTheme) Color.LightGray else Color(0xFF5F6368)
    val tabsBg = if (isDarkTheme) Color(0xFF121217) else Color(0xFFF1F3F4)
    val tabActiveBg = if (isDarkTheme) Color(0xFF1F1F24) else Color.White
    val tabActiveText = if (isDarkTheme) CinemaGold else Color(0xFF1A73E8)
    val tabInactiveText = if (isDarkTheme) Color.Gray else Color(0xFF5F6368)
    val itemCardBg = if (isDarkTheme) Color(0xFF121217) else Color.White
    val itemBorderColor = if (isDarkTheme) Color(0xFF1F1F28) else Color(0xFFE0E0E0)
    val titleTextColor = if (isDarkTheme) Color.White else Color(0xFF131317)
    val subtitleTextColor = if (isDarkTheme) Color.LightGray else Color(0xFF5F6368)

    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(bgColor)
        ) {
            // Header Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = if (subTabState == 0) "Browser Bookmarks" else "Browser History",
                    color = headerTextColor,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )

                IconButton(
                    onClick = onDismissRequest,
                    modifier = Modifier.background(closeBtnBg, CircleShape)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close",
                        tint = closeBtnTint
                    )
                }
            }

            // Tabs Switch Menu (Chrome Style)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(tabsBg)
                    .padding(4.dp)
            ) {
                Button(
                    onClick = { subTabState = 0 },
                    modifier = Modifier.weight(1f).testTag("browser_sub_tab_bookmarks"),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (subTabState == 0) tabActiveBg else Color.Transparent,
                        contentColor = if (subTabState == 0) tabActiveText else tabInactiveText
                    ),
                    shape = RoundedCornerShape(10.dp),
                    elevation = if (subTabState == 0) ButtonDefaults.buttonElevation(defaultElevation = 2.dp) else null
                ) {
                    Icon(
                        imageVector = Icons.Default.Bookmark,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Bookmarks (${browserBookmarks.size})", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                }

                Button(
                    onClick = { subTabState = 1 },
                    modifier = Modifier.weight(1f).testTag("browser_sub_tab_history"),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (subTabState == 1) tabActiveBg else Color.Transparent,
                        contentColor = if (subTabState == 1) tabActiveText else tabInactiveText
                    ),
                    shape = RoundedCornerShape(10.dp),
                    elevation = if (subTabState == 1) ButtonDefaults.buttonElevation(defaultElevation = 2.dp) else null
                ) {
                    Icon(
                        imageVector = Icons.Default.History,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("History (${browserHistory.size})", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Search input field
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .testTag("browser_search_input"),
                placeholder = { Text("Search title or URL...", color = subtitleTextColor) },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = titleTextColor,
                    unfocusedTextColor = titleTextColor,
                    focusedBorderColor = if (isDarkTheme) CinemaGold else Color(0xFF1A73E8),
                    unfocusedBorderColor = if (isDarkTheme) Color(0xFF262633) else Color(0xFFE0E0E0),
                    focusedContainerColor = tabsBg,
                    unfocusedContainerColor = tabsBg
                ),
                shape = RoundedCornerShape(12.dp),
                maxLines = 1,
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Default.Close, contentDescription = "Clear", tint = closeBtnTint)
                        }
                    }
                }
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Content Area which shows bookmarks or history lists
            if (subTabState == 0) {
                val filteredBookmarks = remember(browserBookmarks, searchQuery) {
                    if (searchQuery.isBlank()) browserBookmarks
                    else browserBookmarks.filter { it.title.contains(searchQuery, ignoreCase = true) || it.url.contains(searchQuery, ignoreCase = true) }
                }

                if (filteredBookmarks.isEmpty()) {
                    Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Outlined.BookmarkBorder, contentDescription = null, tint = Color.LightGray, modifier = Modifier.size(54.dp))
                            Spacer(modifier = Modifier.height(10.dp))
                            Text(text = "No saved bookmarks in browser", color = subtitleTextColor, fontSize = 14.sp)
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 16.dp)
                    ) {
                        items(filteredBookmarks, key = { "b_${it.id}" }) { item ->
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                                    .clickable { onUrlClick(item.url) },
                                colors = CardDefaults.cardColors(containerColor = itemCardBg),
                                border = BorderStroke(0.5.dp, itemBorderColor),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(14.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(text = item.title, color = titleTextColor, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Spacer(modifier = Modifier.height(3.dp))
                                        Text(text = item.url, color = subtitleTextColor, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                    IconButton(onClick = { onDeleteBookmark(item.url) }) {
                                        Icon(imageVector = Icons.Default.Delete, contentDescription = "Delete", tint = Color(0xFFD93025))
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                val filteredHistory = remember(browserHistory, searchQuery) {
                    if (searchQuery.isBlank()) browserHistory
                    else browserHistory.filter { it.title.contains(searchQuery, ignoreCase = true) || it.url.contains(searchQuery, ignoreCase = true) }
                }

                if (filteredHistory.isEmpty()) {
                    Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(imageVector = Icons.Outlined.History, contentDescription = null, tint = Color.LightGray, modifier = Modifier.size(54.dp))
                            Spacer(modifier = Modifier.height(10.dp))
                            Text(text = "Browsing history log is empty", color = subtitleTextColor, fontSize = 14.sp)
                        }
                    }
                } else {
                    Column(modifier = Modifier.weight(1f)) {
                        Button(
                            onClick = onClearAllHistory,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isDarkTheme) Color(0xFF3C1F1F) else Color(0xFFFCE8E6),
                                contentColor = if (isDarkTheme) Color(0xFFFF8B8B) else Color(0xFFD93025)
                            ),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier
                                .align(Alignment.End)
                                .padding(end = 16.dp, bottom = 8.dp)
                                .height(32.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
                        ) {
                            Icon(Icons.Default.DeleteForever, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Clear All", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }

                        LazyColumn(
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 16.dp)
                        ) {
                            items(filteredHistory, key = { "h_${it.id}" }) { item ->
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp)
                                        .clickable { onUrlClick(item.url) },
                                    colors = CardDefaults.cardColors(containerColor = itemCardBg),
                                    border = BorderStroke(0.5.dp, itemBorderColor),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(14.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(text = item.title, color = titleTextColor, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            Spacer(modifier = Modifier.height(3.dp))
                                            Text(text = item.url, color = subtitleTextColor, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        }
                                        IconButton(onClick = { onDeleteHistory(item.id) }) {
                                            Icon(imageVector = Icons.Default.Delete, contentDescription = "Delete", tint = Color(0xFFD93025))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Exit & Back to Home button at bottom of History & Bookmarks page
            Button(
                onClick = onExitClick,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
                    .height(48.dp)
                    .testTag("bookmarks_history_exit_button"),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isDarkTheme) CinemaGold else CinemaRed,
                    contentColor = if (isDarkTheme) Color.Black else Color.White
                ),
                shape = RoundedCornerShape(24.dp),
                elevation = ButtonDefaults.buttonElevation(defaultElevation = 2.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Home,
                        contentDescription = null,
                        tint = if (isDarkTheme) Color.Black else Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Exit & Back to Home",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isDarkTheme) Color.Black else Color.White
                    )
                }
            }
        }
    }
}

@Composable
fun BrowserHomepage(
    viewModel: BrowserViewModel,
    tabId: String,
    onUrlFocusTrigger: () -> Unit,
    onHistoryClick: () -> Unit,
    onDownloadsClick: () -> Unit,
    isDarkTheme: Boolean,
    onExitClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isDark = isDarkTheme
    var showSplash by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (showSplash) {
            delay(1200L)
            showSplash = false
        }
    }

    if (showSplash) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(if (isDark) Color(0xFF08080A) else Color(0xFFFAFAFC)),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                // Large official beautiful logo
                Box(
                    modifier = Modifier
                        .size(110.dp)
                        .clip(RoundedCornerShape(28.dp))
                        .background(if (isDark) Color(0xFF121217) else Color.White)
                        .padding(18.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Language,
                        contentDescription = "Able Browser Logo",
                        tint = if (isDark) CinemaGold else CinemaRed,
                        modifier = Modifier.size(74.dp)
                    )
                }
                
                Spacer(modifier = Modifier.height(24.dp))
                
                CircularProgressIndicator(
                    color = if (isDark) CinemaGold else CinemaRed,
                    strokeWidth = 3.dp,
                    modifier = Modifier.size(24.dp)
                )
                
                Spacer(modifier = Modifier.height(24.dp))
                
                Text(
                    text = "Secure Companion Browser",
                    color = if (isDark) Color.LightGray else Color.DarkGray,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 1.2.sp
                )
            }
        }
    } else {
        Column(
            modifier = modifier
                .fillMaxSize()
                .background(if (isDark) Color(0xFF08080A) else Color(0xFFFAFAFC))
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // App Logo/Icon
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(if (isDark) Color(0xFF121217) else Color.White)
                    .padding(14.dp),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Language,
                    contentDescription = "Browser Logo",
                    tint = if (isDark) CinemaGold else CinemaRed,
                    modifier = Modifier.size(52.dp)
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Able Browser",
                fontSize = 26.sp,
                fontWeight = FontWeight.ExtraBold,
                color = if (isDark) Color.White else Color(0xFF202124),
                letterSpacing = 0.5.sp
            )

        Text(
            text = "Search or type address safely",
            fontSize = 13.sp,
            color = if (isDark) Color.Gray else Color(0xFF5F6368),
            modifier = Modifier.padding(top = 4.dp, bottom = 32.dp)
        )

        var searchText by remember { mutableStateOf("") }
        val homeFocusManager = androidx.compose.ui.platform.LocalFocusManager.current
        val centerFocusRequester = remember { androidx.compose.ui.focus.FocusRequester() }
        var isSearchFocused by remember { mutableStateOf(false) }

        val suggestions by viewModel.searchSuggestions.collectAsStateWithLifecycle()

        // Large rounded search/address bar in center - Fully interactive textfield with dropdown suggestions
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .zIndex(10f),
            contentAlignment = Alignment.TopCenter
        ) {
            Column {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(54.dp)
                        .clickable { centerFocusRequester.requestFocus() }
                        .testTag("homepage_center_search_bar"),
                    shape = RoundedCornerShape(27.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (isDark) Color(0xFF121217) else Color.White
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                    border = BorderStroke(
                        width = 1.dp,
                        color = if (isDark) Color(0xFF1F1F28) else Color(0xFFE0E0E0)
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 20.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = "Search",
                            tint = if (isDark) CinemaGold else CinemaRed,
                            modifier = Modifier.size(22.dp)
                        )

                        Spacer(modifier = Modifier.width(14.dp))

                        androidx.compose.foundation.text.BasicTextField(
                            value = searchText,
                            onValueChange = { 
                                searchText = it 
                                viewModel.updateSearchInput(it)
                            },
                            modifier = Modifier
                                .weight(1f)
                                .focusRequester(centerFocusRequester)
                                .onFocusChanged { focusState ->
                                    isSearchFocused = focusState.isFocused
                                    if (focusState.isFocused) {
                                        viewModel.updateSearchInput(searchText)
                                    }
                                }
                                .testTag("homepage_center_input"),
                            textStyle = androidx.compose.ui.text.TextStyle(
                                color = if (isDark) Color.White else Color(0xFF202124),
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Medium
                            ),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Uri,
                                imeAction = ImeAction.Go
                            ),
                            keyboardActions = KeyboardActions(
                                onGo = {
                                    val target = searchText.trim()
                                    if (target.isNotEmpty()) {
                                        viewModel.loadUrl(target)
                                    }
                                    homeFocusManager.clearFocus()
                                }
                            ),
                            decorationBox = { innerTextField ->
                                Box(modifier = Modifier.fillMaxWidth()) {
                                    if (searchText.isEmpty()) {
                                        Text(
                                            text = "Search or type URL",
                                            color = if (isDark) Color.Gray else Color(0xFF5F6368),
                                            fontSize = 15.sp,
                                            fontWeight = FontWeight.Medium
                                        )
                                    }
                                    innerTextField()
                                }
                            }
                        )

                        if (searchText.isNotEmpty()) {
                            IconButton(
                                onClick = { 
                                    searchText = "" 
                                    viewModel.updateSearchInput("")
                                },
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Clear search input",
                                    tint = if (isDark) Color.Gray else Color(0xFF5F6368),
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }

                if (isSearchFocused && suggestions.isNotEmpty()) {
                    var lastFilledText by remember { mutableStateOf("") }
                    SearchSuggestionsDropdown(
                        suggestions = suggestions,
                        isDarkTheme = isDark,
                        onSuggestionClick = { item ->
                            if (searchText.trim().lowercase() == item.text.trim().lowercase() || item.text.trim().lowercase() == lastFilledText.trim().lowercase()) {
                                viewModel.loadUrl(item.text)
                                homeFocusManager.clearFocus()
                            } else {
                                searchText = item.text
                                lastFilledText = item.text
                                viewModel.updateSearchInput(item.text)
                            }
                        },
                        onDeleteSearchClick = { item ->
                            viewModel.deleteSearchQuery(item.id)
                        },
                        onClearAllClick = {
                            viewModel.clearSearchQueryHistory()
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(36.dp))

        // Shortcut icons grid
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            val shortcuts = listOf(
                ShortcutItem("Google", "https://www.google.com", Icons.Default.Search, Color(0xFF4285F4)),
                ShortcutItem("YouTube", "https://www.youtube.com", Icons.Default.PlayArrow, Color(0xFFFF0000)),
                ShortcutItem("Facebook", "https://www.facebook.com", Icons.Default.ThumbUp, Color(0xFF1877F2)),
                ShortcutItem("AbleDrama", "https://www.abledrama.top", Icons.Default.Tv, if (isDark) CinemaGold else CinemaRed)
            )

            shortcuts.forEach { item ->
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .clickable {
                            viewModel.loadUrl(item.url)
                        }
                        .padding(8.dp)
                ) {
                    val itemShape = if (item.label == "AbleDrama") RoundedCornerShape(10.dp) else CircleShape
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(itemShape)
                            .then(
                                if (item.label == "AbleDrama") {
                                    Modifier.background(
                                        Brush.verticalGradient(
                                            colors = listOf(Color(0xFFFFFFFF), Color(0xFFDCDCDC))
                                        )
                                    )
                                } else {
                                    Modifier.background(
                                        if (item.label == "Facebook") Color(0xFF1877F2)
                                        else if (isDark) Color(0xFF121217)
                                        else Color.White
                                    )
                                }
                            )
                            .border(
                                width = 1.dp,
                                color = if (item.label == "AbleDrama") Color(0xFF7F7F7F)
                                        else if (isDark) Color(0xFF1F1F28)
                                        else Color.LightGray.copy(alpha = 0.3f),
                                shape = itemShape
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        if (item.label == "Facebook") {
                            Text(
                                text = "f",
                                color = Color.White,
                                fontSize = 28.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.offset(y = (-3).dp)
                            )
                        } else if (item.label == "YouTube") {
                            Box(
                                modifier = Modifier
                                    .size(width = 28.dp, height = 20.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(Color(0xFFFF0000)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.PlayArrow,
                                    contentDescription = "YouTube",
                                    tint = Color.White,
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                        } else if (item.label == "AbleDrama") {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Text(
                                    text = "A",
                                    color = Color(0xFFEE1111),
                                    fontSize = 19.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                                    modifier = Modifier.offset(x = 1.dp)
                                )
                                Text(
                                    text = "D",
                                    color = Color(0xFFFF7A00),
                                    fontSize = 19.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    modifier = Modifier.offset(x = (-1).dp)
                                )
                            }
                        } else {
                            Icon(
                                imageVector = item.icon,
                                contentDescription = item.label,
                                tint = item.color,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = item.label,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = if (isDark) Color.LightGray else Color(0xFF202124)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        // History and Downloads Row (Exactly where user circled red in screenshot)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // History Button Card
            Card(
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
                    .clickable { onHistoryClick() },
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (isDark) Color(0xFF121217) else Color.White
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                border = BorderStroke(
                    width = 1.dp,
                    color = if (isDark) Color(0xFF1F1F28) else Color.LightGray.copy(alpha = 0.3f)
                )
            ) {
                Row(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.History,
                        contentDescription = "History",
                        tint = if (isDark) CinemaGold else CinemaRed,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "History",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isDark) Color.White else Color(0xFF202124)
                    )
                }
            }

            // Download Manager Button Card
            Card(
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
                    .clickable { onDownloadsClick() },
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (isDark) Color(0xFF121217) else Color.White
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                border = BorderStroke(
                    width = 1.dp,
                    color = if (isDark) Color(0xFF1F1F28) else Color.LightGray.copy(alpha = 0.3f)
                )
            ) {
                Row(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Download,
                        contentDescription = "Downloads",
                        tint = if (isDark) CinemaGold else CinemaRed,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Downloads",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isDark) Color.White else Color(0xFF202124)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(28.dp))

        Button(
            onClick = onExitClick,
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .height(48.dp)
                .testTag("exit_and_back_to_home_button"),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isDark) CinemaGold else CinemaRed,
                contentColor = if (isDark) Color.Black else Color.White
            ),
            shape = RoundedCornerShape(24.dp),
            elevation = ButtonDefaults.buttonElevation(defaultElevation = 2.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Home,
                    contentDescription = null,
                    tint = if (isDark) Color.Black else Color.White,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Exit & Back to Home",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (isDark) Color.Black else Color.White
                )
            }
        }
    } // closes Column
} // closes else block
}

data class ShortcutItem(
    val label: String,
    val url: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val color: Color
)

@Composable
fun AboutBrowserDialog(
    isDarkTheme: Boolean,
    onDismissRequest: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = true)
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (isDarkTheme) Color(0xFF121217) else Color.White
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
            border = BorderStroke(
                width = 1.dp,
                color = if (isDarkTheme) Color(0xFF1F1F28) else Color.LightGray.copy(alpha = 0.4f)
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Large beautifully rendered high quality logo representation
                Box(
                    modifier = Modifier
                        .size(100.dp)
                        .clip(RoundedCornerShape(24.dp))
                        .background(if (isDarkTheme) Color(0xFF08080A) else Color(0xFFF1F3F4))
                        .padding(18.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Language,
                        contentDescription = "Able Browser Logo",
                        tint = if (isDarkTheme) CinemaGold else CinemaRed,
                        modifier = Modifier.size(64.dp)
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "Able Browser",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = if (isDarkTheme) Color.White else Color(0xFF202124),
                    textAlign = TextAlign.Center
                )

                Text(
                    text = "Companion Secure Client v2.4.0",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (isDarkTheme) CinemaGold else CinemaRed,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 2.dp, bottom = 16.dp)
                )

                Text(
                    text = "A dedicated private web-browsing companion client designed exclusively for movie & drama enthusiasts. Intercepts aggressive redirect loops, filters intrusive ad placements, and offers a secure environment for offline downloading.",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Normal,
                    lineHeight = 18.sp,
                    color = if (isDarkTheme) Color.LightGray else Color(0xFF5F6368),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(bottom = 20.dp)
                )

                // Feature pills
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    val features = listOf(
                        Triple(Icons.Default.Security, "Secure Sandbox", "Prevents unauthorized browser page redirections."),
                        Triple(Icons.Default.Block, "Ad Blocking Filter", "Blocks annoying pop-ups on streaming mirrors."),
                        Triple(Icons.Default.Download, "Supercharged Downloads", "A highly robust system for caching film data safely.")
                    )

                    features.forEach { (icon, title, desc) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    color = if (isDarkTheme) Color(0xFF08080A) else Color(0xFFF1F3F4),
                                    shape = RoundedCornerShape(12.dp)
                                )
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = icon,
                                contentDescription = title,
                                tint = if (isDarkTheme) CinemaGold else CinemaRed,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = title,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isDarkTheme) Color.White else Color(0xFF202124)
                                )
                                Text(
                                    text = desc,
                                    fontSize = 11.sp,
                                    color = if (isDarkTheme) Color.LightGray else Color(0xFF5F6368),
                                    lineHeight = 14.sp
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                Button(
                    onClick = onDismissRequest,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isDarkTheme) CinemaGold else CinemaRed,
                        contentColor = if (isDarkTheme) Color.Black else Color.White
                    ),
                    shape = RoundedCornerShape(24.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                ) {
                    Text(
                        text = "Dismiss",
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                }
            }
        }
    }
}

@Composable
fun SearchSuggestionsDropdown(
    suggestions: List<com.example.ui.SearchSuggestionItem>,
    isDarkTheme: Boolean,
    onSuggestionClick: (com.example.ui.SearchSuggestionItem) -> Unit,
    onDeleteSearchClick: (com.example.ui.SearchSuggestionItem) -> Unit,
    onClearAllClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (suggestions.isEmpty()) return

    val cinemaGold = Color(0xFFFFB300)
    val cinemaRed = Color(0xFFE50914)
    val accentColor = if (isDarkTheme) cinemaGold else cinemaRed

    Card(
        modifier = modifier
            .fillMaxWidth()
            .wrapContentHeight()
            .padding(horizontal = 4.dp, vertical = 2.dp)
            .testTag("search_suggestions_panel"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isDarkTheme) Color(0xFF121217) else Color.White
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
        border = BorderStroke(
            width = 1.dp,
            color = if (isDarkTheme) Color(0xFF1F1F28) else Color(0xFFE0E0E0)
        )
    ) {
        Column(modifier = Modifier.padding(vertical = 8.dp)) {
            androidx.compose.foundation.lazy.LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 280.dp),
                verticalArrangement = Arrangement.spacedBy(1.dp)
            ) {
                items(suggestions.size) { index ->
                    val item = suggestions[index]
                    val isRecentSearch = item.type == com.example.ui.SuggestionType.HISTORY_SEARCH

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSuggestionClick(item) }
                            .padding(horizontal = 16.dp, vertical = 11.dp)
                            .testTag("suggestion_item_${index}"),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val icon = when (item.type) {
                            com.example.ui.SuggestionType.HISTORY_SEARCH -> Icons.Default.History
                            com.example.ui.SuggestionType.RELATED_SUGGEST -> Icons.Default.Search
                            com.example.ui.SuggestionType.BROWSER_HISTORY -> Icons.Default.Language
                        }
                        
                        Icon(
                            imageVector = icon,
                            contentDescription = "Suggestion Type",
                            tint = if (isDarkTheme) Color.Gray else Color.Gray,
                            modifier = Modifier.size(18.dp)
                        )

                        Spacer(modifier = Modifier.width(14.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = item.text,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                                color = if (isDarkTheme) Color.White else Color(0xFF202124),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (!item.subText.isNullOrBlank() && item.subText != item.text) {
                                Text(
                                    text = item.subText,
                                    fontSize = 11.sp,
                                    color = if (isDarkTheme) Color.Gray else Color(0xFF5F6368),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(top = 1.dp)
                                )
                            }
                        }

                        if (isRecentSearch) {
                            IconButton(
                                onClick = { onDeleteSearchClick(item) },
                                modifier = Modifier
                                    .size(28.dp)
                                    .testTag("delete_suggestion_item_${index}")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Remove Search Query",
                                    tint = if (isDarkTheme) Color.Gray else Color.Gray,
                                    modifier = Modifier.size(15.dp)
                                )
                            }
                        } else {
                            Icon(
                                imageVector = Icons.Default.ArrowOutward,
                                contentDescription = "Copy Suggestion",
                                tint = if (isDarkTheme) Color.DarkGray else Color.LightGray,
                                modifier = Modifier
                                    .size(14.dp)
                                    .alpha(0.6f)
                            )
                        }
                    }
                }
            }

            val hasHistorySearch = suggestions.any { it.type == com.example.ui.SuggestionType.HISTORY_SEARCH }
            if (hasHistorySearch) {
                HorizontalDivider(
                    thickness = 0.5.dp,
                    color = if (isDarkTheme) Color(0xFF1F1F28) else Color(0xFFECEFF1),
                    modifier = Modifier.padding(vertical = 4.dp)
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onClearAllClick() }
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .testTag("clear_all_search_history"),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Clear History",
                        tint = accentColor.copy(alpha = 0.8f),
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Clear Search History",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = accentColor.copy(alpha = 0.8f)
                    )
                }
            }
        }
    }
}

