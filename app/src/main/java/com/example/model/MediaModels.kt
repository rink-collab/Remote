package com.example.model

data class MediaItem(
    val id: String,
    val displayName: String,
    val mimeType: String,
    val size: Long,
    val dateModified: Long, // Epoch seconds or millis
    val durationMs: Long = 0L,
    val isVideo: Boolean = false,
    val isAudio: Boolean = false,
    val bucketName: String = "Internal Storage", // Directory/Album name
    val relativePath: String = "", // e.g. "DCIM/Camera/"
    val uriString: String? = null // Host device local uri
) {
    val formattedSize: String
        get() {
            val kb = size / 1024.0
            val mb = kb / 1024.0
            val gb = mb / 1024.0
            return when {
                gb >= 1.0 -> String.format("%.2f GB", gb)
                mb >= 1.0 -> String.format("%.1f MB", mb)
                kb >= 1.0 -> String.format("%.0f KB", kb)
                else -> "$size B"
            }
        }

    val formattedDuration: String
        get() {
            if ((!isVideo && !isAudio) || durationMs <= 0) return ""
            val totalSeconds = durationMs / 1000
            val minutes = totalSeconds / 60
            val seconds = totalSeconds % 60
            return String.format("%d:%02d", minutes, seconds)
        }
}

enum class ConnectionStatus {
    IDLE,
    CONNECTING_FIREBASE,
    WAITING_FOR_PEER,
    CONNECTING_WEBRTC,
    CONNECTED,
    DISCONNECTED,
    ERROR
}

data class TransferProgress(
    val fileId: String,
    val fileName: String,
    val bytesTransferred: Long,
    val totalBytes: Long,
    val isUpload: Boolean = true,
    val speedKbps: Long = 0,
    val isComplete: Boolean = false,
    val error: String? = null
) {
    val progressFraction: Float
        get() = if (totalBytes > 0) (bytesTransferred.toFloat() / totalBytes).coerceIn(0f, 1f) else 0f

    val progressPercent: Int
        get() = (progressFraction * 100).toInt()
}

data class HostDevice(
    val id: String, // Room/Host ID (unique)
    val name: String, // e.g. "Xiaomi Redmi Note 7" or "Pixel 8"
    val model: String = "",
    val isOnline: Boolean = true,
    val mediaCount: Int = 0,
    val photosCount: Int = 0,
    val videosCount: Int = 0,
    val audioCount: Int = 0,
    val lastSeen: Long = System.currentTimeMillis()
)

data class ActivityLog(
    val id: String = java.util.UUID.randomUUID().toString(),
    val timestamp: Long = System.currentTimeMillis(),
    val message: String,
    val isError: Boolean = false,
    val isSuccess: Boolean = false
)
