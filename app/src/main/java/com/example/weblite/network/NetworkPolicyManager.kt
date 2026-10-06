package com.example.weblite.network

import android.content.Context
import android.content.pm.PackageManager
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executor

/** How the WebView's own network traffic is routed. */
enum class ProxyMode {
    /** Normal connection (through the system VPN if one is connected). */
    DIRECT,

    /** Through Orbot's local SOCKS proxy. */
    TOR,

    /** Every WebView request fails. Used while the VPN is missing, as a real kill-switch. */
    BLOCKED
}

/**
 * Single owner of the WebView proxy override, so Tor routing and the VPN kill-switch can never
 * overwrite each other: the app computes one [ProxyMode] from both settings and applies it here.
 *
 * Note: this only covers the WebView. Android's DownloadManager is a system service and ignores
 * the override, which is why the app refuses downloads while Tor is on or the VPN is missing.
 */
class NetworkPolicyManager(private val context: Context) {

    companion object {
        const val ORBOT_PACKAGE = "org.torproject.android"
        private const val ORBOT_SOCKS_HOST = "127.0.0.1"
        private const val ORBOT_SOCKS_PORT = 9050

        // Nothing listens on port 1, so every request routed here fails instead of leaking out directly.
        private const val BLACKHOLE_PROXY = "http://127.0.0.1:1"
    }

    fun isOrbotInstalled(): Boolean = try {
        context.packageManager.getPackageInfo(ORBOT_PACKAGE, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    fun isProxyOverrideSupported(): Boolean =
        WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)

    /** True if something accepts connections on Orbot's SOCKS port. Blocking: call off the main thread. */
    fun isOrbotListening(timeoutMs: Int = 1500): Boolean = try {
        Socket().use { it.connect(InetSocketAddress(ORBOT_SOCKS_HOST, ORBOT_SOCKS_PORT), timeoutMs) }
        true
    } catch (e: Exception) {
        false
    }

    /** Applies [mode]; [onComplete] runs once the WebView has switched (immediately if unsupported). */
    fun apply(mode: ProxyMode, onComplete: () -> Unit = {}) {
        if (!isProxyOverrideSupported()) {
            onComplete()
            return
        }
        val executor = Executor { it.run() }
        val controller = ProxyController.getInstance()
        when (mode) {
            ProxyMode.DIRECT -> controller.clearProxyOverride(executor, onComplete)
            ProxyMode.TOR -> controller.setProxyOverride(
                ProxyConfig.Builder().addProxyRule("socks5://$ORBOT_SOCKS_HOST:$ORBOT_SOCKS_PORT").build(),
                executor,
                onComplete
            )
            ProxyMode.BLOCKED -> controller.setProxyOverride(
                ProxyConfig.Builder().addProxyRule(BLACKHOLE_PROXY).build(),
                executor,
                onComplete
            )
        }
    }
}
