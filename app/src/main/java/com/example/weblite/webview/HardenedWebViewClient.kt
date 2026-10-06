package com.example.weblite.webview

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import com.example.weblite.util.TrackerBlocklist
import com.example.weblite.util.UrlUtils

/**
 * Navigation policy, tracker blocking and page-state reporting for the browser WebView.
 *
 * Navigation policy for the main frame:
 *  1. non-web schemes (tel:, mailto:, intent: ...) only ever open an app after a real user tap
 *  2. same site                      -> allowed
 *  3. domain the user hands to an external browser -> opened externally
 *  4. known ad / tracker host        -> blocked and counted
 *  5. server-side redirect           -> allowed (the navigation that led here was already allowed)
 *  6. script-driven jump, no tap     -> blocked and counted
 *  7. user tapped a link to another site -> blocked, and the user is offered "Open anyway"
 * http:// targets are upgraded to https:// wherever a navigation is allowed.
 */
class HardenedWebViewClient(
    private val context: Context,
    private val pageState: PageState
) : WebViewClient() {

    class Callbacks(
        val onPageStarted: (String) -> Unit = {},
        val onUrlChanged: (String) -> Unit = {},
        val onPageFinished: (url: String, canGoBack: Boolean) -> Unit = { _, _ -> },
        val onError: (String) -> Unit = {},
        val onTrackerBlocked: (host: String, category: String) -> Unit = { _, _ -> },
        val onNavigationBlocked: (url: String) -> Unit = {},
        val shouldOpenExternally: (host: String) -> Boolean = { false },
        val onRenderProcessGone: () -> Unit = {}
    )

    // Written on the main thread, read on WebView's IO threads (shouldInterceptRequest).
    @Volatile var callbacks = Callbacks()
    @Volatile var shieldOff = false
    /** Host of the page currently shown; navigations are "same site" relative to this. */
    @Volatile var siteHost = ""
    /** True when the document-start script API is unavailable and the script must be injected manually. */
    @Volatile var injectOnPageStart = false

    @Volatile private var lastStartedUrl = ""

    // ---- request blocking --------------------------------------------------------------------

    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
        // Main-frame requests are never blocked here: the user chose to visit them.
        if (request == null || shieldOff || request.isForMainFrame) {
            return super.shouldInterceptRequest(view, request)
        }
        val host = request.url?.host.orEmpty()
        val path = request.url?.path.orEmpty()
        if (TrackerBlocklist.isBlockedRequest(host, path)) {
            callbacks.onTrackerBlocked(host.lowercase(), BlockCategory.TRACKER)
            return BlockedResponses.forRequest(path)
        }
        return super.shouldInterceptRequest(view, request)
    }

    // ---- navigation policy -------------------------------------------------------------------

    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
        val uri = request?.url ?: return false
        val scheme = uri.scheme?.lowercase().orEmpty()
        if (scheme != "http" && scheme != "https") return handleNonWebScheme(uri, scheme, request.hasGesture())
        // Sub-frames (ads, embedded players) load normally; the request filter handles trackers.
        if (!request.isForMainFrame) return false

        val host = uri.host.orEmpty().lowercase()
        if (UrlUtils.isSameSite(host, siteHost)) return allow(view, uri, scheme)
        if (callbacks.shouldOpenExternally(host)) {
            openExternally(uri)
            return true
        }
        if (!shieldOff && TrackerBlocklist.isBlockedHost(host)) {
            callbacks.onTrackerBlocked(host, BlockCategory.TRACKER)
            return true
        }
        if (request.isRedirect) return allow(view, uri, scheme)
        if (!request.hasGesture()) {
            if (!shieldOff) callbacks.onTrackerBlocked(host, BlockCategory.REDIRECT)
            return true
        }
        callbacks.onNavigationBlocked(uri.toString())
        return true
    }

    /** Lets an allowed navigation continue; plain http:// is upgraded to https:// instead. */
    private fun allow(view: WebView?, uri: Uri, scheme: String): Boolean {
        if (scheme != "http") return false
        view?.loadUrl(uri.buildUpon().scheme("https").build().toString(), WebViewSetup.PRIVACY_HEADERS)
        return true
    }

    private fun handleNonWebScheme(uri: Uri, scheme: String, hasGesture: Boolean): Boolean {
        if (scheme == "about" || scheme == "blob") return false
        // A page may not start another app without the user tapping something.
        if (!hasGesture) return true
        try {
            when (scheme) {
                "tel", "mailto", "geo", "sms", "smsto", "whatsapp" ->
                    context.startActivity(Intent(Intent.ACTION_VIEW, uri))
                "intent" -> startIntentUri(uri.toString())
                else -> Unit
            }
        } catch (e: Exception) {
            Toast.makeText(context, "No app available to open this link", Toast.LENGTH_SHORT).show()
        }
        return true
    }

    private fun startIntentUri(uriString: String) {
        val intent = Intent.parseUri(uriString, Intent.URI_INTENT_SCHEME)
        // A web page must not choose which component of which app is started, only a browsable target.
        intent.addCategory(Intent.CATEGORY_BROWSABLE)
        intent.component = null
        intent.selector = null
        context.startActivity(intent)
    }

    private fun openExternally(uri: Uri) {
        try {
            context.startActivity(Intent.createChooser(Intent(Intent.ACTION_VIEW, uri), "Open with"))
        } catch (e: Exception) {
            Toast.makeText(context, "No browser app available to open this", Toast.LENGTH_SHORT).show()
        }
    }

    // ---- page lifecycle ----------------------------------------------------------------------

    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
        super.onPageStarted(view, url, favicon)
        if (url == null) return
        if (injectOnPageStart) view?.evaluateJavascript(PrivacyScripts.DOCUMENT_START, null)
        pageState.mainFrameFailed = false
        lastStartedUrl = url
        // Every main-frame navigation that reaches this point was allowed, so the new page is the new "site".
        Uri.parse(url).host?.lowercase()?.takeIf { it.isNotEmpty() }?.let { siteHost = it }
        callbacks.onPageStarted(url)
    }

    override fun onPageFinished(view: WebView?, url: String?) {
        super.onPageFinished(view, url)
        // WebView also calls this for its own error page; that is not a successful load.
        if (url == null || pageState.mainFrameFailed) return
        callbacks.onPageFinished(url, view?.canGoBack() == true)
    }

    /** Also fires for single-page-app route changes (history.pushState), which never call onPageStarted. */
    override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
        super.doUpdateVisitedHistory(view, url, isReload)
        if (url != null && (url.startsWith("https://") || url.startsWith("http://"))) {
            callbacks.onUrlChanged(url)
        }
    }

    override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
        super.onReceivedError(view, request, error)
        if (request?.isForMainFrame != true) return
        val description = error?.description?.toString() ?: "Network loading failed"
        // A navigation that was cancelled (new load started, download began) is not an error.
        if (description.contains("ERR_ABORTED")) return
        pageState.mainFrameFailed = true
        callbacks.onError(description)
    }

    // HTTP error statuses (403, 404, 503...) are deliberately NOT handled: the server's own page
    // (for example a Cloudflare challenge) has to stay visible and usable.

    override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
        handler?.cancel()
        val failingHost = error?.url?.let { Uri.parse(it).host }
        val currentHost = Uri.parse(lastStartedUrl).host
        // Sub-resources with a bad certificate are simply refused; only the page itself is an error.
        if (failingHost == null || failingHost == currentHost) {
            pageState.mainFrameFailed = true
            callbacks.onError("SSL Security Certificate Error")
        }
    }

    /**
     * The renderer was killed (usually out of memory on low-RAM phones). Returning true keeps the
     * whole app alive; the host composable then replaces the dead WebView with a fresh one.
     */
    override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
        callbacks.onRenderProcessGone()
        return true
    }
}
