package com.devicelink.viewmodel

import android.app.Application
import android.util.Log
import com.devicelink.network.AudioClient
import com.devicelink.network.ConnectionState
import com.devicelink.network.nowUs
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

private const val TAG = "MessagingManager"

/**
 * Manages text messaging and file transfer via HTTP API.
 */
class MessagingManager(
    private val application: Application,
    private val audioClient: AudioClient,
    private val uiState: MutableStateFlow<UiState>,
    private val scope: CoroutineScope
) {
    private var messagePollJob: Job? = null
    private var lastMessageTs: Long = 0
    private val downloadedTransfers = mutableSetOf<String>()

    private fun getMessagingBaseUrl(): String {
        val host = audioClient.lastHost.ifEmpty { "localhost" }
        return "http://$host:4955"
    }

    fun startMessagePolling() {
        messagePollJob?.cancel()
        lastMessageTs = 0
        downloadedTransfers.clear()
        messagePollJob = scope.launch(Dispatchers.IO) {
            while (isActive && uiState.value.connectionState == ConnectionState.CONNECTED) {
                pollMessages()
                pollTransfers()
                delay(2000)
            }
        }
    }

    fun stopMessagePolling() {
        messagePollJob?.cancel()
        messagePollJob = null
    }

    private suspend fun pollMessages() {
        try {
            val url = java.net.URL("${getMessagingBaseUrl()}/api/messages?since=$lastMessageTs")
            val conn = url.openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 3000
            conn.readTimeout = 3000
            val response = conn.inputStream.bufferedReader().readText()
            conn.disconnect()

            val arr = org.json.JSONArray(response)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val fromId = obj.getString("from_id")
                val ts = obj.getLong("timestamp_us")
                if (ts <= lastMessageTs) continue
                if (fromId == audioClient.clientId) continue
                val chat = ChatMessage(
                    messageId = obj.getString("message_id"),
                    fromId = fromId,
                    fromName = obj.getString("from_name"),
                    content = obj.getString("content"),
                    timestampUs = ts,
                    isFromMe = false
                )
                lastMessageTs = ts
                uiState.update { state ->
                    state.copy(
                        messages = state.messages + chat,
                        unreadMessageCount = if (!state.showMessaging) state.unreadMessageCount + 1 else state.unreadMessageCount
                    )
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "Message poll error: ${e.message}")
        }
    }

    private suspend fun pollTransfers() {
        try {
            val tUrl = java.net.URL("${getMessagingBaseUrl()}/api/transfers")
            val tConn = tUrl.openConnection() as java.net.HttpURLConnection
            tConn.connectTimeout = 3000
            tConn.readTimeout = 3000
            val tResponse = tConn.inputStream.bufferedReader().readText()
            tConn.disconnect()

            val tArr = org.json.JSONArray(tResponse)
            for (i in 0 until tArr.length()) {
                val obj = tArr.getJSONObject(i)
                val tid = obj.getString("transfer_id")
                val fromId = obj.optString("from_id", "")
                val status = obj.getString("status")
                val fileName = obj.getString("file_name")
                val fileSize = obj.optLong("file_size", 0)

                if (fromId == audioClient.clientId) continue
                if (downloadedTransfers.contains(tid)) continue

                if (status == "Completed" || status == "InProgress") {
                    val existing = uiState.value.fileTransfers.find { it.transferId == tid }
                    if (existing == null) {
                        val info = FileTransferInfo(
                            transferId = tid,
                            fromName = obj.optString("from_name", "PC"),
                            fileName = fileName,
                            fileSize = fileSize,
                            mimeType = "",
                            totalChunks = obj.optInt("total_chunks", 1),
                            isIncoming = true
                        )
                        uiState.update { it.copy(fileTransfers = it.fileTransfers + info) }
                    }
                }

                if (status == "Completed" && !downloadedTransfers.contains(tid)) {
                    downloadedTransfers.add(tid)
                    try {
                        val dlUrl = java.net.URL("${getMessagingBaseUrl()}/api/transfers/$tid/download")
                        val dlConn = dlUrl.openConnection() as java.net.HttpURLConnection
                        dlConn.connectTimeout = 5000
                        dlConn.readTimeout = 30000
                        val fileData = dlConn.inputStream.readBytes()
                        dlConn.disconnect()

                        val dir = application.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS)
                        val file = java.io.File(dir, fileName)
                        file.writeBytes(fileData)

                        uiState.update { state ->
                            state.copy(fileTransfers = state.fileTransfers.map { t ->
                                if (t.transferId == tid) t.copy(isCompleted = true, progress = 1f)
                                else t
                            })
                        }
                        Log.i(TAG, "Downloaded file: $fileName to ${file.absolutePath}")
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to download file $tid: ${e.message}")
                    }
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "Transfer poll error: ${e.message}")
        }
    }

    fun sendMessage(content: String) {
        if (content.isBlank()) return
        if (uiState.value.connectionState != ConnectionState.CONNECTED) return

        val chat = ChatMessage(
            messageId = java.util.UUID.randomUUID().toString(),
            fromId = audioClient.clientId,
            fromName = uiState.value.deviceName,
            content = content,
            timestampUs = nowUs(),
            isFromMe = true
        )
        uiState.update { it.copy(messages = it.messages + chat, messageInput = "") }

        scope.launch(Dispatchers.IO) {
            try {
                val url = java.net.URL("${getMessagingBaseUrl()}/api/messages/send")
                val conn = url.openConnection() as java.net.HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.doOutput = true
                conn.connectTimeout = 3000
                val body = org.json.JSONObject().apply {
                    put("content", content)
                    put("from_id", audioClient.clientId)
                    put("from_name", uiState.value.deviceName)
                }.toString()
                conn.outputStream.write(body.toByteArray())
                conn.outputStream.flush()
                conn.inputStream.readBytes()
                conn.disconnect()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send message via HTTP: ${e.message}")
            }
        }
    }

    fun sendFile(uri: android.net.Uri) {
        if (uiState.value.connectionState != ConnectionState.CONNECTED) return

        scope.launch(Dispatchers.IO) {
            try {
                val resolver = application.contentResolver
                val cursor = resolver.query(uri, null, null, null, null)
                var fileName = "file"
                var fileSize = 0L
                cursor?.use {
                    if (it.moveToFirst()) {
                        val nameIdx = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                        val sizeIdx = it.getColumnIndex(android.provider.OpenableColumns.SIZE)
                        if (nameIdx >= 0) fileName = it.getString(nameIdx) ?: "file"
                        if (sizeIdx >= 0) fileSize = it.getLong(sizeIdx)
                    }
                }
                val mimeType = resolver.getType(uri) ?: "application/octet-stream"

                val chunkSize = 65536
                val totalChunks = ((fileSize + chunkSize - 1) / chunkSize).toInt()
                val transferId = java.util.UUID.randomUUID().toString()
                val baseUrl = getMessagingBaseUrl()

                val info = FileTransferInfo(
                    transferId = transferId,
                    fromName = uiState.value.deviceName,
                    fileName = fileName,
                    fileSize = fileSize,
                    mimeType = mimeType,
                    totalChunks = totalChunks,
                    isIncoming = false
                )
                uiState.update { it.copy(fileTransfers = it.fileTransfers + info) }

                // Send offer via HTTP
                val offerUrl = java.net.URL("$baseUrl/api/transfers/offer")
                val offerConn = offerUrl.openConnection() as java.net.HttpURLConnection
                offerConn.requestMethod = "POST"
                offerConn.setRequestProperty("Content-Type", "application/json")
                offerConn.doOutput = true
                val offerBody = org.json.JSONObject().apply {
                    put("transfer_id", transferId)
                    put("from_id", audioClient.clientId)
                    put("from_name", uiState.value.deviceName)
                    put("file_name", fileName)
                    put("file_size", fileSize)
                    put("mime_type", mimeType)
                    put("chunk_size", chunkSize)
                    put("total_chunks", totalChunks)
                }.toString()
                offerConn.outputStream.write(offerBody.toByteArray())
                offerConn.inputStream.readBytes()
                offerConn.disconnect()

                // Upload chunks
                resolver.openInputStream(uri)?.use { inputStream ->
                    val buffer = ByteArray(chunkSize)
                    var chunkIndex = 0
                    var bytesRead: Int
                    while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                        val chunk = if (bytesRead < chunkSize) buffer.copyOf(bytesRead) else buffer
                        val chunkUrl = java.net.URL("$baseUrl/api/transfers/$transferId/chunk")
                        val chunkConn = chunkUrl.openConnection() as java.net.HttpURLConnection
                        chunkConn.requestMethod = "POST"
                        chunkConn.setRequestProperty("Content-Type", "application/octet-stream")
                        chunkConn.doOutput = true
                        chunkConn.outputStream.write(chunk, 0, bytesRead)
                        chunkConn.inputStream.readBytes()
                        chunkConn.disconnect()
                        chunkIndex++

                        val progress = chunkIndex.toFloat() / totalChunks.coerceAtLeast(1)
                        uiState.update { state ->
                            state.copy(fileTransfers = state.fileTransfers.map { t ->
                                if (t.transferId == transferId) t.copy(chunksTransferred = chunkIndex, progress = progress)
                                else t
                            })
                        }
                    }
                }

                // Signal completion
                val completeUrl = java.net.URL("$baseUrl/api/transfers/$transferId/complete")
                val completeConn = completeUrl.openConnection() as java.net.HttpURLConnection
                completeConn.requestMethod = "POST"
                completeConn.setRequestProperty("Content-Type", "application/json")
                completeConn.doOutput = true
                completeConn.outputStream.write("{}".toByteArray())
                completeConn.inputStream.readBytes()
                completeConn.disconnect()

                uiState.update { state ->
                    state.copy(fileTransfers = state.fileTransfers.map { t ->
                        if (t.transferId == transferId) t.copy(isCompleted = true, progress = 1f)
                        else t
                    })
                }

                Log.i(TAG, "File sent via HTTP: $fileName ($totalChunks chunks)")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send file: ${e.message}")
                uiState.update { it.copy(errorMessage = "Failed to send file: ${e.message}") }
            }
        }
    }
}
