package com.example.weblite.privacy

import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebView
import java.io.File

/**
 * Erases what the embedded browser stored on disk (cookies, DOM storage, IndexedDB, HTTP cache).
 *
 * Wiping only when the Activity is destroyed is not enough: if the app is force-stopped or killed,
 * onDestroy never runs. So the same data is also removed before the first WebView of a new process
 * exists, which makes "nothing survives a restart" true even after a crash.
 */
object BrowsingDataWiper {

    @Volatile
    private var coldStartWipeDone = false

    /**
     * Deletes WebView's data directory. Must run before the first WebView of this process is created
     * (the host calls it first thing in onCreate). Does nothing on later calls in the same process.
     */
    fun wipeStoredFilesOnColdStart(context: Context) {
        if (coldStartWipeDone) return
        coldStartWipeDone = true
        try {
            val app = context.applicationContext
            File(app.cacheDir, "WebView").deleteRecursively()
            app.getDir("webview", Context.MODE_PRIVATE).listFiles()?.forEach { it.deleteRecursively() }
        } catch (e: Exception) {
            // Best effort: a failed wipe must never stop the app from starting.
        }
    }

    /** Wipes everything for the live browser, including the HTTP cache and history of [webView]. */
    fun wipeLive(webView: WebView?) {
        try {
            webView?.apply {
                clearHistory()
                clearCache(true)
                @Suppress("DEPRECATION")
                clearFormData()
            }
        } catch (e: Exception) {
            // The WebView may already be destroyed.
        }
        wipeSiteData()
    }

    /** Cookies and DOM storage (localStorage, IndexedDB...) of every site. */
    fun wipeSiteData() {
        try {
            val cookies = CookieManager.getInstance()
            // removeAllCookies is asynchronous: flush only once it has actually finished.
            cookies.removeAllCookies { cookies.flush() }
            WebStorage.getInstance().deleteAllData()
        } catch (e: Exception) {
            // WebView provider unavailable
        }
    }
}
