package com.example.weblite.webview

/** State shared by the WebViewClient and the WebChromeClient of one WebView. */
class PageState {
    /** True when the main frame of the current navigation failed (network error, SSL error...). */
    @Volatile
    var mainFrameFailed: Boolean = false
}
