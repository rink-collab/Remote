package com.example

import android.app.Application
import android.util.Log
import com.example.worker.HostWorkManager

class PeerMediaHostApp : Application() {

    override fun onCreate() {
        super.onCreate()
        Log.d("PeerMediaHostApp", "Initializing Peer Media Host App & scheduling 15-minute PeriodicWorkRequest...")

        // Schedule WorkManager to ensure Host presence runs every 15 minutes
        HostWorkManager.schedulePeriodicHostKeepAlive(this)
    }
}
