package com.devicelink.network

import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer

/**
 * Wire protocol matching the Rust server's protocol.rs
 */
object Protocol {
    const val FRAME_TYPE_AUDIO: Byte = 0x01
    const val FRAME_TYPE_CONTROL: Byte = 0x02
    const val FRAME_TYPE_CLOCK_SYNC: Byte = 0x03
    const val FRAME_TYPE_FILE_DATA: Byte = 0x04

    const val CODEC_OPUS: Byte = 1
    const val CODEC_FLAC: Byte = 2
    const val CODEC_PCM: Byte = 3

    const val AUDIO_FRAME_HEADER_SIZE = 1 + 4 + 8 + 1 + 4 + 1 + 4 // 23 bytes
    /** Audio header bytes after the type byte */
    const val AUDIO_FRAME_REST_SIZE = AUDIO_FRAME_HEADER_SIZE - 1 // 22 bytes
    /** Offset of payload_len within the rest-of-header buffer */
    const val AUDIO_PAYLOAD_LEN_OFFSET = AUDIO_FRAME_REST_SIZE - 4 // 18

    const val FILE_DATA_HEADER_SIZE = 1 + 16 + 4 + 4 // 25 bytes
    /** File data header bytes after the type byte */
    const val FILE_DATA_REST_SIZE = FILE_DATA_HEADER_SIZE - 1 // 24 bytes
    /** Offset of chunk_len within the rest-of-header buffer */
    const val FILE_CHUNK_LEN_OFFSET = FILE_DATA_REST_SIZE - 4 // 20
    const val FILE_CHUNK_MAX_SIZE = 65536 // 64 KB

    /** Clock sync payload size (T1 + T2 + T3 as Long) */
    const val CLOCK_SYNC_PAYLOAD_SIZE = 3 * 8 // 24 bytes
    /** Full clock sync frame size including type byte */
    const val CLOCK_SYNC_FRAME_SIZE = 1 + CLOCK_SYNC_PAYLOAD_SIZE // 25 bytes

    /** Maximum allowed control message size */
    const val CONTROL_MESSAGE_MAX_SIZE = 1_000_000
}

data class AudioFrame(
    val sequence: Int,
    val timestampUs: Long,
    val codec: Byte,
    val sampleRate: Int,
    val channels: Byte,
    val payload: ByteArray
) {
    fun serialize(): ByteArray {
        val buf = ByteBuffer.allocate(Protocol.AUDIO_FRAME_HEADER_SIZE + payload.size)
        buf.put(Protocol.FRAME_TYPE_AUDIO)
        buf.putInt(sequence)
        buf.putLong(timestampUs)
        buf.put(codec)
        buf.putInt(sampleRate)
        buf.put(channels)
        buf.putInt(payload.size)
        buf.put(payload)
        return buf.array()
    }

    companion object {
        fun deserialize(data: ByteArray): AudioFrame {
            val buf = ByteBuffer.wrap(data)
            val type_ = buf.get() // FRAME_TYPE_AUDIO
            val sequence = buf.int
            val timestampUs = buf.long
            val codec = buf.get()
            val sampleRate = buf.int
            val channels = buf.get()
            val payloadLen = buf.int
            val payload = ByteArray(payloadLen)
            buf.get(payload)
            return AudioFrame(sequence, timestampUs, codec, sampleRate, channels, payload)
        }
    }
}

data class ClockSyncMessage(
    val t1: Long,
    val t2: Long,
    val t3: Long
) {
    fun serialize(): ByteArray {
        val buf = ByteBuffer.allocate(Protocol.CLOCK_SYNC_FRAME_SIZE)
        buf.put(Protocol.FRAME_TYPE_CLOCK_SYNC)
        buf.putLong(t1)
        buf.putLong(t2)
        buf.putLong(t3)
        return buf.array()
    }

    companion object {
        fun deserialize(data: ByteArray): ClockSyncMessage {
            val buf = ByteBuffer.wrap(data)
            buf.get() // skip type
            val t1 = buf.long
            val t2 = buf.long
            val t3 = buf.long
            return ClockSyncMessage(t1, t2, t3)
        }
    }
}

