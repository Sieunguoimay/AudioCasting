package com.audiocast.network

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID

private const val TAG = "AudioClient"
private const val CONNECT_TIMEOUT_MS = 5000
private const val SOCKET_TIMEOUT_MS = 30000 // 30s fallback timeout
private const val HEARTBEAT_INTERVAL_MS = 5000L // Send ping every 5 seconds
private const val HEARTBEAT_TIMEOUT_MS = 30000L // Allow 30 seconds for pong response
private const val MAX_MISSED_PONGS = 2 // Disconnect after 2 consecutive missed pongs

enum class ConnectionState {
    DISCONNECTED, CONNECTING, CONNECTED, ERROR, AUTH_REQUIRED
}

data class ConnectionInfo(
    val sessionId: String = "",
    val codec: String = "",
    val sampleRate: Int = 48000,
    val channels: Int = 2
)

/**
 * TCP client that connects to the AudioCast server,
 * performs the handshake, and streams audio frames.
 */
class AudioClient {
    private var socket: Socket? = null
    private var output: DataOutputStream? = null
    private var input: DataInputStream? = null
    private var readJob: Job? = null
    private var heartbeatJob: Job? = null
    private var lastPongTime = MutableStateFlow(0L)
    private var missedPongCount = 0

    private val _state = MutableStateFlow(ConnectionState.DISCONNECTED)
    val state: StateFlow<ConnectionState> = _state

    private val _audioFrames = MutableSharedFlow<AudioFrame>(extraBufferCapacity = 64)
    val audioFrames: SharedFlow<AudioFrame> = _audioFrames

    private val _connectionInfo = MutableStateFlow(ConnectionInfo())
    val connectionInfo: StateFlow<ConnectionInfo> = _connectionInfo

    val clockSync = ClockSync()

    val clientId: String = UUID.randomUUID().toString()
    var clientName: String = "Android Device"

    // Store last connection params for auto-reconnect
    var lastHost: String = ""
        private set
    var lastPort: Int = 0
        private set
    var lastPin: String? = null
        private set

    /**
     * Connect to the server and start receiving audio.
     */
    suspend fun connect(host: String, port: Int, name: String = "Android Device", pin: String? = null) {
        if (_state.value == ConnectionState.CONNECTED || _state.value == ConnectionState.CONNECTING) {
            Log.w(TAG, "Already connected/connecting")
            return
        }

        clientName = name
        lastHost = host
        lastPort = port
        lastPin = pin
        _state.value = ConnectionState.CONNECTING

        withContext(Dispatchers.IO) {
            try {
                // TCP connect
                val sock = Socket()
                sock.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
                sock.tcpNoDelay = true
                sock.keepAlive = true
                sock.soTimeout = SOCKET_TIMEOUT_MS

                socket = sock
                output = DataOutputStream(sock.getOutputStream())
                input = DataInputStream(sock.getInputStream())

                Log.i(TAG, "Connected to $host:$port")

                // Send ClientJoin with optional PIN
                val joinMsg = ControlMessage.ClientJoin(
                    clientName = clientName,
                    clientId = clientId,
                    pin = pin
                )
                output!!.write(joinMsg.serialize())
                output!!.flush()

                // Wait for ClientAccepted or AuthRequired
                val responseData = readFrame(input!!)
                when (responseData[0]) {
                    Protocol.FRAME_TYPE_CONTROL -> {
                        when (val msg = ControlMessage.deserialize(responseData)) {
                            is ControlMessage.ClientAccepted -> {
                                _connectionInfo.value = ConnectionInfo(
                                    sessionId = msg.sessionId,
                                    codec = msg.codec,
                                    sampleRate = msg.sampleRate,
                                    channels = msg.channels
                                )
                                _state.value = ConnectionState.CONNECTED
                                Log.i(TAG, "Session established: ${msg.sessionId}, codec=${msg.codec}")
                            }
                            is ControlMessage.AuthRequired -> {
                                Log.w(TAG, "Authentication required: ${msg.message}")
                                _state.value = ConnectionState.AUTH_REQUIRED
                                disconnect()
                                return@withContext
                            }
                            is ControlMessage.Error -> {
                                Log.e(TAG, "Server rejected: ${msg.message}")
                                _state.value = ConnectionState.ERROR
                                disconnect()
                                return@withContext
                            }
                            else -> {
                                Log.e(TAG, "Unexpected response: $msg")
                                _state.value = ConnectionState.ERROR
                                disconnect()
                                return@withContext
                            }
                        }
                    }
                    else -> {
                        Log.e(TAG, "Unexpected frame type: ${responseData[0]}")
                        _state.value = ConnectionState.ERROR
                        disconnect()
                        return@withContext
                    }
                }

                // Start reading audio frames
                startReading()
                // Start heartbeat
                startHeartbeat()

            } catch (e: Exception) {
                Log.e(TAG, "Connection failed: ${e.message}")
                _state.value = ConnectionState.ERROR
                disconnect()
            }
        }
    }

