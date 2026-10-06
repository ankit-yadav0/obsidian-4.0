package com.example.weblite.vpn

import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Tells the app whether the connection it is using goes through a VPN, and whether Proton VPN is
 * installed. It is event-driven (a network callback), so nothing polls in the background.
 *
 * Limitation, disclosed rather than hidden: Android does not say which app owns the active VPN
 * tunnel. "Proton VPN installed" + "some VPN is the active network" is therefore a best-effort
 * heuristic; if a different VPN app is connected it still reports "active".
 */
class VpnStatusMonitor(context: Context) {

    companion object {
        const val PROTON_VPN_PACKAGE = "ch.protonvpn.android"
    }

    private val appContext = context.applicationContext
    private val connectivityManager =
        appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val _isVpnActive = MutableStateFlow(readVpnState())

    /** True while the app's default network is a VPN. */
    val isVpnActive: StateFlow<Boolean> = _isVpnActive.asStateFlow()

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            _isVpnActive.value = networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        }

        override fun onLost(network: Network) {
            _isVpnActive.value = readVpnState()
        }
    }

    private var registered = false

    init {
        try {
            connectivityManager.registerDefaultNetworkCallback(callback)
            registered = true
        } catch (e: Exception) {
            // Without callbacks refresh() still works, it just has to be called by the host.
        }
    }

    fun isProtonVpnInstalled(): Boolean {
        return try {
            appContext.packageManager.getPackageInfo(PROTON_VPN_PACKAGE, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

    /** Re-reads the current state (cheap); used when the app returns to the foreground. */
    fun refresh() {
        _isVpnActive.value = readVpnState()
    }

    fun unregister() {
        if (!registered) return
        registered = false
        try {
            connectivityManager.unregisterNetworkCallback(callback)
        } catch (e: Exception) {
            // Already unregistered
        }
    }

    private fun readVpnState(): Boolean {
        return try {
            val network = connectivityManager.activeNetwork ?: return false
            val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        } catch (e: Exception) {
            false
        }
    }
}
