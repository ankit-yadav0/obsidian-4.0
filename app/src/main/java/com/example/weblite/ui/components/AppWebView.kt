package com.example.weblite.ui.components

import android.net.Uri
import android.os.SystemClock
import android.view.View
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.example.BuildConfig
import com.example.weblite.viewmodel.NavRequest
import com.example.weblite.webview.HardenedChromeClient
import com.example.weblite.webview.HardenedDownloadListener
import com.example.weblite.webview.HardenedWebViewClient
import com.example.weblite.webview.PageState
import com.example.weblite.webview.WebViewSetup

/**
 * Compose wrapper around the hardened browser WebView. All WebView configuration, privacy scripts,
 * request blocking and the navigation policy live in the `webview` package (plain Kotlin, unit-testable);
 * this composable only creates the view, keeps the clients' callbacks fresh and runs the load requests.
 *
 * Loads are driven by explicit requests ([urlToLoad] for the first load, [navRequest] afterwards) and never
 * by comparing URLs on recomposition, which used to restart loads and jump single-page apps backwards.
 */
@Composable
fun AppWebView(
    urlToLoad: String,
    navRequest: NavRequest?,
    isOnline: Boolean,
    isRefreshing: Boolean,
    networkBlocked: Boolean,
    allowedDomain: String,
    shieldOff: Boolean,
    isIncognito: Boolean,
    onNavRequestHandled: () -> Unit,
    onPageStarted: (String) -> Unit,
    onUrlChanged: (String) -> Unit,
    onProgressChanged: (Int) -> Unit,
    onPageFinished: (String, Boolean) -> Unit,
    onError: (String) -> Unit,
    onShowFileChooser: (ValueCallback<Array<Uri>>?, WebChromeClient.FileChooserParams?) -> Boolean,
    onPermissionRequest: (PermissionRequest) -> Unit,
    onPermissionRequestCanceled: (PermissionRequest) -> Unit,
    onShowCustomView: (View, WebChromeClient.CustomViewCallback) -> Unit,
    onHideCustomView: () -> Unit,
    onWebViewChanged: (WebView?) -> Unit,
    onDownloadRequested: (
        url: String,
        fileName: String,
        mimeType: String,
        userAgent: String?,
        cookie: String?,
        contentLength: Long
    ) -> Unit,
    onTrackerBlocked: (host: String, category: String) -> Unit,
    onNavigationBlocked: (String) -> Unit,
    shouldOpenExternally: (String) -> Boolean,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    val pageState = remember { PageState() }
    val webClient = remember { HardenedWebViewClient(context, pageState) }
    val chromeClient = remember { HardenedChromeClient(pageState) }
    val downloadListener = remember { HardenedDownloadListener() }

    // Bumped when the renderer process dies (out of memory on low-RAM phones): the dead WebView is
    // replaced by a fresh one instead of being reused or taking the whole app down.
    var generation by remember { mutableIntStateOf(0) }
    var lastCrashAt by remember { mutableLongStateOf(0L) }

    val latestUrl by rememberUpdatedState(urlToLoad)
    val incognitoNow by rememberUpdatedState(isIncognito)

    val webView = remember(generation) {
        WebView(context).also { wv ->
            val injectedAtDocumentStart = WebViewSetup.configure(wv, isOnline, BuildConfig.DEBUG)
            webClient.injectOnPageStart = !injectedAtDocumentStart
            wv.webViewClient = webClient
            wv.webChromeClient = chromeClient
            wv.setDownloadListener(downloadListener)
        }
    }

    // Every recomposition hands the clients the newest lambdas and flags, so nothing they hold can go stale.
    SideEffect {
        webClient.shieldOff = shieldOff
        if (webClient.siteHost.isEmpty()) webClient.siteHost = allowedDomain
        webClient.callbacks = HardenedWebViewClient.Callbacks(
            onPageStarted = onPageStarted,
            onUrlChanged = onUrlChanged,
            onPageFinished = onPageFinished,
            onError = onError,
            onTrackerBlocked = onTrackerBlocked,
            onNavigationBlocked = onNavigationBlocked,
            shouldOpenExternally = shouldOpenExternally,
            onRenderProcessGone = {
                val now = SystemClock.elapsedRealtime()
                if (now - lastCrashAt > 10_000L) {
                    lastCrashAt = now
                    generation++
                } else {
                    onError("This page keeps running out of memory.")
                }
            }
        )
        chromeClient.callbacks = HardenedChromeClient.Callbacks(
            onProgressChanged = onProgressChanged,
            onShowFileChooser = onShowFileChooser,
            onPermissionRequest = onPermissionRequest,
            onPermissionRequestCanceled = onPermissionRequestCanceled,
            onShowCustomView = onShowCustomView,
            onHideCustomView = onHideCustomView
        )
        downloadListener.onDownloadRequested = onDownloadRequested
    }

    // First load, requested navigations, and the VPN gate. While the VPN is missing the WebView is stopped
    // and paused and nothing is loaded; the first request only goes out once the connection is safe.
    val started = remember(webView) { booleanArrayOf(false) }
    LaunchedEffect(webView, networkBlocked, navRequest?.id) {
        if (networkBlocked) {
            webView.stopLoading()
            webView.onPause()
            webView.pauseTimers()
            return@LaunchedEffect
        }
        webView.onResume()
        webView.resumeTimers()
        val request = navRequest
        if (request != null) {
            started[0] = true
            webView.loadUrl(request.url, WebViewSetup.PRIVACY_HEADERS)
            onNavRequestHandled()
        } else if (!started[0]) {
            started[0] = true
            webView.loadUrl(latestUrl, WebViewSetup.PRIVACY_HEADERS)
        }
    }

    // Pull-to-refresh / retry: fires once when isRefreshing turns true (or when the VPN returns while a refresh is pending).
    LaunchedEffect(webView, isRefreshing, networkBlocked) {
        if (isRefreshing && !networkBlocked) {
            if (webView.url == null) {
                started[0] = true
                webView.loadUrl(latestUrl, WebViewSetup.PRIVACY_HEADERS)
            } else {
                webView.reload()
            }
        }
    }

    // Keep the cache mode in sync with the live network state.
    LaunchedEffect(webView, isOnline) {
        webView.settings.cacheMode = WebViewSetup.cacheModeFor(isOnline)
    }

    DisposableEffect(webView) {
        onWebViewChanged(webView)
        onDispose {
            onWebViewChanged(null)
            webView.stopLoading()
            if (incognitoNow) {
                // What an incognito page cached or put into this view's history must not outlive the view.
                webView.clearHistory()
                webView.clearCache(true)
            }
            webView.destroy()
        }
    }

    key(generation) {
        AndroidView(factory = { webView }, modifier = modifier)
    }
}
