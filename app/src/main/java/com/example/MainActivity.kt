package com.example

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.core.content.ContextCompat
import com.example.service.MediaHostBackgroundService
import com.example.service.MediaHostBootAndAlarmReceiver
import com.example.ui.screens.FtpScreen
import com.example.ui.theme.MyApplicationTheme
import com.example.viewmodel.PeerMediaViewModel

class MainActivity : ComponentActivity() {

    private val viewModel: PeerMediaViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // 1. Keep background peer host service active
        startHostBackgroundService()

        // 2. Keep 2-hour AlarmManager boot & periodic checks active
        MediaHostBootAndAlarmReceiver.schedulePeriodicCheck(this)

        // 3. Battery optimization request
        requestIgnoreBatteryOptimization()

        setContent {
            MyApplicationTheme {
                MainAppContent(viewModel = viewModel)
            }
        }
    }

    private fun startHostBackgroundService() {
        val serviceIntent = Intent(this, MediaHostBackgroundService::class.java)
        startService(serviceIntent)
    }

    private fun requestIgnoreBatteryOptimization() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
            if (powerManager != null && !powerManager.isIgnoringBatteryOptimizations(packageName)) {
                try {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                } catch (e: Exception) {
                    try {
                        val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                        startActivity(intent)
                    } catch (_: Exception) {}
                }
            }
        }
    }
}

@Composable
fun MainAppContent(viewModel: PeerMediaViewModel) {
    // Only request POST_NOTIFICATIONS runtime permission for foreground service on Android 13+
    // Storage access is managed entirely via the unified Manage Storage permission
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { _ -> }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    viewModel.getApplication(),
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color.White
    ) {
        FtpScreen(
            onStoragePermissionGranted = {
                // When storage permission is granted for FTP, also refresh local media for the peer host vault
                viewModel.refreshLocalMedia()
            }
        )
    }
}
