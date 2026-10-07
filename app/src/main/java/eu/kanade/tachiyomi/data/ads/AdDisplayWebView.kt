package eu.kanade.tachiyomi.data.ads

import android.annotation.SuppressLint
import android.graphics.Color
import android.net.Uri
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import java.util.concurrent.atomic.AtomicBoolean

/** One sandboxed WebView. A page/resource load is never reported as a rendered creative. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun AdDisplayWebView(
    url: String,
    modifier: Modifier = Modifier,
    onFailedToLoad: (() -> Unit)? = null,
    onRequestStarted: (() -> Unit)? = null,
    onReleased: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    var slotHeight by remember(url) { mutableIntStateOf(0) }
    var failed by remember(url) { mutableStateOf(false) }
    val alive = remember(url) { AtomicBoolean(true) }
    val latestFailure by rememberUpdatedState(onFailedToLoad)
    val latestRequestStarted by rememberUpdatedState(onRequestStarted)
    val latestReleased by rememberUpdatedState(onReleased)

    // Replacing a failed network view with the local Debug surface is not boundary disposal.
    DisposableEffect(url) {
        onDispose {
            alive.set(false)
            latestReleased?.invoke()
        }
    }

    fun failSlot() {
        if (alive.get() && !failed) {
            failed = true
            slotHeight = 0
            if (!AdDebugTools.enabled) latestFailure?.invoke()
        }
    }

    LaunchedEffect(url) {
        if (safeSlotUri(url) == null) {
            failSlot()
            return@LaunchedEffect
        }
        delay(12_000L)
        // A sized cross-origin iframe is not affirmative creative evidence. The bounded
        // Debug preview also ends uncertain presentations, without declaring network NO_FILL.
        // Its local replacement performs no request, click, reward or impression accounting.
        if (!failed && (slotHeight == 0 || AdDebugTools.enabled)) failSlot()
    }

    if (failed) {
        if (AdDebugTools.enabled) AdDebugTools.DisplayFallback(modifier)
        return
    }
    if (safeSlotUri(url) == null) return
    Box(modifier = modifier.fillMaxWidth().height(slotHeight.dp)) {
        AndroidView(
            modifier = Modifier.fillMaxWidth().height(slotHeight.dp),
            factory = { viewContext ->
                WebView(viewContext).apply {
                    setBackgroundColor(Color.TRANSPARENT)
                    isVerticalScrollBarEnabled = false
                    isHorizontalScrollBarEnabled = false
                    overScrollMode = WebView.OVER_SCROLL_NEVER
                    settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = false
                        allowFileAccess = false
                        allowContentAccess = false
                        setGeolocationEnabled(false)
                        javaScriptCanOpenWindowsAutomatically = false
                        setSupportMultipleWindows(false)
                        mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                        mediaPlaybackRequiresUserGesture = true
                        useWideViewPort = true
                        loadWithOverviewMode = true
                    }
                    addJavascriptInterface(object {
                        @JavascriptInterface
                        fun onSlotSize(cssPixels: Int) {
                            post {
                                // Sizing signal only; it is not evidence that an ad was served.
                                if (alive.get() && !failed) slotHeight = cssPixels.coerceIn(0, 480)
                            }
                        }

                        @JavascriptInterface
                        fun onPlacementFailed() {
                            post { failSlot() }
                        }
                    }, "MangaroAdBridge")
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest): Boolean {
                            val uri = request.url
                            if (!request.isForMainFrame) {
                                if (request.hasGesture() && isWebUri(uri)) {
                                    SmartLinkAdLauncher.open(context, uri.toString())
                                    return true
                                }
                                // Adsterra cross-origin frames/resources must load normally.
                                return false
                            }
                            if (safeSlotUri(uri.toString()) != null) return false
                            if (request.hasGesture() && isWebUri(uri)) {
                                SmartLinkAdLauncher.open(context, uri.toString())
                            }
                            // Block redirects and top-level navigation without an explicit gesture.
                            return true
                        }

                        override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                            super.onReceivedError(view, request, error)
                            if (request?.isForMainFrame == true) failSlot()
                        }

                        override fun onReceivedHttpError(view: WebView?, request: WebResourceRequest?, response: WebResourceResponse?) {
                            super.onReceivedHttpError(view, request, response)
                            if (request?.isForMainFrame == true) failSlot()
                        }

                        override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                            failSlot()
                            return true
                        }
                    }
                    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0)
                    // Commit after the actual request is enqueued, not on a later page callback
                    // which can be lost if the boundary is disposed immediately afterward.
                    runCatching { loadUrl(url) }
                        .onSuccess { latestRequestStarted?.invoke() }
                        .onFailure { failSlot() }
                }
            },
            update = { webView ->
                val px = (slotHeight * webView.resources.displayMetrics.density).toInt()
                webView.layoutParams = webView.layoutParams.apply { height = px }
            },
            onRelease = { webView ->
                alive.set(false)
                runCatching {
                    webView.webViewClient = WebViewClient()
                    webView.stopLoading()
                    webView.removeJavascriptInterface("MangaroAdBridge")
                    webView.loadUrl("about:blank")
                    webView.removeAllViews()
                    webView.destroy()
                }
            },
        )
    }
}

private fun safeSlotUri(raw: String): Uri? {
    val uri = runCatching { Uri.parse(raw) }.getOrNull() ?: return null
    return uri.takeIf {
        it.scheme == "https" && it.host == "mangaro-web.vercel.app" && it.path == "/ad-slot" &&
            it.userInfo == null && it.port == -1 && it.query == null && it.fragment == null
    }
}

private fun isWebUri(uri: Uri) = uri.scheme == "http" || uri.scheme == "https"
