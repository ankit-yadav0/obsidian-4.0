package com.example.weblite.webview

import android.util.Base64
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream

/**
 * What a blocked request gets back instead of the real resource. Returning a bare empty text/plain
 * response makes page code that expects the tracker's globals (ga, gtag, fbq...) throw TypeErrors and
 * can break the rest of the page, so scripts get harmless no-op stubs and pixels get a 1x1 GIF.
 */
internal object BlockedResponses {

    private val SCRIPT_STUB = (
        "window.dataLayer=window.dataLayer||[];" +
            "window.ga=window.ga||function(){};" +
            "window.gtag=window.gtag||function(){};" +
            "window.fbq=window.fbq||function(){};"
        ).toByteArray(Charsets.UTF_8)

    private val GIF_1X1: ByteArray by lazy {
        Base64.decode("R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7", Base64.DEFAULT)
    }

    private val IMAGE_EXTENSIONS = listOf(".gif", ".png", ".jpg", ".jpeg", ".webp")

    fun forRequest(path: String): WebResourceResponse {
        val p = path.lowercase()
        return when {
            p.endsWith(".js") ->
                WebResourceResponse("application/javascript", "UTF-8", ByteArrayInputStream(SCRIPT_STUB))
            IMAGE_EXTENSIONS.any { p.endsWith(it) } || p.contains("pixel") ->
                WebResourceResponse("image/gif", null, ByteArrayInputStream(GIF_1X1))
            else ->
                WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0)))
        }
    }
}
