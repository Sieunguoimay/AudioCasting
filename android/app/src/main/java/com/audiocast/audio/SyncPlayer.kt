package com.audiocast.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import com.audiocast.network.AudioFrame
import com.audiocast.network.ClockSync
import com.audiocast.network.Protocol
import com.audiocast.network.nowUs
import kotlinx.coroutines.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.PriorityQueue

private const val TAG = "SyncPlayer"

/**
 * Synchronized audio player that uses clock-synced presentation timestamps
 * to ensure all clients play audio at the same wall-clock time.
 *
 * This is the Phase 3 upgrade over the basic AudioPlayer:
 * - Uses ClockSync to convert server timestamps to local time
 * - Schedules playback based on presentation timestamps
 * - Supports configurable per-device delay offset
 */
class SyncPlayer(
    private val sampleRate: Int = 48000,
    private val channels: Int = 2,
    private val clockSync: ClockSync,
    /** Additional delay offset in microseconds (for speaker distance compensation) */
    var delayOffsetUs: Long = 0
) {
    private var audioTrack: AudioTrack? = null
    private var opusDecoder: OpusDecoder? = null
    private var flacDecoder: FlacDecoder? = null
    private var playbackJob: Job? = null
    private var volume: Float = 1.0f

    /**
     * Frame stored for time-scheduled playback.
     */
    private data class TimedFrame(
        val localPlayTimeUs: Long,
        val sequence: Int,
        val samples: ShortArray
    ) : Comparable<TimedFrame> {
        override fun compareTo(other: TimedFrame): Int =
            localPlayTimeUs.compareTo(other.localPlayTimeUs)
    }

    private val frameQueue = PriorityQueue<TimedFrame>()
    private val lock = Any()

    /** Buffer target in microseconds */
    var bufferUs: Long = 50_000 // 50ms default

    var isPlaying: Boolean = false
        private set

    fun initialize(codec: String) {
        val channelConfig = if (channels == 1)
            AudioFormat.CHANNEL_OUT_MONO
        else
            AudioFormat.CHANNEL_OUT_STEREO

        val minBufSize = AudioTrack.getMinBufferSize(
            sampleRate, channelConfig, AudioFormat.ENCODING_PCM_16BIT
        )

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
            .setBufferSizeInBytes(maxOf(minBufSize * 2, sampleRate * channels * 2 / 10))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        when (codec.lowercase()) {
            "opus" -> opusDecoder = OpusDecoder(sampleRate, channels)
            "flac" -> flacDecoder = FlacDecoder(sampleRate, channels)
            "pcm" -> {
                opusDecoder = null
                flacDecoder = null
            }
        }

        Log.i(TAG, "SyncPlayer initialized: ${sampleRate}Hz, ${channels}ch, codec=$codec")
    }

    fun start() {
        if (isPlaying) return
        audioTrack?.play()
        isPlaying = true

        playbackJob = CoroutineScope(Dispatchers.Default).launch {
            Log.i(TAG, "Synchronized playback loop started")

            while (isActive && isPlaying) {
                val frame = synchronized(lock) {
                    val next = frameQueue.peek() ?: return@synchronized null
                    val now = nowUs()

                    when {
                        // Frame is due or overdue — play it
                        next.localPlayTimeUs <= now -> frameQueue.poll()
                        // Frame is in the near future — wait for it
                        next.localPlayTimeUs - now < 1000 -> frameQueue.poll() // within 1ms, close enough
                        // Too early — skip this cycle
                        else -> null
                    }
                }

                if (frame != null) {
                    audioTrack?.write(frame.samples, 0, frame.samples.size)
                } else {
                    delay(1)
                }
            }
        }
    }

    /**
     * Process an incoming audio frame with synchronized scheduling.
     */
    fun onAudioFrame(frame: AudioFrame) {
        val samples = decodeFrame(frame) ?: return

        // Apply volume
        if (volume < 1.0f) {
            for (i in samples.indices) {
                samples[i] = (samples[i] * volume).toInt().coerceIn(-32768, 32767).toShort()
            }
        }

        // Convert server timestamp to local play time
        val localPlayTime = if (clockSync.isSynced) {
            clockSync.serverToLocalTime(frame.timestampUs) + bufferUs + delayOffsetUs
        } else {
            // Not synced yet — play immediately with basic buffering
            nowUs() + bufferUs
        }

        synchronized(lock) {
            frameQueue.add(TimedFrame(localPlayTime, frame.sequence, samples))

            // Purge frames that are too old (>500ms behind)
            val cutoff = nowUs() - 500_000
            while (frameQueue.isNotEmpty() && frameQueue.peek()!!.localPlayTimeUs < cutoff) {
                frameQueue.poll()
            }
        }
    }

    private fun decodeFrame(frame: AudioFrame): ShortArray? {
        return when (frame.codec) {
            Protocol.CODEC_OPUS -> opusDecoder?.decode(frame.payload)
            Protocol.CODEC_FLAC -> flacDecoder?.decode(frame.payload)
            Protocol.CODEC_PCM -> {
                val shorts = ShortArray(frame.payload.size / 2)
                ByteBuffer.wrap(frame.payload).order(ByteOrder.LITTLE_ENDIAN)
                    .asShortBuffer().get(shorts)
                shorts
            }
            else -> null
        }
    }

    fun setVolume(vol: Float) {
        volume = vol.coerceIn(0f, 1f)
        audioTrack?.setVolume(volume)
    }

    fun stop() {
        isPlaying = false
        playbackJob?.cancel()
        audioTrack?.stop()
        synchronized(lock) { frameQueue.clear() }
    }

    fun release() {
        stop()
        audioTrack?.release()
        audioTrack = null
        opusDecoder?.release()
        opusDecoder = null
        flacDecoder?.release()
        flacDecoder = null
    }
}
