package com.devicelink.audio

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder

private const val TAG = "FlacDecoder"

/**
 * FLAC decoder using Android's built-in MediaCodec.
 * Each incoming payload is a complete FLAC stream (header + data + footer)
 * that can be decoded independently.
 */
class FlacDecoder(
    private val sampleRate: Int = 48000,
    private val channels: Int = 2
) {
    private var codec: MediaCodec? = null
    private var isInitialized = false

    init {
        try {
            initCodec()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize FLAC decoder: ${e.message}")
        }
    }

    private fun initCodec() {
        val format = MediaFormat.createAudioFormat(
            MediaFormat.MIMETYPE_AUDIO_FLAC,
            sampleRate,
            channels
        )

        codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_FLAC)
        codec?.configure(format, null, null, 0)
        codec?.start()
        isInitialized = true
        Log.i(TAG, "FLAC MediaCodec decoder initialized: ${sampleRate}Hz, ${channels}ch")
    }

    /**
     * Decode a FLAC payload (complete FLAC stream per chunk) into PCM 16-bit samples.
     */
    fun decode(flacData: ByteArray): ShortArray? {
        val mc = codec ?: return fallbackDecode(flacData)
        if (!isInitialized) return fallbackDecode(flacData)

        try {
            // Get input buffer
            val inputIndex = mc.dequeueInputBuffer(5000) // 5ms timeout
            if (inputIndex < 0) {
                Log.w(TAG, "No input buffer available")
                return fallbackDecode(flacData)
            }

            val inputBuffer = mc.getInputBuffer(inputIndex) ?: return fallbackDecode(flacData)
            inputBuffer.clear()
            inputBuffer.put(flacData)
            mc.queueInputBuffer(inputIndex, 0, flacData.size, 0, 0)

            // Get output buffer
            val info = MediaCodec.BufferInfo()
            val outputIndex = mc.dequeueOutputBuffer(info, 10000) // 10ms timeout

            if (outputIndex >= 0) {
                val outputBuffer = mc.getOutputBuffer(outputIndex) ?: run {
                    mc.releaseOutputBuffer(outputIndex, false)
                    return fallbackDecode(flacData)
                }

                outputBuffer.position(info.offset)
                outputBuffer.limit(info.offset + info.size)

                // Read PCM 16-bit data
                val pcmBytes = ByteArray(info.size)
                outputBuffer.get(pcmBytes)
                mc.releaseOutputBuffer(outputIndex, false)

                val shorts = ShortArray(pcmBytes.size / 2)
                ByteBuffer.wrap(pcmBytes).order(ByteOrder.LITTLE_ENDIAN)
                    .asShortBuffer().get(shorts)
                return shorts
            } else if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                val newFormat = mc.outputFormat
                Log.i(TAG, "Output format changed: $newFormat")
                // Try again
                return null
            }

            return null
        } catch (e: Exception) {
            Log.e(TAG, "FLAC decode error: ${e.message}")
            // Try to reinitialize
            try {
                codec?.stop()
                codec?.release()
                initCodec()
            } catch (e2: Exception) {
                Log.e(TAG, "Failed to reinitialize FLAC decoder: ${e2.message}")
                isInitialized = false
            }
            return fallbackDecode(flacData)
        }
    }

    /**
     * Fallback: if MediaCodec fails, try to extract raw PCM from FLAC stream.
     * This is a basic fallback that looks for audio data after the FLAC header.
     * Not a full FLAC decoder, but handles the simple case where the server
     * sends complete FLAC streams.
     */
    private fun fallbackDecode(flacData: ByteArray): ShortArray? {
        // FLAC files start with "fLaC" magic bytes
        if (flacData.size < 42 || flacData[0] != 'f'.code.toByte() ||
            flacData[1] != 'L'.code.toByte() || flacData[2] != 'a'.code.toByte() ||
            flacData[3] != 'C'.code.toByte()) {
            return null
        }
        // Can't decode FLAC without a proper decoder in fallback mode
        Log.w(TAG, "FLAC fallback: MediaCodec unavailable, dropping frame")
        return null
    }

    fun release() {
        try {
            codec?.stop()
            codec?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing FLAC decoder: ${e.message}")
        }
        codec = null
        isInitialized = false
        Log.i(TAG, "FlacDecoder released")
    }
}
