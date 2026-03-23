package com.audiocast.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

private const val TAG = "NetworkMonitor"

/**
 * Monitors network connectivity changes for auto-reconnect functionality.
 * Detects when Wi-Fi is lost/restored so the app can automatically
 * reconnect to the AudioCast server.
 */
class NetworkMonitor(private val context: Context) {

    sealed class NetworkState {
        object Available : NetworkState()
        object Lost : NetworkState()
        object Unavailable : NetworkState()
    }

    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val _networkState = MutableStateFlow<NetworkState>(NetworkState.Unavailable)
    val networkState: StateFlow<NetworkState> = _networkState

    private val _wifiAvailable = MutableStateFlow(false)
    val wifiAvailable: StateFlow<Boolean> = _wifiAvailable

    private var callback: ConnectivityManager.NetworkCallback? = null

    /**
     * Start monitoring network changes.
     */
    fun start() {
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()

        callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Log.i(TAG, "Wi-Fi network available")
                _networkState.value = NetworkState.Available
                _wifiAvailable.value = true
            }

            override fun onLost(network: Network) {
                Log.w(TAG, "Wi-Fi network lost")
                _networkState.value = NetworkState.Lost
                _wifiAvailable.value = false
            }

            override fun onUnavailable() {
                Log.w(TAG, "Wi-Fi network unavailable")
                _networkState.value = NetworkState.Unavailable
                _wifiAvailable.value = false
            }

            override fun onCapabilitiesChanged(
                network: Network,
                capabilities: NetworkCapabilities
            ) {
                val hasWifi = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                if (hasWifi && _networkState.value != NetworkState.Available) {
                    Log.i(TAG, "Wi-Fi capabilities restored")
                    _networkState.value = NetworkState.Available
                    _wifiAvailable.value = true
                }
            }
        }

        try {
            connectivityManager.registerNetworkCallback(request, callback!!)
            Log.i(TAG, "Network monitoring started")

            // Check current state
            val activeNetwork = connectivityManager.activeNetwork
            val caps = activeNetwork?.let { connectivityManager.getNetworkCapabilities(it) }
            if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) {
                _networkState.value = NetworkState.Available
                _wifiAvailable.value = true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register network callback: ${e.message}")
        }
    }

    /**
     * Stop monitoring network changes.
     */
    fun stop() {
        callback?.let {
            try {
                connectivityManager.unregisterNetworkCallback(it)
            } catch (e: Exception) {
                Log.w(TAG, "Error unregistering callback: ${e.message}")
            }
        }
        callback = null
        Log.i(TAG, "Network monitoring stopped")
    }
}
