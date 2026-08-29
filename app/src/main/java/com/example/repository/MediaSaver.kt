package com.example.repository

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap

class MediaSaver(private val context: Context) {

    private val activeDownloads = ConcurrentHashMap<String, DownloadSession>()

    data class DownloadSession(
        val fileId: String,
        val fileName: String,
        val mimeType: String,
        val totalSize: Long,
        val totalChunks: Int,
        val tempFile: File,
        var receivedChunks: Int = 0,
        val chunksMap: MutableMap<Int, ByteArray> = mutableMapOf()
    )

    fun startDownload(
        fileId: String,
        fileName: String,
        mimeType: String,
        totalSize: Long,
        totalChunks: Int
    ) {
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

    suspend fun appendChunk(
        fileId: String,
        chunkIndex: Int,
        base64Data: String
    ): Pair<Int, Int>? = withContext(Dispatchers.IO) {
        val session = activeDownloads[fileId] ?: return@withContext null
        val bytes = Base64.decode(base64Data, Base64.DEFAULT)

        synchronized(session) {
            session.chunksMap[chunkIndex] = bytes
            session.receivedChunks++
            Pair(session.receivedChunks, session.totalChunks)
        }
    }

    suspend fun finalizeDownload(fileId: String): Uri? = withContext(Dispatchers.IO) {
        val session = activeDownloads.remove(fileId) ?: return@withContext null

        try {
            // Write all chunks in order to temp file
            FileOutputStream(session.tempFile).use { fos ->
                for (i in 0 until session.totalChunks) {
                    val chunk = session.chunksMap[i]
                    if (chunk != null) {
                        fos.write(chunk)
                    }
                }
                fos.flush()
            }

            // Save to Public MediaStore / Downloads directory
            val savedUri = saveToPublicStorage(
                tempFile = session.tempFile,
                fileName = session.fileName,
                mimeType = session.mimeType
            )

            session.tempFile.delete()
            savedUri
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun saveToPublicStorage(tempFile: File, fileName: String, mimeType: String): Uri? {
        val isVideo = mimeType.startsWith("video")
        val contentResolver = context.contentResolver

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val collection = if (isVideo) {
                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
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
        session?.tempFile?.delete()
    }
}
