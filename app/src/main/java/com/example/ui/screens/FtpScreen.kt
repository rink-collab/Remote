package com.example.ui.screens

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiTethering
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.ftp.FtpServerManager
import com.example.ftp.NetworkHelper
import com.example.ftp.NetworkInfo
import com.example.ui.theme.CardBackground
import com.example.ui.theme.CyanBorder
import com.example.ui.theme.DarkTopBar
import com.example.ui.theme.StopBtnBackground
import com.example.ui.theme.StopBtnText
import com.example.ui.theme.StopRed
import com.example.ui.theme.SurfaceLight
import com.example.ui.theme.TealDark
import com.example.ui.theme.TealPrimary
import com.example.ui.theme.TextDark
import com.example.ui.theme.TextSecondary
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FtpScreen(
    onNavigateToPeerHost: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    var networkInfo by remember { mutableStateOf(NetworkHelper.getNetworkInfo(context)) }
    var showGuideDialog by remember { mutableStateOf(false) }
    var showPermissionDialog by remember { mutableStateOf(false) }
    var showNoNetworkDialog by remember { mutableStateOf(false) }

    // Periodically refresh network status to catch hotspot / Wi-Fi toggles
    LaunchedEffect(Unit) {
        while (isActive) {
            val info = NetworkHelper.getNetworkInfo(context)
            networkInfo = info
            if (FtpServerManager.isRunning.value) {
                val shouldStop = when {
                    !info.isAvailable -> true
                    FtpServerManager.startedOnHotspot && !info.isHotspotOn -> true
                    FtpServerManager.startedOnWifi && !info.isWifiConnected -> true
                    info.primaryIp.isEmpty() -> true
                    else -> false
                }
                if (shouldStop) {
                    FtpServerManager.stopService(context)
                    Toast.makeText(
                        context,
                        "Hotspot or Wi-Fi was turned off. Server stopped.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
            delay(1000)
        }
    }

    val legacyStorageLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { _ -> }

    fun hasFullStoragePermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    fun requestStorageAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = Uri.parse("package:${context.packageName}")
                }
                context.startActivity(intent)
            } catch (_: Exception) {
                val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                context.startActivity(intent)
            }
        } else {
            legacyStorageLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(
                modifier = Modifier.width(300.dp),
                drawerContainerColor = CardBackground
            ) {
                DrawerContent(
                    networkInfo = networkInfo,
                    onOpenGuide = {
                        coroutineScope.launch { drawerState.close() }
                        showGuideDialog = true
                    },
                    onRequestStorage = {
                        coroutineScope.launch { drawerState.close() }
                        requestStorageAccess()
                    },
                    onRefreshNetwork = {
                        networkInfo = NetworkHelper.getNetworkInfo(context)
                        Toast.makeText(context, "Network status refreshed", Toast.LENGTH_SHORT).show()
                    },
                    onOpenPeerHost = {
                        coroutineScope.launch { drawerState.close() }
                        onNavigateToPeerHost()
                    }
                )
            }
        }
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            text = "Access from network",
                            color = Color.White,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Medium
                        )
                    },
                    navigationIcon = {
                        IconButton(
                            onClick = { coroutineScope.launch { drawerState.open() } },
                            modifier = Modifier.testTag("menu_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Menu,
                                contentDescription = "Menu",
                                tint = Color.White
                            )
                        }
                    },
                    actions = {
                        IconButton(
                            onClick = onNavigateToPeerHost,
                            modifier = Modifier.testTag("peer_host_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Hub,
                                contentDescription = "Remote Peer Host",
                                tint = Color.White
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = DarkTopBar
                    )
                )
            },
            containerColor = Color.White
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.TopCenter
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .widthIn(max = 540.dp)
                        .padding(horizontal = 20.dp, vertical = 16.dp)
                        .verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = networkInfo.statusText,
                        color = TextSecondary,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Normal,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .padding(bottom = 14.dp)
                            .testTag("network_status_text")
                    )

                    FtpCard(
                        networkInfo = networkInfo,
                        onCheckStoragePermission = {
                            if (!hasFullStoragePermission()) {
                                showPermissionDialog = true
                            }
                        },
                        onNoNetwork = {
                            showNoNetworkDialog = true
                        }
                    )
                }
            }
        }
    }

    if (showGuideDialog) {
        ConnectionGuideDialog(
            primaryIp = networkInfo.primaryIp,
            port = FtpServerManager.port.collectAsState().value,
            onDismiss = { showGuideDialog = false }
        )
    }

    if (showNoNetworkDialog) {
        AlertDialog(
            onDismissRequest = { showNoNetworkDialog = false },
            icon = {
                Icon(
                    imageVector = Icons.Default.NetworkCheck,
                    contentDescription = null,
                    tint = StopRed,
                    modifier = Modifier.size(32.dp)
                )
            },
            title = { Text("Wi-Fi or Hotspot is off") },
            text = {
                Text("Please connect to a Wi-Fi network or turn on Portable Hotspot before starting the FTP server.")
            },
            confirmButton = {
                Button(
                    onClick = {
                        showNoNetworkDialog = false
                        try {
                            context.startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS))
                        } catch (_: Exception) {
                            try {
                                context.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
                            } catch (_: Exception) {}
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = TealPrimary)
                ) {
                    Text("Settings")
                }
            },
            dismissButton = {
                TextButton(onClick = { showNoNetworkDialog = false }) {
                    Text("OK")
                }
            }
        )
    }

    if (showPermissionDialog) {
        AlertDialog(
            onDismissRequest = { showPermissionDialog = false },
            icon = {
                Icon(
                    imageVector = Icons.Default.Folder,
                    contentDescription = null,
                    tint = TealPrimary,
                    modifier = Modifier.size(32.dp)
                )
            },
            title = { Text("Storage Access") },
            text = {
                Text(
                    "To access all files and folders (e.g. Downloads, Photos, Documents) over FTP, please allow All Files Access. Otherwise, only app-specific files will be accessible."
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showPermissionDialog = false
                        requestStorageAccess()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = TealPrimary)
                ) {
                    Text("Grant Permission")
                }
            },
            dismissButton = {
                TextButton(onClick = { showPermissionDialog = false }) {
                    Text("Continue Anyway")
                }
            }
        )
    }
}

