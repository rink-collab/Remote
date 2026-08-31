package com.example.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.model.ActivityLog
import com.example.model.BinaryChunkProtocol
import com.example.model.ConnectionStatus
import com.example.model.MediaItem
import com.example.model.ProtocolMessage
import com.example.model.TransferProgress
import com.example.repository.MediaStoreScanner
import com.example.signaling.FirebaseSignalingManager
import com.example.signaling.IceCandidateData
import com.example.webrtc.WebRTCListener
import com.example.webrtc.WebRTCManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.webrtc.PeerConnection.PeerConnectionState
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap

class PeerMediaViewModel(application: Application) : AndroidViewModel(application), WebRTCListener {

    private val TAG = "PeerMediaViewModel"

    private val scanner = MediaStoreScanner(application)
    private val signalingManager = FirebaseSignalingManager()
    private val webrtcManager = WebRTCManager(application, this)

    private val _connectionStatus = MutableStateFlow(ConnectionStatus.IDLE)
    val connectionStatus: StateFlow<ConnectionStatus> = _connectionStatus.asStateFlow()

    private val _statusMessage = MutableStateFlow("Ready to connect")
    val statusMessage: StateFlow<String> = _statusMessage.asStateFlow()

    private val _roomCode = MutableStateFlow("")
    val roomCode: StateFlow<String> = _roomCode.asStateFlow()

    private fun getLocalDeviceName(): String {
        val manufacturer = android.os.Build.MANUFACTURER.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        val model = android.os.Build.MODEL
        return if (model.startsWith(manufacturer, ignoreCase = true)) {
            model
        } else {
            "$manufacturer $model"
        }
    }

    private val _myHostDeviceName = MutableStateFlow(getLocalDeviceName())
    val myHostDeviceName: StateFlow<String> = _myHostDeviceName.asStateFlow()

    // Host indexed media list
    private val _rawMediaItems = MutableStateFlow<List<MediaItem>>(emptyList())
    val rawMediaItems: StateFlow<List<MediaItem>> = _rawMediaItems.asStateFlow()

    private val _transferMap = MutableStateFlow<Map<String, TransferProgress>>(emptyMap())
    val transferMap: StateFlow<Map<String, TransferProgress>> = _transferMap.asStateFlow()

    private val _activityLogs = MutableStateFlow<List<ActivityLog>>(emptyList())
    val activityLogs: StateFlow<List<ActivityLog>> = _activityLogs.asStateFlow()

    private val activeUploadJobs = ConcurrentHashMap<String, Job>()
    private val hostLocalMediaMap = ConcurrentHashMap<String, MediaItem>()

    init {
        startHosting()
    }

    fun refreshLocalMedia() {
        viewModelScope.launch {
            val localMedia = scanner.queryAllMedia()
            _rawMediaItems.value = localMedia
            hostLocalMediaMap.clear()
            localMedia.forEach { hostLocalMediaMap[it.id] = it }
            addLog("Scanned ${localMedia.size} local files in vault", isSuccess = true)
        }
    }

    fun generateDemoVaultOnHost() {
        viewModelScope.launch {
            val samples = scanner.generateSampleVault()
            val combined = (scanner.queryAllMedia() + samples).distinctBy { it.id }
            _rawMediaItems.value = combined
            hostLocalMediaMap.clear()
            combined.forEach { hostLocalMediaMap[it.id] = it }
            addLog("Generated ${samples.size} demo vault files for instant P2P streaming", isSuccess = true)
        }
    }

    fun startHosting() {
        viewModelScope.launch {
            _connectionStatus.value = ConnectionStatus.CONNECTING_FIREBASE
            _statusMessage.value = "Indexing local media vault..."
            addLog("Scanning local media vault...")

            // 1. Scan local media
            var localMedia = scanner.queryAllMedia()
            if (localMedia.isEmpty()) {
                addLog("Storage has no media yet. Seeding demo vault...")
                val demoMedia = scanner.generateSampleVault()
                localMedia = demoMedia
            }
            _rawMediaItems.value = localMedia
            hostLocalMediaMap.clear()
            localMedia.forEach { hostLocalMediaMap[it.id] = it }

            val photosCount = localMedia.count { !it.isVideo && !it.isAudio }
            val videosCount = localMedia.count { it.isVideo }
            val audioCount = localMedia.count { it.isAudio }
            addLog("Indexed ${localMedia.size} files ($photosCount photos, $videosCount videos, $audioCount audio)", isSuccess = true)

            // 2. Generate Host ID
            val hostId = signalingManager.generateHostId()
            val devName = getLocalDeviceName()
            _myHostDeviceName.value = devName
            _roomCode.value = hostId
            _statusMessage.value = "Broadcasting as $devName..."

            // 3. Register host & broadcast info in Firebase
            signalingManager.hostRoomWithDeviceInfo(
                roomId = hostId,
                deviceName = devName,
                deviceModel = android.os.Build.MODEL,
                mediaCount = localMedia.size,
                photosCount = photosCount,
                videosCount = videosCount,
                audioCount = audioCount,
                onSuccess = {
                    _connectionStatus.value = ConnectionStatus.WAITING_FOR_PEER
                    _statusMessage.value = "Device is online & broadcasting. Waiting for client..."
                    addLog("Broadcasting online as '$devName'. Ready for client to connect...", isSuccess = true)

                    // 4. Initialize WebRTC as Host
                    setupWebRtcHost(hostId)
                },
                onError = { err ->
                    _connectionStatus.value = ConnectionStatus.ERROR
                    _statusMessage.value = err
                    addLog("Signaling Error: $err", isError = true)
                }
            )
        }
    }

