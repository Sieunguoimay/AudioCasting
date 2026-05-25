package com.devicelink.audio

/**
 * Interface for audio capture sources (microphone, system audio).
 */
interface AudioCaptureSource {
    val sourceName: String
    val isCapturing: Boolean

    /**
     * Start capturing audio. Calls [onChunk] with PCM 16-bit samples and timestamp.
     */
    fun start(sampleRate: Int, channels: Int, onChunk: (ShortArray, Long) -> Unit)

    fun stop()
    fun release()
}
