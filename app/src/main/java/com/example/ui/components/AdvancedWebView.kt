package com.example.ui.components

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
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
    isVisible: Boolean = true,
    modifier: Modifier = Modifier,
    onShowCustomView: (android.view.View, WebChromeClient.CustomViewCallback) -> Unit = { _, _ -> },
    onHideCustomView: () -> Unit = {},
    onDoubleTap: () -> Unit = {}
) {
    val context = LocalContext.current
    
    // Remember the WebView instance so it persists across recompositions
    val webView = remember {
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

            // Standard Download listener for movies
            setDownloadListener { url, userAgent, contentDisposition, mimeType, contentLength ->
                try {
                    val request = DownloadManager.Request(Uri.parse(url)).apply {
                        val fileName = URLUtil.guessFileName(url, contentDisposition, mimeType)
                        setTitle(fileName)
                        setDescription("Downloading cinema file...")
                        setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                        setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
                        addRequestHeader("User-Agent", userAgent)
                        
                        // Enable scanning by MediaScanner to index content
                        allowScanningByMediaScanner()
                    }
                    
                    val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                    downloadManager.enqueue(request)
                    Toast.makeText(context, "Movie download started...", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(context, "Failed to start download: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
                    // Fallback to opening in external browser
                    try {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                        context.startActivity(intent)
                    } catch (ex: Exception) {
                        Toast.makeText(context, "No app available to handle this link.", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    val gestureDetector = remember(context, onDoubleTap) {
        GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                onDoubleTap()
                return false
            }
        })
    }

    LaunchedEffect(gestureDetector) {
        webView.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            false
        }
    }

    // Connect WebView controls back to view model status
    webView.webViewClient = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
            val url = request?.url?.toString() ?: return false
            
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
            super.onPageStarted(view, url, favicon)
            viewModel.updateLoadingStatus(true, 10)
        }

        override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
            super.doUpdateVisitedHistory(view, url, isReload)
            if (url != null) {
                viewModel.updateCurrentState(url, view?.title ?: "")
            }
        }

        override fun onPageFinished(view: WebView?, url: String?) {
            super.onPageFinished(view, url)
            viewModel.updateLoadingStatus(false, 100)
            if (url != null) {
                viewModel.updateCurrentState(url, view?.title ?: "")
            }
            viewModel.updateNavigationCapabilities(
                back = view?.canGoBack() ?: false,
                forward = view?.canGoForward() ?: false
            )
        }

        override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
            super.onReceivedError(view, request, error)
            // Error handling can trigger specific offline pages or messages
        }
    }

    webView.webChromeClient = object : WebChromeClient() {
        override fun onProgressChanged(view: WebView?, newProgress: Int) {
            super.onProgressChanged(view, newProgress)
            viewModel.updateLoadingStatus(newProgress < 100, newProgress)
        }

        override fun onReceivedTitle(view: WebView?, title: String?) {
            super.onReceivedTitle(view, title)
            viewModel.updateCurrentState(view?.url ?: "", title ?: "Able Drama")
        }

        // Full Screen video support
        override fun onShowCustomView(view: android.view.View?, callback: CustomViewCallback?) {
            if (view != null && callback != null) {
                onShowCustomView(view, callback)
            }
        }

        override fun onHideCustomView() {
            onHideCustomView()
        }
    }

    // Collect external commands from ViewModel
    LaunchedEffect(viewModel) {
        viewModel.commands.collectLatest { command ->
            when (command) {
                is WebViewCommand.GoBack -> if (webView.canGoBack()) webView.goBack()
                is WebViewCommand.GoForward -> if (webView.canGoForward()) webView.goForward()
                is WebViewCommand.Reload -> webView.reload()
                is WebViewCommand.LoadUrl -> webView.loadUrl(command.url)
            }
        }
    }

    // Synchronize starting URL once on initialization
    LaunchedEffect(Unit) {
        webView.loadUrl(viewModel.currentUrl.value)
    }

    // Intercept system Back button to navigate parent web stack
    val canGoBack by viewModel.canGoBack.collectAsState()
    BackHandler(enabled = canGoBack && isVisible) {
        viewModel.goBack()
    }

    // Embed WebView into Jetpack Compose layout tree
    AndroidView(
        factory = { webView },
        modifier = modifier.fillMaxSize(),
        update = { view ->
            view.visibility = android.view.View.VISIBLE
        }
    )
}
