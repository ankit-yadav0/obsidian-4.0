package com.example.weblite.ui.components

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.SslErrorHandler
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import java.io.ByteArrayInputStream
import com.example.BuildConfig

private val AD_TRACKER_HOSTS = listOf(
    "doubleclick.net",
    "google-analytics.com",
    "googlesyndication.com",
    "googletagservices.com",
    "googletagmanager.com",
    "adnxs.com",
    "popads.net",
    "popcash.net",
    "propellerads.com",
    "exoclick.com",
    "adroll.com",
    "scorecardresearch.com",
    "taboola.com",
    "outbrain.com",
    // Analytics / behavioral trackers
    "facebook.com/tr",
    "connect.facebook.net",
    "hotjar.com",
    "mixpanel.com",
    "segment.io",
    "segment.com",
    "amplitude.com",
    "fullstory.com",
    "clarity.ms",
    "yandex.ru/metrika",
    "quantserve.com",
    "criteo.com",
    "criteo.net",
    "moatads.com",
    "adsystem.com",
    "adservice.google.com",
    "amazon-adsystem.com",
    "bing.com/bat.js",
    "advertising.com",
    "adcolony.com",
    "mopub.com",
    "chartboost.com",
    "unityads.unity3d.com",
    "popunder.net",
    "juicyads.com",
    "adsterra.com",
    "revcontent.com",
    "mgid.com"
)

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun AppWebView(
    urlToLoad: String,
    isOnline: Boolean,
    isRefreshing: Boolean,
    allowedDomain: String,
    shieldOff: Boolean,
    onPageStarted: (String) -> Unit,
    onProgressChanged: (Int) -> Unit,
    onPageFinished: (String, Boolean) -> Unit,
    onError: (String) -> Unit,
    onShowFileChooser: (ValueCallback<Array<Uri>>?, WebChromeClient.FileChooserParams?) -> Boolean,
    onPermissionRequest: (PermissionRequest) -> Unit,
    onShowCustomView: (View, WebChromeClient.CustomViewCallback) -> Unit,
    onHideCustomView: () -> Unit,
    onWebViewCreated: (WebView) -> Unit,
    onDownloadRequested: (url: String, fileName: String, mimeType: String, userAgent: String?, cookie: String?) -> Unit,
    onTrackerBlocked: () -> Unit,
    isAdultContentHost: (String) -> Boolean,
    onAdultContentBlocked: (String) -> Unit,
    shouldOpenExternally: (String) -> Boolean,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    // shieldOff can change while this same WebView instance stays alive
    // (toggled without switching tabs), so it needs a live-mutable holder
    // that the WebViewClient/WebChromeClient closures below can re-read
    // each time — a plain captured parameter would go stale.
    val shieldOffState = remember { mutableStateOf(shieldOff) }
    LaunchedEffect(shieldOff) { shieldOffState.value = shieldOff }

    val webView = remember {
        WebView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )

            // Performance optimizations for Android Go (2GB RAM)
            setLayerType(View.LAYER_TYPE_HARDWARE, null)
            isScrollbarFadingEnabled = true
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false

            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                allowFileAccess = true
                allowContentAccess = true
                useWideViewPort = true
                loadWithOverviewMode = true
                mediaPlaybackRequiresUserGesture = false

                // Block pop-ups / new-window spawning
                javaScriptCanOpenWindowsAutomatically = false
                setSupportMultipleWindows(false)

                // --- Privacy hardening ---
                // Don't remember typed form data (names, searches, etc.) on-device
                @Suppress("DEPRECATION")
                saveFormData = false
                // Google Safe Browsing otherwise reports every URL you visit to
                // Google's servers before loading it. Turning it off keeps that
                // browsing list local to the device (trade-off: lose that
                // malicious-site warning).
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    setSafeBrowsingEnabled(false)
                }

                // Modern caching logic for offline resilience
                cacheMode = if (isOnline) {
                    WebSettings.LOAD_DEFAULT
                } else {
                    WebSettings.LOAD_CACHE_ELSE_NETWORK
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                }

                @Suppress("DEPRECATION")
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                    setRenderPriority(WebSettings.RenderPriority.HIGH)
                }

                // Standard modern User-Agent identifier
                userAgentString = userAgentString.replace("; wv", "")
            }

            // Privacy: block WebRTC's IP-discovery APIs. Even with a VPN
            // active, a site's JavaScript can otherwise use RTCPeerConnection
            // to ask a STUN server for your real network IP, bypassing the
            // VPN tunnel entirely. This runs before any page script, on
            // every frame, so it can't be undone by the page.
            if (androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.DOCUMENT_START_SCRIPT)) {
                androidx.webkit.WebViewCompat.addDocumentStartJavaScript(
                    this,
                    """
                    (function() {
                        var block = function() { throw new Error('Blocked for privacy'); };
                        try {
                            Object.defineProperty(window, 'RTCPeerConnection', { get: block, set: function(){} });
                            Object.defineProperty(window, 'webkitRTCPeerConnection', { get: block, set: function(){} });
                            Object.defineProperty(window, 'RTCDataChannel', { get: block, set: function(){} });
                        } catch (e) {}
                    })();
                    """.trimIndent(),
                    setOf("*")
                )
            }

            // WebView Debugging (Disable in release builds)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
            }

            // Enable first-party cookies only (needed for login/session state),
            // but block third-party cookies — this is the main channel ad
            // networks use to link your activity across different sites.
            val cookieManager = CookieManager.getInstance()
            cookieManager.setAcceptCookie(true)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                cookieManager.setAcceptThirdPartyCookies(this, false)
            }

            // Custom Download Listener for media/file downloads
            setDownloadListener { url, userAgent, contentDisposition, mimeType, contentLength ->
                try {
                    val fileName = URLUtil.guessFileName(url, contentDisposition, mimeType)
                    val cookie = cookieManager.getCookie(url)
                    onDownloadRequested(
                        url,
                        fileName,
                        mimeType ?: "",
                        userAgent,
                        cookie
                    )
                    Toast.makeText(context, "Download started: $fileName", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(context, "Unable to start download: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
                }
            }

            // Swipe from near the left/right screen edge to go back/forward
            // in this tab's history — same gesture as Chrome/Safari's edge
            // back-swipe. Restricting it to the edge (instead of anywhere
            // on the page) avoids hijacking horizontal scrolling, carousels,
            // or tab strips inside the page itself, which start in the
            // middle of the screen, not at the edge.
            val webViewRef = this
            val edgeThresholdPx = 32 * context.resources.displayMetrics.density // ~32dp
            val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
                override fun onFling(
                    e1: MotionEvent?,
                    e2: MotionEvent,
                    velocityX: Float,
                    velocityY: Float
                ): Boolean {
                    val startX = e1?.x ?: return false
                    val diffX = e2.x - startX
                    val diffY = e2.y - (e1.y)
                    val isMostlyHorizontal = kotlin.math.abs(diffX) > kotlin.math.abs(diffY) * 2f
                    val isFastEnough = kotlin.math.abs(velocityX) > 400
                    val isFarEnough = kotlin.math.abs(diffX) > 120
                    val screenWidth = webViewRef.width.takeIf { it > 0 }
                        ?: context.resources.displayMetrics.widthPixels
                    val startedNearLeftEdge = startX <= edgeThresholdPx
                    val startedNearRightEdge = startX >= screenWidth - edgeThresholdPx
                    if (isMostlyHorizontal && isFastEnough && isFarEnough) {
                        return if (diffX > 0 && startedNearLeftEdge) {
                            if (webViewRef.canGoBack()) { webViewRef.goBack(); true } else false
                        } else if (diffX < 0 && startedNearRightEdge) {
                            if (webViewRef.canGoForward()) { webViewRef.goForward(); true } else false
                        } else {
                            false
                        }
                    }
                    return false
                }
            })
            setOnTouchListener { _, event ->
                gestureDetector.onTouchEvent(event)
                false
            }
        }
    }

    // Handle Pull-to-refresh: fire exactly once when isRefreshing turns true,
    // instead of on every recomposition (which could cause a reload loop).
    LaunchedEffect(isRefreshing) {
        if (isRefreshing) {
            webView.reload()
        }
    }

    // Keep cache mode in sync with live network state
    LaunchedEffect(isOnline) {
        webView.settings.cacheMode = if (isOnline) {
            WebSettings.LOAD_DEFAULT
        } else {
            WebSettings.LOAD_CACHE_ELSE_NETWORK
        }
    }

    DisposableEffect(webView) {
        onWebViewCreated(webView)
        onDispose {
            webView.stopLoading()
            webView.destroy()
        }
    }

    AndroidView(
        factory = {
            webView.apply {
                webViewClient = object : WebViewClient() {

                    override fun shouldInterceptRequest(
                        view: WebView?,
                        request: WebResourceRequest?
                    ): WebResourceResponse? {
                        val urlString = request?.url?.toString()?.lowercase() ?: return super.shouldInterceptRequest(view, request)
                        val host = request.url?.host?.lowercase() ?: ""

                        // Adult-content blocking is intentionally independent of the
                        // ad/tracker shield toggle above (shieldOffState) — it stays
                        // active even if the user has turned ad-blocking off for a site.
                        if (isAdultContentHost(host)) {
                            onAdultContentBlocked(host)
                            return WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0)))
                        }

                        if (!shieldOffState.value && (
                            AD_TRACKER_HOSTS.any { host.contains(it) } ||
                            urlString.contains("/adservice/") ||
                            urlString.contains("/pagead/") ||
                            urlString.contains("googleanalytics")
                        )) {
                            onTrackerBlocked()
                            return WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0)))
                        }

                        return super.shouldInterceptRequest(view, request)
                    }

                    override fun shouldOverrideUrlLoading(
                        view: WebView?,
                        request: WebResourceRequest?
                    ): Boolean {
                        val uri = request?.url ?: return false
                        val url = uri.toString()

                        // Handle special URI schemes (tel, mailto, geo, intent, etc.)
                        if (url.startsWith("tel:") ||
                            url.startsWith("mailto:") ||
                            url.startsWith("geo:") ||
                            url.startsWith("whatsapp:") ||
                            url.startsWith("intent:")
                        ) {
                            try {
                                val intent = Intent(Intent.ACTION_VIEW, uri)
                                context.startActivity(intent)
                            } catch (e: Exception) {
                                Toast.makeText(context, "No app available to handle action", Toast.LENGTH_SHORT).show()
                            }
                            return true
                        }

                        // Domain isolation: Only keep example.com in WebView, open others in browser
                        val host = uri.host?.lowercase() ?: ""

                        // Checked before the domain-isolation early-return above it in
                        // priority doesn't apply here — a blocked host should never load,
                        // even if it happened to match allowedDomain.
                        if (isAdultContentHost(host)) {
                            onAdultContentBlocked(host)
                            view?.loadDataWithBaseURL(
                                null,
                                blockedPageHtml(host),
                                "text/html",
                                "UTF-8",
                                null
                            )
                            return true
                        }

                        if (allowedDomain.isNotBlank() && host.contains(allowedDomain)) {
                            return false // Load inside WebView
                        }

                        // Sites whose login/challenge (e.g. Cloudflare) fails
                        // inside this app's hardened WebView — user has opted
                        // to always hand these off to an external browser.
                        if (shouldOpenExternally(host)) {
                            return try {
                                val chooser = Intent.createChooser(Intent(Intent.ACTION_VIEW, uri), "Open with")
                                context.startActivity(chooser)
                                true
                            } catch (e: Exception) {
                                Toast.makeText(context, "No browser app available to open this", Toast.LENGTH_SHORT).show()
                                true
                            }
                        }

                        // Fix: a same-window redirect to a known ad/tracker
                        // domain (very common via invisible click-hijack
                        // overlays sitting on top of player controls) was
                        // being auto-launched in an external browser. Block
                        // these outright instead of leaving the app.
                        if (!shieldOffState.value && AD_TRACKER_HOSTS.any { host.contains(it) }) {
                            onTrackerBlocked()
                            return true // swallow navigation, stay put
                        }

                        // Extra guard: many ad-redirect scripts fire a
                        // same-window navigation automatically (a JS timer),
                        // with no real tap behind it. Only genuine,
                        // gesture-driven navigation gets handed to an
                        // external browser.
                        if (request?.hasGesture() != true) {
                            return true
                        }

                        return try {
                            val intent = Intent(Intent.ACTION_VIEW, uri)
                            context.startActivity(intent)
                            true
                        } catch (e: Exception) {
                            true
                        }
                    }

                    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                        super.onPageStarted(view, url, favicon)
                        url?.let { onPageStarted(it) }
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        url?.let { onPageFinished(it, view?.canGoBack() == true) }
                    }

                    override fun onReceivedError(
                        view: WebView?,
                        request: WebResourceRequest?,
                        error: WebResourceError?
                    ) {
                        super.onReceivedError(view, request, error)
                        if (request?.isForMainFrame == true) {
                            val errorMsg = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                error?.description?.toString() ?: "Network loading failed"
                            } else {
                                "Failed to load page"
                            }
                            onError(errorMsg)
                        }
                    }

                    override fun onReceivedHttpError(
                        view: WebView?,
                        request: WebResourceRequest?,
                        errorResponse: WebResourceResponse?
                    ) {
                        super.onReceivedHttpError(view, request, errorResponse)
                        if (request?.isForMainFrame == true && (errorResponse?.statusCode ?: 200) >= 400) {
                            onError("Server returned error (${errorResponse?.statusCode})")
                        }
                    }

                    override fun onReceivedSslError(
                        view: WebView?,
                        handler: SslErrorHandler?,
                        error: android.net.http.SslError?
                    ) {
                        // Safe SSL handling (Do NOT bypass certificate errors)
                        handler?.cancel()
                        onError("SSL Security Certificate Error")
                    }
                }

                webChromeClient = object : WebChromeClient() {

                    override fun onProgressChanged(view: WebView?, newProgress: Int) {
                        super.onProgressChanged(view, newProgress)
                        onProgressChanged(newProgress)
                    }

                    override fun onShowFileChooser(
                        webView: WebView?,
                        filePathCallback: ValueCallback<Array<Uri>>?,
                        fileChooserParams: FileChooserParams?
                    ): Boolean {
                        return onShowFileChooser(filePathCallback, fileChooserParams)
                    }

                    // Block pop-up / new-window attempts (window.open, target="_blank" spam, etc.)
                    override fun onCreateWindow(
                        view: WebView?,
                        isDialog: Boolean,
                        isUserGesture: Boolean,
                        resultMsg: android.os.Message?
                    ): Boolean {
                        onTrackerBlocked()
                        return false
                    }

                    override fun onPermissionRequest(request: PermissionRequest?) {
                        request?.let { onPermissionRequest(it) }
                    }

                    // Never share device location with sites — deny silently
                    // instead of showing a location prompt.
                    override fun onGeolocationPermissionsShowPrompt(
                        origin: String?,
                        callback: android.webkit.GeolocationPermissions.Callback?
                    ) {
                        callback?.invoke(origin, false, false)
                    }

                    override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                        if (view != null && callback != null) {
                            onShowCustomView(view, callback)
                        }
                    }

                    override fun onHideCustomView() {
                        onHideCustomView()
                    }
                }

                loadUrl(urlToLoad)
            }
        },
        update = {
            if (it.url != urlToLoad && !isRefreshing) {
                // Keep URL updated
            }
        },
        modifier = modifier
    )
}

// Simple local page shown in place of a blocked adult-content site —
// keeps the WebView from just going blank/stuck.
private fun blockedPageHtml(host: String): String {
    val safeHost = host.replace("<", "&lt;").replace(">", "&gt;")
    return """
        <html>
        <head><meta name="viewport" content="width=device-width, initial-scale=1"></head>
        <body style="background:#0D0E15;color:#FFFFFF;font-family:sans-serif;
                     display:flex;flex-direction:column;align-items:center;
                     justify-content:center;height:100vh;margin:0;padding:24px;text-align:center;">
            <div style="font-size:48px;margin-bottom:12px;">&#128683;</div>
            <div style="font-size:18px;font-weight:bold;margin-bottom:8px;">Site blocked</div>
            <div style="font-size:14px;color:#A0A0A0;">$safeHost is blocked by Obsidian's content filter.</div>
        </body>
        </html>
    """.trimIndent()
}

// Helper class for URL file name guessing
private object URLUtil {
    fun guessFileName(url: String, contentDisposition: String?, mimeType: String?): String {
        return android.webkit.URLUtil.guessFileName(url, contentDisposition, mimeType)
    }
}