data class FileDataFrame(
    val transferId: ByteArray, // 16 bytes UUID
    val chunkIndex: Int,
    val payload: ByteArray
) {
    fun serialize(): ByteArray {
        val buf = ByteBuffer.allocate(Protocol.FILE_DATA_HEADER_SIZE + payload.size)
        buf.put(Protocol.FRAME_TYPE_FILE_DATA)
        buf.put(transferId)
        buf.putInt(chunkIndex)
        buf.putInt(payload.size)
        buf.put(payload)
        return buf.array()
    }

    companion object {
        fun deserialize(data: ByteArray): FileDataFrame {
            val buf = ByteBuffer.wrap(data)
            buf.get() // skip type byte
            val transferId = ByteArray(16)
            buf.get(transferId)
            val chunkIndex = buf.int
            val chunkLen = buf.int
            val payload = ByteArray(chunkLen)
            buf.get(payload)
            return FileDataFrame(transferId, chunkIndex, payload)
        }

        /** Convert a UUID string to 16-byte array */
        fun uuidToBytes(uuid: String): ByteArray {
            val u = java.util.UUID.fromString(uuid)
            val buf = ByteBuffer.allocate(16)
            buf.putLong(u.mostSignificantBits)
            buf.putLong(u.leastSignificantBits)
            return buf.array()
        }

        /** Convert 16-byte array to UUID string */
        fun bytesToUuid(bytes: ByteArray): String {
            val buf = ByteBuffer.wrap(bytes)
            val msb = buf.long
            val lsb = buf.long
            return java.util.UUID(msb, lsb).toString()
        }
    }
}

sealed class ControlMessage {
    data class ServerInfo(
        val name: String,
        val version: String,
        val codec: String,
        val sampleRate: Int,
        val channels: Int,
        val bufferMs: Int,
        val requiresPin: Boolean = false
    ) : ControlMessage()

    data class ClientJoin(
        val clientName: String,
        val clientId: String,
        val pin: String? = null
    ) : ControlMessage()

    data class ClientAccepted(
        val sessionId: String,
        val codec: String,
        val sampleRate: Int,
        val channels: Int
    ) : ControlMessage()

    data class AuthRequired(val message: String) : ControlMessage()

    data class SetVolume(val volume: Float) : ControlMessage()
    data class SetVolumeGroup(val groupName: String) : ControlMessage()
    data class ClientLeave(val clientId: String) : ControlMessage()
    data class Ping(val timestampUs: Long) : ControlMessage()
    data class Pong(val pingTimestampUs: Long, val pongTimestampUs: Long) : ControlMessage()
    data class Error(val message: String) : ControlMessage()

    // ─── Text Messaging ──────────────────────────────────────────────────────
    data class TextMessage(
        val messageId: String,
        val fromId: String,
        val fromName: String,
        val content: String,
        val timestampUs: Long
    ) : ControlMessage()

    data class TextMessageAck(val messageId: String) : ControlMessage()

    // ─── File Transfer ───────────────────────────────────────────────────────
    data class FileOffer(
        val transferId: String,
        val fromId: String,
        val fromName: String,
        val fileName: String,
        val fileSize: Long,
        val mimeType: String,
        val chunkSize: Int,
        val totalChunks: Int
    ) : ControlMessage()

    data class FileAccept(val transferId: String) : ControlMessage()
    data class FileReject(val transferId: String, val reason: String) : ControlMessage()
    data class FileComplete(val transferId: String, val checksum: String) : ControlMessage()
    data class FileError(val transferId: String, val message: String) : ControlMessage()

    // ─── Notification Relay ─────────────────────────────────────────────────
    data class NotificationPost(
        val notificationId: String,
        val appPackage: String,
        val appName: String,
        val title: String,
        val content: String,
        val timestampUs: Long
    ) : ControlMessage()

    data class NotificationDismiss(
        val notificationId: String
    ) : ControlMessage()

    // ─── Remote Input ───────────────────────────────────────────────────────
    data class TouchpadMove(
        val dx: Float,
        val dy: Float,
        val fingers: Int
    ) : ControlMessage()

    data class TouchpadGesture(
        val gesture: String,
        val dx: Float,
        val dy: Float
    ) : ControlMessage()

    data class KeyboardInput(
        val text: String,
        val action: String
    ) : ControlMessage()

