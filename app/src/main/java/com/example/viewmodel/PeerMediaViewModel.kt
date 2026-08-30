package com.example.viewmodel

import android.app.Application
import android.net.Uri
import android.util.Base64
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.model.ActivityLog
import com.example.model.AppRole
import com.example.model.BreadcrumbItem
import com.example.model.BrowserViewMode
import com.example.model.ConnectionStatus
import com.example.model.DirectoryGroup
import com.example.model.FolderEntry
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
import kotlinx.coroutines.flow.map
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

    private val _selectedDirectory = MutableStateFlow<String?>(null) // null = all directories
    val selectedDirectory: StateFlow<String?> = _selectedDirectory.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _remoteDeviceName = MutableStateFlow("Xiaomi Note 7")
    val remoteDeviceName: StateFlow<String> = _remoteDeviceName.asStateFlow()

    private val _currentFolderPath = MutableStateFlow("") // "" = Root / Device Storage
    val currentFolderPath: StateFlow<String> = _currentFolderPath.asStateFlow()

    private val _browserViewMode = MutableStateFlow(BrowserViewMode.HIERARCHY)
    val browserViewMode: StateFlow<BrowserViewMode> = _browserViewMode.asStateFlow()

    // Breadcrumbs list for current folder path
    val folderBreadcrumbs: StateFlow<List<BreadcrumbItem>> = combine(
        _currentFolderPath,
        _remoteDeviceName
    ) { path, devName ->
        val rootTitle = if (devName.isNotBlank() && devName != "Remote Device") "Device - $devName" else "Internal Storage"
        val list = mutableListOf(BreadcrumbItem(label = rootTitle, path = ""))
        if (path.isNotEmpty()) {
            val parts = path.split("/")
            var accumulated = ""
            parts.forEach { part ->
                if (part.isNotBlank()) {
                    accumulated = if (accumulated.isEmpty()) part else "$accumulated/$part"
                    list.add(BreadcrumbItem(label = part, path = accumulated))
                }
            }
        }
        list
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), listOf(BreadcrumbItem("Internal Storage", "")))

    // Normalize item path for folder hierarchy
    private fun normalizeItemPath(item: MediaItem): String {
        val rel = item.relativePath.trim().trim('/')
        if (rel.isNotEmpty()) return rel
        val bucket = item.bucketName.trim().trim('/')
        if (bucket.isNotEmpty() && !bucket.equals("Internal Storage", ignoreCase = true) && !bucket.equals("Storage", ignoreCase = true)) {
            return bucket
        }
        return ""
    }

    // Computed subfolders for the current folder path
    val currentSubfolders: StateFlow<List<FolderEntry>> = combine(
        _rawMediaItems,
        _selectedFilter,
        _searchQuery,
        _currentFolderPath
    ) { items, filter, query, curPath ->
        val filtered = items.filter { item ->
            when (filter) {
                MediaFilter.ALL -> true
                MediaFilter.PHOTOS -> !item.isVideo && !item.isAudio
                MediaFilter.VIDEOS -> item.isVideo
                MediaFilter.AUDIO -> item.isAudio
            }
        }

        if (query.isNotBlank()) {
            // When search is active, show matching folder names
            val matchingFolders = mutableMapOf<String, MutableList<MediaItem>>()
            filtered.forEach { item ->
                val path = normalizeItemPath(item)
                if (path.isNotEmpty()) {
                    val folderName = path.substringBefore('/')
                    if (folderName.contains(query.trim(), ignoreCase = true)) {
                        matchingFolders.getOrPut(folderName) { mutableListOf() }.add(item)
                    }
                }
            }
            return@combine matchingFolders.map { (name, list) ->
                FolderEntry(name = name, fullPath = name, fileCount = list.size)
            }.sortedBy { it.name.lowercase() }
        }

        val prefix = if (curPath.isEmpty()) "" else "$curPath/"
        val subfolderMap = mutableMapOf<String, MutableList<MediaItem>>()

        filtered.forEach { item ->
            val path = normalizeItemPath(item)
            if (curPath.isEmpty()) {
                if (path.isNotEmpty()) {
                    val firstSegment = path.substringBefore('/')
                    subfolderMap.getOrPut(firstSegment) { mutableListOf() }.add(item)
                }
            } else {
                if (path.startsWith(prefix) && path.length > prefix.length) {
                    val remainder = path.removePrefix(prefix)
                    val nextSegment = remainder.substringBefore('/')
                    subfolderMap.getOrPut(nextSegment) { mutableListOf() }.add(item)
                }
            }
        }

        subfolderMap.map { (folderName, folderItems) ->
            val fullSubPath = if (curPath.isEmpty()) folderName else "$curPath/$folderName"
            FolderEntry(
                name = folderName,
                fullPath = fullSubPath,
                fileCount = folderItems.size
            )
        }.sortedBy { it.name.lowercase() }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Computed media files for current folder path
    val currentFolderFiles: StateFlow<List<MediaItem>> = combine(
        _rawMediaItems,
        _selectedFilter,
        _searchQuery,
        _currentFolderPath
    ) { items, filter, query, curPath ->
        val filtered = items.filter { item ->
            val matchesFilter = when (filter) {
                MediaFilter.ALL -> true
                MediaFilter.PHOTOS -> !item.isVideo && !item.isAudio
                MediaFilter.VIDEOS -> item.isVideo
                MediaFilter.AUDIO -> item.isAudio
            }
            matchesFilter
        }

        if (query.isNotBlank()) {
            return@combine filtered.filter { item ->
                item.displayName.contains(query.trim(), ignoreCase = true) ||
                    item.bucketName.contains(query.trim(), ignoreCase = true) ||
                    item.relativePath.contains(query.trim(), ignoreCase = true)
            }
        }

        if (curPath.isEmpty()) {
            // Direct files at root level (items without folder)
            filtered.filter { normalizeItemPath(it).isEmpty() }
        } else {
            // Files in this specific folder or sub-leaf
            val prefix = "$curPath/"
            filtered.filter { item ->
                val path = normalizeItemPath(item)
                path.equals(curPath, ignoreCase = true) ||
                    path.startsWith(prefix, ignoreCase = true)
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val directoryGroups: StateFlow<List<DirectoryGroup>> = _rawMediaItems.map { items ->
        items.groupBy { it.bucketName }
            .map { (bucket, list) ->
                DirectoryGroup(
                    name = bucket,
                    count = list.size,
                    latestItem = list.firstOrNull()
                )
            }
            .sortedByDescending { it.count }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val filteredMediaItems: StateFlow<List<MediaItem>> = combine(
        _rawMediaItems,
        _selectedFilter,
        _selectedDirectory,
        _searchQuery
    ) { items, filter, selectedDir, query ->
        items.filter { item ->
            val matchesFilter = when (filter) {
                MediaFilter.ALL -> true
                MediaFilter.PHOTOS -> !item.isVideo && !item.isAudio
                MediaFilter.VIDEOS -> item.isVideo
                MediaFilter.AUDIO -> item.isAudio
            }
            val matchesDir = if (selectedDir == null) true else {
                item.bucketName.equals(selectedDir, ignoreCase = true)
            }
            val matchesQuery = if (query.isBlank()) true else {
                item.displayName.contains(query.trim(), ignoreCase = true) ||
                    item.bucketName.contains(query.trim(), ignoreCase = true)
            }
            matchesFilter && matchesDir && matchesQuery
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
        _selectedDirectory.value = null
        _currentFolderPath.value = ""
        _remoteDeviceName.value = "Xiaomi Note 7"
        _isSelectionMode.value = false
    }

    fun selectDirectory(dir: String?) {
        _selectedDirectory.value = dir
    }

    fun navigateToFolder(path: String) {
        _currentFolderPath.value = path
        _selectedDirectory.value = null
    }

    fun navigateUp() {
        val cur = _currentFolderPath.value
        if (cur.isEmpty()) return
        if (!cur.contains('/')) {
            _currentFolderPath.value = ""
        } else {
            _currentFolderPath.value = cur.substringBeforeLast('/')
        }
    }

    fun navigateToBreadcrumb(path: String) {
        _currentFolderPath.value = path
    }

    fun setBrowserViewMode(mode: BrowserViewMode) {
        _browserViewMode.value = mode
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

    fun refreshMediaCatalog() {
        if (_appRole.value == AppRole.CLIENT) {
            _statusMessage.value = "Requesting catalog from host..."
            requestMediaCatalog()
        } else if (_appRole.value == AppRole.HOST) {
            refreshLocalMedia()
        }
    }

    // --- Host Logic ---

    fun startHosting() {
        viewModelScope.launch {
            _connectionStatus.value = ConnectionStatus.CONNECTING_FIREBASE
            _statusMessage.value = "Indexing local media..."
            addLog("Scanning local photos and videos...")

            // 1. Scan local media (and generate demo media if device is completely empty)
            var localMedia = scanner.queryAllMedia()
            if (localMedia.isEmpty()) {
                addLog("Storage has no media yet. Seeding demo vault...")
                val demoMedia = scanner.generateSampleVault()
                localMedia = demoMedia
            }
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

            // If Client, request catalog immediately and schedule fallback retry if catalog is still empty
            if (_appRole.value == AppRole.CLIENT) {
                requestMediaCatalog()
                viewModelScope.launch {
                    delay(2000)
                    if (_connectionStatus.value == ConnectionStatus.CONNECTED && _rawMediaItems.value.isEmpty()) {
                        addLog("Retrying media catalog request...")
                        requestMediaCatalog()
                    }
                }
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
                handleCatalogResponse(proto)
            }
            is ProtocolMessage.CatalogChunk -> {
                handleCatalogChunk(proto)
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

    fun requestMediaCatalog() {
        addLog("Requesting media catalog & directories from secondary phone...")
        val msg = ProtocolMessage.GetCatalog(0, 500)
        webrtcManager.sendMessage(msg.toJson())
    }

    private fun handleGetCatalogRequest() {
        viewModelScope.launch {
            // 1. If host has no items (e.g. storage permissions were just granted or MediaStore returned 0 items), rescan!
            if (_rawMediaItems.value.isEmpty()) {
                val scanned = scanner.queryAllMedia()
                if (scanned.isNotEmpty()) {
                    _rawMediaItems.value = scanned
                    hostLocalMediaMap.clear()
                    scanned.forEach { hostLocalMediaMap[it.id] = it }
                } else {
                    // Generate sample vault on host so user can test seamlessly
                    val sampleVault = scanner.generateSampleVault()
                    _rawMediaItems.value = sampleVault
                    hostLocalMediaMap.clear()
                    sampleVault.forEach { hostLocalMediaMap[it.id] = it }
                }
            }

            val items = _rawMediaItems.value
            val hostDeviceName = "${android.os.Build.MANUFACTURER.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }} ${android.os.Build.MODEL}".trim()
            addLog("Client requested media catalog. Transmitting ${items.size} items from $hostDeviceName in chunks...")

            if (items.isEmpty()) {
                val msg = ProtocolMessage.CatalogResponse(emptyList(), deviceName = hostDeviceName)
                webrtcManager.sendMessage(msg.toJson())
                return@launch
            }

            // Chunk in batches of 15 items (safe < 4KB per chunk for WebRTC DataChannel)
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
                delay(20) // pacing to prevent DataChannel buffer overflow
            }
            addLog("Finished transmitting catalog chunks to client.", isSuccess = true)
        }
    }

    private fun handleCatalogChunk(chunk: ProtocolMessage.CatalogChunk) {
        if (chunk.deviceName.isNotBlank()) {
            _remoteDeviceName.value = chunk.deviceName
        }
        val current = _rawMediaItems.value.toMutableList()
        if (chunk.chunkIndex == 0) {
            current.clear()
        }
        val existingIds = current.map { it.id }.toSet()
        val newItems = chunk.items.filter { !existingIds.contains(it.id) }
        current.addAll(newItems)
        _rawMediaItems.value = current
        _statusMessage.value = "Streaming catalog: ${current.size} of ${chunk.totalItems} files"
        addLog("Catalog chunk ${chunk.chunkIndex + 1}/${chunk.totalChunks} received (${current.size} files)")
        if (chunk.chunkIndex == chunk.totalChunks - 1) {
            _statusMessage.value = "Ready • ${current.size} items loaded"
            addLog("Media catalog complete: ${current.size} files across directories", isSuccess = true)
        }
    }

    private fun handleCatalogResponse(response: ProtocolMessage.CatalogResponse) {
        if (response.deviceName.isNotBlank()) {
            _remoteDeviceName.value = response.deviceName
        }
        _rawMediaItems.value = response.items
        addLog("Received catalog of ${response.items.size} media items from secondary phone", isSuccess = true)
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
