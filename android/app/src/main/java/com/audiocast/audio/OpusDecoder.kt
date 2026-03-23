package com.audiocast.audio

import android.util.Log

private const val TAG = "OpusDecoder"

/**
 * Stub Opus decoder — currently not used since we stream PCM.
 * MediaCodec Opus decoding is unreliable on many devices.
 * TODO: Use a native libopus JNI wrapper for proper Opus support.
 */
class OpusDecoder(
    private val sampleRate: Int = 48000,
    private val channels: Int = 2
) {
    init {
        Log.i(TAG, "OpusDecoder stub initialized (PCM mode recommended)")
    }

    fun decode(opusData: ByteArray): ShortArray? {
        // MediaCodec Opus decoding doesn't work reliably on all devices
        Log.w(TAG, "Opus decoding not supported — use PCM codec on server")
        return null
    }

    fun release() {
        Log.i(TAG, "OpusDecoder released")
    }
}
