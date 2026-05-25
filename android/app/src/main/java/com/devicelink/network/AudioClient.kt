package com.devicelink.network

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
 * TCP client that connects to the DeviceLink server,
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

    // Text messaging
    private val _textMessages = MutableSharedFlow<ControlMessage.TextMessage>(extraBufferCapacity = 64)
    val textMessages: SharedFlow<ControlMessage.TextMessage> = _textMessages

    // File transfer
    private val _fileOffers = MutableSharedFlow<ControlMessage.FileOffer>(extraBufferCapacity = 16)
    val fileOffers: SharedFlow<ControlMessage.FileOffer> = _fileOffers

    private val _fileProgress = MutableStateFlow<Map<String, Float>>(emptyMap())
    val fileProgress: StateFlow<Map<String, Float>> = _fileProgress

    private val _fileCompleted = MutableSharedFlow<ControlMessage.FileComplete>(extraBufferCapacity = 16)
    val fileCompleted: SharedFlow<ControlMessage.FileComplete> = _fileCompleted

    private val _fileChunks = MutableSharedFlow<Pair<String, FileDataFrame>>(extraBufferCapacity = 128)
    val fileChunks: SharedFlow<Pair<String, FileDataFrame>> = _fileChunks

    // Server-pushed volume changes
    private val _volumeChanged = MutableSharedFlow<Float>(extraBufferCapacity = 8)
    val volumeChanged: SharedFlow<Float> = _volumeChanged

    // Write lock for thread-safe output writes
    private val writeLock = Any()

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
                        Protocol.FRAME_TYPE_FILE_DATA -> {
                            val frame = FileDataFrame.deserialize(frameData)
                            val tid = FileDataFrame.bytesToUuid(frame.transferId)
                            _fileChunks.tryEmit(Pair(tid, frame))
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
                    sendBytes(ping.serialize())
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
                val pong = ControlMessage.Pong(
                    pingTimestampUs = msg.timestampUs,
                    pongTimestampUs = nowUs()
                )
                sendBytes(pong.serialize())
            }
            is ControlMessage.Pong -> {
                lastPongTime.value = System.currentTimeMillis()
                missedPongCount = 0
            }
            is ControlMessage.SetVolume -> {
                Log.i(TAG, "Server set volume to ${msg.volume}")
                _volumeChanged.tryEmit(msg.volume)
            }
            is ControlMessage.Error -> {
                Log.e(TAG, "Server error: ${msg.message}")
            }
            is ControlMessage.TextMessage -> {
                Log.i(TAG, "Text message from ${msg.fromName}: ${msg.content.take(50)}")
                _textMessages.tryEmit(msg)
            }
            is ControlMessage.FileOffer -> {
                Log.i(TAG, "File offer from ${msg.fromName}: ${msg.fileName}")
                _fileOffers.tryEmit(msg)
            }
            is ControlMessage.FileAccept -> {
                Log.i(TAG, "File accepted: ${msg.transferId}")
            }
            is ControlMessage.FileReject -> {
                Log.i(TAG, "File rejected: ${msg.transferId} - ${msg.reason}")
            }
            is ControlMessage.FileComplete -> {
                Log.i(TAG, "File complete: ${msg.transferId}")
                _fileCompleted.tryEmit(msg)
            }
            is ControlMessage.FileError -> {
                Log.e(TAG, "File error: ${msg.transferId} - ${msg.message}")
            }
            else -> {
                Log.d(TAG, "Unhandled control message: $msg")
            }
        }
    }

    private fun handleClockSync(msg: ClockSyncMessage) {
        val response = clockSync.processServerSync(msg.t1)
        sendBytes(response.serialize())
    }

    /** Thread-safe write to output stream */
    fun sendBytes(data: ByteArray) {
        synchronized(writeLock) {
            try {
                output?.write(data)
                output?.flush()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send data: ${e.message}")
            }
        }
    }

    /**
     * Send a volume control message.
     */
    fun setVolume(volume: Float) {
        val msg = ControlMessage.SetVolume(volume.coerceIn(0f, 1f))
        sendBytes(msg.serialize())
    }

    /**
     * Join a volume group.
     */
    fun setVolumeGroup(groupName: String) {
        val msg = ControlMessage.SetVolumeGroup(groupName)
        sendBytes(msg.serialize())
    }

    /**
     * Send a text message to the server (which relays to all other clients).
     */
    fun sendTextMessage(content: String) {
        val msg = ControlMessage.TextMessage(
            messageId = UUID.randomUUID().toString(),
            fromId = clientId,
            fromName = clientName,
            content = content,
            timestampUs = nowUs()
        )
        sendBytes(msg.serialize())
    }

    /**
     * Send a file offer to the server.
     */
    fun sendFileOffer(
        transferId: String,
        fileName: String,
        fileSize: Long,
        mimeType: String,
        chunkSize: Int,
        totalChunks: Int
    ) {
        val msg = ControlMessage.FileOffer(
            transferId = transferId,
            fromId = clientId,
            fromName = clientName,
            fileName = fileName,
            fileSize = fileSize,
            mimeType = mimeType,
            chunkSize = chunkSize,
            totalChunks = totalChunks
        )
        sendBytes(msg.serialize())
    }

    /**
     * Send a file data chunk.
     */
    fun sendFileChunk(transferId: String, chunkIndex: Int, data: ByteArray) {
        val frame = FileDataFrame(
            transferId = FileDataFrame.uuidToBytes(transferId),
            chunkIndex = chunkIndex,
            payload = data
        )
        sendBytes(frame.serialize())
    }

    /**
     * Send a file complete signal.
     */
    fun sendFileComplete(transferId: String, checksum: String) {
        val msg = ControlMessage.FileComplete(transferId = transferId, checksum = checksum)
        sendBytes(msg.serialize())
    }

    /**
     * Accept an incoming file offer.
     */
    fun acceptFile(transferId: String) {
        val msg = ControlMessage.FileAccept(transferId = transferId)
        sendBytes(msg.serialize())
    }

    /**
     * Reject an incoming file offer.
     */
    fun rejectFile(transferId: String) {
        val msg = ControlMessage.FileReject(transferId = transferId, reason = "Rejected by user")
        sendBytes(msg.serialize())
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
