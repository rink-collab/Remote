package com.example.worker

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.repository.MediaStoreScanner
import com.google.android.gms.tasks.Tasks
import com.google.firebase.database.FirebaseDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.TimeUnit

class HostPresenceWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    private val TAG = "HostPresenceWorker"

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "HostPresenceWorker started periodic background presence update...")

            val prefs = applicationContext.getSharedPreferences("peer_media_host_config", Context.MODE_PRIVATE)
            var hostId = prefs.getString("host_id", null)
            if (hostId.isNullOrEmpty()) {
                hostId = "host_" + UUID.randomUUID().toString().take(8)
                prefs.edit().putString("host_id", hostId).apply()
            }

            val manufacturer = Build.MANUFACTURER.replaceFirstChar {
                if (it.isLowerCase()) it.titlecase() else it.toString()
            }
            val model = Build.MODEL
            val deviceName = if (model.startsWith(manufacturer, ignoreCase = true)) {
                model
            } else {
                "$manufacturer $model"
            }

            // Quick index of media items
            val scanner = MediaStoreScanner(applicationContext)
            val mediaList = scanner.queryAllMedia()
            val photosCount = mediaList.count { !it.isVideo && !it.isAudio }
            val videosCount = mediaList.count { it.isVideo }
            val audioCount = mediaList.count { it.isAudio }
            val totalCount = mediaList.size

            // Update Firebase Realtime Database
            val db = FirebaseDatabase.getInstance()
            val roomRef = db.getReference("peer_media_rooms").child(hostId)

            val updates = mapOf<String, Any>(
                "hostId" to hostId,
                "deviceName" to deviceName,
                "deviceModel" to model,
                "mediaCount" to totalCount,
                "photosCount" to photosCount,
                "videosCount" to videosCount,
                "audioCount" to audioCount,
                "hostPresent" to true,
                "lastSeen" to System.currentTimeMillis(),
                "lastBackgroundSync" to System.currentTimeMillis()
            )

            val task = roomRef.updateChildren(updates)
            Tasks.await(task, 10, TimeUnit.SECONDS)

            prefs.edit().putLong("last_worker_sync_timestamp", System.currentTimeMillis()).apply()
            Log.d(TAG, "HostPresenceWorker successfully synced host presence for $hostId ($totalCount items)")

            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "HostPresenceWorker failed background presence sync", e)
            if (runAttemptCount < 3) {
                Result.retry()
            } else {
                Result.failure()
            }
        }
    }

    companion object {
        const val WORK_NAME = "PeerMediaHostPresenceWork"
    }
}
