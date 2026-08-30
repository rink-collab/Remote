package com.example.repository

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.util.Log
import android.util.Size
import com.example.model.MediaItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

class MediaStoreScanner(private val context: Context) {

    private val TAG = "MediaStoreScanner"

    suspend fun queryAllMedia(): List<MediaItem> = withContext(Dispatchers.IO) {
        val mediaList = mutableListOf<MediaItem>()
        val contentResolver = context.contentResolver

        // 1. Query Images
        val imageProjection = mutableListOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.MIME_TYPE,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.DATE_MODIFIED
        ).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                add(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
                add(MediaStore.Images.Media.RELATIVE_PATH)
            } else {
                add(MediaStore.Images.Media.DATA)
            }
        }.toTypedArray()

        val imageSortOrder = "${MediaStore.Images.Media.DATE_MODIFIED} DESC"

        try {
            contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                imageProjection,
                null,
                null,
                imageSortOrder
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE)
                val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
                val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_MODIFIED)
                val bucketCol = cursor.getColumnIndex(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
                val pathCol = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    cursor.getColumnIndex(MediaStore.Images.Media.RELATIVE_PATH)
                } else {
                    cursor.getColumnIndex(MediaStore.Images.Media.DATA)
                }

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val name = cursor.getString(nameCol) ?: "Image_$id"
                    val mime = cursor.getString(mimeCol) ?: "image/jpeg"
                    val size = cursor.getLong(sizeCol)
                    val date = cursor.getLong(dateCol)
                    val bucketName = if (bucketCol >= 0) cursor.getString(bucketCol) ?: "Camera" else "Camera"
                    val relativePath = if (pathCol >= 0) cursor.getString(pathCol) ?: "" else ""
                    val contentUri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)

                    if (size > 0) {
                        mediaList.add(
                            MediaItem(
                                id = "img_$id",
                                displayName = name,
                                mimeType = mime,
                                size = size,
                                dateModified = date,
                                isVideo = false,
                                bucketName = bucketName.ifBlank { "Camera" },
                                relativePath = relativePath,
                                uriString = contentUri.toString()
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error querying MediaStore images", e)
        }

        // 2. Query Videos
        val videoProjection = mutableListOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.MIME_TYPE,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.DATE_MODIFIED,
            MediaStore.Video.Media.DURATION
        ).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                add(MediaStore.Video.Media.BUCKET_DISPLAY_NAME)
                add(MediaStore.Video.Media.RELATIVE_PATH)
            } else {
                add(MediaStore.Video.Media.DATA)
            }
        }.toTypedArray()

        val videoSortOrder = "${MediaStore.Video.Media.DATE_MODIFIED} DESC"

        try {
            contentResolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                videoProjection,
                null,
                null,
                videoSortOrder
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.MIME_TYPE)
                val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
                val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_MODIFIED)
                val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
                val bucketCol = cursor.getColumnIndex(MediaStore.Video.Media.BUCKET_DISPLAY_NAME)
                val pathCol = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    cursor.getColumnIndex(MediaStore.Video.Media.RELATIVE_PATH)
                } else {
                    cursor.getColumnIndex(MediaStore.Video.Media.DATA)
                }

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val name = cursor.getString(nameCol) ?: "Video_$id"
                    val mime = cursor.getString(mimeCol) ?: "video/mp4"
                    val size = cursor.getLong(sizeCol)
                    val date = cursor.getLong(dateCol)
                    val duration = cursor.getLong(durationCol)
                    val bucketName = if (bucketCol >= 0) cursor.getString(bucketCol) ?: "Videos" else "Videos"
                    val relativePath = if (pathCol >= 0) cursor.getString(pathCol) ?: "" else ""
                    val contentUri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)

                    if (size > 0) {
                        mediaList.add(
                            MediaItem(
                                id = "vid_$id",
                                displayName = name,
                                mimeType = mime,
                                size = size,
                                dateModified = date,
                                durationMs = duration,
                                isVideo = true,
                                isAudio = false,
                                bucketName = bucketName.ifBlank { "Videos" },
                                relativePath = relativePath,
                                uriString = contentUri.toString()
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error querying MediaStore videos", e)
        }

        // 3. Query Audio files
        val audioProjection = mutableListOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.MIME_TYPE,
            MediaStore.Audio.Media.SIZE,
            MediaStore.Audio.Media.DATE_MODIFIED,
            MediaStore.Audio.Media.DURATION
        ).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                add(MediaStore.Audio.Media.BUCKET_DISPLAY_NAME)
                add(MediaStore.Audio.Media.RELATIVE_PATH)
            } else {
                add(MediaStore.Audio.Media.DATA)
            }
        }.toTypedArray()

        val audioSortOrder = "${MediaStore.Audio.Media.DATE_MODIFIED} DESC"

        try {
            contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                audioProjection,
                null,
                null,
                audioSortOrder
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
                val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.MIME_TYPE)
                val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
                val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_MODIFIED)
                val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                val bucketCol = cursor.getColumnIndex(MediaStore.Audio.Media.BUCKET_DISPLAY_NAME)
                val pathCol = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    cursor.getColumnIndex(MediaStore.Audio.Media.RELATIVE_PATH)
                } else {
                    cursor.getColumnIndex(MediaStore.Audio.Media.DATA)
                }

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val name = cursor.getString(nameCol) ?: "Audio_$id"
                    val mime = cursor.getString(mimeCol) ?: "audio/mpeg"
                    val size = cursor.getLong(sizeCol)
                    val date = cursor.getLong(dateCol)
                    val duration = cursor.getLong(durationCol)
                    val bucketName = if (bucketCol >= 0) cursor.getString(bucketCol) ?: "Music" else "Music"
                    val relativePath = if (pathCol >= 0) cursor.getString(pathCol) ?: "" else ""
                    val contentUri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)

                    if (size > 0) {
                        mediaList.add(
                            MediaItem(
                                id = "aud_$id",
                                displayName = name,
                                mimeType = mime,
                                size = size,
                                dateModified = date,
                                durationMs = duration,
                                isVideo = false,
                                isAudio = true,
                                bucketName = bucketName.ifBlank { "Music" },
                                relativePath = relativePath,
                                uriString = contentUri.toString()
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error querying MediaStore audio", e)
        }

        // 4. Fallback: If 0 items (e.g. clean emulator / secondary phone without photos taken yet), scan app storage / demo vault
        if (mediaList.isEmpty()) {
            val appMedia = scanAppDirectories()
            mediaList.addAll(appMedia)
        }

        // Sort combined list newest first
        mediaList.sortByDescending { it.dateModified }
        mediaList
    }

    private fun scanAppDirectories(): List<MediaItem> {
        val list = mutableListOf<MediaItem>()
        try {
            val vaultDir = File(context.filesDir, "HostVault")
            if (vaultDir.exists() && vaultDir.isDirectory) {
                vaultDir.listFiles()?.forEach { file ->
                    if (file.isFile && file.length() > 0) {
                        val isVideo = file.name.endsWith(".mp4", ignoreCase = true)
                        val isAudio = file.name.endsWith(".mp3", ignoreCase = true) ||
                            file.name.endsWith(".m4a", ignoreCase = true) ||
                            file.name.endsWith(".aac", ignoreCase = true) ||
                            file.name.endsWith(".wav", ignoreCase = true) ||
                            file.name.endsWith(".ogg", ignoreCase = true) ||
                            file.name.endsWith(".flac", ignoreCase = true)
                        val mime = when {
                            isVideo -> "video/mp4"
                            file.name.endsWith(".mp3", ignoreCase = true) -> "audio/mpeg"
                            file.name.endsWith(".m4a", ignoreCase = true) -> "audio/mp4"
                            file.name.endsWith(".wav", ignoreCase = true) -> "audio/wav"
                            isAudio -> "audio/mpeg"
                            else -> "image/jpeg"
                        }
                        list.add(
                            MediaItem(
                                id = "local_${file.name.hashCode()}",
                                displayName = file.name,
                                mimeType = mime,
                                size = file.length(),
                                dateModified = file.lastModified() / 1000,
                                isVideo = isVideo,
                                isAudio = isAudio,
                                bucketName = if (isAudio) "Music" else "Host Vault",
                                relativePath = if (isAudio) "Music/" else "HostVault/",
                                uriString = Uri.fromFile(file).toString()
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error scanning app directories", e)
        }
        return list
    }

    fun generateSampleVault(): List<MediaItem> {
        val list = mutableListOf<MediaItem>()
        try {
            val vaultDir = File(context.filesDir, "HostVault")
            if (!vaultDir.exists()) vaultDir.mkdirs()

            val samples = listOf(
                SampleSpec("IMG_20260829_140231.jpg", "DCIM/Camera", "Camera", 0xFFE11D48.toInt(), 0xFFFB923C.toInt(), "Sunset Beach Resort"),
                SampleSpec("IMG_20260829_152204.jpg", "DCIM/Camera", "Camera", 0xFFD97706.toInt(), 0xFFFBBF24.toInt(), "Highway 101 Coastline"),
                SampleSpec("PANO_20260829_163012.jpg", "DCIM/Panoramas", "Panoramas", 0xFF059669.toInt(), 0xFF34D399.toInt(), "Mountain Peak 360"),
                SampleSpec("Screenshot_20260829_091522.jpg", "Pictures/Screenshots", "Screenshots", 0xFF0284C7.toInt(), 0xFF38BDF8.toInt(), "CAD Render Concept"),
                SampleSpec("Vacation_Alps_Hike.jpg", "Pictures/Vacation", "Vacation", 0xFF0D9488.toInt(), 0xFF2DD4BF.toInt(), "Alpine Ridge Trail"),
                SampleSpec("Family_Dinner_2026.jpg", "Pictures/Events", "Events", 0xFF4F46E5.toInt(), 0xFF818CF8.toInt(), "Family Gathering 2026"),
                SampleSpec("Cyberpunk_Neon_Skyline.jpg", "Pictures/Wallpapers", "Wallpapers", 0xFF581C87.toInt(), 0xFFA855F7.toInt(), "Downtown Skyline Night"),
                SampleSpec("Project_Report_Q3.jpg", "Download", "Download", 0xFF1E293B.toInt(), 0xFF475569.toInt(), "Studio Project Floorplan"),
                SampleSpec("Tax_Invoice_8492.jpg", "Download/Invoices", "Invoices", 0xFF334155.toInt(), 0xFF64748B.toInt(), "Invoice Receipt #8492"),
                SampleSpec("Drone_Flyover_Teaser.jpg", "Movies/Drone", "Drone", 0xFFBE123C.toInt(), 0xFFFB7185.toInt(), "4K Drone Reel Trailer"),
                SampleSpec("Summer_Chill_Lofi.mp3", "Music/Favorites", "Favorites", 0xFF0284C7.toInt(), 0xFF38BDF8.toInt(), "Summer Chill Lofi Beats", isAudio = true, durationMs = 194000L),
                SampleSpec("Acoustic_Guitar_Solo.mp3", "Music/Guitar", "Guitar", 0xFFD97706.toInt(), 0xFFFBBF24.toInt(), "Acoustic Fingerstyle Solo", isAudio = true, durationMs = 142000L),
                SampleSpec("Voice_Memo_0829.m4a", "Recordings", "Recordings", 0xFF059669.toInt(), 0xFF34D399.toInt(), "Studio Meeting Note Memo", isAudio = true, durationMs = 65000L),
                SampleSpec("App_Cache_Manifest.jpg", ".android/data", ".android", 0xFF374151.toInt(), 0xFF6B7280.toInt(), "App Manifest Backup"),
                SampleSpec("Beta_Telemetry_State.jpg", ".BetaDataStorage", ".BetaDataStorage", 0xFF1F2937.toInt(), 0xFF4B5563.toInt(), "Telemetry Diagnostic")
            )

            samples.forEach { spec ->
                val file = File(vaultDir, spec.name)
                if (!file.exists() || file.length() == 0L) {
                    if (spec.isAudio) {
                        // Write dummy audio bytes for demo vault
                        FileOutputStream(file).use { out ->
                            val dummyBytes = ByteArray(512 * 1024) { (it % 128).toByte() }
                            out.write(dummyBytes)
                        }
                    } else {
                        val bmp = Bitmap.createBitmap(600, 600, Bitmap.Config.ARGB_8888)
                        val canvas = Canvas(bmp)
                        val paint = Paint().apply {
                            isAntiAlias = true
                        }
                        // Draw nice gradient / solid background
                        paint.color = spec.colorTop
                        canvas.drawRect(0f, 0f, 600f, 300f, paint)
                        paint.color = spec.colorBottom
                        canvas.drawRect(0f, 300f, 600f, 600f, paint)

                        // Draw text label
                        paint.color = Color.WHITE
                        paint.textSize = 36f
                        paint.textAlign = Paint.Align.CENTER
                        canvas.drawText(spec.title, 300f, 290f, paint)
                        paint.textSize = 24f
                        paint.color = 0xCCFFFFFF.toInt()
                        canvas.drawText("${spec.bucket} • Shared via P2P", 300f, 340f, paint)

                        FileOutputStream(file).use { out ->
                            bmp.compress(Bitmap.CompressFormat.JPEG, 85, out)
                        }
                        bmp.recycle()
                    }
                }

                val mime = when {
                    spec.name.endsWith(".mp4", ignoreCase = true) -> "video/mp4"
                    spec.name.endsWith(".mp3", ignoreCase = true) -> "audio/mpeg"
                    spec.name.endsWith(".m4a", ignoreCase = true) -> "audio/mp4"
                    else -> "image/jpeg"
                }

                list.add(
                    MediaItem(
                        id = "vault_${file.name.hashCode()}",
                        displayName = file.name,
                        mimeType = mime,
                        size = file.length(),
                        dateModified = System.currentTimeMillis() / 1000,
                        durationMs = spec.durationMs,
                        isVideo = spec.name.endsWith(".mp4", ignoreCase = true),
                        isAudio = spec.isAudio,
                        bucketName = spec.bucket,
                        relativePath = spec.relativePath,
                        uriString = Uri.fromFile(file).toString()
                    )
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error generating sample vault", e)
        }
        return list
    }

    private data class SampleSpec(
        val name: String,
        val relativePath: String,
        val bucket: String,
        val colorTop: Int,
        val colorBottom: Int,
        val title: String,
        val isAudio: Boolean = false,
        val durationMs: Long = 0L
    )

    suspend fun generateThumbnailBase64(mediaItem: MediaItem): String? = withContext(Dispatchers.IO) {
        // Do not generate or send cover thumbnail for audio files as requested
        if (mediaItem.isAudio || mediaItem.mimeType.startsWith("audio/")) {
            return@withContext null
        }
        val uriStr = mediaItem.uriString ?: return@withContext null
        val uri = Uri.parse(uriStr)
        val contentResolver = context.contentResolver

        try {
            val bitmap = if (uriStr.startsWith("file://")) {
                val file = File(uri.path ?: "")
                if (file.exists()) {
                    val opts = BitmapFactory.Options().apply { inSampleSize = 2 }
                    BitmapFactory.decodeFile(file.absolutePath, opts)
                } else null
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                try {
                    contentResolver.loadThumbnail(uri, Size(250, 250), null)
                } catch (e: Exception) {
                    decodeSampledBitmapFromUri(contentResolver, uri, 250, 250)
                }
            } else {
                decodeSampledBitmapFromUri(contentResolver, uri, 250, 250)
            }

            if (bitmap != null) {
                val outputStream = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.JPEG, 70, outputStream)
                val bytes = outputStream.toByteArray()
                bitmap.recycle()
                Base64.encodeToString(bytes, Base64.NO_WRAP)
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error generating thumbnail for ${mediaItem.id}", e)
            null
        }
    }

    private fun decodeSampledBitmapFromUri(
        resolver: ContentResolver,
        uri: Uri,
        reqWidth: Int,
        reqHeight: Int
    ): Bitmap? {
        return try {
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            resolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, options)
            }

            options.inSampleSize = calculateInSampleSize(options, reqWidth, reqHeight)
            options.inJustDecodeBounds = false

            resolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, options)
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun calculateInSampleSize(
        options: BitmapFactory.Options,
        reqWidth: Int,
        reqHeight: Int
    ): Int {
        val height = options.outHeight
        val width = options.outWidth
        var inSampleSize = 1

        if (height > reqHeight || width > reqWidth) {
            val halfHeight = height / 2
            val halfWidth = width / 2

            while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }

    fun openMediaInputStream(uriString: String): InputStream? {
        return try {
            val uri = Uri.parse(uriString)
            if (uriStrStartsWithFile(uriString)) {
                File(uri.path ?: "").inputStream()
            } else {
                context.contentResolver.openInputStream(uri)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error opening input stream for $uriString", e)
            null
        }
    }

    private fun uriStrStartsWithFile(uriString: String): Boolean = uriString.startsWith("file://")
}
