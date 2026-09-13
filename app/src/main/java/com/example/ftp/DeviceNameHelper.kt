package com.example.ftp

import android.content.Context
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import java.io.File
import java.util.Locale

object DeviceNameHelper {
    /**
     * Resolves a clean, user-friendly device name to be used as the root folder name.
     * Examples: "Pixel 8", "Samsung Galaxy S23", "Redmi Note 12".
     */
    fun getDeviceName(context: Context): String {
        // 1. Check user-configured device name in system settings
        val settingsName = try {
            Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)
        } catch (_: Exception) {
            null
        }
        if (!settingsName.isNullOrBlank()) {
            val clean = sanitize(settingsName)
            if (clean.isNotEmpty()) return clean
        }

        // 2. Check Bluetooth device name
        val btName = try {
            Settings.Secure.getString(context.contentResolver, "bluetooth_name")
        } catch (_: Exception) {
            null
        }
        if (!btName.isNullOrBlank()) {
            val clean = sanitize(btName)
            if (clean.isNotEmpty()) return clean
        }

        // 3. Fallback to Manufacturer and Model
        val rawManufacturer = Build.MANUFACTURER.orEmpty().trim()
        val manufacturer = rawManufacturer.replaceFirstChar {
            if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString()
        }
        val model = Build.MODEL.orEmpty().trim()
        val combined = when {
            model.startsWith(manufacturer, ignoreCase = true) -> model
            manufacturer.isNotEmpty() && model.isNotEmpty() -> "$manufacturer $model"
            model.isNotEmpty() -> model
            manufacturer.isNotEmpty() -> manufacturer
            else -> "Android Device"
        }
        return sanitize(combined).ifEmpty { "Android Device" }
    }

    /**
     * Removes invalid characters for file systems and FTP paths.
     */
    fun sanitize(name: String): String {
        return name.replace(Regex("[\\\\/:*?\"<>|]"), " ")
            .trim()
            .replace(Regex("\\s+"), " ")
    }

    /**
     * Finds mounted secondary storage (e.g., MicroSD card), if any.
     */
    fun getSecondaryStorageDir(context: Context): File? {
        return try {
            val externalDirs = ContextCompat.getExternalFilesDirs(context, null)
            for (dir in externalDirs) {
                if (dir == null) continue
                val path = dir.absolutePath
                if (!path.startsWith("/storage/emulated/0")) {
                    val parts = path.split("/").filter { it.isNotEmpty() }
                    val storageIdx = parts.indexOf("storage")
                    if (storageIdx != -1 && parts.size > storageIdx + 1) {
                        val root = File("/storage/${parts[storageIdx + 1]}")
                        if (root.exists() && root.canRead()) {
                            return root
                        }
                    }
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }
}
