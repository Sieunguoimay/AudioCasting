package com.audiocast.network

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

    const val CODEC_OPUS: Byte = 1
    const val CODEC_FLAC: Byte = 2
    const val CODEC_PCM: Byte = 3
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
        val buf = ByteBuffer.allocate(1 + 4 + 8 + 1 + 4 + 1 + 4 + payload.size)
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
        val buf = ByteBuffer.allocate(25)
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
            // Read fixed header: seq(4) + ts(8) + codec(1) + sr(4) + ch(1) + len(4) = 22 bytes
            val header = ByteArray(22)
            input.readFully(header)

            // Extract payload length from last 4 bytes
            val payloadLen = ByteBuffer.wrap(header, 18, 4).int
            val payload = ByteArray(payloadLen)
            input.readFully(payload)

            // Assemble full frame
            ByteBuffer.allocate(1 + 22 + payloadLen).apply {
                put(frameType)
                put(header)
                put(payload)
            }.array()
        }

        Protocol.FRAME_TYPE_CONTROL -> {
            val msgLen = input.readInt()
            if (msgLen > 1_000_000) throw IllegalStateException("Control message too large: $msgLen")
            val msg = ByteArray(msgLen)
            input.readFully(msg)

            ByteBuffer.allocate(1 + 4 + msgLen).apply {
                put(frameType)
                putInt(msgLen)
                put(msg)
            }.array()
        }

        Protocol.FRAME_TYPE_CLOCK_SYNC -> {
            val rest = ByteArray(24)
            input.readFully(rest)

            ByteBuffer.allocate(25).apply {
                put(frameType)
                put(rest)
            }.array()
        }

        else -> throw IllegalStateException("Unknown frame type: $frameType")
    }
}

fun nowUs(): Long = System.currentTimeMillis() * 1000L