    fun serialize(): ByteArray {
        val json = when (this) {
            is ClientJoin -> JSONObject().apply {
                put("type", "ClientJoin")
                put("client_name", clientName)
                put("client_id", clientId)
                if (pin != null) put("pin", pin)
            }
            is SetVolume -> JSONObject().apply {
                put("type", "SetVolume")
                put("volume", volume.toDouble())
            }
            is SetVolumeGroup -> JSONObject().apply {
                put("type", "SetVolumeGroup")
                put("group_name", groupName)
            }
            is ClientLeave -> JSONObject().apply {
                put("type", "ClientLeave")
                put("client_id", clientId)
            }
            is Ping -> JSONObject().apply {
                put("type", "Ping")
                put("timestamp_us", timestampUs)
            }
            is Pong -> JSONObject().apply {
                put("type", "Pong")
                put("ping_timestamp_us", pingTimestampUs)
                put("pong_timestamp_us", pongTimestampUs)
            }
            is ServerInfo -> JSONObject().apply {
                put("type", "ServerInfo")
                put("name", name)
                put("version", version)
                put("codec", codec)
                put("sample_rate", sampleRate)
                put("channels", channels)
                put("buffer_ms", bufferMs)
                put("requires_pin", requiresPin)
            }
            is ClientAccepted -> JSONObject().apply {
                put("type", "ClientAccepted")
                put("session_id", sessionId)
                put("codec", codec)
                put("sample_rate", sampleRate)
                put("channels", channels)
            }
            is AuthRequired -> JSONObject().apply {
                put("type", "AuthRequired")
                put("message", message)
            }
            is Error -> JSONObject().apply {
                put("type", "Error")
                put("message", message)
            }
            is TextMessage -> JSONObject().apply {
                put("type", "TextMessage")
                put("message_id", messageId)
                put("from_id", fromId)
                put("from_name", fromName)
                put("content", content)
                put("timestamp_us", timestampUs)
            }
            is TextMessageAck -> JSONObject().apply {
                put("type", "TextMessageAck")
                put("message_id", messageId)
            }
            is FileOffer -> JSONObject().apply {
                put("type", "FileOffer")
                put("transfer_id", transferId)
                put("from_id", fromId)
                put("from_name", fromName)
                put("file_name", fileName)
                put("file_size", fileSize)
                put("mime_type", mimeType)
                put("chunk_size", chunkSize)
                put("total_chunks", totalChunks)
            }
            is FileAccept -> JSONObject().apply {
                put("type", "FileAccept")
                put("transfer_id", transferId)
            }
            is FileReject -> JSONObject().apply {
                put("type", "FileReject")
                put("transfer_id", transferId)
                put("reason", reason)
            }
            is FileComplete -> JSONObject().apply {
                put("type", "FileComplete")
                put("transfer_id", transferId)
                put("checksum", checksum)
            }
            is FileError -> JSONObject().apply {
                put("type", "FileError")
                put("transfer_id", transferId)
                put("message", message)
            }
            is NotificationPost -> JSONObject().apply {
                put("type", "NotificationPost")
                put("notification_id", notificationId)
                put("app_package", appPackage)
                put("app_name", appName)
                put("title", title)
                put("content", content)
                put("timestamp_us", timestampUs)
            }
            is NotificationDismiss -> JSONObject().apply {
                put("type", "NotificationDismiss")
                put("notification_id", notificationId)
            }
            is TouchpadMove -> JSONObject().apply {
                put("type", "TouchpadMove")
                put("dx", dx.toDouble())
                put("dy", dy.toDouble())
                put("fingers", fingers)
            }
            is TouchpadGesture -> JSONObject().apply {
                put("type", "TouchpadGesture")
                put("gesture", gesture)
                put("dx", dx.toDouble())
                put("dy", dy.toDouble())
            }
            is KeyboardInput -> JSONObject().apply {
                put("type", "KeyboardInput")
                put("text", text)
                put("action", action)
            }
        }

        val jsonBytes = json.toString().toByteArray(Charsets.UTF_8)
        val buf = ByteBuffer.allocate(1 + 4 + jsonBytes.size)
        buf.put(Protocol.FRAME_TYPE_CONTROL)
        buf.putInt(jsonBytes.size)
        buf.put(jsonBytes)
        return buf.array()
    }

