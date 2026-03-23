package com.audiocast.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import com.audiocast.network.AudioFrame
import com.audiocast.network.ClockSync
import com.audiocast.network.Protocol
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.SharedFlow
import java.nio.ByteBuffer
import java.nio.ByteOrder

private const val TAG = "AudioPlayer"

/**
 * Audio playback engine.
 * Receives encoded audio frames, decodes them, buffers via JitterBuffer,
 * and plays through Android AudioTrack.
 */
class AudioPlayer(
    private val sampleRate: Int = 48000,
    private val channels: Int = 2,
    bufferMs: Int = 50
) {
    private var audioTrack: AudioTrack? = null
    private var opusDecoder: OpusDecoder? = null
    private var flacDecoder: FlacDecoder? = null
    private val jitterBuffer = JitterBuffer(bufferMs, sampleRate, channels)
    private var playbackJob: Job? = null
    private var volume: Float = 1.0f

    var isPlaying: Boolean = false
        private set

    /**
     * Initialize the audio player with the given codec and parameters.
     */
    fun initialize(codec: String) {
        val channelConfig = if (channels == 1)
            AudioFormat.CHANNEL_OUT_MONO
        else
            AudioFormat.CHANNEL_OUT_STEREO

        val minBufSize = AudioTrack.getMinBufferSize(
            sampleRate,
            channelConfig,
            AudioFormat.ENCODING_PCM_16BIT
        )

        // Use at least 2x minimum buffer for smoother playback
        val bufferSize = maxOf(minBufSize * 2, sampleRate * channels * 2 / 10) // at least 100ms

        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setChannelMask(channelConfig)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build()
            )
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        // Initialize decoder based on codec
        when (codec.lowercase()) {
            "opus" -> {
                opusDecoder = OpusDecoder(sampleRate, channels)
            }
            "flac" -> {
                flacDecoder = FlacDecoder(sampleRate, channels)
            }
            "pcm" -> {
                // No decoder needed for PCM
                opusDecoder = null
                flacDecoder = null
            }
        }

        Log.i(TAG, "AudioPlayer initialized: ${sampleRate}Hz, ${channels}ch, codec=$codec, buf=$bufferSize")
    }

    /**
     * Start playback. Begins consuming from jitter buffer and writing to AudioTrack.
     */
    fun start() {
        if (isPlaying) return

        audioTrack?.play()
        isPlaying = true

        playbackJob = CoroutineScope(Dispatchers.Default).launch {
            Log.i(TAG, "Playback loop started")
            while (isActive && isPlaying) {
                val frame = jitterBuffer.pull()
                if (frame != null) {
                    audioTrack?.write(frame.samples, 0, frame.samples.size)
                } else {
                    // No frame available — wait briefly
                    delay(1)
                }
            }
            Log.i(TAG, "Playback loop ended")
        }
    }

    /**
     * Process an incoming audio frame: decode and push to jitter buffer.
     */
    private var frameCount = 0L

    fun onAudioFrame(frame: AudioFrame) {
        frameCount++
        if (frameCount % 250 == 1L) {
            Log.i(TAG, "Frame #$frameCount received: codec=${frame.codec}, payload=${frame.payload.size} bytes, seq=${frame.sequence}")
        }

        val samples = when (frame.codec) {
            Protocol.CODEC_OPUS -> {
                val decoded = opusDecoder?.decode(frame.payload)
                if (decoded == null && frameCount % 50 == 0L) {
                    Log.w(TAG, "Opus decode returned null for frame #$frameCount")
                }
                decoded
            }
            Protocol.CODEC_FLAC -> {
                val decoded = flacDecoder?.decode(frame.payload)
                if (decoded == null && frameCount % 50 == 0L) {
                    Log.w(TAG, "FLAC decode returned null for frame #$frameCount")
                }
                decoded
            }
            Protocol.CODEC_PCM -> {
                pcmBytesToShorts(frame.payload)
            }
            else -> {
                Log.w(TAG, "Unknown codec: ${frame.codec}")
                null
            }
        }

        if (samples != null) {
            if (frameCount % 250 == 1L) {
                Log.i(TAG, "Decoded ${samples.size} samples, buffer depth=${jitterBuffer.size()}")
            }
            // Apply volume
            if (volume < 1.0f) {
                for (i in samples.indices) {
                    samples[i] = (samples[i] * volume).toInt().coerceIn(-32768, 32767).toShort()
                }
            }

            jitterBuffer.push(frame.sequence, frame.timestampUs, samples)
        }
    }

    private fun pcmBytesToShorts(bytes: ByteArray): ShortArray {
        val shorts = ShortArray(bytes.size / 2)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts)
        return shorts
    }

    fun setVolume(vol: Float) {
        volume = vol.coerceIn(0f, 1f)
        audioTrack?.setVolume(volume)
    }

    fun setBufferMs(ms: Int) {
        jitterBuffer.bufferMs = ms
    }

    fun setAdaptiveMode(enabled: Boolean) {
        jitterBuffer.adaptiveMode = enabled
    }

    fun getBufferStats(): JitterBuffer.BufferStats = jitterBuffer.getStats()

    fun stop() {
        isPlaying = false
        playbackJob?.cancel()
        playbackJob = null
        audioTrack?.stop()
        jitterBuffer.clear()
        Log.i(TAG, "Playback stopped")
    }

    fun release() {
        stop()
        audioTrack?.release()
        audioTrack = null
        opusDecoder?.release()
        opusDecoder = null
        flacDecoder?.release()
        flacDecoder = null
        Log.i(TAG, "AudioPlayer released")
    }
}
