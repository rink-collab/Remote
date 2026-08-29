package com.example.viewmodel

import android.app.Application
import android.net.Uri
import android.util.Base64
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.model.ActivityLog
import com.example.model.AppRole
import com.example.model.ConnectionStatus
import com.example.model.MediaFilter
import com.example.model.MediaItem
import com.example.model.ProtocolMessage
import com.example.model.TransferProgress
import com.example.repository.MediaSaver
import com.example.repository.MediaStoreScanner
import com.example.signaling.FirebaseSignalingManager
import com.example.signaling.IceCandidateData
import com.example.webrtc.WebRTCListener
import com.example.webrtc.WebRTCManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.webrtc.PeerConnection.PeerConnectionState
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap

class PeerMediaViewModel(application: Application) : AndroidViewModel(application), WebRTCListener {

    private val TAG = "PeerMediaViewModel"

    private val scanner = MediaStoreScanner(application)
    private val saver = MediaSaver(application)
    private val signalingManager = FirebaseSignalingManager()
    private val webrtcManager = WebRTCManager(application, this)

    private val _appRole = MutableStateFlow(AppRole.NONE)
    val appRole: StateFlow<AppRole> = _appRole.asStateFlow()

    private val _connectionStatus = MutableStateFlow(ConnectionStatus.IDLE)
    val connectionStatus: StateFlow<ConnectionStatus> = _connectionStatus.asStateFlow()

    private val _statusMessage = MutableStateFlow("Ready to connect")
    val statusMessage: StateFlow<String> = _statusMessage.asStateFlow()

    private val _roomCode = MutableStateFlow("")
    val roomCode: StateFlow<String> = _roomCode.asStateFlow()

    // Host or Client media list
    private val _rawMediaItems = MutableStateFlow<List<MediaItem>>(emptyList())
    val rawMediaItems: StateFlow<List<MediaItem>> = _rawMediaItems.asStateFlow()

