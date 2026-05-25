package com.devicelink.service

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.devicelink.network.AudioClient
import com.devicelink.network.ControlMessage

class NotificationRelayService : NotificationListenerService() {

    companion object {
        private const val TAG = "NotificationRelay"
        var audioClient: AudioClient? = null
        var enabled: Boolean = false

        fun isPermissionGranted(context: Context): Boolean {
            val cn = ComponentName(context, NotificationRelayService::class.java)
            val flat = android.provider.Settings.Secure.getString(
                context.contentResolver,
                "enabled_notification_listeners"
            )
            return flat != null && flat.contains(cn.flattenToString())
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (!enabled) return
        val client = audioClient ?: return

        val notification = sbn.notification ?: return
        val extras = notification.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""

        // Skip empty notifications and own notifications
        if (title.isEmpty() && text.isEmpty()) return
        if (sbn.packageName == packageName) return

        val appName = try {
            packageManager.getApplicationLabel(
                packageManager.getApplicationInfo(sbn.packageName, 0)
            ).toString()
        } catch (e: PackageManager.NameNotFoundException) {
            sbn.packageName
        }

        val msg = ControlMessage.NotificationPost(
            notificationId = sbn.key,
            appPackage = sbn.packageName,
            appName = appName,
            title = title,
            content = text,
            timestampUs = sbn.postTime * 1000
        )

        try {
            client.sendBytes(msg.serialize())
            Log.d(TAG, "Relayed notification from $appName: $title")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to relay notification: ${e.message}")
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (!enabled) return
        val client = audioClient ?: return

        val msg = ControlMessage.NotificationDismiss(
            notificationId = sbn.key
        )

        try {
            client.sendBytes(msg.serialize())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to relay dismiss: ${e.message}")
        }
    }
}
