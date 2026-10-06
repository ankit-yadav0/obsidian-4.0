package com.example.weblite.webview

import android.annotation.SuppressLint
import android.os.Build
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.example.weblite.util.UrlUtils
import kotlin.math.abs

/** One-time hardening and configuration of a browser WebView. */
object WebViewSetup {

    /**
     * Do Not Track + Global Privacy Control as HTTP headers. They can only be attached to loads the app
     * starts itself (typed URLs, retries); the same signals are exposed to scripts as navigator.doNotTrack
     * and navigator.globalPrivacyControl by [PrivacyScripts].
     */
    val PRIVACY_HEADERS: Map<String, String> = mapOf("DNT" to "1", "Sec-GPC" to "1")

    /**
     * Applies all settings to a freshly created WebView.
     * @return true if the privacy script is injected by the document-start API; false means the script
     *         has to be injected from onPageStarted instead (see [HardenedWebViewClient.injectOnPageStart]).
     */
    @SuppressLint("SetJavaScriptEnabled")
    fun configure(webView: WebView, isOnline: Boolean, debuggable: Boolean): Boolean {
        webView.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        webView.isScrollbarFadingEnabled = true
        webView.isVerticalScrollBarEnabled = false
        webView.isHorizontalScrollBarEnabled = false
        // Keeps Android's autofill framework (often backed by a Google service) away from page form fields.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            webView.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
        }

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            // Pages never need file:// or content:// access; uploads go through the file-chooser callback.
            allowFileAccess = false
            allowContentAccess = false
            useWideViewPort = true
            loadWithOverviewMode = true
            mediaPlaybackRequiresUserGesture = false

            // Scripts cannot open windows on their own. (Windows opened by a tap load in this same view,
            // because multiple windows are not supported, and then go through the navigation policy.)
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)

            @Suppress("DEPRECATION")
            saveFormData = false

            // Safe Browsing is switched off so that no URL (not even as a hash prefix) is sent to Google.
            // Trade-off: the built-in phishing / malware warning page is lost.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                setSafeBrowsingEnabled(false)
            }

            cacheMode = cacheModeFor(isOnline)
            userAgentString = UrlUtils.cleanUserAgent(userAgentString)
        }

        val injected = if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(webView, PrivacyScripts.DOCUMENT_START, setOf("*"))
            true
        } else {
            false
        }

        WebView.setWebContentsDebuggingEnabled(debuggable)

        // First-party cookies only (needed for logins); third-party cookies are the main channel
        // ad networks use to follow you between sites.
        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(webView, false)

        installEdgeSwipeNavigation(webView)
        return injected
    }

    fun cacheModeFor(isOnline: Boolean): Int =
        if (isOnline) WebSettings.LOAD_DEFAULT else WebSettings.LOAD_CACHE_ELSE_NETWORK

    /**
     * Swipe in from the left / right screen edge to go back / forward. Limiting it to the edge avoids
     * hijacking horizontal scrolling inside the page (carousels, tab strips), which starts mid-screen.
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun installEdgeSwipeNavigation(webView: WebView) {
        val context = webView.context
        val edgeThresholdPx = 32 * context.resources.displayMetrics.density
        val detector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                val startX = e1?.x ?: return false
                val diffX = e2.x - startX
                val diffY = e2.y - e1.y
                val isMostlyHorizontal = abs(diffX) > abs(diffY) * 2f
                val isFastEnough = abs(velocityX) > 400
                val isFarEnough = abs(diffX) > 120
                val width = webView.width.takeIf { it > 0 } ?: context.resources.displayMetrics.widthPixels
                if (!(isMostlyHorizontal && isFastEnough && isFarEnough)) return false
                return if (diffX > 0 && startX <= edgeThresholdPx) {
                    if (webView.canGoBack()) { webView.goBack(); true } else false
                } else if (diffX < 0 && startX >= width - edgeThresholdPx) {
                    if (webView.canGoForward()) { webView.goForward(); true } else false
                } else {
                    false
                }
            }
        })
        webView.setOnTouchListener { _, event ->
            detector.onTouchEvent(event)
            false
        }
    }
}
