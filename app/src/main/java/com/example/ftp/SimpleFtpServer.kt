package com.example.ftp

import android.content.Context
import android.os.Environment
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicInteger

class SimpleFtpServer(
    private val context: Context,
    val port: Int = 2222,
    val password: String = "123456",
    val requirePassword: Boolean = true,
    val showHiddenFiles: Boolean = false,
    val deviceName: String = "",
    val showDeviceFolder: Boolean = true,
    private val onConnectionChanged: ((activeCount: Int) -> Unit)? = null
) {
    private val tag = "SimpleFtpServer"
    private var serverSocket: ServerSocket? = null
    private var serverJob: Job? = null
    private val activeConnections = AtomicInteger(0)
    private val scope = CoroutineScope(Dispatchers.IO)

    val isRunning: Boolean
        get() = serverSocket != null && serverSocket?.isClosed == false

    val effectiveDeviceName: String by lazy {
        if (deviceName.isNotBlank()) {
            DeviceNameHelper.sanitize(deviceName).ifEmpty { "Android Device" }
        } else {
            DeviceNameHelper.getDeviceName(context)
        }
    }

    private val rootDir: File by lazy {
        val ext = Environment.getExternalStorageDirectory()
        if (ext != null && ext.exists() && ext.canRead()) {
            ext
        } else {
            context.getExternalFilesDir(null) ?: context.filesDir
        }
    }

    private val sdCardDir: File? by lazy {
        DeviceNameHelper.getSecondaryStorageDir(context)
    }

    sealed class ResolvedTarget {
        object VirtualRoot : ResolvedTarget()
        data class Real(val file: File, val isUnderInternal: Boolean) : ResolvedTarget()
        object Invalid : ResolvedTarget()
    }

    fun start() {
        if (isRunning) return
        try {
            serverSocket = ServerSocket(port, 50, InetAddress.getByName("0.0.0.0"))
            serverSocket?.reuseAddress = true
            Log.d(tag, "FTP Server started on port $port, deviceFolder: $effectiveDeviceName, root: ${rootDir.absolutePath}")

            serverJob = scope.launch {
                while (isActive && serverSocket != null && !serverSocket!!.isClosed) {
                    try {
                        val clientSocket = serverSocket!!.accept()
                        launch {
                            handleClientSession(clientSocket)
                        }
                    } catch (e: Exception) {
                        if (isActive && !serverSocket!!.isClosed) {
                            Log.e(tag, "Accept error", e)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "Failed to start FTP server on port $port", e)
            stop()
            throw e
        }
    }

    fun stop() {
        try {
            serverJob?.cancel()
            serverJob = null
            serverSocket?.close()
            serverSocket = null
            activeConnections.set(0)
            onConnectionChanged?.invoke(0)
            Log.d(tag, "FTP Server stopped")
        } catch (e: Exception) {
            Log.e(tag, "Error stopping FTP server", e)
        }
    }

    private fun handleClientSession(socket: Socket) {
        val count = activeConnections.incrementAndGet()
        onConnectionChanged?.invoke(count)

        var reader: BufferedReader? = null
        var writer: BufferedWriter? = null
        // null means at Virtual Root "/" showing the device folder (if showDeviceFolder is true)
        var currentDir: File? = if (showDeviceFolder) null else rootDir
        var isAuthenticated = !requirePassword || password.isEmpty()
        var username: String? = null
        var renameSource: File? = null
        var pasvServer: ServerSocket? = null
        var activeHost: String? = null
        var activePort: Int = -1

        try {
            socket.soTimeout = 120_000 // 2 minutes timeout
            reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
            writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8))

            fun send(response: String) {
                writer.write("$response\r\n")
                writer.flush()
            }

            send("220 Simple FTP Server Ready")

            while (!socket.isClosed) {
                val line = reader.readLine() ?: break
                val trimmed = line.trim()
                if (trimmed.isEmpty()) continue

                val spaceIndex = trimmed.indexOf(' ')
                val command = if (spaceIndex > 0) trimmed.substring(0, spaceIndex).uppercase(Locale.US) else trimmed.uppercase(Locale.US)
                val arg = if (spaceIndex > 0) trimmed.substring(spaceIndex + 1).trim() else ""

                when (command) {
                    "USER" -> {
                        username = arg
                        if (!requirePassword || password.isEmpty()) {
                            isAuthenticated = true
                            send("230 User logged in, proceed.")
                        } else {
                            send("331 Password required for $arg")
                        }
                    }
                    "PASS" -> {
                        if (!requirePassword || password.isEmpty() || arg == password) {
                            isAuthenticated = true
                            send("230 User logged in, proceed.")
                        } else {
                            send("530 Not logged in, incorrect password.")
                        }
                    }
                    "QUIT" -> {
                        send("221 Goodbye.")
                        break
                    }
                    "NOOP" -> send("200 OK.")
                    "SYST" -> send("215 UNIX Type: L8")
                    "FEAT" -> {
                        writer.write("211-Features:\r\n UTF8\r\n SIZE\r\n MDTM\r\n MLSD\r\n PASV\r\n EPSV\r\n211 End\r\n")
                        writer.flush()
                    }
                    "OPTS" -> {
                        if (arg.uppercase(Locale.US).startsWith("UTF8")) {
                            send("200 UTF8 mode is always ON")
                        } else {
                            send("200 OPTS OK")
                        }
                    }
                    "TYPE" -> {
                        when (arg.uppercase(Locale.US)) {
                            "A", "A N" -> send("200 Type set to ASCII")
                            "I", "L 8" -> send("200 Type set to Binary")
                            else -> send("200 Type set to $arg")
                        }
                    }
                    "PWD", "XPWD" -> {
                        val virtualPath = getVirtualPath(currentDir)
                        send("257 \"$virtualPath\" is the current directory")
                    }
                    "CWD", "XCWD" -> {
                        if (!isAuthenticated) {
                            send("530 Please login with USER and PASS.")
                        } else {
                            when (val resolved = resolveTarget(currentDir, arg)) {
                                is ResolvedTarget.VirtualRoot -> {
                                    currentDir = null
                                    send("250 Directory changed to /")
                                }
                                is ResolvedTarget.Real -> {
                                    if (resolved.file.exists() && resolved.file.isDirectory) {
                                        currentDir = resolved.file
                                        send("250 Directory changed to ${getVirtualPath(currentDir)}")
                                    } else {
                                        send("550 Failed to change directory: Not a directory.")
                                    }
                                }
                                is ResolvedTarget.Invalid -> {
                                    send("550 Failed to change directory: Directory not found.")
                                }
                            }
                        }
                    }
                    "CDUP", "XCUP" -> {
                        if (!isAuthenticated) {
                            send("530 Please login with USER and PASS.")
                        } else {
                            if (!showDeviceFolder) {
                                if (currentDir == null || currentDir == rootDir) {
                                    currentDir = rootDir
                                    send("200 Already at root directory")
                                } else {
                                    val parent = currentDir!!.parentFile
                                    if (parent != null && isChildOrSame(parent, rootDir)) {
                                        currentDir = parent
                                        send("200 Directory changed to ${getVirtualPath(currentDir)}")
                                    } else {
                                        currentDir = rootDir
                                        send("200 Directory changed to /")
                                    }
                                }
                            } else {
                                if (currentDir == null) {
                                    send("200 Already at root directory")
                                } else if (currentDir == rootDir || (sdCardDir != null && currentDir == sdCardDir)) {
                                    currentDir = null
                                    send("200 Directory changed to /")
                                } else {
                                    val parent = currentDir!!.parentFile
                                    if (parent != null && isChildOrSame(parent, rootDir)) {
                                        currentDir = parent
                                        send("200 Directory changed to ${getVirtualPath(currentDir)}")
                                    } else if (parent != null && sdCardDir != null && isChildOrSame(parent, sdCardDir!!)) {
                                        currentDir = parent
                                        send("200 Directory changed to ${getVirtualPath(currentDir)}")
                                    } else {
                                        currentDir = null
                                        send("200 Directory changed to /")
                                    }
                                }
                            }
                        }
                    }
                    "PASV" -> {
                        try {
                            pasvServer?.close()
                            pasvServer = ServerSocket(0, 1, socket.localAddress)
                            val localPort = pasvServer!!.localPort
                            val p1 = localPort / 256
                            val p2 = localPort % 256
                            val localAddress = socket.localAddress
                            val hostAddress = localAddress.hostAddress ?: "127.0.0.1"
                            val parts = hostAddress.replace(':', '.').split('.').take(4)
                            val ipFormatted = if (parts.size == 4) parts.joinToString(",") else "127,0,0,1"
                            send("227 Entering Passive Mode ($ipFormatted,$p1,$p2)")
                        } catch (e: Exception) {
                            Log.e(tag, "PASV error", e)
                            send("425 Can't open passive connection")
                        }
                    }
                    "EPSV" -> {
                        try {
                            pasvServer?.close()
                            pasvServer = ServerSocket(0, 1, socket.localAddress)
                            val localPort = pasvServer!!.localPort
                            send("229 Entering Extended Passive Mode (|||$localPort|)")
                        } catch (e: Exception) {
                            Log.e(tag, "EPSV error", e)
                            send("425 Can't open extended passive connection")
                        }
                    }
                    "PORT" -> {
                        try {
                            val parts = arg.split(',')
                            if (parts.size == 6) {
                                activeHost = "${parts[0]}.${parts[1]}.${parts[2]}.${parts[3]}"
                                activePort = parts[4].toInt() * 256 + parts[5].toInt()
                                send("200 PORT command successful")
                            } else {
                                send("501 Syntax error in PORT command")
                            }
                        } catch (e: Exception) {
                            send("501 Syntax error in PORT command")
                        }
                    }
                    "LIST", "NLST" -> {
                        if (!isAuthenticated) {
                            send("530 Please login.")
                        } else {
                            val dataSocket = getDataSocket(pasvServer, activeHost, activePort)
                            pasvServer = null
                            if (dataSocket == null) {
                                send("425 Can't open data connection.")
                            } else {
                                send("150 Here comes the directory listing.")
                                try {
                                    val dataOut = BufferedWriter(OutputStreamWriter(dataSocket.getOutputStream(), Charsets.UTF_8))
                                    val pathArg = extractPathFromListArg(arg)
                                    val targetResolved = if (pathArg.isNotEmpty()) {
                                        resolveTarget(currentDir, pathArg)
                                    } else {
                                        if (currentDir == null) ResolvedTarget.VirtualRoot else ResolvedTarget.Real(currentDir!!, isChildOrSame(currentDir!!, rootDir))
                                    }
                                    val dateFormat = SimpleDateFormat("MMM dd HH:mm", Locale.US)
                                    val yearFormat = SimpleDateFormat("MMM dd  yyyy", Locale.US)
                                    val sixMonthsAgo = System.currentTimeMillis() - 180L * 24 * 60 * 60 * 1000

                                    when (targetResolved) {
                                        is ResolvedTarget.VirtualRoot -> {
                                            // Top level shows the device name folder
                                            val modTime = rootDir.lastModified().let { if (it > 0) it else System.currentTimeMillis() }
                                            val dateStr = if (modTime > sixMonthsAgo) dateFormat.format(Date(modTime)) else yearFormat.format(Date(modTime))
                                            if (command == "NLST") {
                                                dataOut.write("$effectiveDeviceName\r\n")
                                                if (sdCardDir != null && sdCardDir!!.exists()) {
                                                    dataOut.write("SD Card\r\n")
                                                }
                                            } else {
                                                dataOut.write("drwxr-xr-x 1 owner group 4096 $dateStr $effectiveDeviceName\r\n")
                                                if (sdCardDir != null && sdCardDir!!.exists()) {
                                                    val sdMod = sdCardDir!!.lastModified().let { if (it > 0) it else System.currentTimeMillis() }
                                                    val sdDateStr = if (sdMod > sixMonthsAgo) dateFormat.format(Date(sdMod)) else yearFormat.format(Date(sdMod))
                                                    dataOut.write("drwxr-xr-x 1 owner group 4096 $sdDateStr SD Card\r\n")
                                                }
                                            }
                                        }
                                        is ResolvedTarget.Real -> {
                                            val target = targetResolved.file
                                            if (target.isDirectory) {
                                                val files = target.listFiles() ?: emptyArray()
                                                for (file in files) {
                                                    if (!showHiddenFiles && file.name.startsWith(".")) continue
                                                    if (command == "NLST") {
                                                        dataOut.write("${file.name}\r\n")
                                                    } else {
                                                        val isDir = file.isDirectory
                                                        val perms = if (isDir) "drwxr-xr-x" else "-rw-r--r--"
                                                        val size = if (isDir) 4096 else file.length()
                                                        val modTime = file.lastModified().let { if (it > 0) it else System.currentTimeMillis() }
                                                        val dateStr = if (modTime > sixMonthsAgo) dateFormat.format(Date(modTime)) else yearFormat.format(Date(modTime))
                                                        dataOut.write("$perms 1 owner group $size $dateStr ${file.name}\r\n")
                                                    }
                                                }
                                            } else {
                                                val perms = "-rw-r--r--"
                                                val size = target.length()
                                                val modTime = target.lastModified().let { if (it > 0) it else System.currentTimeMillis() }
                                                val dateStr = if (modTime > sixMonthsAgo) dateFormat.format(Date(modTime)) else yearFormat.format(Date(modTime))
                                                dataOut.write("$perms 1 owner group $size $dateStr ${target.name}\r\n")
                                            }
                                        }
                                        is ResolvedTarget.Invalid -> {
                                            // Empty list if invalid
                                        }
                                    }
                                    dataOut.flush()
                                    dataSocket.close()
                                    send("226 Directory send OK.")
                                } catch (e: Exception) {
                                    Log.e(tag, "LIST transfer error", e)
                                    send("426 Connection closed; transfer aborted.")
                                } finally {
                                    dataSocket.close()
                                }
                            }
                        }
                    }
                    "MLSD" -> {
                        if (!isAuthenticated) {
                            send("530 Please login.")
                        } else {
                            val dataSocket = getDataSocket(pasvServer, activeHost, activePort)
                            pasvServer = null
                            if (dataSocket == null) {
                                send("425 Can't open data connection.")
                            } else {
                                send("150 Opening data connection for MLSD.")
                                try {
                                    val dataOut = BufferedWriter(OutputStreamWriter(dataSocket.getOutputStream(), Charsets.UTF_8))
                                    val pathArg = arg.trim()
                                    val targetResolved = if (pathArg.isNotEmpty()) {
                                        resolveTarget(currentDir, pathArg)
                                    } else {
                                        if (currentDir == null) ResolvedTarget.VirtualRoot else ResolvedTarget.Real(currentDir!!, isChildOrSame(currentDir!!, rootDir))
                                    }
                                    val mlsdFormat = SimpleDateFormat("yyyyMMddHHmmss", Locale.US).apply {
                                        timeZone = TimeZone.getTimeZone("UTC")
                                    }
                                    when (targetResolved) {
                                        is ResolvedTarget.VirtualRoot -> {
                                            val modDate = mlsdFormat.format(Date(rootDir.lastModified().let { if (it > 0) it else System.currentTimeMillis() }))
                                            dataOut.write("type=dir;size=4096;modify=$modDate;UNIX.mode=0755;UNIX.owner=owner;UNIX.group=group;perm=cdeflmp; $effectiveDeviceName\r\n")
                                            if (sdCardDir != null && sdCardDir!!.exists()) {
                                                val sdMod = mlsdFormat.format(Date(sdCardDir!!.lastModified().let { if (it > 0) it else System.currentTimeMillis() }))
                                                dataOut.write("type=dir;size=4096;modify=$sdMod;UNIX.mode=0755;UNIX.owner=owner;UNIX.group=group;perm=cdeflmp; SD Card\r\n")
                                            }
                                        }
                                        is ResolvedTarget.Real -> {
                                            val target = targetResolved.file
                                            val files = target.listFiles() ?: emptyArray()
                                            for (file in files) {
                                                if (!showHiddenFiles && file.name.startsWith(".")) continue
                                                val isDir = file.isDirectory
                                                val type = if (isDir) "dir" else "file"
                                                val size = if (isDir) "size=4096;" else "size=${file.length()};"
                                                val fileMod = file.lastModified().let { if (it > 0) it else System.currentTimeMillis() }
                                                val modify = "modify=${mlsdFormat.format(Date(fileMod))};"
                                                val perm = if (isDir) "perm=cdeflmp;" else "perm=adfrw;"
                                                val unixMode = if (isDir) "UNIX.mode=0755;" else "UNIX.mode=0644;"
                                                dataOut.write("type=$type;$size$modify${unixMode}UNIX.owner=owner;UNIX.group=group;$perm ${file.name}\r\n")
                                            }
                                        }
                                        is ResolvedTarget.Invalid -> {
                                            // Empty list
                                        }
                                    }
                                    dataOut.flush()
                                    dataSocket.close()
                                    send("226 MLSD complete.")
                                } catch (e: Exception) {
                                    send("426 Transfer aborted.")
                                } finally {
                                    dataSocket.close()
                                }
                            }
                        }
                    }
                    "RETR" -> {
                        if (!isAuthenticated) {
                            send("530 Please login.")
                        } else {
                            val resolved = resolveTarget(currentDir, arg)
                            val file = if (resolved is ResolvedTarget.Real) resolved.file else null
                            if (file == null || !file.exists() || file.isDirectory || !file.canRead()) {
                                send("550 File not found or not accessible.")
                            } else {
                                val dataSocket = getDataSocket(pasvServer, activeHost, activePort)
                                pasvServer = null
                                if (dataSocket == null) {
                                    send("425 Can't open data connection.")
                                } else {
                                    send("150 Opening BINARY mode data connection for ${file.name} (${file.length()} bytes)")
                                    try {
                                        FileInputStream(file).use { fis ->
                                            dataSocket.getOutputStream().use { os ->
                                                val buffer = ByteArray(64 * 1024)
                                                var bytesRead: Int
                                                while (fis.read(buffer).also { bytesRead = it } != -1) {
                                                    os.write(buffer, 0, bytesRead)
                                                }
                                                os.flush()
                                            }
                                        }
                                        send("226 Transfer complete.")
                                    } catch (e: Exception) {
                                        Log.e(tag, "RETR error", e)
                                        send("426 Connection closed; transfer aborted.")
                                    } finally {
                                        dataSocket.close()
                                    }
                                }
                            }
                        }
                    }
                    "STOR" -> {
                        if (!isAuthenticated) {
                            send("530 Please login.")
                        } else {
                            val targetFile = resolveWriteTarget(currentDir, arg)
                            if (targetFile == null) {
                                send("550 Cannot upload directly to root. Please open the '$effectiveDeviceName' folder first.")
                            } else {
                                val dataSocket = getDataSocket(pasvServer, activeHost, activePort)
                                pasvServer = null
                                if (dataSocket == null) {
                                    send("425 Can't open data connection.")
                                } else {
                                    send("150 Opening BINARY mode data connection for ${targetFile.name}")
                                    try {
                                        FileOutputStream(targetFile).use { fos ->
                                            dataSocket.getInputStream().use { `is` ->
                                                val buffer = ByteArray(64 * 1024)
                                                var bytesRead: Int
                                                while (`is`.read(buffer).also { bytesRead = it } != -1) {
                                                    fos.write(buffer, 0, bytesRead)
                                                }
                                                fos.flush()
                                            }
                                        }
                                        send("226 Transfer complete.")
                                    } catch (e: Exception) {
                                        Log.e(tag, "STOR error", e)
                                        send("426 Connection closed; transfer aborted.")
                                    } finally {
                                        dataSocket.close()
                                    }
                                }
                            }
                        }
                    }
                    "APPE" -> {
                        if (!isAuthenticated) {
                            send("530 Please login.")
                        } else {
                            val targetFile = resolveWriteTarget(currentDir, arg)
                            if (targetFile == null) {
                                send("550 Cannot append to root. Please open the '$effectiveDeviceName' folder first.")
                            } else {
                                val dataSocket = getDataSocket(pasvServer, activeHost, activePort)
                                pasvServer = null
                                if (dataSocket == null) {
                                    send("425 Can't open data connection.")
                                } else {
                                    send("150 Appending to ${targetFile.name}")
                                    try {
                                        FileOutputStream(targetFile, true).use { fos ->
                                            dataSocket.getInputStream().use { `is` ->
                                                val buffer = ByteArray(64 * 1024)
                                                var bytesRead: Int
                                                while (`is`.read(buffer).also { bytesRead = it } != -1) {
                                                    fos.write(buffer, 0, bytesRead)
                                                }
                                                fos.flush()
                                            }
                                        }
                                        send("226 Transfer complete.")
                                    } catch (e: Exception) {
                                        Log.e(tag, "APPE error", e)
                                        send("426 Transfer aborted.")
                                    } finally {
                                        dataSocket.close()
                                    }
                                }
                            }
                        }
                    }
                    "DELE" -> {
                        if (!isAuthenticated) {
                            send("530 Please login.")
                        } else {
                            val resolved = resolveTarget(currentDir, arg)
                            if (resolved !is ResolvedTarget.Real || resolved.file == rootDir || (sdCardDir != null && resolved.file == sdCardDir)) {
                                send("550 Cannot delete device root folder.")
                            } else if (resolved.file.exists() && resolved.file.isFile && resolved.file.delete()) {
                                send("250 File deleted successfully.")
                            } else {
                                send("550 Could not delete file.")
                            }
                        }
                    }
                    "MKD", "XMKD" -> {
                        if (!isAuthenticated) {
                            send("530 Please login.")
                        } else {
                            val targetDir = resolveWriteTarget(currentDir, arg)
                            if (targetDir == null) {
                                send("550 Cannot create directory in root. Please open the '$effectiveDeviceName' folder first.")
                            } else if (targetDir.mkdirs() || targetDir.exists()) {
                                send("257 \"${getVirtualPath(targetDir)}\" created.")
                            } else {
                                send("550 Directory creation failed.")
                            }
                        }
                    }
                    "RMD", "XRMD" -> {
                        if (!isAuthenticated) {
                            send("530 Please login.")
                        } else {
                            val resolved = resolveTarget(currentDir, arg)
                            if (resolved !is ResolvedTarget.Real || resolved.file == rootDir || (sdCardDir != null && resolved.file == sdCardDir)) {
                                send("550 Cannot remove device root folder.")
                            } else if (resolved.file.exists() && resolved.file.isDirectory && resolved.file.delete()) {
                                send("250 Directory removed.")
                            } else {
                                send("550 Could not remove directory.")
                            }
                        }
                    }
                    "RNFR" -> {
                        if (!isAuthenticated) {
                            send("530 Please login.")
                        } else {
                            val resolved = resolveTarget(currentDir, arg)
                            if (resolved !is ResolvedTarget.Real || resolved.file == rootDir || (sdCardDir != null && resolved.file == sdCardDir)) {
                                send("550 Cannot rename device root folder.")
                            } else if (resolved.file.exists()) {
                                renameSource = resolved.file
                                send("350 Ready for RNTO.")
                            } else {
                                send("550 File not found.")
                            }
                        }
                    }
                    "RNTO" -> {
                        if (!isAuthenticated) {
                            send("530 Please login.")
                        } else if (renameSource == null) {
                            send("503 Bad sequence of commands, use RNFR first.")
                        } else {
                            val targetFile = resolveWriteTarget(currentDir, arg)
                            if (targetFile == null) {
                                send("550 Cannot rename to virtual root.")
                            } else {
                                val success = renameSource!!.renameTo(targetFile)
                                renameSource = null
                                if (success) {
                                    send("250 Rename successful.")
                                } else {
                                    send("550 Rename failed.")
                                }
                            }
                        }
                    }
                    "SIZE" -> {
                        if (!isAuthenticated) {
                            send("530 Please login.")
                        } else {
                            val resolved = resolveTarget(currentDir, arg)
                            if (resolved is ResolvedTarget.Real && resolved.file.exists() && resolved.file.isFile) {
                                send("213 ${resolved.file.length()}")
                            } else {
                                send("550 Could not get file size: Not a plain file.")
                            }
                        }
                    }
                    "MDTM" -> {
                        if (!isAuthenticated) {
                            send("530 Please login.")
                        } else {
                            val resolved = resolveTarget(currentDir, arg)
                            if (resolved is ResolvedTarget.Real && resolved.file.exists()) {
                                val mdtmFormat = SimpleDateFormat("yyyyMMddHHmmss", Locale.US).apply {
                                    timeZone = TimeZone.getTimeZone("UTC")
                                }
                                send("213 ${mdtmFormat.format(Date(resolved.file.lastModified()))}")
                            } else {
                                send("550 Could not get file modification time.")
                            }
                        }
                    }
                    else -> {
                        send("502 Command not implemented.")
                    }
                }
            }
        } catch (e: Exception) {
            Log.d(tag, "Client disconnected or error: ${e.message}")
        } finally {
            try {
                pasvServer?.close()
                socket.close()
            } catch (_: Exception) {
            }
            val count = activeConnections.decrementAndGet()
            onConnectionChanged?.invoke(count.coerceAtLeast(0))
        }
    }

    private fun getDataSocket(pasvServer: ServerSocket?, activeHost: String?, activePort: Int): Socket? {
        return try {
            if (pasvServer != null) {
                pasvServer.soTimeout = 30_000
                pasvServer.accept()
            } else if (!activeHost.isNullOrEmpty() && activePort > 0) {
                Socket(activeHost, activePort)
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(tag, "Data socket connection failed", e)
            null
        }
    }

    private fun extractPathFromListArg(arg: String): String {
        val trimmed = arg.trim()
        if (trimmed.isEmpty()) return ""
        val tokens = trimmed.split(Regex("\\s+"))
        val pathTokens = tokens.filterNot { it.startsWith("-") }
        return pathTokens.joinToString(" ").trim()
    }

    private fun resolveWriteTarget(current: File?, path: String): File? {
        val resolved = resolveTarget(current, path)
        return when (resolved) {
            is ResolvedTarget.Real -> resolved.file
            is ResolvedTarget.VirtualRoot -> null
            is ResolvedTarget.Invalid -> {
                var clean = path.replace('\\', '/').trim().trim('\"')
                try {
                    if (clean.contains("%")) {
                        clean = java.net.URLDecoder.decode(clean, "UTF-8")
                    }
                } catch (_: Exception) {}
                clean = clean.replace(Regex("/+"), "/")
                if (!showDeviceFolder) {
                    val candidate = if (clean.startsWith("/")) {
                        File(rootDir, clean.trimStart('/')).canonicalFile
                    } else if (current != null) {
                        File(current, clean).canonicalFile
                    } else {
                        File(rootDir, clean).canonicalFile
                    }
                    if (isChildOrSame(candidate, rootDir)) candidate else null
                } else if (current != null) {
                    val candidate = File(current, clean).canonicalFile
                    if (isChildOrSame(candidate, rootDir) || (sdCardDir != null && isChildOrSame(candidate, sdCardDir!!))) {
                        candidate
                    } else null
                } else {
                    val trimmed = clean.trimStart('/')
                    if (trimmed.startsWith("$effectiveDeviceName/", ignoreCase = true)) {
                        val sub = trimmed.substring(effectiveDeviceName.length + 1)
                        val candidate = File(rootDir, sub).canonicalFile
                        if (isChildOrSame(candidate, rootDir)) candidate else null
                    } else if (sdCardDir != null && trimmed.startsWith("SD Card/", ignoreCase = true)) {
                        val sub = trimmed.substring("SD Card/".length)
                        val candidate = File(sdCardDir!!, sub).canonicalFile
                        if (isChildOrSame(candidate, sdCardDir!!)) candidate else null
                    } else {
                        null
                    }
                }
            }
        }
    }

    private fun resolveTarget(current: File?, path: String): ResolvedTarget {
        var clean = path.replace('\\', '/').trim()
        if (clean.startsWith("\"") && clean.endsWith("\"") && clean.length >= 2) {
            clean = clean.substring(1, clean.length - 1).trim()
        }
        try {
            if (clean.contains("%")) {
                clean = java.net.URLDecoder.decode(clean, "UTF-8")
            }
        } catch (_: Exception) {}
        clean = clean.replace(Regex("/+"), "/")

        if (!showDeviceFolder) {
            if (clean.isEmpty() || clean == "." || clean == "./") {
                val c = current ?: rootDir
                return ResolvedTarget.Real(c, isChildOrSame(c, rootDir))
            }
            if (clean == "/") {
                return ResolvedTarget.Real(rootDir, true)
            }
            if (clean == "..") {
                val c = current ?: rootDir
                val parent = c.parentFile
                return if (parent != null && isChildOrSame(parent, rootDir)) {
                    ResolvedTarget.Real(parent, true)
                } else {
                    ResolvedTarget.Real(rootDir, true)
                }
            }
            val target = if (clean.startsWith("/")) {
                File(rootDir, clean.trimStart('/')).canonicalFile
            } else {
                File(current ?: rootDir, clean).canonicalFile
            }
            return if (isChildOrSame(target, rootDir)) ResolvedTarget.Real(target, true) else ResolvedTarget.Invalid
        }

        if (clean.isEmpty() || clean == "." || clean == "./") {
            return if (current == null) ResolvedTarget.VirtualRoot else ResolvedTarget.Real(current, isChildOrSame(current, rootDir))
        }
        if (clean == "/") {
            return ResolvedTarget.VirtualRoot
        }
        while (clean.length > 1 && clean.endsWith("/")) {
            clean = clean.substring(0, clean.length - 1)
        }
        if (clean == "..") {
            if (current == null) return ResolvedTarget.VirtualRoot
            if (current == rootDir || (sdCardDir != null && current == sdCardDir)) {
                return ResolvedTarget.VirtualRoot
            }
            val parent = current.parentFile
            return if (parent != null && isChildOrSame(parent, rootDir)) {
                ResolvedTarget.Real(parent, true)
            } else if (parent != null && sdCardDir != null && isChildOrSame(parent, sdCardDir!!)) {
                ResolvedTarget.Real(parent, false)
            } else {
                ResolvedTarget.VirtualRoot
            }
        }

        val trimmed = clean.trimStart('/')
        if (trimmed.equals(effectiveDeviceName, ignoreCase = true)) {
            return ResolvedTarget.Real(rootDir, true)
        }
        if (trimmed.startsWith("$effectiveDeviceName/", ignoreCase = true)) {
            val sub = trimmed.substring(effectiveDeviceName.length + 1)
            val target = File(rootDir, sub).canonicalFile
            return if (isChildOrSame(target, rootDir)) ResolvedTarget.Real(target, true) else ResolvedTarget.Invalid
        }
        if (sdCardDir != null) {
            if (trimmed.equals("SD Card", ignoreCase = true)) {
                return ResolvedTarget.Real(sdCardDir!!, false)
            }
            if (trimmed.startsWith("SD Card/", ignoreCase = true)) {
                val sub = trimmed.substring("SD Card/".length)
                val target = File(sdCardDir!!, sub).canonicalFile
                return if (isChildOrSame(target, sdCardDir!!)) ResolvedTarget.Real(target, false) else ResolvedTarget.Invalid
            }
        }
        if (clean.startsWith("/")) {
            val fallback = File(rootDir, trimmed).canonicalFile
            if (isChildOrSame(fallback, rootDir) && fallback.exists()) {
                return ResolvedTarget.Real(fallback, true)
            }
            return ResolvedTarget.Invalid
        }
        if (current == null) {
            val fallback = File(rootDir, clean).canonicalFile
            if (isChildOrSame(fallback, rootDir) && fallback.exists()) {
                return ResolvedTarget.Real(fallback, true)
            }
            return ResolvedTarget.Invalid
        } else {
            val target = File(current, clean).canonicalFile
            if (isChildOrSame(target, rootDir)) {
                return ResolvedTarget.Real(target, true)
            }
            if (sdCardDir != null && isChildOrSame(target, sdCardDir!!)) {
                return ResolvedTarget.Real(target, false)
            }
            return ResolvedTarget.VirtualRoot
        }
    }

    private fun isChildOrSame(file: File, parent: File): Boolean {
        var c: File? = file
        while (c != null) {
            if (c == parent) return true
            c = c.parentFile
        }
        return false
    }

    private fun getVirtualPath(file: File?): String {
        if (!showDeviceFolder) {
            if (file == null) return "/"
            val rootCanonical = rootDir.canonicalPath
            val fileCanonical = file.canonicalPath
            if (fileCanonical == rootCanonical) return "/"
            if (fileCanonical.startsWith(rootCanonical)) {
                val rel = fileCanonical.substring(rootCanonical.length).replace('\\', '/')
                return if (rel.startsWith("/")) rel else "/$rel"
            }
            if (sdCardDir != null) {
                val sdCanonical = sdCardDir!!.canonicalPath
                if (fileCanonical == sdCanonical) return "/SD Card"
                if (fileCanonical.startsWith(sdCanonical)) {
                    val rel = fileCanonical.substring(sdCanonical.length).replace('\\', '/')
                    return "/SD Card$rel"
                }
            }
            return "/"
        }
        if (file == null) return "/"
        val rootCanonical = rootDir.canonicalPath
        val fileCanonical = file.canonicalPath
        if (fileCanonical == rootCanonical) {
            return "/$effectiveDeviceName"
        }
        if (fileCanonical.startsWith(rootCanonical)) {
            val rel = fileCanonical.substring(rootCanonical.length).replace('\\', '/')
            return "/$effectiveDeviceName$rel"
        }
        if (sdCardDir != null) {
            val sdCanonical = sdCardDir!!.canonicalPath
            if (fileCanonical == sdCanonical) {
                return "/SD Card"
            }
            if (fileCanonical.startsWith(sdCanonical)) {
                val rel = fileCanonical.substring(sdCanonical.length).replace('\\', '/')
                return "/SD Card$rel"
            }
        }
        return "/"
    }
}
