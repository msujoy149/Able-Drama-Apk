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
import kotlinx.coroutines.flow.collectLatest

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun AdvancedWebView(
    viewModel: BrowserViewModel,
    tabId: String,
    isVisible: Boolean = true,
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
            }

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
            }
        }

        override fun onPageFinished(view: WebView?, url: String?) {
            super.onPageFinished(view, url)
            viewModel.updateLoadingStatus(tabId, false, 100)
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

    val isSelectedTab = remember(selectedTabIdVal, tabId) {
        selectedTabIdVal == tabId
    }

    val canGoBack = remember(tabsListVal, tabId) {
        tabsListVal.find { it.id == tabId }?.canGoBack ?: false
    }

    BackHandler(enabled = canGoBack && isVisible && isSelectedTab) {
        viewModel.goBack()
    }

    LaunchedEffect(webView, isVisible) {
        if (isVisible) {
            kotlinx.coroutines.delay(2000)
            while (true) {
                captureWebViewThumbnail(webView, tabId, viewModel)
                kotlinx.coroutines.delay(4000)
            }
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
                val originalBitmap = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
                val canvas = android.graphics.Canvas(originalBitmap)
                webView.draw(canvas)
                
                val maxDim = 320
                val scale = maxDim.toFloat() / Math.max(width, height).toFloat()
                val finalBitmap = if (scale < 1.0f) {
                    val sw = (width * scale).toInt()
                    val sh = (height * scale).toInt()
                    val scaled = android.graphics.Bitmap.createScaledBitmap(originalBitmap, sw, sh, true)
                    originalBitmap.recycle()
                    scaled
                } else {
                    originalBitmap
                }
                viewModel.updateTabScreenshot(tabId, finalBitmap)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
