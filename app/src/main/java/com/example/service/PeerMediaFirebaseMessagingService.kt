package com.example.service

import android.content.Intent
import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/**
 * Firebase Cloud Messaging Service that listens for remote push messages to wake up
 * and start the MediaHostBackgroundService on demand.
 */
class PeerMediaFirebaseMessagingService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.d(TAG, "New FCM Registration Token generated: $token")
        PeerMediaHostController.updateFcmToken(token)
        PeerMediaHostController.subscribeToTopic("all_users")
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)
        Log.d(TAG, "FCM Push received from: ${remoteMessage.from}")

        val data = remoteMessage.data
        val action = data["action"] ?: data["cmd"] ?: "start_host"
        val senderDevice = data["sender"] ?: "Remote Client"

        Log.d(TAG, "FCM action: $action, sender: $senderDevice")

        when (action.lowercase()) {
            "stop_host", "stop" -> {
                Log.d(TAG, "FCM request to stop host")
                PeerMediaHostController.disconnect()
            }
            else -> {
                // Default: Start hosting upon receiving FCM push
                Log.d(TAG, "FCM request to start host from $senderDevice. Waking up service...")
                try {
                    // 1. Immediately start hosting in the background controller
                    PeerMediaHostController.startHosting(applicationContext)

                    // 2. Start the background service to keep process active
                    val serviceIntent = Intent(applicationContext, MediaHostBackgroundService::class.java).apply {
                        this.action = MediaHostBackgroundService.ACTION_START_HOST
                    }
                    applicationContext.startService(serviceIntent)
                } catch (e: Exception) {
                    Log.e(TAG, "Error starting host service from FCM push", e)
                }
            }
        }
    }

    companion object {
        private const val TAG = "PeerMediaFCM"
    }
}