    private val _selectedFilter = MutableStateFlow(MediaFilter.ALL)
    val selectedFilter: StateFlow<MediaFilter> = _selectedFilter.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    val filteredMediaItems: StateFlow<List<MediaItem>> = combine(
        _rawMediaItems,
        _selectedFilter,
        _searchQuery
    ) { items, filter, query ->
        items.filter { item ->
            val matchesFilter = when (filter) {
                MediaFilter.ALL -> true
                MediaFilter.PHOTOS -> !item.isVideo
                MediaFilter.VIDEOS -> item.isVideo
            }
            val matchesQuery = if (query.isBlank()) true else {
                item.displayName.contains(query.trim(), ignoreCase = true)
            }
            matchesFilter && matchesQuery
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _transferMap = MutableStateFlow<Map<String, TransferProgress>>(emptyMap())
    val transferMap: StateFlow<Map<String, TransferProgress>> = _transferMap.asStateFlow()

    private val _activityLogs = MutableStateFlow<List<ActivityLog>>(emptyList())
    val activityLogs: StateFlow<List<ActivityLog>> = _activityLogs.asStateFlow()

    private val _selectedItemForDetail = MutableStateFlow<MediaItem?>(null)
    val selectedItemForDetail: StateFlow<MediaItem?> = _selectedItemForDetail.asStateFlow()

    private val _selectedItemIds = MutableStateFlow<Set<String>>(emptySet())
    val selectedItemIds: StateFlow<Set<String>> = _selectedItemIds.asStateFlow()

    private val _isSelectionMode = MutableStateFlow(false)
    val isSelectionMode: StateFlow<Boolean> = _isSelectionMode.asStateFlow()

    // Track active thumbnail requests to avoid duplicates
    private val requestedThumbnails = ConcurrentHashMap.newKeySet<String>()
    private val activeUploadJobs = ConcurrentHashMap<String, Job>()

    // Local host media lookup cache
    private val hostLocalMediaMap = ConcurrentHashMap<String, MediaItem>()

    // --- Mode Setup ---

    fun selectRole(role: AppRole) {
        _appRole.value = role
        if (role == AppRole.HOST) {
            startHosting()
        }
    }

    fun resetToRoleSelection() {
        disconnect()
        _appRole.value = AppRole.NONE
        _rawMediaItems.value = emptyList()
        _transferMap.value = emptyMap()
        _selectedItemIds.value = emptySet()
        _isSelectionMode.value = false
    }

    // --- Host Logic ---

    fun startHosting() {
        viewModelScope.launch {
            _connectionStatus.value = ConnectionStatus.CONNECTING_FIREBASE
            _statusMessage.value = "Indexing local media..."
            addLog("Scanning local photos and videos...")

            // 1. Scan local media
            val localMedia = scanner.queryAllMedia()
            _rawMediaItems.value = localMedia
            hostLocalMediaMap.clear()
            localMedia.forEach { hostLocalMediaMap[it.id] = it }
            addLog("Found ${localMedia.size} media files ready to share", isSuccess = true)

            // 2. Generate Room Code
            val code = signalingManager.generateRoomCode()
            _roomCode.value = code
            _statusMessage.value = "Creating room $code..."

            // 3. Register room in Firebase
            signalingManager.hostRoom(
                roomId = code,
                onSuccess = {
                    _connectionStatus.value = ConnectionStatus.WAITING_FOR_PEER
                    _statusMessage.value = "Waiting for primary device to connect..."
                    addLog("Room $code active. Waiting for peer...")

                    // 4. Initialize WebRTC as Host
                    setupWebRtcHost(code)
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
                addLog("Generated WebRTC Offer. Listening for Answer...")

                // Listen for client's SDP Answer and ICE candidates
                signalingManager.listenForClientAnswer(
                    roomId = code,
                    onAnswerReceived = { answerSdp ->
                        addLog("Received Answer from Client. Establishing P2P link...")
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

    // --- Client Logic ---

    fun joinRoomAsClient(code: String) {
        val cleanCode = code.trim().uppercase()
        if (cleanCode.length < 4) {
            _statusMessage.value = "Please enter a valid Room Code"
            return
        }

        _roomCode.value = cleanCode
        _connectionStatus.value = ConnectionStatus.CONNECTING_FIREBASE
        _statusMessage.value = "Connecting to room $cleanCode..."
        addLog("Connecting to secondary device room $cleanCode...")

        webrtcManager.createPeerConnection(isHost = false)

        signalingManager.joinRoom(
            roomId = cleanCode,
            onOfferReceived = { offerSdp ->
                addLog("Received Offer from Host. Creating Answer...")
                _connectionStatus.value = ConnectionStatus.CONNECTING_WEBRTC
                _statusMessage.value = "Exchanging WebRTC handshake..."

                webrtcManager.handleRemoteOffer(
                    offerSdp = offerSdp,
                    onAnswerCreated = { answerDesc ->
                        signalingManager.sendAnswer(cleanCode, answerDesc.description)
                        addLog("Sent Answer. Awaiting P2P Data Channel...")
                    },
                    onError = { err ->
                        _connectionStatus.value = ConnectionStatus.ERROR
                        _statusMessage.value = err
                        addLog("Error handling offer: $err", isError = true)
                    }
                )
            },
            onHostCandidateReceived = { candidate ->
                webrtcManager.addRemoteIceCandidate(candidate)
            },
            onError = { err ->
                _connectionStatus.value = ConnectionStatus.ERROR
                _statusMessage.value = err
                addLog("Join Room Error: $err", isError = true)
            }
        )
    }

    // --- WebRTC Callbacks ---

    override fun onIceCandidateGenerated(candidate: IceCandidateData) {
        val code = _roomCode.value
        if (code.isNotEmpty()) {
            val isHost = _appRole.value == AppRole.HOST
            signalingManager.sendIceCandidate(code, isHost, candidate)
        }
    }

    override fun onConnectionStateChanged(state: PeerConnectionState) {
        Log.d(TAG, "PeerConnectionState: $state")
        when (state) {
            PeerConnectionState.CONNECTED -> {
                _connectionStatus.value = ConnectionStatus.CONNECTED
                _statusMessage.value = "P2P WebRTC Direct Connected"
                addLog("Direct WebRTC P2P connection established!", isSuccess = true)
            }
            PeerConnectionState.DISCONNECTED, PeerConnectionState.CLOSED -> {
                _connectionStatus.value = ConnectionStatus.DISCONNECTED
                _statusMessage.value = "Disconnected from peer"
                addLog("P2P connection closed", isError = true)
            }
            PeerConnectionState.FAILED -> {
                _connectionStatus.value = ConnectionStatus.ERROR
                _statusMessage.value = "P2P Connection failed"
                addLog("WebRTC Connection failed", isError = true)
            }
            PeerConnectionState.CONNECTING -> {
                _connectionStatus.value = ConnectionStatus.CONNECTING_WEBRTC
                _statusMessage.value = "Establishing direct P2P link..."
            }
            else -> {}
        }
    }

    override fun onDataChannelStateChanged(isOpen: Boolean) {
        if (isOpen) {
            _connectionStatus.value = ConnectionStatus.CONNECTED
            _statusMessage.value = "Ready to stream media"
            addLog("WebRTC Data Channel OPEN", isSuccess = true)

            // If Client, request catalog immediately
            if (_appRole.value == AppRole.CLIENT) {
                requestMediaCatalog()
            }
        } else {
            addLog("Data Channel Closed")
        }
    }

    override fun onMessageReceived(message: String) {
        val proto = ProtocolMessage.fromJson(message) ?: return

        when (proto) {
            is ProtocolMessage.GetCatalog -> {
                handleGetCatalogRequest()
            }
            is ProtocolMessage.CatalogResponse -> {
                handleCatalogResponse(proto.items)
            }
            is ProtocolMessage.GetThumbnail -> {
                handleGetThumbnailRequest(proto.id)
            }
            is ProtocolMessage.ThumbnailResponse -> {
                handleThumbnailResponse(proto.id, proto.base64Data)
            }
            is ProtocolMessage.DownloadRequest -> {
                handleDownloadRequest(proto.id)
            }
            is ProtocolMessage.DownloadHeader -> {
                handleDownloadHeader(proto)
            }
            is ProtocolMessage.DownloadChunk -> {
                handleDownloadChunk(proto)
            }
            is ProtocolMessage.DownloadComplete -> {
                handleDownloadComplete(proto.id)
            }
            is ProtocolMessage.DownloadCancel -> {
                saver.cancelDownload(proto.id)
                removeTransfer(proto.id)
            }
            is ProtocolMessage.Ping -> {
                webrtcManager.sendMessage(ProtocolMessage.Pong.toJson())
            }
            is ProtocolMessage.Pong -> {}
        }
    }

    // --- Message Handling Logic ---

    private fun requestMediaCatalog() {
        addLog("Requesting media catalog from secondary phone...")
        val msg = ProtocolMessage.GetCatalog(0, 500)
        webrtcManager.sendMessage(msg.toJson())
    }

    private fun handleGetCatalogRequest() {
        addLog("Client requested media catalog. Sending ${_rawMediaItems.value.size} items...")
        val msg = ProtocolMessage.CatalogResponse(_rawMediaItems.value)
        webrtcManager.sendMessage(msg.toJson())
    }

    private fun handleCatalogResponse(items: List<MediaItem>) {
        _rawMediaItems.value = items
        addLog("Received catalog of ${items.size} media items from secondary phone", isSuccess = true)
    }

    fun requestThumbnail(itemId: String) {
        if (_appRole.value != AppRole.CLIENT) return
        if (requestedThumbnails.contains(itemId)) return

        requestedThumbnails.add(itemId)
        val msg = ProtocolMessage.GetThumbnail(itemId)
        webrtcManager.sendMessage(msg.toJson())
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

    private fun handleThumbnailResponse(itemId: String, base64: String) {
        val current = _rawMediaItems.value
        val updated = current.map {
            if (it.id == itemId) it.copy(thumbnailBase64 = base64) else it
        }
        _rawMediaItems.value = updated
    }

    fun requestDownload(item: MediaItem) {
        if (_appRole.value != AppRole.CLIENT) return
        addLog("Requesting download: ${item.displayName} (${item.formattedSize})...")

        updateTransfer(
            TransferProgress(
                fileId = item.id,
                fileName = item.displayName,
                bytesTransferred = 0,
                totalBytes = item.size,
                isUpload = false
            )
        )

        val req = ProtocolMessage.DownloadRequest(item.id)
        webrtcManager.sendMessage(req.toJson())
    }

    fun requestBatchDownload() {
        val selectedIds = _selectedItemIds.value
        val itemsToDownload = _rawMediaItems.value.filter { selectedIds.contains(it.id) }
        _selectedItemIds.value = emptySet()
        _isSelectionMode.value = false

        viewModelScope.launch {
            itemsToDownload.forEach { item ->
                requestDownload(item)
                delay(100)
            }
        }
    }

    private fun handleDownloadRequest(itemId: String) {
        val item = hostLocalMediaMap[itemId] ?: return
        addLog("Streaming ${item.displayName} to primary device...")

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

                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                    val chunkData = if (bytesRead == chunkSize) buffer else buffer.copyOf(bytesRead)
                    val base64 = Base64.encodeToString(chunkData, Base64.NO_WRAP)

                    val chunkMsg = ProtocolMessage.DownloadChunk(
                        id = item.id,
                        chunkIndex = chunkIndex,
                        totalChunks = totalChunks,
                        data = base64
                    )

                    webrtcManager.sendMessage(chunkMsg.toJson())

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

                    // Small pause to prevent buffer clogging
                    delay(5)
                }
                inputStream.close()

                // Send Complete
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

    private fun handleDownloadHeader(header: ProtocolMessage.DownloadHeader) {
        saver.startDownload(
            fileId = header.id,
            fileName = header.fileName,
            mimeType = header.mimeType,
            totalSize = header.size,
            totalChunks = header.totalChunks
        )
        updateTransfer(
            TransferProgress(
                fileId = header.id,
                fileName = header.fileName,
                bytesTransferred = 0,
                totalBytes = header.size,
                isUpload = false
            )
        )
    }

    private fun handleDownloadChunk(chunk: ProtocolMessage.DownloadChunk) {
        viewModelScope.launch(Dispatchers.IO) {
            val progressPair = saver.appendChunk(chunk.id, chunk.chunkIndex, chunk.data)
            if (progressPair != null) {
                val (received, total) = progressPair
                val current = _transferMap.value[chunk.id]
                if (current != null) {
                    val bytes = ((received.toFloat() / total) * current.totalBytes).toLong()
                    updateTransfer(
                        current.copy(bytesTransferred = bytes)
                    )
                }
            }
        }
    }

    private fun handleDownloadComplete(itemId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val savedUri = saver.finalizeDownload(itemId)
            val current = _transferMap.value[itemId]

            if (savedUri != null) {
                addLog("Download finished & saved: ${current?.fileName ?: itemId}", isSuccess = true)
                updateTransfer(
                    current?.copy(
                        bytesTransferred = current.totalBytes,
                        isComplete = true
                    ) ?: TransferProgress(
                        fileId = itemId,
                        fileName = "Media_$itemId",
                        bytesTransferred = 1,
                        totalBytes = 1,
                        isComplete = true
                    )
                )

                // Mark item as downloaded in list
                val updated = _rawMediaItems.value.map {
                    if (it.id == itemId) it.copy(isDownloaded = true, localUri = savedUri) else it
                }
                _rawMediaItems.value = updated
            } else {
                addLog("Failed to save downloaded file $itemId", isError = true)
            }
        }
    }

    // --- UI Controls ---

    fun setFilter(filter: MediaFilter) {
        _selectedFilter.value = filter
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun setDetailItem(item: MediaItem?) {
        _selectedItemForDetail.value = item
    }

    fun toggleItemSelection(id: String) {
        val current = _selectedItemIds.value.toMutableSet()
        if (current.contains(id)) {
            current.remove(id)
        } else {
            current.add(id)
        }
        _selectedItemIds.value = current
        _isSelectionMode.value = current.isNotEmpty()
    }

    fun clearSelection() {
        _selectedItemIds.value = emptySet()
        _isSelectionMode.value = false
    }

    fun selectAll() {
        _selectedItemIds.value = filteredMediaItems.value.map { it.id }.toSet()
        _isSelectionMode.value = true
    }

    private fun updateTransfer(progress: TransferProgress) {
        val current = _transferMap.value.toMutableMap()
        current[progress.fileId] = progress
        _transferMap.value = current
    }

    private fun removeTransfer(fileId: String) {
        val current = _transferMap.value.toMutableMap()
        current.remove(fileId)
        _transferMap.value = current
    }

    private fun addLog(msg: String, isError: Boolean = false, isSuccess: Boolean = false) {
        val log = ActivityLog(message = msg, isError = isError, isSuccess = isSuccess)
        _activityLogs.value = listOf(log) + _activityLogs.value.take(49)
    }

    fun disconnect() {
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