@Composable
fun FtpCard(
    networkInfo: NetworkInfo,
    onCheckStoragePermission: () -> Unit,
    onNoNetwork: () -> Unit
) {
    val context = LocalContext.current
    val isRunning by FtpServerManager.isRunning.collectAsState()
    val port by FtpServerManager.port.collectAsState()
    val password by FtpServerManager.password.collectAsState()
    val isRandomPassword by FtpServerManager.isRandomPassword.collectAsState()
    val showHiddenFiles by FtpServerManager.showHiddenFiles.collectAsState()
    val errorMessage by FtpServerManager.errorMessage.collectAsState()

    var portInput by remember(port) { mutableStateOf(port.toString()) }
    var passwordInput by remember(password) { mutableStateOf(password) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("ftp_card"),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = CardBackground),
        border = BorderStroke(1.5.dp, CyanBorder)
    ) {
        if (!isRunning) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Row 1: Random password checkbox
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = !isRunning) {
                            FtpServerManager.setRandomPassword(!isRandomPassword)
                        }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = isRandomPassword,
                        onCheckedChange = { checked ->
                            FtpServerManager.setRandomPassword(checked)
                        },
                        enabled = !isRunning,
                        colors = CheckboxDefaults.colors(
                            checkedColor = CyanBorder,
                            uncheckedColor = TextSecondary
                        ),
                        modifier = Modifier.testTag("random_password_checkbox")
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Random password",
                        fontSize = 16.sp,
                        color = if (isRunning) TextSecondary else TextDark
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Row 2: Port row
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Port",
                        fontSize = 16.sp,
                        color = TextSecondary,
                        modifier = Modifier.width(90.dp)
                    )
                    Box(
                        modifier = Modifier
                            .width(130.dp)
                            .padding(bottom = 2.dp)
                    ) {
                        Column {
                            BasicTextField(
                                value = portInput,
                                onValueChange = { input ->
                                    if (!isRunning && input.all { it.isDigit() } && input.length <= 5) {
                                        portInput = input
                                        input.toIntOrNull()?.let { p ->
                                            if (p in 1024..65535) {
                                                FtpServerManager.setPort(p)
                                            }
                                        }
                                    }
                                },
                                enabled = !isRunning,
                                textStyle = TextStyle(
                                    fontSize = 17.sp,
                                    color = if (isRunning) TextSecondary else TextDark,
                                    fontWeight = FontWeight.Normal
                                ),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                singleLine = true,
                                cursorBrush = SolidColor(CyanBorder),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("port_input")
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(1.dp)
                                    .background(if (isRunning) Color(0xFFCCCCCC) else Color(0xFF9E9E9E))
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Row 3: Password row
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Password",
                        fontSize = 16.sp,
                        color = TextSecondary,
                        modifier = Modifier.width(90.dp)
                    )
                    Box(
                        modifier = Modifier
                            .width(130.dp)
                            .padding(bottom = 2.dp)
                    ) {
                        Column {
                            BasicTextField(
                                value = if (isRandomPassword) password else passwordInput,
                                onValueChange = { input ->
                                    if (!isRunning && !isRandomPassword) {
                                        passwordInput = input
                                        FtpServerManager.setPassword(input)
                                    }
                                },
                                enabled = !isRunning && !isRandomPassword,
                                textStyle = TextStyle(
                                    fontSize = 17.sp,
                                    color = if (isRunning || isRandomPassword) TextSecondary else TextDark,
                                    fontWeight = FontWeight.Normal
                                ),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                                singleLine = true,
                                cursorBrush = SolidColor(CyanBorder),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("password_input")
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(1.dp)
                                    .background(if (isRunning || isRandomPassword) Color(0xFFCCCCCC) else Color(0xFF9E9E9E))
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Row 4: Show hidden files checkbox
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            FtpServerManager.setShowHiddenFiles(!showHiddenFiles)
                        }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = showHiddenFiles,
                        onCheckedChange = { checked ->
                            FtpServerManager.setShowHiddenFiles(checked)
                        },
                        colors = CheckboxDefaults.colors(
                            checkedColor = CyanBorder,
                            uncheckedColor = TextSecondary
                        ),
                        modifier = Modifier.testTag("show_hidden_files_checkbox")
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Show hidden files",
                        fontSize = 16.sp,
                        color = TextDark
                    )
                }

                Spacer(modifier = Modifier.height(18.dp))

                HorizontalDivider(
                    color = CyanBorder.copy(alpha = 0.5f),
                    thickness = 1.dp,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(20.dp))

                Text(
                    text = "Start the service to access files\nfrom the network.",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextDark,
                    textAlign = TextAlign.Center,
                    lineHeight = 24.sp,
                    modifier = Modifier.padding(horizontal = 8.dp)
                )

                if (errorMessage != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = errorMessage ?: "",
                        color = StopRed,
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center
                    )
                }

                Spacer(modifier = Modifier.height(20.dp))

                Button(
                    onClick = {
                        val currentNet = NetworkHelper.getNetworkInfo(context)
                        if (!currentNet.isAvailable || currentNet.primaryIp.isEmpty()) {
                            Toast.makeText(
                                context,
                                "Please connect to Wi-Fi or turn on Hotspot first",
                                Toast.LENGTH_LONG
                            ).show()
                            onNoNetwork()
                            return@Button
                        }
                        onCheckStoragePermission()
                        FtpServerManager.startService(
                            context = context,
                            ipAddress = currentNet.primaryIp,
                            startedOnHotspot = currentNet.isHotspotOn,
                            startedOnWifi = currentNet.isWifiConnected
                        )
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = TealPrimary),
                    shape = RoundedCornerShape(4.dp),
                    modifier = Modifier
                        .fillMaxWidth(0.85f)
                        .height(44.dp)
                        .testTag("start_service_button")
                ) {
                    Text(
                        text = "START SERVICE",
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        } else {
            val ftpAddress = "ftp://${networkInfo.primaryIp}:$port"
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = ftpAddress,
                    color = TealPrimary,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable {
                            val clipboard =
                                context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("FTP URL", ftpAddress))
                            Toast.makeText(context, "Copied: $ftpAddress", Toast.LENGTH_SHORT).show()
                        }
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                        .testTag("ftp_url_text")
                )

                Spacer(modifier = Modifier.height(20.dp))

                Text(
                    text = "Username",
                    fontSize = 15.sp,
                    color = TextSecondary,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "pc",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = TealPrimary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.testTag("ftp_username_text")
                )

                Spacer(modifier = Modifier.height(14.dp))

                Text(
                    text = "Password",
                    fontSize = 15.sp,
                    color = TextSecondary,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = password.ifEmpty { "123456" },
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = TealPrimary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.testTag("ftp_password_text")
                )

                Spacer(modifier = Modifier.height(28.dp))

                HorizontalDivider(
                    color = CyanBorder.copy(alpha = 0.7f),
                    thickness = 1.dp,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(24.dp))

                Text(
                    text = "Enter the above address in a file explorer on PC.",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextDark,
                    textAlign = TextAlign.Center,
                    lineHeight = 25.sp,
                    modifier = Modifier.padding(horizontal = 10.dp)
                )

                Spacer(modifier = Modifier.height(24.dp))

                Button(
                    onClick = {
                        FtpServerManager.stopService(context)
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = StopBtnBackground,
                        contentColor = StopBtnText
                    ),
                    shape = RoundedCornerShape(4.dp),
                    elevation = ButtonDefaults.buttonElevation(
                        defaultElevation = 2.dp,
                        pressedElevation = 4.dp
                    ),
                    modifier = Modifier
                        .width(180.dp)
                        .height(44.dp)
                        .testTag("stop_service_button")
                ) {
                    Text(
                        text = "STOP SERVICE",
                        color = StopBtnText,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

@Composable
fun DrawerContent(
    networkInfo: NetworkInfo,
    onOpenGuide: () -> Unit,
    onRequestStorage: () -> Unit,
    onRefreshNetwork: () -> Unit,
    onOpenPeerHost: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(vertical = 24.dp)
    ) {
        Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
            Text(
                text = "FTP File Server",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = TealDark
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Wireless high-speed file transfer",
                fontSize = 13.sp,
                color = TextSecondary
            )
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

        NavigationDrawerItem(
            label = { Text("Connection Guide") },
            icon = { Icon(Icons.Default.Devices, contentDescription = null, tint = TealPrimary) },
            selected = false,
            onClick = onOpenGuide,
            modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
        )

        NavigationDrawerItem(
            label = { Text("Storage Permissions") },
            icon = { Icon(Icons.Default.Folder, contentDescription = null, tint = TealPrimary) },
            selected = false,
            onClick = onRequestStorage,
            modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
        )

        NavigationDrawerItem(
            label = { Text("Remote Peer Host Vault") },
            icon = { Icon(Icons.Default.Hub, contentDescription = null, tint = TealPrimary) },
            selected = false,
            onClick = onOpenPeerHost,
            modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
        )

        NavigationDrawerItem(
            label = { Text("Refresh Network") },
            icon = { Icon(Icons.Default.Refresh, contentDescription = null, tint = TealPrimary) },
            selected = false,
            onClick = onRefreshNetwork,
            modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
        )

        Spacer(modifier = Modifier.weight(1f))

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            shape = RoundedCornerShape(8.dp),
            color = SurfaceLight
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = if (networkInfo.isHotspotOn) Icons.Default.WifiTethering else Icons.Default.Wifi,
                        contentDescription = null,
                        tint = TealPrimary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (networkInfo.isHotspotOn) "Hotspot Active" else if (networkInfo.isWifiConnected) "Wi-Fi Active" else "Offline",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "IP: ${networkInfo.primaryIp}",
                    fontSize = 12.sp,
                    color = TextSecondary,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}

@Composable
fun ConnectionGuideDialog(
    primaryIp: String,
    port: Int,
    onDismiss: () -> Unit
) {
    val ftpAddress = "ftp://$primaryIp:$port/"

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "How to Connect",
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = "1. Network Setup",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp,
                    color = TealDark
                )
                Text(
                    text = "Ensure your PC, Mac, or other device is connected to this phone's Hotspot or the same Wi-Fi network.",
                    fontSize = 14.sp,
                    color = TextDark,
                    modifier = Modifier.padding(top = 4.dp, bottom = 12.dp)
                )

                Text(
                    text = "2. Windows File Explorer",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp,
                    color = TealDark
                )
                Text(
                    text = "  Open File Explorer (Win + E)\n  Click on the address bar at the top\n  Type:\n  $ftpAddress\n  Press Enter to browse and drag-and-drop files!",
                    fontSize = 14.sp,
                    color = TextDark,
                    modifier = Modifier.padding(top = 4.dp, bottom = 12.dp)
                )

                Text(
                    text = "3. Mac Finder",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp,
                    color = TealDark
                )
                Text(
                    text = "  Open Finder and press Command + K\n  Enter: $ftpAddress\n  Click Connect.",
                    fontSize = 14.sp,
                    color = TextDark,
                    modifier = Modifier.padding(top = 4.dp, bottom = 12.dp)
                )

                Text(
                    text = "4. FTP Clients (FileZilla / WinSCP)",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp,
                    color = TealDark
                )
                Text(
                    text = "  Host: $primaryIp\n  Port: $port\n  Encryption: Plain FTP\n  Logon Type: Normal or Anonymous",
                    fontSize = 14.sp,
                    color = TextDark,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(containerColor = TealPrimary)
            ) {
                Text("Got It")
            }
        }
    )
}
