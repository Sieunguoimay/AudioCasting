package com.audiocast.viewmodel

import android.app.Application
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.audiocast.audio.AudioPlayer
import com.audiocast.audio.JitterBuffer
import com.audiocast.audio.MicCapture
import com.audiocast.audio.AudioCaptureSource
import com.audiocast.data.ProfileManager
import com.audiocast.network.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

private const val TAG = "MainViewModel"
private const val MAX_RECONNECT_ATTEMPTS = 5
private const val RECONNECT_BASE_DELAY_MS = 3000L
private const val RECONNECT_MAX_DELAY_MS = 30000L
private const val RECONNECT_CONNECT_WAIT_MS = 6000L // Wait for TCP handshake + auth

enum class AppMode { RECEIVER, SENDER }
enum class CaptureSource { MICROPHONE, SYSTEM_AUDIO }

data class UiState(
    val appMode: AppMode = AppMode.RECEIVER,
    val connectionState: ConnectionState = ConnectionState.DISCONNECTED,
    val servers: List<DiscoveredServer> = emptyList(),
    val savedServers: List<ProfileManager.ServerProfile> = emptyList(),
    val connectedServer: DiscoveredServer? = null,
    val connectionInfo: ConnectionInfo = ConnectionInfo(),
    val volume: Float = 1.0f,
    val bufferMs: Int = 50,
    val bufferStats: JitterBuffer.BufferStats = JitterBuffer.BufferStats(),
    val errorMessage: String? = null,
    val manualHost: String = "",
    val manualPort: String = "4953",
    val deviceName: String = "Android Device",
    val showPinDialog: Boolean = false,
    val pinInput: String = "",
    val pendingPinServer: DiscoveredServer? = null,
    val autoReconnect: Boolean = true,
    val adaptiveBuffer: Boolean = true,
    val volumeGroup: String = "",
    val reconnectAttempt: Int = 0,
    val isReconnecting: Boolean = false,
    // Sender mode state
    val isBroadcasting: Boolean = false,
    val senderClientCount: Int = 0,
    val senderClients: List<String> = emptyList(),
    val captureSource: CaptureSource = CaptureSource.MICROPHONE,
    val senderPort: String = "4953",
    val senderPin: String = ""
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val audioClient = AudioClient()
    private var audioPlayer: AudioPlayer? = null
    private val discovery = Discovery(application)
    private val profileManager = ProfileManager(application)
    private val networkMonitor = NetworkMonitor(application)

    // Sender mode
    private var audioServer: AudioServer? = null
    private var captureSource: AudioCaptureSource? = null
    private var serverDiscovery: ServerDiscovery? = null
    private var senderStatsJob: Job? = null

    private var discoveryJob: Job? = null
    private var audioJob: Job? = null
    private var statsJob: Job? = null
    private var reconnectJob: Job? = null
    private var networkJob: Job? = null

    init {
        // Load saved preferences
        _uiState.update {
            it.copy(
                deviceName = profileManager.getDeviceName(),
                volume = profileManager.getDefaultVolume(),
                bufferMs = profileManager.getDefaultBufferMs(),
                autoReconnect = profileManager.getAutoReconnect(),
                adaptiveBuffer = profileManager.getAdaptiveBuffer(),
                volumeGroup = profileManager.getVolumeGroup(),
                savedServers = profileManager.getSavedServers()
            )
        }

        // Observe connection state changes
        viewModelScope.launch {
            audioClient.state.collect { state ->
                _uiState.update { it.copy(connectionState = state) }

                when (state) {
                    ConnectionState.DISCONNECTED, ConnectionState.ERROR -> {
                        audioPlayer?.stop()
                        // Trigger auto-reconnect if enabled and was previously connected
                        if (state == ConnectionState.ERROR &&
                            _uiState.value.autoReconnect &&
                            audioClient.canReconnect() &&
                            !_uiState.value.isReconnecting
                        ) {
                            startAutoReconnect()
                        }
                    }
                    ConnectionState.AUTH_REQUIRED -> {
                        // Show PIN dialog
                        _uiState.update {
                            it.copy(
                                showPinDialog = true,
                                pendingPinServer = it.connectedServer,
                                errorMessage = "PIN required to connect"
                            )
                        }
                    }
                    ConnectionState.CONNECTED -> {
                        // Reset reconnect state on successful connection
                        _uiState.update { it.copy(reconnectAttempt = 0, isReconnecting = false) }
                    }
                    else -> {}
                }
            }
        }

        // Start network monitoring for auto-reconnect
        networkMonitor.start()
        networkJob = viewModelScope.launch {
            networkMonitor.networkState.collect { state ->
                when (state) {
                    is NetworkMonitor.NetworkState.Available -> {
                        // Wi-Fi restored — auto-reconnect if needed
                        if (_uiState.value.connectionState == ConnectionState.ERROR &&
                            _uiState.value.autoReconnect &&
                            audioClient.canReconnect()
                        ) {
                            Log.i(TAG, "Wi-Fi restored, attempting auto-reconnect")
                            startAutoReconnect()
                        }
                    }
                    is NetworkMonitor.NetworkState.Lost -> {
                        Log.w(TAG, "Wi-Fi lost")
                        cancelAutoReconnect()
                    }
                    else -> {}
                }
            }
        }

        // Start discovery
        startDiscovery()
    }

    fun startDiscovery() {
        discoveryJob?.cancel()
        discoveryJob = viewModelScope.launch {
            discovery.discoverServers().collect { event ->
                when (event) {
                    is DiscoveryEvent.Found -> {
                        _uiState.update { state ->
                            val servers = state.servers.toMutableList()
                            servers.removeAll { it.name == event.server.name }
                            servers.add(event.server)
                            state.copy(servers = servers)
                        }
                        Log.i(TAG, "Server found: ${event.server.name} at ${event.server.host}:${event.server.port}")
                    }
                    is DiscoveryEvent.Lost -> {
                        _uiState.update { state ->
                            state.copy(servers = state.servers.filter { it.name != event.name })
                        }
                        Log.i(TAG, "Server lost: ${event.name}")
                    }
                }
            }
        }
    }

    fun connectToServer(server: DiscoveredServer, pin: String? = null) {
        cancelAutoReconnect()
        viewModelScope.launch {
            _uiState.update { it.copy(connectedServer = server, errorMessage = null, showPinDialog = false) }

            // Check for saved PIN
            val effectivePin = pin ?: profileManager.getSavedPin(server.host, server.port)

            try {
                audioClient.connect(server.host, server.port, _uiState.value.deviceName, effectivePin)

                // Wait for connection info
                val info = audioClient.connectionInfo.first { it.sessionId.isNotEmpty() }
                _uiState.update { it.copy(connectionInfo = info) }

                // Save server profile
                profileManager.saveServer(
                    ProfileManager.ServerProfile(
                        name = server.name,
                        host = server.host,
                        port = server.port,
                        codec = info.codec,
                        sampleRate = info.sampleRate,
                        channels = info.channels
                    )
                )
                profileManager.setLastServer(
                    ProfileManager.ServerProfile(
                        name = server.name,
                        host = server.host,
                        port = server.port,
                        codec = info.codec,
                        sampleRate = info.sampleRate,
                        channels = info.channels
                    )
                )

                // Save PIN if provided
                if (pin != null) {
                    profileManager.savePin(server.host, server.port, pin)
                }

                // Refresh saved servers list
                _uiState.update { it.copy(savedServers = profileManager.getSavedServers()) }

                // Initialize and start audio player
                val player = AudioPlayer(
                    sampleRate = info.sampleRate,
                    channels = info.channels,
                    bufferMs = _uiState.value.bufferMs
                )
                player.initialize(info.codec)
                player.setVolume(_uiState.value.volume)
                player.setAdaptiveMode(_uiState.value.adaptiveBuffer)
                player.start()
                audioPlayer = player

                // Join volume group if configured
                val group = _uiState.value.volumeGroup
                if (group.isNotEmpty()) {
                    audioClient.setVolumeGroup(group)
                }

                // Start consuming audio frames
                startAudioConsumer(player)

                // Start stats polling
                startStatsPolling(player)

                // Start foreground service for background playback
                startForegroundService(server.name, info.codec, info.sampleRate)

            } catch (e: Exception) {
                Log.e(TAG, "Connection failed: ${e.message}")
                _uiState.update { it.copy(errorMessage = e.message) }
            }
        }
    }

    fun connectManually() {
        val host = _uiState.value.manualHost.trim()
        val port = _uiState.value.manualPort.trim().toIntOrNull() ?: 4953
        if (host.isEmpty()) {
            _uiState.update { it.copy(errorMessage = "Please enter a host address") }
            return
        }

        val server = DiscoveredServer(
            name = "Manual ($host)",
            host = host,
            port = port
        )
        connectToServer(server)
    }

    fun connectSavedServer(profile: ProfileManager.ServerProfile) {
        val server = DiscoveredServer(
            name = profile.name,
            host = profile.host,
            port = profile.port,
            codec = profile.codec,
            sampleRate = profile.sampleRate,
            channels = profile.channels
        )
        connectToServer(server)
    }

    fun submitPin(pin: String) {
        val server = _uiState.value.pendingPinServer ?: return
        _uiState.update { it.copy(showPinDialog = false, pinInput = "") }
        connectToServer(server, pin)
    }

    fun dismissPinDialog() {
        _uiState.update { it.copy(showPinDialog = false, pinInput = "", pendingPinServer = null) }
    }

    fun updatePinInput(pin: String) {
        _uiState.update { it.copy(pinInput = pin) }
    }

    fun disconnect() {
        cancelAutoReconnect()
        audioJob?.cancel()
        statsJob?.cancel()
        audioPlayer?.release()
        audioPlayer = null
        audioClient.disconnect()
        stopForegroundService()
        _uiState.update { it.copy(connectedServer = null, connectionInfo = ConnectionInfo(), isReconnecting = false) }
    }

    fun setVolume(volume: Float) {
        _uiState.update { it.copy(volume = volume) }
        audioPlayer?.setVolume(volume)
        audioClient.setVolume(volume)
        profileManager.setDefaultVolume(volume)
    }

    fun setBufferMs(ms: Int) {
        _uiState.update { it.copy(bufferMs = ms) }
        audioPlayer?.setBufferMs(ms)
        profileManager.setDefaultBufferMs(ms)
    }

    fun setAutoReconnect(enabled: Boolean) {
        _uiState.update { it.copy(autoReconnect = enabled) }
        profileManager.setAutoReconnect(enabled)
    }

    fun setAdaptiveBuffer(enabled: Boolean) {
        _uiState.update { it.copy(adaptiveBuffer = enabled) }
        audioPlayer?.setAdaptiveMode(enabled)
        profileManager.setAdaptiveBuffer(enabled)
    }

    fun setVolumeGroup(group: String) {
        _uiState.update { it.copy(volumeGroup = group) }
        profileManager.setVolumeGroup(group)
        if (_uiState.value.connectionState == ConnectionState.CONNECTED) {
            audioClient.setVolumeGroup(group)
        }
    }

    fun updateManualHost(host: String) {
        _uiState.update { it.copy(manualHost = host) }
    }

    fun updateManualPort(port: String) {
        _uiState.update { it.copy(manualPort = port) }
    }

    fun updateDeviceName(name: String) {
        _uiState.update { it.copy(deviceName = name) }
        profileManager.setDeviceName(name)
    }

    fun removeSavedServer(profile: ProfileManager.ServerProfile) {
        profileManager.removeServer(profile.host, profile.port)
        _uiState.update { it.copy(savedServers = profileManager.getSavedServers()) }
    }

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    // ──── Auto-Reconnect ────

    private fun startAutoReconnect() {
        if (_uiState.value.isReconnecting) return
        if (!audioClient.canReconnect()) return

        _uiState.update { it.copy(isReconnecting = true, reconnectAttempt = 0) }
        reconnectJob?.cancel()
        reconnectJob = viewModelScope.launch {
            var attempt = 0
            while (attempt < MAX_RECONNECT_ATTEMPTS && isActive) {
                attempt++
                _uiState.update { it.copy(reconnectAttempt = attempt) }
                Log.i(TAG, "Auto-reconnect attempt $attempt/$MAX_RECONNECT_ATTEMPTS")

                // Exponential backoff with jitter
                val baseDelay = (RECONNECT_BASE_DELAY_MS * (1L shl (attempt - 1).coerceAtMost(4)))
                    .coerceAtMost(RECONNECT_MAX_DELAY_MS)
                val jitter = (Math.random() * baseDelay * 0.3).toLong() // up to 30% jitter
                delay(baseDelay + jitter)

                if (!isActive) break

                try {
                    val host = audioClient.lastHost
                    val port = audioClient.lastPort
                    val pin = audioClient.lastPin

                    audioClient.connect(host, port, _uiState.value.deviceName, pin)

                    // Wait for connection with proper timeout
                    val connected = withTimeoutOrNull(RECONNECT_CONNECT_WAIT_MS) {
                        audioClient.state.first { it == ConnectionState.CONNECTED || it == ConnectionState.ERROR || it == ConnectionState.DISCONNECTED }
                    }

                    if (connected == ConnectionState.CONNECTED) {
                        Log.i(TAG, "Auto-reconnect successful on attempt $attempt")

                        val info = audioClient.connectionInfo.value
                        if (info.sessionId.isNotEmpty()) {
                            _uiState.update { it.copy(connectionInfo = info) }

                            val player = AudioPlayer(
                                sampleRate = info.sampleRate,
                                channels = info.channels,
                                bufferMs = _uiState.value.bufferMs
                            )
                            player.initialize(info.codec)
                            player.setVolume(_uiState.value.volume)
                            player.setAdaptiveMode(_uiState.value.adaptiveBuffer)
                            player.start()
                            audioPlayer = player

                            val group = _uiState.value.volumeGroup
                            if (group.isNotEmpty()) {
                                audioClient.setVolumeGroup(group)
                            }

                            startAudioConsumer(player)
                            startStatsPolling(player)
                        }
                        return@launch
                    } else {
                        Log.w(TAG, "Reconnect attempt $attempt: state=$connected")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Reconnect attempt $attempt failed: ${e.message}")
                }
            }

            Log.w(TAG, "Auto-reconnect failed after $MAX_RECONNECT_ATTEMPTS attempts")
            _uiState.update { it.copy(isReconnecting = false, errorMessage = "Auto-reconnect failed") }
        }
    }

    private fun cancelAutoReconnect() {
        reconnectJob?.cancel()
        reconnectJob = null
        _uiState.update { it.copy(isReconnecting = false, reconnectAttempt = 0) }
    }

    // ──── Audio & Stats ────

    private fun startAudioConsumer(player: AudioPlayer) {
        audioJob?.cancel()
        audioJob = viewModelScope.launch(Dispatchers.Default) {
            audioClient.audioFrames.collect { frame ->
                player.onAudioFrame(frame)
            }
        }
    }

    private fun startStatsPolling(player: AudioPlayer) {
        statsJob?.cancel()
        statsJob = viewModelScope.launch {
            while (isActive) {
                delay(500) // Update stats every 500ms
                _uiState.update { it.copy(bufferStats = player.getBufferStats()) }
            }
        }
    }

    // ──── Mode Switching ────

    fun switchMode(mode: AppMode) {
        if (_uiState.value.appMode == mode) return
        // Stop current mode
        if (_uiState.value.appMode == AppMode.SENDER) stopBroadcasting()
        if (_uiState.value.appMode == AppMode.RECEIVER) disconnect()
        _uiState.update { it.copy(appMode = mode) }
    }

    // ──── Sender Mode ────

    fun startBroadcasting() {
        if (_uiState.value.isBroadcasting) return

        val state = _uiState.value
        val port = state.senderPort.toIntOrNull() ?: 4953

        // Create and start server
        val server = AudioServer(
            serverName = state.deviceName,
            port = port,
            sampleRate = 48000,
            channelCount = 2,
            pin = state.senderPin
        )
        server.start()
        audioServer = server

        // Start mDNS advertisement
        val sd = ServerDiscovery(getApplication())
        sd.register(state.deviceName, port)
        serverDiscovery = sd

        // Start mic capture (system audio is handled via startBroadcastingWithCapture)
        val capture = MicCapture()
        startCaptureAndBroadcast(capture, server)
    }

    /**
     * Start broadcasting with a specific capture source (e.g. SystemAudioCapture from MediaProjection).
     * Called from Activity after user grants screen capture permission.
     */
    fun startBroadcastingWithCapture(capture: AudioCaptureSource) {
        if (_uiState.value.isBroadcasting) return

        val state = _uiState.value
        val port = state.senderPort.toIntOrNull() ?: 4953

        val server = AudioServer(
            serverName = state.deviceName,
            port = port,
            sampleRate = 48000,
            channelCount = 2,
            pin = state.senderPin
        )
        server.start()
        audioServer = server

        val sd = ServerDiscovery(getApplication())
        sd.register(state.deviceName, port)
        serverDiscovery = sd

        startCaptureAndBroadcast(capture, server)
    }

    private fun startCaptureAndBroadcast(capture: AudioCaptureSource, server: AudioServer) {
        capture.start(48000, 2) { samples, timestamp ->
            server.onAudioChunk(samples, timestamp)
        }
        captureSource = capture

        _uiState.update { it.copy(isBroadcasting = true) }

        // Poll sender stats
        senderStatsJob = viewModelScope.launch {
            while (isActive) {
                delay(1000)
                _uiState.update {
                    it.copy(
                        senderClientCount = server.clientCount.value,
                        senderClients = server.clientList.value
                    )
                }
            }
        }

        Log.i(TAG, "Broadcasting started with ${capture.sourceName}")
    }

    fun stopBroadcasting() {
        senderStatsJob?.cancel()
        senderStatsJob = null
        captureSource?.release()
        captureSource = null
        serverDiscovery?.unregister()
        serverDiscovery = null
        audioServer?.stop()
        audioServer = null
        _uiState.update { it.copy(isBroadcasting = false, senderClientCount = 0, senderClients = emptyList()) }
        Log.i(TAG, "Broadcasting stopped")
    }

    fun updateSenderPort(port: String) {
        _uiState.update { it.copy(senderPort = port) }
    }

    fun updateSenderPin(pin: String) {
        _uiState.update { it.copy(senderPin = pin) }
    }

    fun updateCaptureSource(source: CaptureSource) {
        _uiState.update { it.copy(captureSource = source) }
    }

    private fun startForegroundService(serverName: String, codec: String, sampleRate: Int) {
        try {
            val context = getApplication<Application>()
            val intent = Intent(context, com.audiocast.service.AudioService::class.java).apply {
                putExtra("server_name", serverName)
                putExtra("codec", codec)
                putExtra("sample_rate", sampleRate)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
            Log.i(TAG, "Foreground service started")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start foreground service: ${e.message}")
        }
    }

    private fun stopForegroundService() {
        try {
            val context = getApplication<Application>()
            val intent = Intent(context, com.audiocast.service.AudioService::class.java)
            context.stopService(intent)
            Log.i(TAG, "Foreground service stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop foreground service: ${e.message}")
        }
    }

    override fun onCleared() {
        super.onCleared()
        disconnect()
        stopBroadcasting()
        discoveryJob?.cancel()
        networkJob?.cancel()
        networkMonitor.stop()
    }
}
