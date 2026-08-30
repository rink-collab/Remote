package com.example.repository

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap

class MediaSaver(private val context: Context) {

    private val TAG = "MediaSaver"
    private val activeDownloads = ConcurrentHashMap<String, DownloadSession>()

    class DownloadSession(
        val fileId: String,
        val fileName: String,
        val mimeType: String,
        val totalSize: Long,
        val totalChunks: Int,
        val tempFile: File
    ) {
        private var outputStream: BufferedOutputStream? = null
        var bytesReceived: Long = 0L
        var receivedChunks: Int = 0
        var isClosed: Boolean = false

        init {
            try {
                outputStream = BufferedOutputStream(FileOutputStream(tempFile), 64 * 1024)
            } catch (e: Exception) {
                Log.e("DownloadSession", "Failed to initialize temp file output stream", e)
            }
        }

        @Synchronized
        fun writeChunkData(data: ByteArray): Long {
            if (isClosed) return bytesReceived
            try {
                outputStream?.write(data)
                bytesReceived += data.size
                receivedChunks++
            } catch (e: Exception) {
                Log.e("DownloadSession", "Error writing chunk to disk for $fileId", e)
            }
            return bytesReceived
        }

        @Synchronized
        fun close() {
            if (!isClosed) {
                isClosed = true
                try {
                    outputStream?.flush()
                    outputStream?.close()
                } catch (e: Exception) {
                    Log.e("DownloadSession", "Error closing download output stream for $fileId", e)
                } finally {
                    outputStream = null
                }
            }
        }
    }

    fun startDownload(
        fileId: String,
        fileName: String,
        mimeType: String,
        totalSize: Long,
        totalChunks: Int
    ) {
        // Clean up any stale session with the same file ID
        val old = activeDownloads.remove(fileId)
        old?.close()
        old?.tempFile?.delete()

        val tempFile = File(context.cacheDir, "dl_$fileId.tmp")
        if (tempFile.exists()) tempFile.delete()

        activeDownloads[fileId] = DownloadSession(
            fileId = fileId,
            fileName = fileName,
            mimeType = mimeType,
            totalSize = totalSize,
            totalChunks = totalChunks,
            tempFile = tempFile
        )
    }

    /**
     * Efficiently appends raw binary chunk bytes directly to disk without memory accumulation.
     * Returns Pair(bytesReceivedSoFar, totalSize).
     */
    fun appendBinaryChunk(
        fileId: String,
        chunkIndex: Int,
        totalChunks: Int,
        chunkData: ByteArray
    ): Pair<Long, Long>? {
        val session = activeDownloads[fileId] ?: return null
        val bytes = session.writeChunkData(chunkData)
        return Pair(bytes, session.totalSize)
    }

    /**
     * Legacy Base64 append method, also writing directly to disk stream.
     */
    suspend fun appendChunk(
        fileId: String,
        chunkIndex: Int,
        base64Data: String
    ): Pair<Int, Int>? = withContext(Dispatchers.IO) {
        val session = activeDownloads[fileId] ?: return@withContext null
        val bytes = Base64.decode(base64Data, Base64.DEFAULT)
        session.writeChunkData(bytes)
        Pair(session.receivedChunks, session.totalChunks)
    }

    suspend fun finalizeDownload(fileId: String): Uri? = withContext(Dispatchers.IO) {
        val session = activeDownloads.remove(fileId) ?: return@withContext null
        session.close()

        try {
            if (!session.tempFile.exists() || session.tempFile.length() == 0L) {
                Log.e(TAG, "Temp file is empty or missing: ${session.tempFile.absolutePath}")
                return@withContext null
            }

            // Save from temp file directly to Public MediaStore / Downloads directory
            val savedUri = saveToPublicStorage(
                tempFile = session.tempFile,
                fileName = session.fileName,
                mimeType = session.mimeType
            )

            session.tempFile.delete()
            savedUri
        } catch (e: Exception) {
            Log.e(TAG, "Error finalizing download for $fileId", e)
            session.tempFile.delete()
            null
        }
    }

    private fun saveToPublicStorage(tempFile: File, fileName: String, mimeType: String): Uri? {
        val isVideo = mimeType.startsWith("video")
        val isAudio = mimeType.startsWith("audio")
        val contentResolver = context.contentResolver

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val collection = if (isVideo) {
                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            } else if (isAudio) {
                MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            } else if (mimeType.startsWith("image")) {
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            } else {
                MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            }

            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }

            val itemUri = contentResolver.insert(collection, values) ?: return null

            contentResolver.openOutputStream(itemUri)?.use { out ->
                tempFile.inputStream().use { input ->
                    input.copyTo(out)
                }
            }

            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            contentResolver.update(itemUri, values, null, null)

            return itemUri
        } else {
            // Pre-Android 10
            val directory = if (isVideo) {
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
            } else if (isAudio) {
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
            } else {
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
            }
            val appDir = File(directory, "PeerMedia")
            if (!appDir.exists()) appDir.mkdirs()

            val targetFile = File(appDir, fileName)
            tempFile.copyTo(targetFile, overwrite = true)

            MediaScannerConnection.scanFile(
                context,
                arrayOf(targetFile.absolutePath),
                arrayOf(mimeType),
                null
            )
            return Uri.fromFile(targetFile)
        }
    }

    fun cancelDownload(fileId: String) {
        val session = activeDownloads.remove(fileId)
        session?.close()
        session?.tempFile?.delete()
    }
}
