package com.audiocast.audio

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.core.content.ContextCompat
import com.audiocast.network.nowUs

private const val TAG = "MicCapture"

/**
 * Microphone audio capture using AudioRecord.
 * Works on API 24+, requires RECORD_AUDIO permission.
 */
class MicCapture : AudioCaptureSource {
    override val sourceName = "Microphone"
    override var isCapturing = false
        private set

    private var audioRecord: AudioRecord? = null
    private var captureThread: Thread? = null

    override fun start(sampleRate: Int, channels: Int, onChunk: (ShortArray, Long) -> Unit) {
        if (isCapturing) return

        val channelConfig = if (channels == 1)
            AudioFormat.CHANNEL_IN_MONO
        else
            AudioFormat.CHANNEL_IN_STEREO

        val minBufSize = AudioRecord.getMinBufferSize(
            sampleRate, channelConfig, AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBufSize == AudioRecord.ERROR || minBufSize == AudioRecord.ERROR_BAD_VALUE) {
            Log.e(TAG, "Invalid AudioRecord buffer size: $minBufSize")
            return
        }

        // 20ms frame size
        val frameSamples = (sampleRate * channels * 20) / 1000
        val bufferSize = maxOf(minBufSize * 2, frameSamples * 2)

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                sampleRate,
                channelConfig,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord failed to initialize")
                audioRecord?.release()
                audioRecord = null
                return
            }

            audioRecord?.startRecording()
            isCapturing = true

            captureThread = Thread({
                Log.i(TAG, "Capture started: ${sampleRate}Hz, ${channels}ch, frame=$frameSamples samples")
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

                Log.i(TAG, "Capture thread ended")
            }, "MicCapture").also { it.start() }

        } catch (e: SecurityException) {
            Log.e(TAG, "RECORD_AUDIO permission not granted: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start mic capture: ${e.message}")
        }
    }

    override fun stop() {
        isCapturing = false
        try {
            audioRecord?.stop()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping AudioRecord: ${e.message}")
        }
        captureThread?.join(2000)
        captureThread = null
    }

    override fun release() {
        stop()
        audioRecord?.release()
        audioRecord = null
        Log.i(TAG, "MicCapture released")
    }
}
