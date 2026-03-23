package com.audiocast.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.audiocast.MainActivity

private const val TAG = "AudioService"
private const val CHANNEL_ID = "audiocast_playback"
private const val NOTIFICATION_ID = 1

/**
 * Foreground service for background audio playback.
 * Acquires Wi-Fi and wake locks to keep the connection alive.
 */
class AudioService : Service() {

    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        acquireLocks()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val serverName = intent?.getStringExtra("server_name") ?: "AudioCast"
        val codec = intent?.getStringExtra("codec") ?: "opus"
        val sampleRate = intent?.getIntExtra("sample_rate", 48000) ?: 48000

        val notification = buildNotification(serverName, codec, sampleRate)
        startForeground(NOTIFICATION_ID, notification)

        Log.i(TAG, "Service started for server: $serverName")
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        releaseLocks()
        Log.i(TAG, "Service destroyed")
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Audio Playback",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "AudioCast audio streaming"
                setShowBadge(false)
            }

            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(serverName: String, codec: String, sampleRate: Int): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("AudioCast")
            .setContentText("Streaming from $serverName (${codec.uppercase()} ${sampleRate}Hz)")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun acquireLocks() {
        // Wi-Fi lock — prevents Wi-Fi from going to sleep
        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        wifiLock = wifiManager.createWifiLock(
            WifiManager.WIFI_MODE_FULL_HIGH_PERF,
            "AudioCast:WifiLock"
        ).apply {
            setReferenceCounted(false)
            acquire()
        }

        // Wake lock — keeps CPU running for audio processing
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "AudioCast:WakeLock"
        ).apply {
            setReferenceCounted(false)
            acquire()
        }

        Log.i(TAG, "Wi-Fi and wake locks acquired")
    }

    private fun releaseLocks() {
        wifiLock?.let {
            if (it.isHeld) it.release()
        }
        wifiLock = null

        wakeLock?.let {
            if (it.isHeld) it.release()
        }
        wakeLock = null

        Log.i(TAG, "Locks released")
    }
}
