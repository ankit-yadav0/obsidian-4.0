package com.example.weblite.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class NetworkObserver(context: Context) {

    private val connectivityManager =
        context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val _isOnline = MutableStateFlow(isOnlineNow())
    val isOnline: StateFlow<Boolean> = _isOnline.asStateFlow()

    // Follows only the app's default network, so a second network (for example mobile data) that
    // comes and goes while Wi-Fi is up cannot flip the state.
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            _isOnline.value = isOnlineNow()
        }

        override fun onLost(network: Network) {
            _isOnline.value = isOnlineNow()
        }

        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            _isOnline.value = networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }
    }

    private var registered = false

    init {
        try {
            connectivityManager.registerDefaultNetworkCallback(networkCallback)
            registered = true
        } catch (e: Exception) {
            _isOnline.value = isOnlineNow()
        }
    }

    fun isOnlineNow(): Boolean {
        return try {
            val activeNetwork = connectivityManager.activeNetwork ?: return false
            val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return false
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (e: Exception) {
            true // Default to true if unable to check
        }
    }

    fun unregister() {
        if (!registered) return
        registered = false
        try {
            connectivityManager.unregisterNetworkCallback(networkCallback)
        } catch (e: Exception) {
            // Ignore if already unregistered
        }
    }
}
