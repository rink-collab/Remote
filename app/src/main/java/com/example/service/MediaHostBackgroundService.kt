package com.example.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.MainActivity

class MediaHostBackgroundService : Service() {

    private val TAG = "MediaHostBgService"

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START_HOST
        Log.d(TAG, "MediaHostBackgroundService onStartCommand action=$action")

        if (action == ACTION_STOP_HOST) {
            PeerMediaHostController.disconnect()
            stopSelf()
            return START_NOT_STICKY
        }

        try {
            // Check if caller or system expects a foreground notification
            val asForeground = intent?.getBooleanExtra(EXTRA_FOREGROUND, false) == true
            if (asForeground) {
                promoteToForeground()
            }

            // Start the actual P2P host discovery and signaling in background
            PeerMediaHostController.startHosting(applicationContext)
        } catch (e: Exception) {
            Log.e(TAG, "Error starting host in background service", e)
        }

        return START_STICKY
    }

    private fun promoteToForeground() {
        try {
            createNotificationChannel()
            val notification = createNotification()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start foreground", e)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Media Host Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Runs local media host server for P2P streaming"
                setShowBadge(false)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            manager?.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Peer Media Host Active")
            .setContentText("Broadcasting local media vault to client devices")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        Log.d(TAG, "MediaHostBackgroundService destroyed")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START_HOST = "com.example.service.START_MEDIA_HOST"
        const val ACTION_STOP_HOST = "com.example.service.STOP_MEDIA_HOST"
        const val EXTRA_FOREGROUND = "extra_foreground"
        const val CHANNEL_ID = "media_host_service_channel"
        const val NOTIFICATION_ID = 1001

        @Volatile
        var isRunning: Boolean = false
            private set
    }
}
