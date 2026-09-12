package com.example.ftp

import android.content.Context
import android.content.Intent
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object FtpServerManager {
    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private val _port = MutableStateFlow(2222)
    val port: StateFlow<Int> = _port.asStateFlow()

    private val _password = MutableStateFlow("123456")
    val password: StateFlow<String> = _password.asStateFlow()

    private val _isRandomPassword = MutableStateFlow(false)
    val isRandomPassword: StateFlow<Boolean> = _isRandomPassword.asStateFlow()

    private val _showHiddenFiles = MutableStateFlow(false)
    val showHiddenFiles: StateFlow<Boolean> = _showHiddenFiles.asStateFlow()

    private val _activeClients = MutableStateFlow(0)
    val activeClients: StateFlow<Int> = _activeClients.asStateFlow()

    private val _serverUrl = MutableStateFlow<String?>(null)
    val serverUrl: StateFlow<String?> = _serverUrl.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    var startedOnHotspot: Boolean = false
        private set

    var startedOnWifi: Boolean = false
        private set

    var activeServer: SimpleFtpServer? = null

    fun setPort(port: Int) {
        if (!_isRunning.value) {
            _port.value = port
        }
    }

    fun setPassword(password: String) {
        if (!_isRunning.value) {
            _password.value = password
        }
    }

    fun setRandomPassword(enabled: Boolean) {
        _isRandomPassword.value = enabled
        if (enabled && !_isRunning.value) {
            val randomSixDigits = (100000..999999).random().toString()
            _password.value = randomSixDigits
        }
    }

    fun setShowHiddenFiles(show: Boolean) {
        _showHiddenFiles.value = show
    }

    fun startService(
        context: Context,
        ipAddress: String,
        startedOnHotspot: Boolean = false,
        startedOnWifi: Boolean = false
    ) {
        _startedOnHotspot = startedOnHotspot
        _startedOnWifi = startedOnWifi
        _errorMessage.value = null

        val intent = Intent(context, FtpService::class.java).apply {
            action = FtpService.ACTION_START
            putExtra(FtpService.EXTRA_PORT, _port.value)
            putExtra(FtpService.EXTRA_PASSWORD, _password.value)
            putExtra(FtpService.EXTRA_SHOW_HIDDEN, _showHiddenFiles.value)
            putExtra(FtpService.EXTRA_IP, ipAddress)
            putExtra(FtpService.EXTRA_STARTED_ON_HOTSPOT, startedOnHotspot)
            putExtra(FtpService.EXTRA_STARTED_ON_WIFI, startedOnWifi)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    private var _startedOnHotspot: Boolean = false
    private var _startedOnWifi: Boolean = false

    fun stopService(context: Context) {
        val intent = Intent(context, FtpService::class.java).apply {
            action = FtpService.ACTION_STOP
        }
        context.startService(intent)
    }

    fun onServerStarted(url: String) {
        _isRunning.value = true
        _serverUrl.value = url
        _errorMessage.value = null
    }

    fun onServerStopped() {
        _isRunning.value = false
        _serverUrl.value = null
        _activeClients.value = 0
        _startedOnHotspot = false
        _startedOnWifi = false
    }

    fun updateActiveClients(count: Int) {
        _activeClients.value = count
    }

    fun onError(error: String) {
        _errorMessage.value = error
        _isRunning.value = false
        _serverUrl.value = null
    }
}
