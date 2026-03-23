package com.audiocast.audio

import com.audiocast.network.AudioFrame
import com.audiocast.network.Protocol
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicInteger

/**
 * Encodes raw PCM ShortArray samples into AudioFrame for streaming.
 * Uses PCM codec (raw 16-bit LE) for maximum compatibility.
 */
class AudioEncoder(
    private val sampleRate: Int = 48000,
    private val channels: Int = 2
) {
    private val sequence = AtomicInteger(0)

    /**
     * Encode PCM samples into an AudioFrame ready for transmission.
     */
    fun encode(samples: ShortArray, timestampUs: Long): AudioFrame {
        val seq = sequence.getAndIncrement()

        // Convert ShortArray to little-endian byte array
        val payload = ByteArray(samples.size * 2)
        ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(samples)

        return AudioFrame(
            sequence = seq,
            timestampUs = timestampUs,
            codec = Protocol.CODEC_PCM,
            sampleRate = sampleRate,
            channels = channels.toByte(),
            payload = payload
        )
    }

    fun reset() {
        sequence.set(0)
    }
}