    /**
     * Start the frame reading loop in a coroutine.
     */
    private fun startReading() {
        readJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                val inp = input ?: return@launch
                while (isActive && _state.value == ConnectionState.CONNECTED) {
                    val frameData = readFrame(inp)

                    // Check if still connected after reading
                    if (_state.value != ConnectionState.CONNECTED) break

                    when (frameData[0]) {
                        Protocol.FRAME_TYPE_AUDIO -> {
                            val frame = AudioFrame.deserialize(frameData)
                            _audioFrames.emit(frame)
                        }
                        Protocol.FRAME_TYPE_CONTROL -> {
                            val msg = ControlMessage.deserialize(frameData)
                            handleControlMessage(msg)
                        }
                        Protocol.FRAME_TYPE_CLOCK_SYNC -> {
                            val syncMsg = ClockSyncMessage.deserialize(frameData)
                            handleClockSync(syncMsg)
                        }
                    }
                }
            } catch (e: Exception) {
                val currentState = _state.value
                Log.e(TAG, "Read error (state=$currentState): ${e.message}")
                if (currentState == ConnectionState.CONNECTED) {
                    _state.value = ConnectionState.ERROR
                }
            }
        }
    }

    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        lastPongTime.value = System.currentTimeMillis()
        missedPongCount = 0
        heartbeatJob = CoroutineScope(Dispatchers.IO).launch {
            while (isActive && _state.value == ConnectionState.CONNECTED) {
                delay(HEARTBEAT_INTERVAL_MS)

                // Check if still connected before sending ping
                if (_state.value != ConnectionState.CONNECTED) break

                val timeSincePong = System.currentTimeMillis() - lastPongTime.value
                if (timeSincePong > HEARTBEAT_TIMEOUT_MS) {
                    missedPongCount++
                    Log.w(TAG, "Heartbeat timeout ($missedPongCount/$MAX_MISSED_PONGS), no pong for ${timeSincePong}ms")
                    if (missedPongCount >= MAX_MISSED_PONGS) {
                        Log.e(TAG, "Too many missed pongs, disconnecting")
                        _state.value = ConnectionState.ERROR
                        disconnect()
                        return@launch
                    }
                } else {
                    missedPongCount = 0
                }

                try {
                    val ping = ControlMessage.Ping(timestampUs = nowUs())
                    output?.write(ping.serialize())
                    output?.flush()
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to send ping: ${e.message}")
                    _state.value = ConnectionState.ERROR
                    disconnect()
                    return@launch
                }
            }
        }
    }

    private fun handleControlMessage(msg: ControlMessage) {
        when (msg) {
            is ControlMessage.Ping -> {
                // Respond with Pong
                val pong = ControlMessage.Pong(
                    pingTimestampUs = msg.timestampUs,
                    pongTimestampUs = nowUs()
                )
                try {
                    output?.write(pong.serialize())
                    output?.flush()
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to send pong: ${e.message}")
                }
            }
            is ControlMessage.Pong -> {
                lastPongTime.value = System.currentTimeMillis()
                missedPongCount = 0
            }
            is ControlMessage.Error -> {
                Log.e(TAG, "Server error: ${msg.message}")
            }
            else -> {
                Log.d(TAG, "Unhandled control message: $msg")
            }
        }
    }

    private fun handleClockSync(msg: ClockSyncMessage) {
        // Server sent us a sync request with t1 filled in
        val response = clockSync.processServerSync(msg.t1)
        try {
            output?.write(response.serialize())
            output?.flush()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send clock sync response: ${e.message}")
        }
    }

    /**
     * Send a volume control message.
     */
    fun setVolume(volume: Float) {
        try {
            val msg = ControlMessage.SetVolume(volume.coerceIn(0f, 1f))
            output?.write(msg.serialize())
            output?.flush()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send volume: ${e.message}")
        }
    }

    /**
     * Join a volume group.
     */
    fun setVolumeGroup(groupName: String) {
        try {
            val msg = ControlMessage.SetVolumeGroup(groupName)
            output?.write(msg.serialize())
            output?.flush()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send volume group: ${e.message}")
        }
    }

    /**
     * Check if we have valid reconnection parameters.
     */
    fun canReconnect(): Boolean {
        return lastHost.isNotEmpty() && lastPort > 0
    }

    /**
     * Disconnect from the server.
     */
    fun disconnect() {
        try {
            readJob?.cancel()
            readJob = null
            heartbeatJob?.cancel()
            heartbeatJob = null

            // Send leave message
            if (_state.value == ConnectionState.CONNECTED) {
                try {
                    val leave = ControlMessage.ClientLeave(clientId)
                    output?.write(leave.serialize())
                    output?.flush()
                } catch (_: Exception) {}
            }

            output?.close()
            input?.close()
            socket?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error disconnecting: ${e.message}")
        } finally {
            socket = null
            output = null
            input = null
            _state.value = ConnectionState.DISCONNECTED
            clockSync.reset()
            Log.i(TAG, "Disconnected")
        }
    }
}
