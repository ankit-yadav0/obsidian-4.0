package com.example.weblite.tor

import android.content.Context
import android.content.pm.PackageManager
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import java.util.concurrent.Executor

/**
 * Routes WebView traffic through Orbot (the Guardian Project's official,
 * open-source Tor client for Android) instead of implementing Tor's onion
 * routing protocol from scratch. Orbot runs a local SOCKS5 proxy once
 * connected; this class just points the WebView's network requests at it
 * using Android's supported WebView proxy-override API.
 *
 * This app does NOT bundle or reimplement any part of Tor — it requires
 * Orbot to be installed and running, exactly like our VPN advice: use an
 * already-audited, purpose-built tool rather than a from-scratch attempt.
 */
class TorManager(private val context: Context) {

    companion object {
        const val ORBOT_PACKAGE = "org.torproject.android"
        // Orbot's default local SOCKS5 proxy address/port.
        private const val ORBOT_SOCKS_HOST = "127.0.0.1"
        private const val ORBOT_SOCKS_PORT = 9050
    }

    fun isOrbotInstalled(): Boolean {
        return try {
            context.packageManager.getPackageInfo(ORBOT_PACKAGE, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

    fun isProxyOverrideSupported(): Boolean {
        return WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)
    }

    /**
     * Points the WebView's traffic at Orbot's SOCKS5 proxy. Call only after
     * confirming Orbot is installed AND the user has actually connected it
     * (this class has no way to know Orbot's connection state — that's
     * surfaced to the user separately, see MainViewModel's guidance text).
     */
    fun enableTorRouting(onComplete: () -> Unit) {
        if (!isProxyOverrideSupported()) return
        val proxyConfig = ProxyConfig.Builder()
            .addProxyRule("socks5://$ORBOT_SOCKS_HOST:$ORBOT_SOCKS_PORT")
            .build()
        ProxyController.getInstance().setProxyOverride(
            proxyConfig,
            Executor { it.run() },
            onComplete
        )
    }

    fun disableTorRouting(onComplete: () -> Unit) {
        if (!isProxyOverrideSupported()) return
        ProxyController.getInstance().clearProxyOverride(
            Executor { it.run() },
            onComplete
        )
    }
}
