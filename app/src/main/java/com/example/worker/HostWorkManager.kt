package com.example.worker

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.concurrent.TimeUnit

object HostWorkManager {

    private const val TAG = "HostWorkManager"

    /**
     * Schedules a PeriodicWorkRequest to run every 15 minutes (the minimum Android WorkManager period)
     * with a 5-minute flex interval, requiring an active network connection.
     */
    fun schedulePeriodicHostKeepAlive(context: Context) {
        try {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            // 15 minutes is the minimum supported interval for PeriodicWorkRequest on Android
            val periodicWorkRequest = PeriodicWorkRequestBuilder<HostPresenceWorker>(
                repeatInterval = 15,
                repeatIntervalTimeUnit = TimeUnit.MINUTES,
                flexTimeInterval = 5,
                flexTimeIntervalUnit = TimeUnit.MINUTES
            )
                .setConstraints(constraints)
                .addTag("peer_media_host_keepalive")
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                HostPresenceWorker.WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                periodicWorkRequest
            )

            Log.d(TAG, "Enqueued 15-minute PeriodicWorkRequest for HostPresenceWorker")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to schedule periodic host keep alive", e)
        }
    }

    /**
     * Triggers an immediate one-time sync or checks status
     */
    fun getWorkInfoFlow(context: Context): Flow<WorkInfo?> {
        return WorkManager.getInstance(context)
            .getWorkInfosForUniqueWorkFlow(HostPresenceWorker.WORK_NAME)
            .map { list -> list.firstOrNull() }
    }
}
