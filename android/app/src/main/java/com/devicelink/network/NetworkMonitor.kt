package com.devicelink.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

private const val TAG = "NetworkMonitor"
private const val DEBOUNCE_MS = 1000L // 1 second debounce for state changes

/**
 * Monitors network connectivity changes for auto-reconnect functionality.
 * Detects when Wi-Fi is lost/restored so the app can automatically
 * reconnect to the DeviceLink server.
 *
 * Uses debouncing to avoid triggering reconnects on transient WiFi fluctuations.
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
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var debounceJob: Job? = null

    private fun setStateDebounced(newState: NetworkState) {
        debounceJob?.cancel()
        debounceJob = scope.launch {
            delay(DEBOUNCE_MS)
            if (_networkState.value != newState) {
                Log.i(TAG, "Network state changed: ${_networkState.value} -> $newState (after debounce)")
                _networkState.value = newState
                _wifiAvailable.value = newState is NetworkState.Available
            }
        }
    }

    /**
     * Start monitoring network changes.
     */
    fun start() {
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()

        callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Log.i(TAG, "Wi-Fi network available (raw)")
                setStateDebounced(NetworkState.Available)
            }

            override fun onLost(network: Network) {
                Log.w(TAG, "Wi-Fi network lost (raw)")
                setStateDebounced(NetworkState.Lost)
            }

            override fun onUnavailable() {
                Log.w(TAG, "Wi-Fi network unavailable (raw)")
                setStateDebounced(NetworkState.Unavailable)
            }

            override fun onCapabilitiesChanged(
                network: Network,
                capabilities: NetworkCapabilities
            ) {
                // Only react to actual WiFi transport changes, not capability updates
                val hasWifi = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                if (hasWifi && _networkState.value !is NetworkState.Available) {
                    Log.i(TAG, "Wi-Fi capabilities restored (raw)")
                    setStateDebounced(NetworkState.Available)
                }
            }
        }

        try {
            connectivityManager.registerNetworkCallback(request, callback!!)
            Log.i(TAG, "Network monitoring started")

            // Check current state (no debounce for initial state)
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
        debounceJob?.cancel()
        scope.cancel()
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