    companion object {
        fun deserialize(data: ByteArray): ControlMessage {
            val buf = ByteBuffer.wrap(data)
            buf.get() // skip type byte
            val len = buf.int
            val jsonBytes = ByteArray(len)
            buf.get(jsonBytes)
            val json = JSONObject(String(jsonBytes, Charsets.UTF_8))

            return when (json.getString("type")) {
                "ServerInfo" -> ServerInfo(
                    name = json.getString("name"),
                    version = json.getString("version"),
                    codec = json.getString("codec"),
                    sampleRate = json.getInt("sample_rate"),
                    channels = json.getInt("channels"),
                    bufferMs = json.getInt("buffer_ms"),
                    requiresPin = json.optBoolean("requires_pin", false)
                )
                "ClientAccepted" -> ClientAccepted(
                    sessionId = json.getString("session_id"),
                    codec = json.getString("codec"),
                    sampleRate = json.getInt("sample_rate"),
                    channels = json.getInt("channels")
                )
                "ClientJoin" -> ClientJoin(
                    clientName = json.getString("client_name"),
                    clientId = json.getString("client_id"),
                    pin = json.optString("pin", null)
                )
                "AuthRequired" -> AuthRequired(json.getString("message"))
                "Ping" -> Ping(json.getLong("timestamp_us"))
                "Pong" -> Pong(
                    json.getLong("ping_timestamp_us"),
                    json.getLong("pong_timestamp_us")
                )
                "Error" -> Error(json.getString("message"))
                "TextMessage" -> TextMessage(
                    messageId = json.getString("message_id"),
                    fromId = json.getString("from_id"),
                    fromName = json.getString("from_name"),
                    content = json.getString("content"),
                    timestampUs = json.getLong("timestamp_us")
                )
                "TextMessageAck" -> TextMessageAck(json.getString("message_id"))
                "FileOffer" -> FileOffer(
                    transferId = json.getString("transfer_id"),
                    fromId = json.getString("from_id"),
                    fromName = json.getString("from_name"),
                    fileName = json.getString("file_name"),
                    fileSize = json.getLong("file_size"),
                    mimeType = json.getString("mime_type"),
                    chunkSize = json.getInt("chunk_size"),
                    totalChunks = json.getInt("total_chunks")
                )
                "FileAccept" -> FileAccept(json.getString("transfer_id"))
                "FileReject" -> FileReject(
                    transferId = json.getString("transfer_id"),
                    reason = json.getString("reason")
                )
                "FileComplete" -> FileComplete(
                    transferId = json.getString("transfer_id"),
                    checksum = json.getString("checksum")
                )
                "FileError" -> FileError(
                    transferId = json.getString("transfer_id"),
                    message = json.getString("message")
                )
                "SetVolume" -> SetVolume(json.getDouble("volume").toFloat())
                "SetVolumeGroup" -> SetVolumeGroup(json.getString("group_name"))
                "NotificationPost" -> NotificationPost(
                    notificationId = json.getString("notification_id"),
                    appPackage = json.getString("app_package"),
                    appName = json.getString("app_name"),
                    title = json.getString("title"),
                    content = json.getString("content"),
                    timestampUs = json.getLong("timestamp_us")
                )
                "NotificationDismiss" -> NotificationDismiss(
                    notificationId = json.getString("notification_id")
                )
                "TouchpadMove" -> TouchpadMove(
                    dx = json.getDouble("dx").toFloat(),
                    dy = json.getDouble("dy").toFloat(),
                    fingers = json.getInt("fingers")
                )
                "TouchpadGesture" -> TouchpadGesture(
                    gesture = json.getString("gesture"),
                    dx = json.getDouble("dx").toFloat(),
                    dy = json.getDouble("dy").toFloat()
                )
                "KeyboardInput" -> KeyboardInput(
                    text = json.getString("text"),
                    action = json.getString("action")
                )
                else -> Error("Unknown message type: ${json.getString("type")}")
            }
        }
    }
}

/**
 * Reads a complete frame from an InputStream.
 * Blocks until a full frame is available.
 */
fun readFrame(input: DataInputStream): ByteArray {
    val frameType = input.readByte()

    return when (frameType) {
        Protocol.FRAME_TYPE_AUDIO -> {
            val header = ByteArray(Protocol.AUDIO_FRAME_REST_SIZE)
            input.readFully(header)

            val payloadLen = ByteBuffer.wrap(header, Protocol.AUDIO_PAYLOAD_LEN_OFFSET, 4).int
            val payload = ByteArray(payloadLen)
            input.readFully(payload)

            ByteBuffer.allocate(Protocol.AUDIO_FRAME_HEADER_SIZE + payloadLen).apply {
                put(frameType)
                put(header)
                put(payload)
            }.array()
        }

        Protocol.FRAME_TYPE_CONTROL -> {
            val msgLen = input.readInt()
            if (msgLen > Protocol.CONTROL_MESSAGE_MAX_SIZE) throw IllegalStateException("Control message too large: $msgLen")
            val msg = ByteArray(msgLen)
            input.readFully(msg)

            ByteBuffer.allocate(1 + 4 + msgLen).apply {
                put(frameType)
                putInt(msgLen)
                put(msg)
            }.array()
        }

        Protocol.FRAME_TYPE_CLOCK_SYNC -> {
            val rest = ByteArray(Protocol.CLOCK_SYNC_PAYLOAD_SIZE)
            input.readFully(rest)

            ByteBuffer.allocate(Protocol.CLOCK_SYNC_FRAME_SIZE).apply {
                put(frameType)
                put(rest)
            }.array()
        }

        Protocol.FRAME_TYPE_FILE_DATA -> {
            val header = ByteArray(Protocol.FILE_DATA_REST_SIZE)
            input.readFully(header)

            val chunkLen = ByteBuffer.wrap(header, Protocol.FILE_CHUNK_LEN_OFFSET, 4).int
            if (chunkLen > Protocol.FILE_CHUNK_MAX_SIZE) {
                throw IllegalStateException("File chunk too large: $chunkLen")
            }
            val payload = ByteArray(chunkLen)
            input.readFully(payload)

            ByteBuffer.allocate(Protocol.FILE_DATA_HEADER_SIZE + chunkLen).apply {
                put(frameType)
                put(header)
                put(payload)
            }.array()
        }

        else -> throw IllegalStateException("Unknown frame type: $frameType")
    }
}

fun nowUs(): Long = System.currentTimeMillis() * 1000L
