package com.example.weblite.vpn

import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/**
 * Checks whether Proton VPN is installed and whether a VPN connection is
 * currently active, so the app can show a "connect your VPN" warning.
 *
 * Important limitation, disclosed here rather than hidden: Android does
 * not expose an API to identify which specific app owns the active VPN
 * tunnel. isProtonVpnActive() therefore combines two separate, weaker
 * signals — "Proton VPN is installed" and "some VPN connection is
 * currently active" — as a best-effort heuristic. If a different VPN app
 * is connected instead, this will still report "active". There's no way
 * to do better than that from a regular (non-VpnService) app on Android.
 */
class VpnStatusMonitor(private val context: Context) {

    companion object {
        const val PROTON_VPN_PACKAGE = "ch.protonvpn.android"
    }

    fun isProtonVpnInstalled(): Boolean {
        return try {
            context.packageManager.getPackageInfo(PROTON_VPN_PACKAGE, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

    fun isAnyVpnActive(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val network = cm.activeNetwork ?: return false
        val capabilities = cm.getNetworkCapabilities(network) ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
    }

    /** Best-effort: see class doc for the exact limitation. */
    fun isProtonVpnActive(): Boolean = isProtonVpnInstalled() && isAnyVpnActive()
}
