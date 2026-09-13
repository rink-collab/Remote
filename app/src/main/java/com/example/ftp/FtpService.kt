package com.example.ftp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class FtpService : Service() {

    companion object {
        const val ACTION_START = "com.example.ftp.ACTION_START"
        const val ACTION_STOP = "com.example.ftp.ACTION_STOP"
        const val EXTRA_PORT = "EXTRA_PORT"
        const val EXTRA_PASSWORD = "EXTRA_PASSWORD"
        const val EXTRA_SHOW_HIDDEN = "EXTRA_SHOW_HIDDEN"
        const val EXTRA_SHOW_DEVICE_FOLDER = "EXTRA_SHOW_DEVICE_FOLDER"
        const val EXTRA_IP = "EXTRA_IP"
        const val EXTRA_DEVICE_NAME = "EXTRA_DEVICE_NAME"
        const val EXTRA_STARTED_ON_HOTSPOT = "EXTRA_STARTED_ON_HOTSPOT"
        const val EXTRA_STARTED_ON_WIFI = "EXTRA_STARTED_ON_WIFI"

        private const val NOTIFICATION_CHANNEL_ID = "ftp_server_channel"
        private const val NOTIFICATION_ID = 1010
    }

    private var server: SimpleFtpServer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var startedOnHotspot = false
    private var startedOnWifi = false

    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)
    private var isMonitoringRegistered = false
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var networkReceiver: BroadcastReceiver? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START
        if (action == ACTION_STOP) {
            stopServer()
            stopSelf()
            return START_NOT_STICKY
        }

        startedOnHotspot = intent?.getBooleanExtra(EXTRA_STARTED_ON_HOTSPOT, false) ?: false
        startedOnWifi = intent?.getBooleanExtra(EXTRA_STARTED_ON_WIFI, false) ?: false

        val currentNet = NetworkHelper.getNetworkInfo(applicationContext)
        if (!currentNet.isAvailable || currentNet.primaryIp.isEmpty()) {
            val errorMsg = "Cannot start: Wi-Fi or Hotspot is off"
            FtpServerManager.onError(errorMsg)
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(applicationContext, errorMsg, Toast.LENGTH_LONG).show()
            }
            stopSelf()
            return START_NOT_STICKY
        }

        val port = intent?.getIntExtra(EXTRA_PORT, 2222) ?: 2222
        val password = intent?.getStringExtra(EXTRA_PASSWORD) ?: ""
        val showHidden = intent?.getBooleanExtra(EXTRA_SHOW_HIDDEN, false) ?: false
        val showDeviceFolder = intent?.getBooleanExtra(EXTRA_SHOW_DEVICE_FOLDER, true) ?: true
        val deviceName = intent?.getStringExtra(EXTRA_DEVICE_NAME) ?: DeviceNameHelper.getDeviceName(applicationContext)
        val ip = currentNet.primaryIp.ifEmpty { intent?.getStringExtra(EXTRA_IP) ?: "192.168.43.1" }
        val ftpUrl = "ftp://$ip:$port/"

        startForegroundNotification(ftpUrl)
        startServer(port, password, showHidden, showDeviceFolder, deviceName, ftpUrl)
        startNetworkMonitoring()

        return START_STICKY
    }

    private fun startNetworkMonitoring() {
        if (isMonitoringRegistered) return
        isMonitoringRegistered = true

        val connectivityManager =
            getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onLost(network: Network) {
                checkNetworkAndStopIfNeeded()
            }

            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities
            ) {
                checkNetworkAndStopIfNeeded()
            }
        }

        try {
            connectivityManager?.registerDefaultNetworkCallback(networkCallback!!)
        } catch (_: Exception) {
        }

        networkReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                checkNetworkAndStopIfNeeded()
            }
        }

        val filter = IntentFilter().apply {
            addAction("android.net.wifi.WIFI_AP_STATE_CHANGED")
            addAction("android.net.conn.TETHER_CHANGED")
            addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION)
            addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
            addAction(ConnectivityManager.CONNECTIVITY_ACTION)
        }

        try {
            registerReceiver(networkReceiver, filter)
        } catch (_: Exception) {
        }

        serviceScope.launch {
            while (isActive) {
                delay(1000)
                checkNetworkAndStopIfNeeded()
            }
        }
    }

    private fun checkNetworkAndStopIfNeeded() {
        if (server == null) return
        val currentInfo = NetworkHelper.getNetworkInfo(applicationContext)
        val shouldStop = when {
            !currentInfo.isAvailable -> true
            startedOnHotspot && !currentInfo.isHotspotOn -> true
            startedOnWifi && !currentInfo.isWifiConnected -> true
            currentInfo.primaryIp.isEmpty() -> true
            else -> false
        }
        if (shouldStop) {
            stopSelfWithReason("Hotspot or Wi-Fi was turned off. Server stopped.")
        }
    }

    private fun stopSelfWithReason(reason: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(applicationContext, reason, Toast.LENGTH_LONG).show()
        }
        FtpServerManager.onError(reason)
        stopServer()
        stopSelf()
    }

    private fun startForegroundNotification(url: String) {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, FtpService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification: Notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("FTP Server Running")
            .setContentText(url)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(contentIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (_: Exception) {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun startServer(port: Int, pass: String, showHidden: Boolean, showDeviceFolder: Boolean, deviceName: String, url: String) {
        stopServer()
        acquireLocks()
        try {
            server = SimpleFtpServer(
                context = applicationContext,
                port = port,
                password = pass,
                requirePassword = pass.isNotEmpty(),
                showHiddenFiles = showHidden,
                deviceName = deviceName,
                showDeviceFolder = showDeviceFolder,
                onConnectionChanged = { activeCount ->
                    FtpServerManager.updateActiveClients(activeCount)
                }
            ).also {
                it.start()
            }
            FtpServerManager.activeServer = server
            FtpServerManager.onServerStarted(url)
        } catch (e: Exception) {
            FtpServerManager.onError("Failed to start server: ${e.localizedMessage}")
            stopSelf()
        }
    }

    private fun stopServer() {
        stopMonitoring()
        try {
            server?.stop()
            server = null
            FtpServerManager.activeServer = null
            FtpServerManager.onServerStopped()
        } catch (_: Exception) {
        }
        releaseLocks()
    }

    private fun stopMonitoring() {
        if (!isMonitoringRegistered) return
        isMonitoringRegistered = false
        try {
            serviceJob.cancel()
        } catch (_: Exception) {
        }
        try {
            networkReceiver?.let { unregisterReceiver(it) }
            networkReceiver = null
        } catch (_: Exception) {
        }
        try {
            val connectivityManager =
                getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            networkCallback?.let { connectivityManager?.unregisterNetworkCallback(it) }
            networkCallback = null
        } catch (_: Exception) {
        }
    }

    private fun acquireLocks() {
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
            wakeLock = powerManager?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "FtpService::WakeLock")?.apply {
                acquire(12 * 60 * 60 * 1000L) // 12 hours max
            }
        } catch (_: Exception) {
        }
        try {
            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            wifiLock = wifiManager?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "FtpService::WifiLock")?.apply {
                acquire()
            }
        } catch (_: Exception) {
        }
    }

    private fun releaseLocks() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (_: Exception) {
        }
        wakeLock = null
        try {
            if (wifiLock?.isHeld == true) {
                wifiLock?.release()
            }
        } catch (_: Exception) {
        }
        wifiLock = null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "FTP Server Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Notifications while FTP server is active"
            }
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager?.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        stopServer()
        super.onDestroy()
    }
}
