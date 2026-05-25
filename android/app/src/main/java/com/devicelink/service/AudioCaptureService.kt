package com.devicelink.service

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
import com.devicelink.MainActivity

private const val TAG = "AudioCaptureService"
private const val CHANNEL_ID = "devicelink_sender"
private const val NOTIFICATION_ID = 2

/**
 * Foreground service for audio capture and streaming in sender mode.
 * Holds wifi and wake locks to keep the broadcast alive.
 */
class AudioCaptureService : Service() {

    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        acquireLocks()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val serverName = intent?.getStringExtra("server_name") ?: "DeviceLink Sender"
        val clientCount = intent?.getIntExtra("client_count", 0) ?: 0

        val notification = buildNotification(serverName, clientCount)
        startForeground(NOTIFICATION_ID, notification)

        Log.i(TAG, "Capture service started: $serverName")
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        releaseLocks()
        Log.i(TAG, "Capture service destroyed")
        super.onDestroy()
    }

    fun updateNotification(serverName: String, clientCount: Int) {
        val notification = buildNotification(serverName, clientCount)
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Audio Broadcasting",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "DeviceLink audio broadcasting"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(serverName: String, clientCount: Int): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("DeviceLink Broadcasting")
            .setContentText("$serverName — $clientCount client(s) connected")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun acquireLocks() {
        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        wifiLock = wifiManager.createWifiLock(
            WifiManager.WIFI_MODE_FULL_HIGH_PERF,
            "DeviceLink:SenderWifiLock"
        ).apply {
            setReferenceCounted(false)
            acquire()
        }

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "DeviceLink:SenderWakeLock"
        ).apply {
            setReferenceCounted(false)
            acquire()
        }

        Log.i(TAG, "Locks acquired")
    }

    private fun releaseLocks() {
        wifiLock?.let { if (it.isHeld) it.release() }
        wifiLock = null
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        Log.i(TAG, "Locks released")
    }
}
