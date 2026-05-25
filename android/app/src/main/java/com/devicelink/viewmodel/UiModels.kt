package com.devicelink.viewmodel

import com.devicelink.audio.JitterBuffer
import com.devicelink.data.ProfileManager
import com.devicelink.network.ConnectionInfo
import com.devicelink.network.ConnectionState
import com.devicelink.network.DiscoveredServer

enum class AppMode { RECEIVER, SENDER }
enum class CaptureSource { MICROPHONE, SYSTEM_AUDIO }

data class FileTransferInfo(
    val transferId: String,
    val fromName: String,
    val fileName: String,
    val fileSize: Long,
    val mimeType: String,
    val totalChunks: Int,
    val chunksTransferred: Int = 0,
    val progress: Float = 0f,
    val isIncoming: Boolean = true,
    val isCompleted: Boolean = false
)

data class ChatMessage(
    val messageId: String,
    val fromId: String,
    val fromName: String,
    val content: String,
    val timestampUs: Long,
    val isFromMe: Boolean = false
)

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
    val senderPin: String = "",
    // Messaging state
    val messages: List<ChatMessage> = emptyList(),
    val messageInput: String = "",
    val unreadMessageCount: Int = 0,
    val showMessaging: Boolean = false,
    // File transfer state
    val fileTransfers: List<FileTransferInfo> = emptyList(),
    val pendingFileOffers: List<FileTransferInfo> = emptyList(),
    // Touchpad state
    val showTouchpad: Boolean = false
)
