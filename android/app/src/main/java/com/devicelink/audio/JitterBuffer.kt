package com.devicelink.audio

import android.util.Log
import java.util.PriorityQueue

private const val TAG = "JitterBuffer"

/**
 * Adaptive jitter buffer that reorders and smooths audio frame delivery.
 * Frames are stored by sequence number and released in order.
 *
 * When adaptive mode is enabled, the buffer automatically adjusts its depth
 * based on observed network jitter and packet loss.
 */
class JitterBuffer(
    /** Target buffer depth in milliseconds */
    var bufferMs: Int = 50,
    /** Sample rate for timing calculations */
    private val sampleRate: Int = 48000,
    /** Channels count */
    private val channels: Int = 2,
    /** Enable adaptive buffer sizing */
    var adaptiveMode: Boolean = true
) {
    data class BufferedFrame(
        val sequence: Int,
        val timestampUs: Long,
        val samples: ShortArray
    ) : Comparable<BufferedFrame> {
        override fun compareTo(other: BufferedFrame): Int = sequence.compareTo(other.sequence)
    }

    private val queue = PriorityQueue<BufferedFrame>()
    private var nextExpectedSeq: Int = -1
    private var isPreBuffering = true
    private var stats = BufferStats()

    // Adaptive buffer state
    private var minBufferMs = 10
    private var maxBufferMs = 200
    private var jitterWindowMs = mutableListOf<Long>() // recent inter-arrival jitter values
    private var lastArrivalTimeMs: Long = 0
    private var lastExpectedIntervalMs: Long = 20 // default frame interval
    private var lossWindowCount = 0 // losses in current window
    private var windowFrameCount = 0 // frames in current window
    private val adaptiveWindowSize = 50 // frames per adaptation window

    data class BufferStats(
        var framesReceived: Long = 0,
        var framesPlayed: Long = 0,
        var framesDropped: Long = 0,
        var framesLost: Long = 0,
        var currentDepthMs: Int = 0,
        var maxDepthMs: Int = 0,
        var adaptiveTargetMs: Int = 0,
        var networkJitterMs: Float = 0f
    )

    /**
     * Push a decoded audio frame into the buffer.
     */
    @Synchronized
    fun push(sequence: Int, timestampUs: Long, samples: ShortArray) {
        stats.framesReceived++

        // Drop frames that are too old (already played)
        if (nextExpectedSeq >= 0 && sequence < nextExpectedSeq) {
            stats.framesDropped++
            return
        }

        queue.add(BufferedFrame(sequence, timestampUs, samples))

        // Update depth stats
        val depthMs = (queue.size * samples.size / channels * 1000) / sampleRate
        stats.currentDepthMs = depthMs
        if (depthMs > stats.maxDepthMs) stats.maxDepthMs = depthMs

        // Track jitter for adaptive mode
        if (adaptiveMode) {
            trackJitter()
        }
    }

    /**
     * Track inter-arrival jitter for adaptive buffer sizing.
     */
    private fun trackJitter() {
        val now = System.currentTimeMillis()
        if (lastArrivalTimeMs > 0) {
            val interArrival = now - lastArrivalTimeMs
            val jitter = kotlin.math.abs(interArrival - lastExpectedIntervalMs)
            jitterWindowMs.add(jitter)

            // Keep sliding window of recent jitter values
            if (jitterWindowMs.size > 100) {
                jitterWindowMs.removeAt(0)
            }
        }
        lastArrivalTimeMs = now

        windowFrameCount++
        if (windowFrameCount >= adaptiveWindowSize) {
            adaptBuffer()
            windowFrameCount = 0
            lossWindowCount = 0
        }
    }

    /**
     * Adapt buffer size based on network conditions.
     * Increases buffer when jitter/loss is high, decreases when stable.
     */
    private fun adaptBuffer() {
        if (jitterWindowMs.isEmpty()) return

        // Calculate average jitter
        val avgJitter = jitterWindowMs.average().toFloat()
        stats.networkJitterMs = avgJitter

        // Calculate 95th percentile jitter
        val sorted = jitterWindowMs.sorted()
        val p95Jitter = sorted[(sorted.size * 0.95).toInt().coerceAtMost(sorted.size - 1)]

        // Loss rate in current window
        val lossRate = if (windowFrameCount > 0) lossWindowCount.toFloat() / windowFrameCount else 0f

        // Target buffer = 2x p95 jitter + loss penalty
        var targetMs = (p95Jitter * 2).toInt()
        if (lossRate > 0.01f) {
            targetMs += (lossRate * 100).toInt() // Add ms per % loss
        }
        targetMs = targetMs.coerceIn(minBufferMs, maxBufferMs)

        // Smooth adjustment: move 25% toward target each window
        val current = bufferMs
        val adjusted = current + ((targetMs - current) * 0.25f).toInt()
        val newBuffer = adjusted.coerceIn(minBufferMs, maxBufferMs)

        if (newBuffer != bufferMs) {
            Log.d(TAG, "Adaptive buffer: ${bufferMs}ms -> ${newBuffer}ms (jitter=${avgJitter.toInt()}ms, p95=${p95Jitter}ms, loss=${(lossRate*100).toInt()}%)")
            bufferMs = newBuffer
        }

        stats.adaptiveTargetMs = targetMs
    }

    /**
     * Pull the next frame from the buffer.
     * Returns null if the buffer isn't ready yet (pre-buffering)
     * or if no frames are available.
     */
    @Synchronized
    fun pull(): BufferedFrame? {
        if (queue.isEmpty()) return null

        // Pre-buffering: wait until we have enough frames
        if (isPreBuffering) {
            val firstFrame = queue.peek() ?: return null
            val samplesPerFrame = firstFrame.samples.size / channels
            val framesNeeded = (bufferMs * sampleRate) / (samplesPerFrame * 1000)

            if (queue.size < framesNeeded) {
                return null // Still buffering
            }

            isPreBuffering = false
            Log.i(TAG, "Pre-buffering complete, depth=${queue.size} frames")
        }

        val frame = queue.poll() ?: return null

        // Track sequence gaps (packet loss)
        if (nextExpectedSeq >= 0 && frame.sequence > nextExpectedSeq) {
            val lost = frame.sequence - nextExpectedSeq
            stats.framesLost += lost
            lossWindowCount += lost
            if (lost > 1) {
                Log.w(TAG, "Lost $lost frames (seq $nextExpectedSeq-${frame.sequence - 1})")
            }
        }

        nextExpectedSeq = frame.sequence + 1
        stats.framesPlayed++

        // Update depth
        val depthMs = if (queue.isNotEmpty() && frame.samples.isNotEmpty()) {
            val samplesPerFrame = frame.samples.size / channels
            (queue.size * samplesPerFrame * 1000) / sampleRate
        } else 0
        stats.currentDepthMs = depthMs

        return frame
    }

    @Synchronized
    fun clear() {
        queue.clear()
        nextExpectedSeq = -1
        isPreBuffering = true
        stats = BufferStats()
        jitterWindowMs.clear()
        lastArrivalTimeMs = 0
        windowFrameCount = 0
        lossWindowCount = 0
    }

    @Synchronized
    fun getStats(): BufferStats = stats.copy()

    @Synchronized
    fun size(): Int = queue.size
}
