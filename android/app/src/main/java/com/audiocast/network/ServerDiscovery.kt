package com.audiocast.network

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log

private const val TAG = "ServerDiscovery"
private const val SERVICE_TYPE = "_audiocast._tcp."

/**
 * mDNS service registration for advertising the Android AudioCast server.
 * This is the inverse of Discovery.kt — it advertises rather than discovers.
 */
class ServerDiscovery(private val context: Context) {

    private val nsdManager: NsdManager by lazy {
        context.getSystemService(Context.NSD_SERVICE) as NsdManager
    }

    private var registrationListener: NsdManager.RegistrationListener? = null
    var isRegistered: Boolean = false
        private set

    /**
     * Register the AudioCast server on the local network via mDNS.
     */
    fun register(
        serverName: String,
        port: Int,
        codec: String = "pcm",
        sampleRate: Int = 48000,
        channels: Int = 2
    ) {
        if (isRegistered) {
            Log.w(TAG, "Already registered")
            return
        }

        val serviceInfo = NsdServiceInfo().apply {
            serviceName = serverName
            serviceType = SERVICE_TYPE
            setPort(port)
            setAttribute("codec", codec)
            setAttribute("sample_rate", sampleRate.toString())
            setAttribute("channels", channels.toString())
            setAttribute("platform", "android")
            setAttribute("version", "0.1.0")
        }

        registrationListener = object : NsdManager.RegistrationListener {
            override fun onRegistrationFailed(si: NsdServiceInfo?, errorCode: Int) {
                Log.e(TAG, "Registration failed: $errorCode")
                isRegistered = false
            }

            override fun onUnregistrationFailed(si: NsdServiceInfo?, errorCode: Int) {
                Log.e(TAG, "Unregistration failed: $errorCode")
            }

            override fun onServiceRegistered(si: NsdServiceInfo?) {
                Log.i(TAG, "Service registered: ${si?.serviceName} on port $port")
                isRegistered = true
            }

            override fun onServiceUnregistered(si: NsdServiceInfo?) {
                Log.i(TAG, "Service unregistered")
                isRegistered = false
            }
        }

        try {
            nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener)
            Log.i(TAG, "Registering mDNS service: $serverName on port $port")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register service: ${e.message}")
        }
    }

    /**
     * Unregister the service from the network.
     */
    fun unregister() {
        if (!isRegistered && registrationListener == null) return

        try {
            registrationListener?.let { nsdManager.unregisterService(it) }
        } catch (e: Exception) {
            Log.w(TAG, "Error unregistering: ${e.message}")
        }
        registrationListener = null
        isRegistered = false
    }
}
