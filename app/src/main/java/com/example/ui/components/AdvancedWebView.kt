package com.example.ui.components

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.key
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.example.ui.BrowserViewModel
import com.example.ui.WebViewCommand
import com.example.ui.DetectedResource
import kotlinx.coroutines.flow.collectLatest

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun AdvancedWebView(
    viewModel: BrowserViewModel,
    tabId: String,
    isVisible: Boolean = true,
    isDarkTheme: Boolean = false,
    isDesktopModeAllowed: Boolean = false,
    modifier: Modifier = Modifier,
    onShowCustomView: (android.view.View, WebChromeClient.CustomViewCallback) -> Unit = { _, _ -> },
    onHideCustomView: () -> Unit = {},
    onSingleTap: () -> Unit = {},
    onDownloadRequested: (url: String, contentDisposition: String?, mimeType: String?, contentLength: Long) -> Unit = { _, _, _, _ -> }
) {
    val context = LocalContext.current
    
    val currentOnShowCustomView by rememberUpdatedState(onShowCustomView)
    val currentOnHideCustomView by rememberUpdatedState(onHideCustomView)
    val currentOnSingleTap by rememberUpdatedState(onSingleTap)

    val gestureDetector = remember(context) {
        GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                currentOnSingleTap()
                return false
            }
        }, Handler(Looper.getMainLooper()))
    }
    
    var webViewVersion by remember { mutableStateOf(0) }
    var lastRecreationTime by remember { mutableStateOf(0L) }

    // Remember the WebView instance so it persists across recompositions
    val webView = remember(webViewVersion) {
        WebView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            
            isFocusable = true
            isFocusableInTouchMode = true
            
            // WebViews in hybrid apps should have third-party cookie support enabled
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                allowFileAccess = true
                allowContentAccess = true
                loadWithOverviewMode = true
                useWideViewPort = true
                builtInZoomControls = true
                displayZoomControls = false
                mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                mediaPlaybackRequiresUserGesture = false
                cacheMode = WebSettings.LOAD_DEFAULT
                setSupportZoom(true)
                textZoom = 100
                setSupportMultipleWindows(false)
                loadsImagesAutomatically = true
                offscreenPreRaster = true
            }

            // Bind Javascript Interface for real-time video playback events and title observation
            addJavascriptInterface(VideoPlayObserver(viewModel, tabId), "VideoPlayObserver")

            // Custom Download listener to show 1DM style Downloader Dialog
            setDownloadListener { url, userAgent, contentDisposition, mimeType, contentLength ->
                onDownloadRequested(url, contentDisposition, mimeType, contentLength)
            }

            // Set Touch Listener exactly ONCE during initialization
            setOnTouchListener { _, event ->
                gestureDetector.onTouchEvent(event)
                false
            }
        }
    }

    val defaultUserAgent = remember(webView) { webView.settings.userAgentString }
    val isDesktopModeEnabledState by viewModel.isDesktopModeEnabled.collectAsState()
    val isDesktopCurrentlyActive = isDesktopModeAllowed && isDesktopModeEnabledState

    var prevDesktopMode by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(isDesktopCurrentlyActive, webView) {
        if (isDesktopCurrentlyActive) {
            val desktopUserAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/115.0.0.0 Safari/537.36"
            webView.settings.userAgentString = desktopUserAgent
        } else {
            webView.settings.userAgentString = defaultUserAgent
        }

        if (prevDesktopMode != null && prevDesktopMode != isDesktopCurrentlyActive) {
            val currentUrl = webView.url
            if (isVisible && !currentUrl.isNullOrBlank() && currentUrl != "browser://home" && !currentUrl.startsWith("browser:")) {
                webView.reload()
            }
        }
        prevDesktopMode = isDesktopCurrentlyActive
    }

    LaunchedEffect(webView, isDarkTheme) {
        applyBrowserLevelDarkTheme(webView, isDarkTheme)
    }

    // Clean up older webViews safely to prevent leaking native rendering contexts and memory
    DisposableEffect(webView) {
        onDispose {
            try {
                webView.stopLoading()
                webView.clearHistory()
                webView.removeAllViews()
                // Let the garbage collector reclaim the WebView natively during AndroidView's normal lifecycle, 
                // avoiding any sudden InputChannel closures while still attached.
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    // Connect WebView controls back to view model status
    webView.webViewClient = object : WebViewClient() {
        override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): android.webkit.WebResourceResponse? {
            val url = request?.url?.toString() ?: return null
            val urlLower = url.lowercase()
            
            // Background smart detection of downloadable resource
            val detected = detectMediaType(url)
            if (detected != null) {
                view?.post {
                    viewModel.addDetectedResource(tabId, detected)
                }
            }
            
            val blockAdPattern = urlLower.contains("googleads") ||
                    urlLower.contains("googlesyndication") ||
                    urlLower.contains("doubleclick") ||
                    urlLower.contains("google-analytics") ||
                    urlLower.contains("googletagmanager") ||
                    urlLower.contains("amung.us") ||
                    urlLower.contains("histats") ||
                    urlLower.contains("exoclick") ||
                    urlLower.contains("addthis") ||
                    urlLower.contains("popads") ||
                    urlLower.contains("popcash") ||
                    urlLower.contains("mgid") ||
                    urlLower.contains("adskeeper") ||
                    urlLower.contains("propellerads") ||
                    urlLower.contains("onclickads") ||
                    urlLower.contains("juicyads") ||
                    urlLower.contains("zebid") ||
                    urlLower.contains("adsystem") ||
                    urlLower.contains("adnxs")
            
            if (blockAdPattern) {
                return android.webkit.WebResourceResponse(
                    "text/plain", 
                    "UTF-8", 
                    java.io.ByteArrayInputStream("".toByteArray())
                )
            }
            return super.shouldInterceptRequest(view, request)
        }

        private fun handleGoogleSearchInterception(view: WebView?, url: String): Boolean {
            try {
                val uri = Uri.parse(url)
                val host = uri.host ?: ""
                val path = uri.path ?: ""
                if (host.contains("google.") && path.contains("/search")) {
                    val query = uri.getQueryParameter("q")
                    if (!query.isNullOrBlank()) {
                        val trimmedQuery = query.trim()
                        val isQueryUrl = trimmedQuery.startsWith("http://", ignoreCase = true) || 
                                         trimmedQuery.startsWith("https://", ignoreCase = true)
                        val hasDot = trimmedQuery.contains(".") && 
                                     !trimmedQuery.startsWith(".") && 
                                     !trimmedQuery.endsWith(".")
                        val noSpaces = !trimmedQuery.contains(" ") && 
                                       !trimmedQuery.contains("\n") && 
                                       !trimmedQuery.contains("\t")
                        
                        if (isQueryUrl || (hasDot && noSpaces)) {
                            var isLikelyDomain = isQueryUrl
                            val lastDot = trimmedQuery.lastIndexOf('.')
                            if (!isLikelyDomain && lastDot > 0 && lastDot < trimmedQuery.length - 1) {
                                val partAfterDot = trimmedQuery.substring(lastDot + 1).split('/')[0]
                                val isTldValid = partAfterDot.isNotEmpty() && 
                                                 partAfterDot.all { it.isLetter() || it.isDigit() } && 
                                                 partAfterDot.length >= 2
                                if (isTldValid) {
                                    isLikelyDomain = true
                                }
                            }
                            
                            if (isLikelyDomain) {
                                val targetUrl = if (isQueryUrl) trimmedQuery else "https://$trimmedQuery"
                                view?.post {
                                    view.stopLoading()
                                    view.loadUrl(targetUrl)
                                }
                                return true
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
            return false
        }

        private fun handleGoogleHomepageInterception(view: WebView?, url: String): Boolean {
            return false
        }

        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
            val url = request?.url?.toString() ?: return false
            
            if (handleGoogleHomepageInterception(view, url)) {
                return true
            }

            if (handleGoogleSearchInterception(view, url)) {
                return true
            }

            // Handle standard HTTP/HTTPS links inside the WebView
            if (url.startsWith("http://") || url.startsWith("https://")) {
                return false
            }

            // Handle deep links like whatsapp://, intent://, tg://, mailto://, etc.
            try {
                val intent = Intent.parseUri(url, Intent.URI_INTENT_SCHEME) ?: Intent(Intent.ACTION_VIEW, Uri.parse(url))
                val resolvedActivity = intent.resolveActivity(context.packageManager)
                if (resolvedActivity != null) {
                    context.startActivity(intent)
                    return true
                } else {
                    // Try fallback URL if intent schema has one defined (common in movie ads)
                    val fallbackUrl = intent.getStringExtra("browser_fallback_url")
                    if (fallbackUrl != null) {
                        view?.loadUrl(fallbackUrl)
                        return true
                    }
                }
            } catch (e: Exception) {
                // Ignore parsing errors and show brief failure msg
            }
            return true // Prevent loading unsupported schema inside WebView
        }

        override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
            viewModel.clearDetectedResources(tabId)
            if (url != null) {
                if (handleGoogleHomepageInterception(view, url)) {
                    view?.stopLoading()
                    view?.post {
                        val canGoBack = view.canGoBack()
                        if (!canGoBack) {
                            view.loadUrl("https://www.abledrama.top")
                        }
                    }
                    return
                }
                if (handleGoogleSearchInterception(view, url)) {
                    return
                }
            }
            super.onPageStarted(view, url, favicon)
            viewModel.updateLoadingStatus(tabId, true, 10)
            viewModel.updateWebThemeColor(tabId, null)
            if (view != null) {
                applyBrowserLevelDarkTheme(view, isDarkTheme)
            }
        }

        override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
            super.doUpdateVisitedHistory(view, url, isReload)
            if (url != null) {
                if (handleGoogleHomepageInterception(view, url)) {
                    view?.stopLoading()
                    view?.post {
                        val canGoBack = view.canGoBack()
                        if (!canGoBack) {
                            view.loadUrl("https://www.abledrama.top")
                        }
                    }
                    return
                }
                if (handleGoogleSearchInterception(view, url)) {
                    return
                }
                viewModel.updateCurrentState(tabId, url, view?.title ?: "")
                if (view != null) {
                    injectVideoPlaybackObserver(view, tabId, viewModel)
                    view.postDelayed({
                        injectVideoPlaybackObserver(view, tabId, viewModel)
                    }, 1000)
                    view.postDelayed({
                        injectVideoPlaybackObserver(view, tabId, viewModel)
                    }, 2500)
                }
            }
        }

        override fun onPageFinished(view: WebView?, url: String?) {
            super.onPageFinished(view, url)
            viewModel.updateLoadingStatus(tabId, false, 100)
            if (view != null) {
                injectVideoPlaybackObserver(view, tabId, viewModel)
                view.postDelayed({
                    injectVideoPlaybackObserver(view, tabId, viewModel)
                }, 1500)
                view.postDelayed({
                    injectVideoPlaybackObserver(view, tabId, viewModel)
                }, 3500)

                extractPageResources(view, tabId, viewModel)
                view.postDelayed({
                    extractPageResources(view, tabId, viewModel)
                }, 3000)
                
                applyBrowserLevelDarkTheme(view, isDarkTheme)
                val adHideJs = "(function() {\n" +
                        "  var style = document.createElement('style');\n" +
                        "  style.type = 'text/css';\n" +
                        "  style.id = 'able-ad-blocker-style';\n" +
                        "  style.innerHTML = '.google-auto-placed, .adsbygoogle, #amungus, .histats, #histats, .ad-banner, .pop-ads, iframe[src*=\"googleads\"], iframe[src*=\"doubleclick\"] { display: none !important; opacity: 0 !important; height: 0 !important; width: 0 !important; pointer-events: none !important; }';\n" +
                        "  document.head.appendChild(style);\n" +
                        "})()"
                view.evaluateJavascript(adHideJs, null)
            }
            if (url != null && view != null) {
                view.evaluateJavascript(
                    "(function() {\n" +
                    "  var ogImg = document.querySelector('meta[property=\"og:image\"]');\n" +
                    "  if (ogImg && ogImg.content) return ogImg.content;\n" +
                    "  var linkImg = document.querySelector('link[rel=\"image_src\"]');\n" +
                    "  if (linkImg && linkImg.href) return linkImg.href;\n" +
                    "  var postImg = document.querySelector('article img, .post-body img');\n" +
                    "  if (postImg && postImg.src) return postImg.src;\n" +
                    "  return '';\n" +
                    "})()",
                    { result ->
                        val cleanThumb = result?.trim()?.removeSurrounding("\"")?.replace("\\/", "/")
                        val finalThumb = if (cleanThumb != null && cleanThumb != "null" && cleanThumb.startsWith("http")) cleanThumb else null
                        viewModel.updateCurrentStateWithHistory(tabId, url, view.title ?: "", finalThumb)
                    }
                )
            }
            viewModel.updateNavigationCapabilities(
                tabId,
                back = view?.canGoBack() ?: false,
                forward = view?.canGoForward() ?: false
            )
            view?.evaluateJavascript(
                "(function() {\n" +
                "  var metaTheme = document.querySelector('meta[name=\"theme-color\"]');\n" +
                "  if (metaTheme && metaTheme.content) return metaTheme.content;\n" +
                "  var metaTile = document.querySelector('meta[name=\"msapplication-TileColor\"]');\n" +
                "  if (metaTile && metaTile.content) return metaTile.content;\n" +
                "  return null;\n" +
                "})()",
                { result ->
                    val cleanResult = result?.trim()?.replace("\"", "")
                    if (cleanResult != null && cleanResult != "null" && cleanResult.isNotBlank()) {
                        viewModel.updateWebThemeColor(tabId, cleanResult)
                    } else {
                        viewModel.updateWebThemeColor(tabId, null)
                    }
                }
            )
            if (view != null) {
                Handler(Looper.getMainLooper()).postDelayed({
                    captureWebViewThumbnail(view, tabId, viewModel)
                }, 800)
            }
        }

        override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
            super.onReceivedError(view, request, error)
            // Error handling can trigger specific offline pages or messages
        }

        override fun onRenderProcessGone(view: WebView?, detail: android.webkit.RenderProcessGoneDetail?): Boolean {
            android.util.Log.e("AdvancedWebView", "WebView render process gone. Recreating webView...")
            try {
                if (view != null) {
                    val parent = view.parent as? android.view.ViewGroup
                    parent?.removeView(view)
                    view.destroy()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
            val now = System.currentTimeMillis()
            if (now - lastRecreationTime > 10000L) {
                lastRecreationTime = now
                webViewVersion++
            } else {
                android.util.Log.e("AdvancedWebView", "WebView render process crashed too frequently. Cooldown active.")
            }
            return true
        }
    }

    webView.webChromeClient = object : WebChromeClient() {
        override fun onProgressChanged(view: WebView?, newProgress: Int) {
            super.onProgressChanged(view, newProgress)
            viewModel.updateLoadingStatus(tabId, newProgress < 100, newProgress)
            if (view != null && newProgress >= 15) {
                applyBrowserLevelDarkTheme(view, isDarkTheme)
            }
        }

        override fun onReceivedTitle(view: WebView?, title: String?) {
            super.onReceivedTitle(view, title)
            viewModel.updateCurrentState(tabId, view?.url ?: "", title ?: "Able Drama")
        }

        // Full Screen video support
        override fun onShowCustomView(view: android.view.View?, callback: CustomViewCallback?) {
            if (view != null && callback != null) {
                currentOnShowCustomView(view, callback)
            }
        }

        override fun onHideCustomView() {
            currentOnHideCustomView()
        }
    }

    // Collect external commands from ViewModel filtered for this tabId
    LaunchedEffect(viewModel, webView, tabId) {
        viewModel.commands.collectLatest { command ->
            if (command.tabId == tabId) {
                try {
                    when (command) {
                        is WebViewCommand.GoBack -> if (webView.canGoBack()) webView.goBack()
                        is WebViewCommand.GoForward -> if (webView.canGoForward()) webView.goForward()
                        is WebViewCommand.Reload -> webView.reload()
                        is WebViewCommand.LoadUrl -> webView.loadUrl(command.url)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    // Synchronize starting URL once on initialization and on recreate
    val startingUrl = remember { viewModel.tabs.value.find { it.id == tabId }?.url ?: "https://www.abledrama.top" }
    LaunchedEffect(webView) {
        try {
            webView.loadUrl(startingUrl)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // Intercept system Back button to navigate parent web stack if this tab is active
    val selectedTabIdVal by viewModel.selectedTabId.collectAsState()
    val tabsListVal by viewModel.tabs.collectAsState()
    val isDramaActiveState by viewModel.isDramaModeActive.collectAsState()

    val isSelectedTab = remember(selectedTabIdVal, tabId) {
        selectedTabIdVal == tabId
    }

    val canGoBack = remember(tabsListVal, tabId) {
        tabsListVal.find { it.id == tabId }?.canGoBack ?: false
    }

    val backHandlerEnabled = remember(isDramaActiveState, canGoBack, isVisible, isSelectedTab) {
        if (!isDramaActiveState) {
            isVisible && isSelectedTab
        } else {
            canGoBack && isVisible && isSelectedTab
        }
    }

    BackHandler(enabled = backHandlerEnabled) {
        if (!isDramaActiveState) {
            if (webView.canGoBack()) {
                webView.goBack()
                viewModel.updateNavigationCapabilities(
                    tabId,
                    back = webView.canGoBack(),
                    forward = webView.canGoForward()
                )
            } else {
                val currentUrlVal = webView.url ?: ""
                val isAtActiveHome = currentUrlVal == "browser://home" || currentUrlVal.isBlank()
                if (!isAtActiveHome) {
                    viewModel.loadUrl("browser://home")
                } else {
                    viewModel.triggerReturnToDramaDialog()
                }
            }
        } else {
            viewModel.goBack()
        }
    }

    LaunchedEffect(webView, isVisible) {
        if (!isVisible) {
            // Screen is being hidden, capture its last state immediately for tab switcher
            captureWebViewThumbnail(webView, tabId, viewModel)
        }
    }

    // Embed WebView into Jetpack Compose layout tree with key-controlled recreation
    key(webViewVersion) {
        AndroidView(
            factory = { webView },
            modifier = modifier.fillMaxSize(),
            update = { view ->
                view.visibility = if (isVisible) android.view.View.VISIBLE else android.view.View.GONE
            }
        )
    }
}

private fun captureWebViewThumbnail(webView: WebView, tabId: String, viewModel: BrowserViewModel) {
    webView.post {
        try {
            val width = webView.width
            val height = webView.height
            if (width > 0 && height > 0) {
                val maxDim = 320
                val scale = maxDim.toFloat() / Math.max(width, height).toFloat()
                if (scale < 1.0f) {
                    val sw = (width * scale).toInt().coerceAtLeast(1)
                    val sh = (height * scale).toInt().coerceAtLeast(1)
                    val thumbnail = android.graphics.Bitmap.createBitmap(sw, sh, android.graphics.Bitmap.Config.ARGB_8888)
                    val canvas = android.graphics.Canvas(thumbnail)
                    canvas.scale(scale, scale)
                    webView.draw(canvas)
                    viewModel.updateTabScreenshot(tabId, thumbnail)
                } else {
                    val thumbnail = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
                    val canvas = android.graphics.Canvas(thumbnail)
                    webView.draw(canvas)
                    viewModel.updateTabScreenshot(tabId, thumbnail)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}

internal fun applyBrowserLevelDarkTheme(webView: WebView, isDarkTheme: Boolean) {
    try {
        val settings = webView.settings
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            settings.setAlgorithmicDarkeningAllowed(false)
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            settings.setForceDark(WebSettings.FORCE_DARK_OFF)
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }

    // Completely remove the injected styles to prevent any website content/color inversion or overriding
    val js = "(function() {\n" +
            "  var style = document.getElementById('able-dark-theme-style');\n" +
            "  if (style) { style.parentNode.removeChild(style); }\n" +
            "})()"
    webView.evaluateJavascript(js, null)
}

fun detectMediaType(url: String): DetectedResource? {
    val cleanUrl = url.substringBefore("?").lowercase()
    val fullUrlLower = url.lowercase()

    // 1. Check strict blacklist words indicating configs, analytics, trackers, ads, fragments
    val blacklistKeywords = listOf(
        "analytics", "telemetry", "metrics", "collect", "tracker", "logging", "logger", 
        "google-analytics", "doubleclick", "googlesyndication", "/ad", "popads", "popcash", 
        "config", "settings", "manifest.json", "manifest.mpd", "hotkeys", "caption", 
        "subtitles", "playlog", "ping", "/v1/logs", "youtubei/v1", "/log_event", "pagead",
        "favicon.ico", "adsystem", "exoclick", "clck.ru", "stat", "beacon", "pixel",
        "/fragment", "-fragment", "_fragment",
        "/chunk", "-chunk", "_chunk",
        "/segment", "-segment", "_segment",
        "m3u8_audio", "range="
    )
    
    for (keyword in blacklistKeywords) {
        if (fullUrlLower.contains(keyword)) {
            if (fullUrlLower.contains("videoplayback")) {
                if (fullUrlLower.contains("mime=audio")) {
                    return null
                }
            } else {
                return null
            }
        }
    }

    // 2. Explicit exclusions for file types we don't want to count as video
    val imageExtensions = listOf(".jpg", ".jpeg", ".png", ".gif", ".webp", ".bmp", ".svg", ".ico", ".tiff")
    val audioExtensions = listOf(".mp3", ".wav", ".m4a", ".ogg", ".aac", ".flac", ".wma", ".opus", ".mka", ".m3u", ".m3u8_audio")
    val docExtensions = listOf(".pdf", ".epub", ".docx", ".xlsx", ".pptx", ".txt", ".doc", ".xls", ".csv", ".rtf")
    val archiveAndGeneralExtensions = listOf(".zip", ".rar", ".7z", ".apk", ".dmg", ".tar", ".gz", ".exe", ".bin", ".msi", ".iso", ".jar", ".json", ".xml", ".css", ".js", ".ts")

    val isExcluded = imageExtensions.any { cleanUrl.endsWith(it) } ||
                     audioExtensions.any { cleanUrl.endsWith(it) } ||
                     docExtensions.any { cleanUrl.endsWith(it) } ||
                     archiveAndGeneralExtensions.any { cleanUrl.endsWith(it) } ||
                     fullUrlLower.contains("audio_stream") || 
                     fullUrlLower.contains("audioplayback")

    if (isExcluded) {
        return null
    }

    // 3. Identify Video extensions
    val videoExtensions = listOf(".mp4", ".mkv", ".webm", ".mov", ".avi", ".flv", ".3gp", ".m4v", ".wmv", ".mpeg", ".m3u8")
    val isVideoExtension = videoExtensions.any { cleanUrl.endsWith(it) }

    // 4. Identify Video Streams / CDN / Specific sites
    val isVideoStream = fullUrlLower.contains("video_stream") || 
                        fullUrlLower.contains("/videoplayback") || 
                        fullUrlLower.contains("video-cdn") ||
                        fullUrlLower.contains("googlevideo.com") ||
                        fullUrlLower.contains(".m3u8") ||
                        (fullUrlLower.contains("fbcdn.net") && (fullUrlLower.contains("/v/") || fullUrlLower.contains("_v_") || fullUrlLower.contains(".mp4"))) ||
                        fullUrlLower.contains("tiktokv.com") ||
                        fullUrlLower.contains("tiktok.com") ||
                        (fullUrlLower.contains("/video/") && !cleanUrl.endsWith(".html") && !cleanUrl.endsWith(".php") && !cleanUrl.endsWith(".js") && !cleanUrl.endsWith(".css")) ||
                        (fullUrlLower.contains("/embed/") && fullUrlLower.contains("video"))

    if (!isVideoExtension && !isVideoStream) {
        return null
    }

    // Determine target extension for download filename determination
    val ext = videoExtensions.firstOrNull { cleanUrl.endsWith(it) } ?: "mp4"
    val displayType = if (fullUrlLower.contains(".m3u8")) "HLS Playlist" else if (isVideoStream) "Video Stream" else "Video ${ext.uppercase()}"
    
    // Guess a meaningful title
    var title = url.substringBefore("?").substringAfterLast("/").trim()
    if (title.isBlank() || title.length < 3 || title.contains("videoplayback") || title.contains("video_stream")) {
        title = "Video Media Resource"
    }

    // Extract potential quality hints from URL
    var quality: String? = null
    if (fullUrlLower.contains("1080p") || fullUrlLower.contains("1080")) quality = "1080p"
    else if (fullUrlLower.contains("720p") || fullUrlLower.contains("720")) quality = "720p"
    else if (fullUrlLower.contains("480p") || fullUrlLower.contains("480")) quality = "480p"
    else if (fullUrlLower.contains("360p") || fullUrlLower.contains("360")) quality = "360p"
    else if (fullUrlLower.contains("2160p") || fullUrlLower.contains("4k") || fullUrlLower.contains("2160")) quality = "4K"

    return DetectedResource(
        url = url,
        title = title,
        fileType = "Video",
        quality = quality ?: displayType,
        fileSize = 0L
    )
}

fun extractPageResources(webView: WebView?, tabId: String, viewModel: BrowserViewModel) {
    if (webView == null) return
    val js = """
        (function() {
          var resources = [];
          
          function findTitleForVideo(videoElement) {
            if (videoElement.getAttribute('aria-label')) return videoElement.getAttribute('aria-label').trim();
            if (videoElement.getAttribute('title')) return videoElement.getAttribute('title').trim();

            var ytTitle = document.querySelector('h1.ytd-watch-metadata') || document.querySelector('h1.title.ytd-video-primary-info-renderer');
            if (ytTitle) {
              var txt = ytTitle.innerText || ytTitle.textContent;
              if (txt && txt.trim()) return txt.trim();
            }

            var parent = videoElement.parentElement;
            for (var depth = 0; depth < 5 && parent; depth++) {
              var headings = parent.querySelectorAll('h1, h2, h3, h4, h5, h6, [class*="title"], [id*="title"]');
              for (var k = 0; k < headings.length; k++) {
                var h = headings[k];
                if (h.innerText && h.innerText.trim().length > 3 && h.innerText.trim().length < 150) {
                  return h.innerText.trim();
                }
              }
              parent = parent.parentElement;
            }
            return "";
          }

          // 1. Scan <video> elements only
          var videos = document.querySelectorAll('video');
          for (var i = 0; i < videos.length; i++) {
            var v = videos[i];
            var vTitle = findTitleForVideo(v) || (document.title ? document.title.replace(/\s*-\s*YouTube/gi, "").trim() : "") || "Video Element";
            if (v.src && v.src.indexOf('http') === 0) {
              resources.push({ url: v.src, type: 'Video', title: vTitle });
            }
            var sources = v.querySelectorAll('source');
            for (var j = 0; j < sources.length; j++) {
              var s = sources[j];
              if (s.src && s.src.indexOf('http') === 0) {
                resources.push({ url: s.src, type: 'Video', title: vTitle });
              }
            }
          }
          
          // 2. Scan direct file anchors with video extensions only
          var videoExtensions = ['.mp4', '.mkv', '.webm', '.avi', '.mov', '.flv', '.3gp', '.m4v', '.wmv', '.mpeg', '.m3u8'];
          var anchors = document.querySelectorAll('a');
          for (var i = 0; i < anchors.length; i++) {
            var href = anchors[i].href;
            if (href && href.indexOf('http') === 0) {
              var cleanHref = href.toLowerCase().split('?')[0];
              for (var k = 0; k < videoExtensions.length; k++) {
                if (cleanHref.endsWith(videoExtensions[k])) {
                  var linkTitle = anchors[i].innerText ? anchors[i].innerText.trim() : '';
                  if (linkTitle.length > 60) linkTitle = linkTitle.substring(0, 57) + '...';
                  resources.push({ url: href, type: 'Video', title: linkTitle || 'Video Link' });
                  break;
                }
              }
            }
          }
          
          return JSON.stringify(resources);
        })()
    """.trimIndent()

    webView.evaluateJavascript(js) { result ->
        if (result != null && result != "null" && result.isNotBlank()) {
            try {
                val jsonStr = if (result.startsWith("\"") && result.endsWith("\"") && result.length >= 2) {
                    val parser = org.json.JSONTokener(result)
                    val value = parser.nextValue()
                    value.toString()
                } else {
                    result
                }
                
                val array = org.json.JSONArray(jsonStr)
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    val rawUrl = obj.getString("url")
                    val rawTitle = obj.optString("title", "Video Resource")
                    
                    val detected = detectMediaType(rawUrl)?.copy(
                        title = if (rawTitle.isNotBlank() && rawTitle != "Video Element" && rawTitle != "Video Stream Source") rawTitle else rawUrl.substringBefore("?").substringAfterLast("/").ifBlank { "Video Resource" }
                    )
                    if (detected != null) {
                        viewModel.addDetectedResource(tabId, detected)
                    }
                }
            } catch (e: Exception) {
                // Ignore errors
            }
        }
    }
}

fun injectVideoPlaybackObserver(webView: WebView?, tabId: String, viewModel: BrowserViewModel) {
    if (webView == null) return
    val js = """
        (function() {
          function isGenericJsTitle(title) {
            if (!title) return true;
            var t = title.toLowerCase().trim();
            return t === "" || 
                   t === "video" || 
                   t === "video element" || 
                   t === "videoplayback" || 
                   t === "unknown" || 
                   t === "stream" || 
                   t === "blob" || 
                   t === "collect" || 
                   t === "config" || 
                   t === "resource" || 
                   t === "video_001" ||
                   t === "resource_001" || 
                   t.indexOf("video stream") !== -1 ||
                   t.indexOf("video link") !== -1;
          }

          function getCleanPageTitle() {
            // 1. Video Title
            var ytTitle = document.querySelector('h1.ytd-watch-metadata') || 
                           document.querySelector('h1.title.ytd-video-primary-info-renderer') ||
                           document.querySelector('.ytp-title-link');
            if (ytTitle) {
              var txt = ytTitle.innerText || ytTitle.textContent;
              if (txt && txt.trim() && !isGenericJsTitle(txt)) return txt.trim();
            }

            var videos = document.querySelectorAll('video');
            for (var i = 0; i < videos.length; i++) {
              var label = videos[i].getAttribute('title') || videos[i].getAttribute('aria-label');
              if (label && label.trim() && !isGenericJsTitle(label)) {
                return label.trim();
              }
            }

            var headline = document.querySelector('h1.entry-title') || 
                           document.querySelector('h1.post-title') ||
                           document.querySelector('h1.title') ||
                           document.querySelector('.video-title');
            if (headline && headline.innerText && headline.innerText.trim()) {
              var txt = headline.innerText.trim();
              if (!isGenericJsTitle(txt)) return txt;
            }

            // 2. Post Caption / Caption Description
            var tiktokDesc = document.querySelector('[data-e2e="browse-video-desc"]') || 
                             document.querySelector('[class*="StyledCaption"]') ||
                             document.querySelector('.tiktok-video-desc');
            if (tiktokDesc && tiktokDesc.innerText && tiktokDesc.innerText.trim()) {
              var txt = tiktokDesc.innerText.trim();
              if (!isGenericJsTitle(txt)) return txt;
            }
            
            var fbDesc = document.querySelector('[data-ad-preview="message"]') || 
                         document.querySelector('[class*="styled-text"]') ||
                         document.querySelector('.post_message') ||
                         document.querySelector('[data-testid="post_message"]');
            if (fbDesc && fbDesc.innerText && fbDesc.innerText.trim()) {
              var txt = fbDesc.innerText.trim();
              if (!isGenericJsTitle(txt)) return txt;
            }

            // 3. Metadata Title (Open Graph / Twitter titles)
            var ogTitle = document.querySelector('meta[property="og:title"]') || 
                           document.querySelector('meta[name="twitter:title"]');
            if (ogTitle && ogTitle.content && ogTitle.content.trim()) {
              var txt = ogTitle.content.trim();
              if (!isGenericJsTitle(txt)) return txt;
            }

            // 4. Page Title
            var t = document.title || "";
            if (t) {
              t = t.replace(/\s*-\s*YouTube/gi, "");
              t = t.replace(/\s*\|\s*Facebook/gi, "");
              t = t.replace(/\s*-\s*TikTok/gi, "");
              t = t.replace(/\s*-\s*Instagram/gi, "");
              t = t.trim();
              if (t && !isGenericJsTitle(t)) {
                return t;
              }
            }
            return "Video Playback Theme";
          }

          function findVideos() {
            var allVideos = Array.from(document.querySelectorAll('video'));
            var iframes = document.querySelectorAll('iframe');
            for (var i = 0; i < iframes.length; i++) {
              try {
                var iframeDoc = iframes[i].contentDocument || iframes[i].contentWindow.document;
                if (iframeDoc) {
                  var iframeVideos = iframeDoc.querySelectorAll('video');
                  for (var j = 0; j < iframeVideos.length; j++) {
                    allVideos.push(iframeVideos[j]);
                  }
                }
              } catch(e) {
                // Cross-origin iframe, ignore
              }
            }
            return allVideos;
          }

          function checkVideos() {
            var videos = findVideos();
            var anyPlaying = false;
            var anyStarted = false;
            var allEnded = true;
            var activeSrc = "";
            var activeTitle = getCleanPageTitle();

            var activeDuration = 0.0;
            if (videos.length > 0) {
              for (var i = 0; i < videos.length; i++) {
                var v = videos[i];
                if (!v.paused && !v.ended) {
                  anyPlaying = true;
                }
                if (v.currentTime > 0.1 && !v.ended) {
                  anyStarted = true;
                }
                if (!v.ended) {
                  allEnded = false;
                }
                if (!v.paused && (v.src || v.currentSrc)) {
                  activeSrc = v.src || v.currentSrc || "";
                  activeDuration = v.duration || 0.0;
                }
              }
              if (activeDuration === 0.0 && videos[0]) {
                activeDuration = videos[0].duration || 0.0;
              }
            } else {
              allEnded = false;
            }

            var isPlaybackActive = anyPlaying || (anyStarted && !allEnded);

            if (window.VideoPlayObserver) {
              window.VideoPlayObserver.onVideoState(isPlaybackActive, activeTitle, activeSrc, activeDuration);
            }
          }

          function setupListeners() {
            var videos = findVideos();
            for (var i = 0; i < videos.length; i++) {
              var v = videos[i];
              if (!v.hasAttribute('data-play-listener')) {
                v.setAttribute('data-play-listener', 'true');
                v.addEventListener('play', checkVideos);
                v.addEventListener('playing', checkVideos);
                v.addEventListener('pause', checkVideos);
                v.addEventListener('ended', checkVideos);
                v.addEventListener('timeupdate', checkVideos);
              }
            }
          }

          setupListeners();
          checkVideos();

          if (window.MutationObserver) {
            var observer = new MutationObserver(function() {
              setupListeners();
              checkVideos();
            });
            observer.observe(document.body, { childList: true, subtree: true });
          }

          if (!window.videoCheckInterval) {
            window.videoCheckInterval = setInterval(checkVideos, 1000);
          }
        })()
    """.trimIndent()
    webView.evaluateJavascript(js, null)
}

class VideoPlayObserver(
    private val viewModel: BrowserViewModel,
    private val tabId: String
) {
    @android.webkit.JavascriptInterface
    fun onVideoState(isPlaying: Boolean, title: String, currentSrc: String, duration: Double) {
        viewModel.onVideoPlaybackStateChanged(tabId, isPlaying, title, currentSrc, duration)
    }

    @android.webkit.JavascriptInterface
    fun onVideoState(isPlaying: Boolean, title: String, currentSrc: String) {
        viewModel.onVideoPlaybackStateChanged(tabId, isPlaying, title, currentSrc, 0.0)
    }
}