    private fun setupWebRtcHost(code: String) {
        webrtcManager.createPeerConnection(isHost = true)

        // Create SDP Offer
        webrtcManager.createOffer(
            onSuccess = { offerDesc ->
                signalingManager.sendOffer(code, offerDesc.description)
                addLog("Generated WebRTC Offer. Listening for incoming connections...")

                // Listen for client's SDP Answer and ICE candidates
                signalingManager.listenForClientAnswer(
                    roomId = code,
                    onAnswerReceived = { answerSdp ->
                        addLog("Client connected! Establishing direct P2P link...")
                        webrtcManager.handleRemoteAnswer(answerSdp) { err ->
                            addLog("Error handling remote answer: $err", isError = true)
                        }
                    },
                    onClientCandidateReceived = { candidate ->
                        webrtcManager.addRemoteIceCandidate(candidate)
                    }
                )
            },
            onError = { err ->
                _connectionStatus.value = ConnectionStatus.ERROR
                _statusMessage.value = err
                addLog("Error creating WebRTC offer: $err", isError = true)
            }
        )
    }

    // --- WebRTC Callbacks ---

    override fun onIceCandidateGenerated(candidate: IceCandidateData) {
        val code = _roomCode.value
        if (code.isNotEmpty()) {
            signalingManager.sendIceCandidate(code, isHost = true, candidate)
        }
    }

    override fun onConnectionStateChanged(state: PeerConnectionState) {
        Log.d(TAG, "PeerConnectionState: $state")
        when (state) {
            PeerConnectionState.CONNECTED -> {
                _connectionStatus.value = ConnectionStatus.CONNECTED
                _statusMessage.value = "P2P WebRTC Direct Connected"
                addLog("Direct WebRTC P2P connection established with client!", isSuccess = true)
            }
            PeerConnectionState.DISCONNECTED, PeerConnectionState.CLOSED -> {
                _connectionStatus.value = ConnectionStatus.DISCONNECTED
                _statusMessage.value = "Client disconnected"
                addLog("Client connection closed", isError = true)
            }
            PeerConnectionState.FAILED -> {
                _connectionStatus.value = ConnectionStatus.ERROR
                _statusMessage.value = "P2P Connection failed"
                addLog("WebRTC Connection failed", isError = true)
            }
            PeerConnectionState.CONNECTING -> {
                _connectionStatus.value = ConnectionStatus.CONNECTING_WEBRTC
                _statusMessage.value = "Connecting to client..."
            }
            else -> {}
        }
    }

    override fun onDataChannelStateChanged(isOpen: Boolean) {
        if (isOpen) {
            _connectionStatus.value = ConnectionStatus.CONNECTED
            _statusMessage.value = "Ready to stream media to client"
            addLog("WebRTC Data Channel OPEN", isSuccess = true)
        } else {
            addLog("Data Channel Closed")
        }
    }

    override fun onBinaryReceived(bytes: ByteArray) {
        // Host receives commands via string/binary
    }

    override fun onMessageReceived(message: String) {
        val proto = ProtocolMessage.fromJson(message) ?: return

        when (proto) {
            is ProtocolMessage.GetCatalog -> {
                handleGetCatalogRequest()
            }
            is ProtocolMessage.GetThumbnail -> {
                handleGetThumbnailRequest(proto.id)
            }
            is ProtocolMessage.DownloadRequest -> {
                handleDownloadRequest(proto.id)
            }
            is ProtocolMessage.Ping -> {
                webrtcManager.sendMessage(ProtocolMessage.Pong.toJson())
            }
            is ProtocolMessage.Pong -> {}
            else -> {}
        }
    }

    private fun handleGetCatalogRequest() {
        viewModelScope.launch {
            if (_rawMediaItems.value.isEmpty()) {
                val scanned = scanner.queryAllMedia()
                if (scanned.isNotEmpty()) {
                    _rawMediaItems.value = scanned
                    hostLocalMediaMap.clear()
                    scanned.forEach { hostLocalMediaMap[it.id] = it }
                } else {
                    val sampleVault = scanner.generateSampleVault()
                    _rawMediaItems.value = sampleVault
                    hostLocalMediaMap.clear()
                    sampleVault.forEach { hostLocalMediaMap[it.id] = it }
                }
            }

            val items = _rawMediaItems.value
            val hostDeviceName = "${android.os.Build.MANUFACTURER.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }} ${android.os.Build.MODEL}".trim()
            addLog("Client requested media catalog. Transmitting ${items.size} items in chunks...")

            if (items.isEmpty()) {
                val msg = ProtocolMessage.CatalogResponse(emptyList(), deviceName = hostDeviceName)
                webrtcManager.sendMessage(msg.toJson())
                return@launch
            }

            val chunkSize = 15
            val chunks = items.chunked(chunkSize)
            val totalChunks = chunks.size
            val totalItems = items.size

            chunks.forEachIndexed { index, chunkList ->
                val chunkMsg = ProtocolMessage.CatalogChunk(
                    items = chunkList,
                    chunkIndex = index,
                    totalChunks = totalChunks,
                    totalItems = totalItems,
                    deviceName = hostDeviceName
                )
                val sent = webrtcManager.sendMessage(chunkMsg.toJson())
                if (!sent) {
                    addLog("Warning: Chunk $index/$totalChunks send failed", isError = true)
                }
                delay(20)
            }
            addLog("Finished transmitting catalog chunks to client.", isSuccess = true)
        }
    }

