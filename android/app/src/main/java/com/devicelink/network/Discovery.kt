package com.devicelink.network

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

private const val TAG = "Discovery"
private const val SERVICE_TYPE = "_devicelink._tcp."

data class DiscoveredServer(
    val name: String,
    val host: String,
    val port: Int,
    val codec: String = "",
    val sampleRate: Int = 48000,
    val channels: Int = 2
)

/**
 * mDNS/NSD service discovery for finding DeviceLink servers on the LAN.
 */
class Discovery(private val context: Context) {

    private val nsdManager: NsdManager by lazy {
        context.getSystemService(Context.NSD_SERVICE) as NsdManager
    }

    /**
     * Returns a Flow that emits discovered servers.
     * Emits DiscoveryEvent.Found when a server is discovered,
     * and DiscoveryEvent.Lost when a server disappears.
     */
    fun discoverServers(): Flow<DiscoveryEvent> = callbackFlow {
        val listener = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(serviceType: String?, errorCode: Int) {
                Log.e(TAG, "Discovery start failed: $errorCode")
                close()
            }

            override fun onStopDiscoveryFailed(serviceType: String?, errorCode: Int) {
                Log.e(TAG, "Discovery stop failed: $errorCode")
            }

            override fun onDiscoveryStarted(serviceType: String?) {
                Log.i(TAG, "Discovery started for $serviceType")
            }

            override fun onDiscoveryStopped(serviceType: String?) {
                Log.i(TAG, "Discovery stopped")
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo?) {
                serviceInfo ?: return
                Log.i(TAG, "Service found: ${serviceInfo.serviceName}")

                // Resolve to get host and port
                nsdManager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(si: NsdServiceInfo?, errorCode: Int) {
                        Log.e(TAG, "Resolve failed for ${si?.serviceName}: $errorCode")
                    }

                    override fun onServiceResolved(si: NsdServiceInfo?) {
                        si ?: return
                        val host = si.host?.hostAddress ?: return
                        val port = si.port

                        // Extract TXT record attributes
                        val attrs = si.attributes
                        val codec = attrs["codec"]?.let { String(it) } ?: "opus"
                        val sampleRate = attrs["sample_rate"]?.let { String(it).toIntOrNull() } ?: 48000
                        val channels = attrs["channels"]?.let { String(it).toIntOrNull() } ?: 2

                        val server = DiscoveredServer(
                            name = si.serviceName,
                            host = host,
                            port = port,
                            codec = codec,
                            sampleRate = sampleRate,
                            channels = channels
                        )

                        Log.i(TAG, "Resolved: $server")
                        trySend(DiscoveryEvent.Found(server))
                    }
                })
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo?) {
                serviceInfo ?: return
                Log.i(TAG, "Service lost: ${serviceInfo.serviceName}")
                trySend(DiscoveryEvent.Lost(serviceInfo.serviceName))
            }
        }

        nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)

        awaitClose {
            try {
                nsdManager.stopServiceDiscovery(listener)
            } catch (e: Exception) {
                Log.w(TAG, "Error stopping discovery: ${e.message}")
            }
        }
    }
}

sealed class DiscoveryEvent {
    data class Found(val server: DiscoveredServer) : DiscoveryEvent()
    data class Lost(val name: String) : DiscoveryEvent()
}
