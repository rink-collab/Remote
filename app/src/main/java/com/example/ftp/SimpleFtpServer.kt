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
    private val onConnectionChanged: ((activeCount: Int) -> Unit)? = null
) {
    private val tag = "SimpleFtpServer"
    private var serverSocket: ServerSocket? = null
    private var serverJob: Job? = null
    private val activeConnections = AtomicInteger(0)
    private val scope = CoroutineScope(Dispatchers.IO)

    val isRunning: Boolean
        get() = serverSocket != null && serverSocket?.isClosed == false

    private val rootDir: File by lazy {
        val ext = Environment.getExternalStorageDirectory()
        if (ext != null && ext.exists() && ext.canRead()) {
            ext
        } else {
            context.getExternalFilesDir(null) ?: context.filesDir
        }
    }

    fun start() {
        if (isRunning) return
        try {
            serverSocket = ServerSocket(port, 50, InetAddress.getByName("0.0.0.0"))
            serverSocket?.reuseAddress = true
            Log.d(tag, "FTP Server started on port $port, root: ${rootDir.absolutePath}")

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
        var currentDir = rootDir
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
                            val target = resolveFile(currentDir, arg)
                            if (target.exists() && target.isDirectory) {
                                currentDir = target
                                send("250 Directory successfully changed to \"${getVirtualPath(currentDir)}\"")
                            } else {
                                send("550 Failed to change directory.")
                            }
                        }
                    }
                    "CDUP", "XCUP" -> {
                        if (!isAuthenticated) {
                            send("530 Please login with USER and PASS.")
                        } else {
                            val parent = currentDir.parentFile
                            if (parent != null && isChildOrSame(parent, rootDir)) {
                                currentDir = parent
                                send("200 Directory changed to \"${getVirtualPath(currentDir)}\"")
                            } else {
                                send("200 Already at root directory \"/\"")
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
                                    val targetDir = if (arg.isNotEmpty() && !arg.startsWith("-")) resolveFile(currentDir, arg) else currentDir
                                    val files = targetDir.listFiles() ?: emptyArray()
                                    val dateFormat = SimpleDateFormat("MMM dd HH:mm", Locale.US)
                                    val yearFormat = SimpleDateFormat("MMM dd  yyyy", Locale.US)
                                    val sixMonthsAgo = System.currentTimeMillis() - 180L * 24 * 60 * 60 * 1000

                                    for (file in files) {
                                        if (!showHiddenFiles && file.name.startsWith(".")) continue
                                        if (command == "NLST") {
                                            dataOut.write("${file.name}\r\n")
                                        } else {
                                            val isDir = file.isDirectory
                                            val perms = if (isDir) "drwxr-xr-x" else "-rw-r--r--"
                                            val size = if (isDir) 4096 else file.length()
                                            val modTime = file.lastModified()
                                            val dateStr = if (modTime > sixMonthsAgo) dateFormat.format(Date(modTime)) else yearFormat.format(Date(modTime))
                                            dataOut.write("$perms 1 owner group $size $dateStr ${file.name}\r\n")
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
                                    val targetDir = if (arg.isNotEmpty()) resolveFile(currentDir, arg) else currentDir
                                    val files = targetDir.listFiles() ?: emptyArray()
                                    val mlsdFormat = SimpleDateFormat("yyyyMMddHHmmss", Locale.US).apply {
                                        timeZone = TimeZone.getTimeZone("UTC")
                                    }
                                    for (file in files) {
                                        if (!showHiddenFiles && file.name.startsWith(".")) continue
                                        val type = if (file.isDirectory) "dir" else "file"
                                        val size = if (file.isDirectory) "" else "size=${file.length()};"
                                        val modify = "modify=${mlsdFormat.format(Date(file.lastModified()))};"
                                        val perm = if (file.isDirectory) "perm=cdeflmp;" else "perm=adfrw;"
                                        dataOut.write("type=$type;$size$modify$perm ${file.name}\r\n")
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
                            val file = resolveFile(currentDir, arg)
                            if (!file.exists() || file.isDirectory || !file.canRead()) {
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
                            val file = resolveFile(currentDir, arg)
                            val dataSocket = getDataSocket(pasvServer, activeHost, activePort)
                            pasvServer = null
                            if (dataSocket == null) {
                                send("425 Can't open data connection.")
                            } else {
                                send("150 Opening BINARY mode data connection for ${file.name}")
                                try {
                                    FileOutputStream(file).use { fos ->
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
                    "APPE" -> {
                        if (!isAuthenticated) {
                            send("530 Please login.")
                        } else {
                            val file = resolveFile(currentDir, arg)
                            val dataSocket = getDataSocket(pasvServer, activeHost, activePort)
                            pasvServer = null
                            if (dataSocket == null) {
                                send("425 Can't open data connection.")
                            } else {
                                send("150 Appending to ${file.name}")
                                try {
                                    FileOutputStream(file, true).use { fos ->
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
                                    send("426 Transfer aborted.")
                                } finally {
                                    dataSocket.close()
                                }
                            }
                        }
                    }
                    "DELE" -> {
                        if (!isAuthenticated) {
                            send("530 Please login.")
                        } else {
                            val file = resolveFile(currentDir, arg)
                            if (file.exists() && file.isFile && file.delete()) {
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
                            val target = resolveFile(currentDir, arg)
                            if (target.mkdirs()) {
                                send("257 \"${getVirtualPath(target)}\" created.")
                            } else {
                                send("550 Directory creation failed.")
                            }
                        }
                    }
                    "RMD", "XRMD" -> {
                        if (!isAuthenticated) {
                            send("530 Please login.")
                        } else {
                            val target = resolveFile(currentDir, arg)
                            if (target.exists() && target.isDirectory && target.delete()) {
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
                            val file = resolveFile(currentDir, arg)
                            if (file.exists()) {
                                renameSource = file
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
                            val target = resolveFile(currentDir, arg)
                            val success = renameSource!!.renameTo(target)
                            renameSource = null
                            if (success) {
                                send("250 Rename successful.")
                            } else {
                                send("550 Rename failed.")
                            }
                        }
                    }
                    "SIZE" -> {
                        if (!isAuthenticated) {
                            send("530 Please login.")
                        } else {
                            val file = resolveFile(currentDir, arg)
                            if (file.exists() && file.isFile) {
                                send("213 ${file.length()}")
                            } else {
                                send("550 Could not get file size.")
                            }
                        }
                    }
                    "MDTM" -> {
                        if (!isAuthenticated) {
                            send("530 Please login.")
                        } else {
                            val file = resolveFile(currentDir, arg)
                            if (file.exists()) {
                                val mdtmFormat = SimpleDateFormat("yyyyMMddHHmmss", Locale.US).apply {
                                    timeZone = TimeZone.getTimeZone("UTC")
                                }
                                send("213 ${mdtmFormat.format(Date(file.lastModified()))}")
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

    private fun resolveFile(current: File, path: String): File {
        var clean = path.replace('\\', '/')
        if (clean.startsWith("\"") && clean.endsWith("\"")) {
            clean = clean.substring(1, clean.length - 1)
        }
        val target = if (clean.startsWith("/")) {
            File(rootDir, clean.trimStart('/'))
        } else {
            File(current, clean)
        }
        val canonical = target.canonicalFile
        return if (isChildOrSame(canonical, rootDir)) {
            canonical
        } else {
            rootDir
        }
    }

    private fun isChildOrSame(file: File, parent: File): Boolean {
        var current: File? = file
        while (current != null) {
            if (current == parent) return true
            current = current.parentFile
        }
        return false
    }

    private fun getVirtualPath(file: File): String {
        val rootPath = rootDir.canonicalPath
        val filePath = file.canonicalPath
        return if (filePath == rootPath) {
            "/"
        } else if (filePath.startsWith(rootPath)) {
            filePath.substring(rootPath.length).replace('\\', '/')
        } else {
            "/"
        }
    }
}
