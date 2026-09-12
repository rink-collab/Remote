package com.example.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.util.Log

/**
 * Battery-friendly BroadcastReceiver that handles:
 * 1. Device boot (BOOT_COMPLETED, QUICKBOOT_POWERON)
 * 2. Periodic AlarmManager checks (every 1 hour)
 *
 * It checks whether MediaHostBackgroundService is already active.
 * If not active, it starts the service.
 * It also maintains the 1-hour alarm schedule without battery drain.
 */
class MediaHostBootAndAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action
        Log.d(TAG, "onReceive triggered with action: $action")

        val isServiceRunning = MediaHostBackgroundService.isRunning || PeerMediaHostController.isHostingActive()
        Log.d(TAG, "Current host service running status: $isServiceRunning")

        if (!isServiceRunning) {
            Log.d(TAG, "Service is NOT running. Starting MediaHostBackgroundService...")
            try {
                val serviceIntent = Intent(context, MediaHostBackgroundService::class.java).apply {
                    this.action = MediaHostBackgroundService.ACTION_START_HOST
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    // Start service gracefully in background
                    context.startService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start MediaHostBackgroundService from receiver", e)
            }
        } else {
            Log.d(TAG, "Service is already actively running. No start needed.")
        }

        // Always ensure the next 1-hour battery-friendly alarm is scheduled
        schedulePeriodicCheck(context)
    }

    companion object {
        private const val TAG = "MediaHostBootAlarmRcvr"
        const val ACTION_ALARM_CHECK = "com.example.service.ACTION_ALARM_CHECK"
        private const val REQUEST_CODE = 4040

        /**
         * Schedules an inexact 1-hour alarm.
         * Using ELAPSED_REALTIME with setInexactRepeating minimizes battery consumption
         * by allowing Android's Doze mode and OS to batch wakeups with other system alarms.
         */
        fun schedulePeriodicCheck(context: Context) {
            try {
                val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return

                val intent = Intent(context, MediaHostBootAndAlarmReceiver::class.java).apply {
                    action = ACTION_ALARM_CHECK
                }

                val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                } else {
                    PendingIntent.FLAG_UPDATE_CURRENT
                }

                val pendingIntent = PendingIntent.getBroadcast(
                    context,
                    REQUEST_CODE,
                    intent,
                    flags
                )

                // Production periodic interval: 2 hours (7,200,000 ms)
                val intervalMillis = 2 * 60 * 60 * 1000L // 2 hours
                val triggerAtMillis = SystemClock.elapsedRealtime() + intervalMillis

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    alarmManager.setAndAllowWhileIdle(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        triggerAtMillis,
                        pendingIntent
                    )
                } else {
                    alarmManager.set(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        triggerAtMillis,
                        pendingIntent
                    )
                }

                Log.d(TAG, "Scheduled 2-hour periodic alarm (interval = 2 hours)")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to schedule periodic check alarm", e)
            }
        }
    }
}
