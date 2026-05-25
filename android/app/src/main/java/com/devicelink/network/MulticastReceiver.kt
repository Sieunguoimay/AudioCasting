package com.devicelink.network

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.MulticastSocket

private const val TAG = "MulticastReceiver"
private const val MAX_PACKET_SIZE = 4096

/**
 * Receives audio frames via UDP multicast.
 * More efficient than TCP for multi-device scenarios:
 * server sends one stream, all clients receive it.
 */
class MulticastReceiver(private val context: Context) {
    private var socket: MulticastSocket? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var receiveJob: Job? = null

    private val _audioFrames = MutableSharedFlow<AudioFrame>(extraBufferCapacity = 64)
    val audioFrames: SharedFlow<AudioFrame> = _audioFrames

    var isRunning: Boolean = false
        private set

    /**
     * Start receiving multicast audio on the given group and port.
     */
    fun start(groupAddress: String = "239.255.77.77", port: Int = 4955) {
        if (isRunning) return

        // Acquire multicast lock (Android disables multicast by default to save battery)
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        multicastLock = wifiManager.createMulticastLock("DeviceLink:MulticastLock").apply {
            setReferenceCounted(false)
            acquire()
        }

        try {
            val sock = MulticastSocket(port)
            val group = InetAddress.getByName(groupAddress)
            sock.joinGroup(group)
            sock.soTimeout = 5000 // 5s timeout for health checks
            socket = sock

            isRunning = true
            Log.i(TAG, "Joined multicast group $groupAddress:$port")

            // Start receive loop
            receiveJob = CoroutineScope(Dispatchers.IO).launch {
                val buffer = ByteArray(MAX_PACKET_SIZE)
                val packet = DatagramPacket(buffer, buffer.size)

                while (isActive && isRunning) {
                    try {
                        sock.receive(packet)

                        if (packet.length > 0 && buffer[0] == Protocol.FRAME_TYPE_AUDIO) {
                            val frameData = buffer.copyOf(packet.length)
                            val frame = AudioFrame.deserialize(frameData)
                            _audioFrames.emit(frame)
                        }
                    } catch (e: java.net.SocketTimeoutException) {
                        // Normal timeout, continue
                    } catch (e: Exception) {
                        if (isRunning) {
                            Log.e(TAG, "Receive error: ${e.message}")
                        }
                        break
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start multicast receiver: ${e.message}")
            stop()
        }
    }

    fun stop() {
        isRunning = false
        receiveJob?.cancel()
        receiveJob = null

        try {
            socket?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing socket: ${e.message}")
        }
        socket = null

        multicastLock?.let {
            if (it.isHeld) it.release()
        }
        multicastLock = null

        Log.i(TAG, "Multicast receiver stopped")
    }
}
