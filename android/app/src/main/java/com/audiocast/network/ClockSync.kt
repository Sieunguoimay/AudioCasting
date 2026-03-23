package com.audiocast.network

import android.util.Log

private const val TAG = "ClockSync"

/**
 * Client-side clock synchronization.
 * Maintains an estimate of the offset between local clock and server clock.
 */
class ClockSync {
    /** Estimated offset: serverTime = localTime + offsetUs */
    var offsetUs: Long = 0L
        private set

    var rttUs: Long = 0L
        private set

    var syncCount: Int = 0
        private set

    private val alpha = 0.3 // EMA weight

    val isSynced: Boolean get() = syncCount >= 3

    /**
     * Process a clock sync message from the server.
     * The server sent t1, we received at t2, we respond with t3, server receives at t4.
     * Since we're the client, we fill in t2 when we receive the sync request,
     * and t3 when we send the response.
     */
    fun processServerSync(serverT1: Long): ClockSyncMessage {
        val t2 = nowUs()
        val t3 = nowUs()
        return ClockSyncMessage(t1 = serverT1, t2 = t2, t3 = t3)
    }

    /**
     * Update with completed round-trip data.
     * t1: server send, t2: client receive, t3: client send, t4: server receive
     */
    fun update(t1: Long, t2: Long, t3: Long, t4: Long) {
        val rtt = (t4 - t1) - (t3 - t2)
        val offset = ((t2 - t1) + (t3 - t4)) / 2

        if (syncCount == 0) {
            this.rttUs = if (rtt < 0) -rtt else rtt
            this.offsetUs = offset
        } else {
            val absRtt = if (rtt < 0) -rtt else rtt
            this.rttUs = (alpha * absRtt + (1.0 - alpha) * this.rttUs).toLong()
            this.offsetUs = (alpha * offset + (1.0 - alpha) * this.offsetUs).toLong()
        }

        syncCount++
        Log.d(TAG, "Sync #$syncCount: offset=${offsetUs}us, rtt=${rttUs}us")
    }

    /**
     * Convert a server timestamp to local time
     */
    fun serverToLocalTime(serverTimeUs: Long): Long {
        return serverTimeUs - offsetUs
    }

    /**
     * Convert local time to server time
     */
    fun localToServerTime(localTimeUs: Long): Long {
        return localTimeUs + offsetUs
    }

    fun reset() {
        offsetUs = 0
        rttUs = 0
        syncCount = 0
    }
}
