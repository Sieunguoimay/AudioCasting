package com.audiocast.network

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log

private const val TAG = "NetworkChangeReceiver"

class NetworkChangeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ConnectivityManager.CONNECTIVITY_ACTION) return

        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork
        val capabilities = network?.let { connectivityManager.getNetworkCapabilities(it) }

        val isWifi = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true

        if (isWifi) {
            Log.i(TAG, "Wi-Fi connected")
        } else {
            Log.i(TAG, "Wi-Fi disconnected")
        }
    }
}
