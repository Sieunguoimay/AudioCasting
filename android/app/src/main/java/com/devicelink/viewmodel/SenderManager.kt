package com.devicelink.viewmodel

import android.app.Application
import android.util.Log
import com.devicelink.audio.AudioCaptureSource
import com.devicelink.audio.MicCapture
import com.devicelink.network.AudioServer
import com.devicelink.network.ServerDiscovery
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

private const val TAG = "SenderManager"

/**
 * Manages sender/broadcasting mode: audio capture, server, and mDNS advertisement.
 */
class SenderManager(
    private val application: Application,
    private val uiState: MutableStateFlow<UiState>,
    private val scope: CoroutineScope
) {
    private var audioServer: AudioServer? = null
    private var captureSource: AudioCaptureSource? = null
    private var serverDiscovery: ServerDiscovery? = null
    private var senderStatsJob: Job? = null

    fun startBroadcasting() {
        if (uiState.value.isBroadcasting) return

        val state = uiState.value
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

        val sd = ServerDiscovery(application)
        sd.register(state.deviceName, port)
        serverDiscovery = sd

        val capture = MicCapture()
        startCaptureAndBroadcast(capture, server)
    }

    fun startBroadcastingWithCapture(capture: AudioCaptureSource) {
        if (uiState.value.isBroadcasting) return

        val state = uiState.value
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

        val sd = ServerDiscovery(application)
        sd.register(state.deviceName, port)
        serverDiscovery = sd

        startCaptureAndBroadcast(capture, server)
    }

    private fun startCaptureAndBroadcast(capture: AudioCaptureSource, server: AudioServer) {
        capture.start(48000, 2) { samples, timestamp ->
            server.onAudioChunk(samples, timestamp)
        }
        captureSource = capture

        uiState.update { it.copy(isBroadcasting = true) }

        senderStatsJob = scope.launch {
            while (isActive) {
                delay(1000)
                uiState.update {
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
        uiState.update { it.copy(isBroadcasting = false, senderClientCount = 0, senderClients = emptyList()) }
        Log.i(TAG, "Broadcasting stopped")
    }
}