    private fun handleGetThumbnailRequest(itemId: String) {
        val item = hostLocalMediaMap[itemId] ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val thumbBase64 = scanner.generateThumbnailBase64(item)
            if (thumbBase64 != null) {
                val resp = ProtocolMessage.ThumbnailResponse(itemId, thumbBase64)
                webrtcManager.sendMessage(resp.toJson())
            }
        }
    }

    private fun handleDownloadRequest(itemId: String) {
        val item = hostLocalMediaMap[itemId] ?: return
        addLog("Streaming ${item.displayName} to client device...")

        val job = viewModelScope.launch(Dispatchers.IO) {
            try {
                val inputStream: InputStream? = scanner.openMediaInputStream(item.uriString ?: "")
                if (inputStream == null) {
                    addLog("Failed to open input stream for ${item.displayName}", isError = true)
                    return@launch
                }

                val chunkSize = 32 * 1024 // 32 KB chunks
                val totalChunks = ((item.size + chunkSize - 1) / chunkSize).toInt().coerceAtLeast(1)

                // Send Header
                val header = ProtocolMessage.DownloadHeader(
                    id = item.id,
                    fileName = item.displayName,
                    size = item.size,
                    mimeType = item.mimeType,
                    totalChunks = totalChunks
                )
                webrtcManager.sendMessage(header.toJson())

                var chunkIndex = 0
                val buffer = ByteArray(chunkSize)
                var bytesRead: Int
                var totalBytesSent = 0L
                val startTime = System.currentTimeMillis()
                val highWatermark = 512 * 1024L // 512 KB backpressure threshold

                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                    while (webrtcManager.getBufferedAmount() > highWatermark) {
                        delay(2)
                    }

                    val packet = BinaryChunkProtocol.createChunkPacket(
                        fileId = item.id,
                        chunkIndex = chunkIndex,
                        totalChunks = totalChunks,
                        payload = buffer,
                        payloadOffset = 0,
                        payloadLength = bytesRead
                    )

                    webrtcManager.sendBinary(packet)

                    totalBytesSent += bytesRead
                    chunkIndex++

                    val elapsedSec = (System.currentTimeMillis() - startTime).coerceAtLeast(1) / 1000.0
                    val speedKbps = if (elapsedSec > 0) ((totalBytesSent / 1024.0) / elapsedSec).toLong() else 0L

                    updateTransfer(
                        TransferProgress(
                            fileId = item.id,
                            fileName = item.displayName,
                            bytesTransferred = totalBytesSent,
                            totalBytes = item.size,
                            isUpload = true,
                            speedKbps = speedKbps
                        )
                    )
                }
                inputStream.close()

                while (webrtcManager.getBufferedAmount() > 0L) {
                    delay(2)
                }

                webrtcManager.sendMessage(ProtocolMessage.DownloadComplete(item.id).toJson())
                addLog("Completed streaming ${item.displayName} to client", isSuccess = true)

                updateTransfer(
                    TransferProgress(
                        fileId = item.id,
                        fileName = item.displayName,
                        bytesTransferred = item.size,
                        totalBytes = item.size,
                        isUpload = true,
                        isComplete = true
                    )
                )
            } catch (e: Exception) {
                Log.e(TAG, "Error streaming file", e)
                addLog("Error streaming ${item.displayName}: ${e.message}", isError = true)
            } finally {
                activeUploadJobs.remove(itemId)
            }
        }
        activeUploadJobs[itemId] = job
    }

    private fun updateTransfer(progress: TransferProgress) {
        val current = _transferMap.value.toMutableMap()
        current[progress.fileId] = progress
        _transferMap.value = current
    }

    private fun addLog(msg: String, isError: Boolean = false, isSuccess: Boolean = false) {
        val log = ActivityLog(message = msg, isError = isError, isSuccess = isSuccess)
        _activityLogs.value = listOf(log) + _activityLogs.value.take(49)
    }

    fun disconnect() {
        signalingManager.stopListeningForOnlineHosts()
        signalingManager.cleanup()
        webrtcManager.close()
        _connectionStatus.value = ConnectionStatus.DISCONNECTED
        _statusMessage.value = "Disconnected"
        addLog("Disconnected")
    }

    override fun onCleared() {
        super.onCleared()
        disconnect()
    }
}
