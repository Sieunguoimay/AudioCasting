package com.devicelink.network

import android.util.Log
import com.devicelink.audio.AudioCaptureSource
import com.devicelink.audio.AudioEncoder
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

private const val TAG = "AudioServer"

/**
 * TCP audio streaming server for Android sender mode.
 * Captures audio from a source, encodes it, and streams to connected clients.
 */
class AudioServer(
    private val serverName: String = "Android DeviceLink",
    private val port: Int = 4953,
    private val sampleRate: Int = 48000,
    private val channelCount: Int = 2,
    private val pin: String = ""
) {
    data class ConnectedClient(
        val clientId: String,
        val clientName: String,
        val socket: Socket,
        val output: DataOutputStream,
        val connectedAt: Long
    )

    private var serverSocket: ServerSocket? = null
    private var acceptJob: Job? = null
    private var scope: CoroutineScope? = null
    private val encoder = AudioEncoder(sampleRate, channelCount)

    private val clients = ConcurrentHashMap<String, ConnectedClient>()

    private val _clientCount = MutableStateFlow(0)
    val clientCount: StateFlow<Int> = _clientCount

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning

    private val _clientList = MutableStateFlow<List<String>>(emptyList())
    val clientList: StateFlow<List<String>> = _clientList

    /**
     * Start the server and begin accepting clients.
     * Audio capture should be started separately and fed via [onAudioChunk].
     */
    fun start() {
        if (_isRunning.value) return

        try {
            serverSocket = ServerSocket(port)
            serverSocket?.reuseAddress = true
            _isRunning.value = true

            scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

            // Accept loop
            acceptJob = scope?.launch {
                Log.i(TAG, "Server listening on port $port")
                while (isActive && _isRunning.value) {
                    try {
                        val socket = serverSocket?.accept() ?: break
                        socket.tcpNoDelay = true
                        socket.keepAlive = true
                        Log.i(TAG, "New connection from ${socket.remoteSocketAddress}")
                        launch { handleClient(socket) }
                    } catch (e: Exception) {
                        if (_isRunning.value) {
                            Log.e(TAG, "Accept error: ${e.message}")
                        }
                    }
                }
            }

            Log.i(TAG, "Server started: $serverName on port $port (${sampleRate}Hz, ${channelCount}ch)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start server: ${e.message}")
            _isRunning.value = false
        }
    }

    /**
     * Handle a single client connection: handshake then stream.
     */
    private suspend fun handleClient(socket: Socket) {
        var clientId = ""
        try {
            val input = DataInputStream(socket.getInputStream())
            val output = DataOutputStream(socket.getOutputStream())

            // Read ClientJoin
            val frameData = readFrame(input)
            if (frameData[0] != Protocol.FRAME_TYPE_CONTROL) {
                socket.close()
                return
            }

            val msg = ControlMessage.deserialize(frameData)
            if (msg !is ControlMessage.ClientJoin) {
                val err = ControlMessage.Error("Expected ClientJoin")
                output.write(err.serialize())
                output.flush()
                socket.close()
                return
            }

            // Validate PIN
            if (pin.isNotEmpty() && msg.pin != pin) {
                val auth = ControlMessage.AuthRequired("Invalid PIN")
                output.write(auth.serialize())
                output.flush()
                socket.close()
                Log.w(TAG, "Client ${msg.clientName} failed PIN auth")
                return
            }

            clientId = msg.clientId
            val sessionId = UUID.randomUUID().toString()

            // Send ClientAccepted
            val accepted = ControlMessage.ClientAccepted(
                sessionId = sessionId,
                codec = "pcm",
                sampleRate = sampleRate,
                channels = channelCount
            )
            output.write(accepted.serialize())
            output.flush()

            // Register client
            val client = ConnectedClient(
                clientId = clientId,
                clientName = msg.clientName,
                socket = socket,
                output = output,
                connectedAt = System.currentTimeMillis()
            )
            clients[clientId] = client
            updateClientState()
            Log.i(TAG, "Client accepted: ${msg.clientName} ($clientId), session=$sessionId")

            // Read control messages from client (keeps connection alive)
            withContext(Dispatchers.IO) {
                try {
                    while (isActive && _isRunning.value && !socket.isClosed) {
                        val data = readFrame(input)
                        when (data[0]) {
                            Protocol.FRAME_TYPE_CONTROL -> {
                                val ctrlMsg = ControlMessage.deserialize(data)
                                handleControlMessage(clientId, ctrlMsg)
                            }
                            Protocol.FRAME_TYPE_CLOCK_SYNC -> {
                                // Echo clock sync back
                                output.write(data)
                                output.flush()
                            }
                        }
                    }
                } catch (e: Exception) {
                    if (_isRunning.value) {
                        Log.d(TAG, "Client $clientId read ended: ${e.message}")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Client handler error: ${e.message}")
        } finally {
            if (clientId.isNotEmpty()) {
                clients.remove(clientId)
                updateClientState()
                Log.i(TAG, "Client disconnected: $clientId")
            }
            try { socket.close() } catch (_: Exception) {}
        }
    }

    private fun handleControlMessage(clientId: String, msg: ControlMessage) {
        when (msg) {
            is ControlMessage.SetVolume -> {
                Log.d(TAG, "Client $clientId volume: ${msg.volume}")
            }
            is ControlMessage.ClientLeave -> {
                Log.i(TAG, "Client $clientId requested leave")
                clients[clientId]?.socket?.close()
            }
            else -> Log.d(TAG, "Control from $clientId: $msg")
        }
    }

    /**
     * Called by the capture source to broadcast audio to all clients.
     */
    fun onAudioChunk(samples: ShortArray, timestampUs: Long) {
        if (clients.isEmpty()) return

        val frame = encoder.encode(samples, timestampUs)
        val data = frame.serialize()

        // Broadcast to all connected clients
        val disconnected = mutableListOf<String>()
        for ((id, client) in clients) {
            try {
                client.output.write(data)
                client.output.flush()
            } catch (e: Exception) {
                Log.d(TAG, "Write failed for $id: ${e.message}")
                disconnected.add(id)
            }
        }

        // Remove disconnected clients
        for (id in disconnected) {
            clients.remove(id)?.socket?.let {
                try { it.close() } catch (_: Exception) {}
            }
        }
        if (disconnected.isNotEmpty()) {
            updateClientState()
        }
    }

    private fun updateClientState() {
        _clientCount.value = clients.size
        _clientList.value = clients.values.map { "${it.clientName} (${it.socket.remoteSocketAddress})" }
    }

    fun stop() {
        _isRunning.value = false
        acceptJob?.cancel()

        // Disconnect all clients
        for ((_, client) in clients) {
            try { client.socket.close() } catch (_: Exception) {}
        }
        clients.clear()
        updateClientState()

        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null

        scope?.cancel()
        scope = null
        encoder.reset()
        Log.i(TAG, "Server stopped")
    }
}
