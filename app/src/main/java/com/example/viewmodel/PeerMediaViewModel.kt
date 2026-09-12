package com.example.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.example.model.ActivityLog
import com.example.model.ConnectionStatus
import com.example.model.MediaItem
import com.example.model.TransferProgress
import com.example.service.PeerMediaHostController
import kotlinx.coroutines.flow.StateFlow

class PeerMediaViewModel(application: Application) : AndroidViewModel(application) {

    val connectionStatus: StateFlow<ConnectionStatus> = PeerMediaHostController.connectionStatus
    val statusMessage: StateFlow<String> = PeerMediaHostController.statusMessage
    val roomCode: StateFlow<String> = PeerMediaHostController.roomCode
    val fcmToken: StateFlow<String> = PeerMediaHostController.fcmToken
    val myHostDeviceName: StateFlow<String> = PeerMediaHostController.myHostDeviceName
    val rawMediaItems: StateFlow<List<MediaItem>> = PeerMediaHostController.rawMediaItems
    val transferMap: StateFlow<Map<String, TransferProgress>> = PeerMediaHostController.transferMap
    val activityLogs: StateFlow<List<ActivityLog>> = PeerMediaHostController.activityLogs

    init {
        startHosting()
    }

    fun refreshLocalMedia() {
        PeerMediaHostController.refreshLocalMedia(getApplication())
    }

    fun generateDemoVaultOnHost() {
        PeerMediaHostController.generateDemoVaultOnHost(getApplication())
    }

    fun startHosting() {
        PeerMediaHostController.startHosting(getApplication())
    }

    fun disconnect() {
        PeerMediaHostController.disconnect()
    }
}
