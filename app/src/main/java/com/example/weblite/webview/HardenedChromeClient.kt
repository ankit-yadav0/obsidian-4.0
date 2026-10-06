package com.example.weblite.webview

import android.net.Uri
import android.view.View
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView

/**
 * WebChromeClient that forwards everything to [callbacks]. The callbacks are replaced on every
 * recomposition, and are always reached through the `callbacks.` prefix, so a callback can never
 * accidentally call the override that shares its name.
 */
class HardenedChromeClient(private val pageState: PageState) : WebChromeClient() {

    class Callbacks(
        val onProgressChanged: (Int) -> Unit = {},
        val onShowFileChooser: (ValueCallback<Array<Uri>>?, WebChromeClient.FileChooserParams?) -> Boolean =
            { _, _ -> false },
        val onPermissionRequest: (PermissionRequest) -> Unit = { it.deny() },
        val onPermissionRequestCanceled: (PermissionRequest) -> Unit = {},
        val onShowCustomView: (View, WebChromeClient.CustomViewCallback) -> Unit =
            { _, callback -> callback.onCustomViewHidden() },
        val onHideCustomView: () -> Unit = {}
    )

    @Volatile
    var callbacks = Callbacks()

    override fun onProgressChanged(view: WebView?, newProgress: Int) {
        super.onProgressChanged(view, newProgress)
        // WebView also reports 100% for its own error page; that must not clear our error screen.
        if (newProgress >= 100 && pageState.mainFrameFailed) return
        callbacks.onProgressChanged(newProgress)
    }

    override fun onShowFileChooser(
        webView: WebView?,
        filePathCallback: ValueCallback<Array<Uri>>?,
        fileChooserParams: FileChooserParams?
    ): Boolean = callbacks.onShowFileChooser(filePathCallback, fileChooserParams)

    override fun onPermissionRequest(request: PermissionRequest?) {
        if (request != null) callbacks.onPermissionRequest(request)
    }

    override fun onPermissionRequestCanceled(request: PermissionRequest?) {
        if (request != null) callbacks.onPermissionRequestCanceled(request)
    }

    // Location is never shared with websites.
    override fun onGeolocationPermissionsShowPrompt(origin: String?, callback: GeolocationPermissions.Callback?) {
        callback?.invoke(origin, false, false)
    }

    override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
        if (view != null && callback != null) callbacks.onShowCustomView(view, callback)
    }

    override fun onHideCustomView() {
        callbacks.onHideCustomView()
    }
}
