package com.example.weblite.webview

import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.URLUtil

/** Hands download requests to the app, which decides whether (and how) to download. */
class HardenedDownloadListener : DownloadListener {

    @Volatile
    var onDownloadRequested: (
        url: String,
        fileName: String,
        mimeType: String,
        userAgent: String?,
        cookie: String?,
        contentLength: Long
    ) -> Unit = { _, _, _, _, _, _ -> }

    override fun onDownloadStart(
        url: String?,
        userAgent: String?,
        contentDisposition: String?,
        mimetype: String?,
        contentLength: Long
    ) {
        if (url.isNullOrEmpty()) return
        val fileName = URLUtil.guessFileName(url, contentDisposition, mimetype)
        val cookie = try {
            CookieManager.getInstance().getCookie(url)
        } catch (e: Exception) {
            null
        }
        onDownloadRequested(url, fileName, mimetype ?: "", userAgent, cookie, contentLength)
    }
}
