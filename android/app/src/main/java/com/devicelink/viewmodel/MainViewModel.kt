package com.devicelink.viewmodel

import android.app.Application
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.devicelink.audio.AudioCaptureSource
import com.devicelink.audio.AudioPlayer
import com.devicelink.data.ProfileManager
import com.devicelink.network.*
import com.devicelink.service.NotificationRelayService
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

private const val TAG = "MainViewModel"
private const val MAX_RECONNECT_ATTEMPTS = 5
private const val RECONNECT_BASE_DELAY_MS = 3000L
private const val RECONNECT_MAX_DELAY_MS = 30000L
private const val RECONNECT_CONNECT_WAIT_MS = 6000L

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val audioClient = AudioClient()
    private var audioPlayer: AudioPlayer? = null
    private val discovery = Discovery(application)
    private val profileManager = ProfileManager(application)
    private val networkMonitor = NetworkMonitor(application)

    private val messagingManager = MessagingManager(application, audioClient, _uiState, viewModelScope)
    private val senderManager = SenderManager(application, _uiState, viewModelScope)

    private var discoveryJob: Job? = null
    private var audioJob: Job? = null
    private var statsJob: Job? = null
    private var reconnectJob: Job? = null
    private var networkJob: Job? = null
    private var volumeListenerJob: Job? = null

    init {
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

        viewModelScope.launch {
            audioClient.state.collect { state ->
                _uiState.update { it.copy(connectionState = state) }

                when (state) {
                    ConnectionState.DISCONNECTED, ConnectionState.ERROR -> {
                        audioPlayer?.stop()
                        if (state == ConnectionState.ERROR &&
                            _uiState.value.autoReconnect &&
                            audioClient.canReconnect() &&
                            !_uiState.value.isReconnecting
                        ) {
                            startAutoReconnect()
                        }
                    }
                    ConnectionState.AUTH_REQUIRED -> {
                        _uiState.update {
                            it.copy(
                                showPinDialog = true,
                                pendingPinServer = it.connectedServer,
                                errorMessage = "PIN required to connect"
                            )
                        }
                    }
                    ConnectionState.CONNECTED -> {
                        _uiState.update { it.copy(reconnectAttempt = 0, isReconnecting = false) }
                        messagingManager.startMessagePolling()
                        NotificationRelayService.audioClient = audioClient
                        NotificationRelayService.enabled = true
                    }
                    else -> {}
                }
            }
        }

        networkMonitor.start()
        networkJob = viewModelScope.launch {
            networkMonitor.networkState.collect { state ->
                when (state) {
                    is NetworkMonitor.NetworkState.Available -> {
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

        startDiscovery()
    }

    // ──── Discovery ────

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

    // ──── Connection ────

    fun connectToServer(server: DiscoveredServer, pin: String? = null) {
        cancelAutoReconnect()
        viewModelScope.launch {
            _uiState.update { it.copy(connectedServer = server, errorMessage = null, showPinDialog = false) }

            val effectivePin = pin ?: profileManager.getSavedPin(server.host, server.port)

            try {
                audioClient.connect(server.host, server.port, _uiState.value.deviceName, effectivePin)

                val info = audioClient.connectionInfo.first { it.sessionId.isNotEmpty() }
                _uiState.update { it.copy(connectionInfo = info) }

                profileManager.saveServer(
                    ProfileManager.ServerProfile(
                        name = server.name, host = server.host, port = server.port,
                        codec = info.codec, sampleRate = info.sampleRate, channels = info.channels
                    )
                )
                profileManager.setLastServer(
                    ProfileManager.ServerProfile(
                        name = server.name, host = server.host, port = server.port,
                        codec = info.codec, sampleRate = info.sampleRate, channels = info.channels
                    )
                )

                if (pin != null) {
                    profileManager.savePin(server.host, server.port, pin)
                }
                _uiState.update { it.copy(savedServers = profileManager.getSavedServers()) }

                val player = AudioPlayer(
                    sampleRate = info.sampleRate, channels = info.channels, bufferMs = _uiState.value.bufferMs
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
                startVolumeListener(player)
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
        connectToServer(DiscoveredServer(name = "Manual ($host)", host = host, port = port))
    }

    fun connectSavedServer(profile: ProfileManager.ServerProfile) {
        connectToServer(DiscoveredServer(
            name = profile.name, host = profile.host, port = profile.port,
            codec = profile.codec, sampleRate = profile.sampleRate, channels = profile.channels
        ))
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
        messagingManager.stopMessagePolling()
        NotificationRelayService.audioClient = null
        NotificationRelayService.enabled = false
        audioJob?.cancel()
        statsJob?.cancel()
        audioPlayer?.release()
        audioPlayer = null
        audioClient.disconnect()
        stopForegroundService()
        _uiState.update { it.copy(connectedServer = null, connectionInfo = ConnectionInfo(), isReconnecting = false) }
    }

    // ──── Settings ────

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

    fun updateManualHost(host: String) { _uiState.update { it.copy(manualHost = host) } }
    fun updateManualPort(port: String) { _uiState.update { it.copy(manualPort = port) } }
    fun updateDeviceName(name: String) {
        _uiState.update { it.copy(deviceName = name) }
        profileManager.setDeviceName(name)
    }

    fun removeSavedServer(profile: ProfileManager.ServerProfile) {
        profileManager.removeServer(profile.host, profile.port)
        _uiState.update { it.copy(savedServers = profileManager.getSavedServers()) }
    }

    fun clearError() { _uiState.update { it.copy(errorMessage = null) } }

    // ──── Messaging (delegated) ────

    fun sendMessage(content: String) = messagingManager.sendMessage(content)
    fun updateMessageInput(text: String) { _uiState.update { it.copy(messageInput = text) } }
    fun toggleMessaging() {
        _uiState.update {
            val show = !it.showMessaging
            it.copy(showMessaging = show, unreadMessageCount = if (show) 0 else it.unreadMessageCount)
        }
    }
    fun clearMessages() { _uiState.update { it.copy(messages = emptyList()) } }
    fun sendFile(uri: android.net.Uri) = messagingManager.sendFile(uri)

    fun acceptFileOffer(transferId: String) {
        _uiState.update { state ->
            val offer = state.pendingFileOffers.find { it.transferId == transferId }
            state.copy(
                pendingFileOffers = state.pendingFileOffers.filter { it.transferId != transferId },
                fileTransfers = if (offer != null) state.fileTransfers + offer else state.fileTransfers
            )
        }
        viewModelScope.launch(Dispatchers.IO) { audioClient.acceptFile(transferId) }
    }

    fun rejectFileOffer(transferId: String) {
        _uiState.update { state ->
            state.copy(pendingFileOffers = state.pendingFileOffers.filter { it.transferId != transferId })
        }
        viewModelScope.launch(Dispatchers.IO) { audioClient.rejectFile(transferId) }
    }

    fun clearCompletedTransfers() {
        _uiState.update { it.copy(fileTransfers = it.fileTransfers.filter { t -> !t.isCompleted }) }
    }

    // ──── Remote Input ────

    fun setShowTouchpad(show: Boolean) { _uiState.update { it.copy(showTouchpad = show) } }

    fun sendTouchpadMove(dx: Float, dy: Float) {
        if (_uiState.value.connectionState != ConnectionState.CONNECTED) return
        val msg = ControlMessage.TouchpadMove(dx = dx, dy = dy, fingers = 1)
        viewModelScope.launch(Dispatchers.IO) {
            try { audioClient.sendBytes(msg.serialize()) }
            catch (e: Exception) { Log.e(TAG, "Failed to send touchpad move: ${e.message}") }
        }
    }

    fun sendTouchpadGesture(gesture: String, dx: Float = 0f, dy: Float = 0f) {
        if (_uiState.value.connectionState != ConnectionState.CONNECTED) return
        val msg = ControlMessage.TouchpadGesture(gesture = gesture, dx = dx, dy = dy)
        viewModelScope.launch(Dispatchers.IO) {
            try { audioClient.sendBytes(msg.serialize()) }
            catch (e: Exception) { Log.e(TAG, "Failed to send touchpad gesture: ${e.message}") }
        }
    }

    fun sendKeyboardInput(text: String) {
        if (_uiState.value.connectionState != ConnectionState.CONNECTED) return
        val msg = ControlMessage.KeyboardInput(text = text, action = "text")
        viewModelScope.launch(Dispatchers.IO) {
            try { audioClient.sendBytes(msg.serialize()) }
            catch (e: Exception) { Log.e(TAG, "Failed to send keyboard input: ${e.message}") }
        }
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

                val baseDelay = (RECONNECT_BASE_DELAY_MS * (1L shl (attempt - 1).coerceAtMost(4)))
                    .coerceAtMost(RECONNECT_MAX_DELAY_MS)
                val jitter = (Math.random() * baseDelay * 0.3).toLong()
                delay(baseDelay + jitter)

                if (!isActive) break

                try {
                    audioClient.connect(audioClient.lastHost, audioClient.lastPort, _uiState.value.deviceName, audioClient.lastPin)

                    val connected = withTimeoutOrNull(RECONNECT_CONNECT_WAIT_MS) {
                        audioClient.state.first { it == ConnectionState.CONNECTED || it == ConnectionState.ERROR || it == ConnectionState.DISCONNECTED }
                    }

                    if (connected == ConnectionState.CONNECTED) {
                        Log.i(TAG, "Auto-reconnect successful on attempt $attempt")
                        val info = audioClient.connectionInfo.value
                        if (info.sessionId.isNotEmpty()) {
                            _uiState.update { it.copy(connectionInfo = info) }
                            val player = AudioPlayer(
                                sampleRate = info.sampleRate, channels = info.channels, bufferMs = _uiState.value.bufferMs
                            )
                            player.initialize(info.codec)
                            player.setVolume(_uiState.value.volume)
                            player.setAdaptiveMode(_uiState.value.adaptiveBuffer)
                            player.start()
                            audioPlayer = player

                            val group = _uiState.value.volumeGroup
                            if (group.isNotEmpty()) { audioClient.setVolumeGroup(group) }

                            startAudioConsumer(player)
                            startStatsPolling(player)
                            startVolumeListener(player)
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
            audioClient.audioFrames.collect { frame -> player.onAudioFrame(frame) }
        }
    }

    private fun startVolumeListener(player: AudioPlayer) {
        volumeListenerJob?.cancel()
        volumeListenerJob = viewModelScope.launch {
            audioClient.volumeChanged.collect { volume ->
                player.setVolume(volume)
                _uiState.update { it.copy(volume = volume) }
            }
        }
    }

    private fun startStatsPolling(player: AudioPlayer) {
        statsJob?.cancel()
        statsJob = viewModelScope.launch {
            while (isActive) {
                delay(500)
                _uiState.update { it.copy(bufferStats = player.getBufferStats()) }
            }
        }
    }

    // ──── Mode Switching ────

    fun switchMode(mode: AppMode) {
        if (_uiState.value.appMode == mode) return
        if (_uiState.value.appMode == AppMode.SENDER) senderManager.stopBroadcasting()
        if (_uiState.value.appMode == AppMode.RECEIVER) disconnect()
        _uiState.update { it.copy(appMode = mode) }
    }

    // ──── Sender Mode (delegated) ────

    fun startBroadcasting() = senderManager.startBroadcasting()
    fun startBroadcastingWithCapture(capture: AudioCaptureSource) = senderManager.startBroadcastingWithCapture(capture)
    fun stopBroadcasting() = senderManager.stopBroadcasting()
    fun updateSenderPort(port: String) { _uiState.update { it.copy(senderPort = port) } }
    fun updateSenderPin(pin: String) { _uiState.update { it.copy(senderPin = pin) } }
    fun updateCaptureSource(source: CaptureSource) { _uiState.update { it.copy(captureSource = source) } }

    // ──── Foreground Service ────

    private fun startForegroundService(serverName: String, codec: String, sampleRate: Int) {
        try {
            val context = getApplication<Application>()
            val intent = Intent(context, com.devicelink.service.AudioService::class.java).apply {
                putExtra("server_name", serverName)
                putExtra("codec", codec)
                putExtra("sample_rate", sampleRate)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start foreground service: ${e.message}")
        }
    }

    private fun stopForegroundService() {
        try {
            val context = getApplication<Application>()
            context.stopService(Intent(context, com.devicelink.service.AudioService::class.java))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop foreground service: ${e.message}")
        }
    }

    override fun onCleared() {
        super.onCleared()
        disconnect()
        senderManager.stopBroadcasting()
        discoveryJob?.cancel()
        networkJob?.cancel()
        networkMonitor.stop()
    }
}
