package com.audiocast.audio

import android.annotation.TargetApi
import android.media.*
import android.media.projection.MediaProjection
import android.os.Build
import android.util.Log
import com.audiocast.network.nowUs

private const val TAG = "SystemAudioCapture"

/**
 * System audio capture using MediaProjection + AudioPlaybackCapture.
 * Only available on API 29+ (Android 10).
 * Requires a MediaProjection token obtained from user consent.
 */
@TargetApi(Build.VERSION_CODES.Q)
class SystemAudioCapture(
    private val mediaProjection: MediaProjection
) : AudioCaptureSource {
    override val sourceName = "System Audio"
    override var isCapturing = false
        private set

    private var audioRecord: AudioRecord? = null
    private var captureThread: Thread? = null

    override fun start(sampleRate: Int, channels: Int, onChunk: (ShortArray, Long) -> Unit) {
        if (isCapturing) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            Log.e(TAG, "System audio capture requires API 29+")
            return
        }

        val channelConfig = if (channels == 1)
            AudioFormat.CHANNEL_IN_MONO
        else
            AudioFormat.CHANNEL_IN_STEREO

        val frameSamples = (sampleRate * channels * 20) / 1000

        try {
            val playbackConfig = AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()

            val format = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(sampleRate)
                .setChannelMask(channelConfig)
                .build()

            val minBufSize = AudioRecord.getMinBufferSize(
                sampleRate, channelConfig, AudioFormat.ENCODING_PCM_16BIT
            )
            val bufferSize = maxOf(minBufSize * 2, frameSamples * 2)

            audioRecord = AudioRecord.Builder()
                .setAudioPlaybackCaptureConfig(playbackConfig)
                .setAudioFormat(format)
                .setBufferSizeInBytes(bufferSize)
                .build()

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord failed to initialize for system audio")
                audioRecord?.release()
                audioRecord = null
                return
            }

            audioRecord?.startRecording()
            isCapturing = true

            captureThread = Thread({
                Log.i(TAG, "System audio capture started: ${sampleRate}Hz, ${channels}ch")
                val buffer = ShortArray(frameSamples)

                while (isCapturing) {
                    val read = audioRecord?.read(buffer, 0, frameSamples) ?: -1
                    if (read > 0) {
                        val samples = if (read == frameSamples) buffer else buffer.copyOf(read)
                        onChunk(samples, nowUs())
                    } else if (read < 0) {
                        Log.e(TAG, "AudioRecord read error: $read")
                        break
                    }
                }

                Log.i(TAG, "System audio capture thread ended")
            }, "SystemAudioCapture").also { it.start() }

        } catch (e: Exception) {
            Log.e(TAG, "Failed to start system audio capture: ${e.message}")
        }
    }

    override fun stop() {
        isCapturing = false
        try {
            audioRecord?.stop()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping: ${e.message}")
        }
        captureThread?.join(2000)
        captureThread = null
    }

    override fun release() {
        stop()
        audioRecord?.release()
        audioRecord = null
        mediaProjection.stop()
        Log.i(TAG, "SystemAudioCapture released")
    }
}
