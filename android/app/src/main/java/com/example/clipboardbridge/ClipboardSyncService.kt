package com.example.clipboardbridge

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import java.io.DataInputStream
import java.io.File
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.security.MessageDigest
import javax.crypto.spec.SecretKeySpec

class ClipboardSyncService : Service() {

    private lateinit var clipboardManager: ClipboardManager
    private val handler = Handler(Looper.getMainLooper())

    private var multicastLock: WifiManager.MulticastLock? = null
    private var udpSocket: DatagramSocket? = null
    private var tcpSocket: Socket? = null
    private var isServiceRunning = false

    private var aesKey: SecretKeySpec? = null

    // Tracking hashes to prevent feedback loops
    private var lastSentTextHash: String? = null
    private var lastSentImageHash: String? = null
    private var lastReceivedTextHash: String? = null
    private var lastReceivedImageHash: String? = null

    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener {
        handleLocalClipboardChange()
    }

    private var lastProcessedScreenshotId: Long = -1

    private val screenshotObserver = object : android.database.ContentObserver(handler) {
        override fun onChange(selfChange: Boolean, uri: Uri?) {
            super.onChange(selfChange, uri)
            checkForNewScreenshot()
        }
    }

    private fun checkForNewScreenshot() {
        val hasPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            checkSelfPermission(android.Manifest.permission.READ_MEDIA_IMAGES) == android.content.pm.PackageManager.PERMISSION_GRANTED
        } else {
            checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }

        if (!hasPermission) return

        try {
            val tenSecondsAgo = (System.currentTimeMillis() / 1000) - 10
            val projection = arrayOf(
                android.provider.MediaStore.Images.Media._ID,
                android.provider.MediaStore.Images.Media.DISPLAY_NAME,
                android.provider.MediaStore.Images.Media.DATE_ADDED,
                android.provider.MediaStore.Images.Media.RELATIVE_PATH
            )
            val selection = "${android.provider.MediaStore.Images.Media.DATE_ADDED} >= ? AND (" +
                "${android.provider.MediaStore.Images.Media.RELATIVE_PATH} LIKE ? OR " +
                "${android.provider.MediaStore.Images.Media.RELATIVE_PATH} LIKE ? OR " +
                "${android.provider.MediaStore.Images.Media.DISPLAY_NAME} LIKE ?)"
            val selectionArgs = arrayOf(
                tenSecondsAgo.toString(),
                "%Screenshot%",
                "%screenshot%",
                "%Screenshot%"
            )
            val sortOrder = "${android.provider.MediaStore.Images.Media.DATE_ADDED} DESC"

            val cursor = contentResolver.query(
                android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                sortOrder
            )

            cursor?.use {
                if (it.moveToFirst()) {
                    val id = it.getLong(it.getColumnIndexOrThrow(android.provider.MediaStore.Images.Media._ID))
                    if (id == lastProcessedScreenshotId) return
                    
                    lastProcessedScreenshotId = id
                    val name = it.getString(it.getColumnIndexOrThrow(android.provider.MediaStore.Images.Media.DISPLAY_NAME))
                    val imageUri = Uri.withAppendedPath(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id.toString())
                    
                    addLog("Auto-detected new screenshot: $name")
                    
                    Thread {
                        val bytes = readBytesFromUri(imageUri)
                        if (bytes != null && bytes.isNotEmpty()) {
                            val hash = getHash(bytes)
                            if (hash != lastSentImageHash) {
                                lastSentImageHash = hash
                                sendPacket(1, bytes)
                                addLog("Auto sync: screenshot sent to Windows (${bytes.size} bytes)")
                            }
                        }
                    }.start()
                }
            }
        } catch (e: Exception) {
            addLog("Screenshot auto-detect failed: ${e.message}")
        }
    }

    // Removed testReceiver

    companion object {
        val isRunning = MutableStateFlow(false)
        val connectionStatus = MutableStateFlow("Disconnected")
        val key = MutableStateFlow("")
        val logs = MutableStateFlow<List<String>>(emptyList())
        val manualIp = MutableStateFlow("")
        val useAutoDiscovery = MutableStateFlow(true)
        val syncDirection = MutableStateFlow("bidirectional")
        val wifiOnly = MutableStateFlow(true)

        fun addLog(msg: String) {
            val formatted = "[${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())}] $msg"
            logs.value = (logs.value + formatted).takeLast(100)
            android.util.Log.i("ClipboardBridge", msg)
        }
    }

    override fun onCreate() {
        super.onCreate()
        clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val prefs = getSharedPreferences("ClipboardBridgePrefs", Context.MODE_PRIVATE)
        val savedKey = prefs.getString("key", "") ?: ""
        if (savedKey.length == 16) {
            key.value = savedKey
        }
        syncDirection.value = prefs.getString("syncDirection", "bidirectional") ?: "bidirectional"
        wifiOnly.value = prefs.getBoolean("wifiOnly", true)

        useAutoDiscovery.value = prefs.getBoolean("useAutoDiscovery", true)
        manualIp.value = prefs.getString("manualIp", "") ?: ""

        val manualIpExtra = intent?.getStringExtra("manualIp")
        if (manualIpExtra != null) {
            manualIp.value = manualIpExtra
            useAutoDiscovery.value = false
        }
        val autoDiscoverExtra = intent?.getStringExtra("autoDiscover")
        if (autoDiscoverExtra != null) {
            useAutoDiscovery.value = autoDiscoverExtra.toBoolean()
        }
        if (!isServiceRunning) {
            isServiceRunning = true
            isRunning.value = true
            addLog("Clipboard Service starting...")
            startForegroundService()
            acquireMulticastLock()

            // Observe key changes to re-derive aesKey
            kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.Default) {
                key.collect { newKey ->
                    if (newKey.length == 16) {
                        aesKey = CryptoUtils.deriveKey(newKey)
                        addLog("AES Key updated from 16-char Key")
                        try {
                            tcpSocket?.close()
                        } catch (e: Exception) {}
                    } else {
                        aesKey = null
                    }
                }
            }

            // Start network threads
            Thread { udpDiscoveryLoop() }.start()
            Thread { tcpConnectionLoop() }.start()

            // Register clipboard listener on main thread
            handler.post {
                clipboardManager.addPrimaryClipChangedListener(clipListener)
                addLog("Clipboard listener registered")
                
                // Register screenshot observer
                try {
                    contentResolver.registerContentObserver(
                        android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        true,
                        screenshotObserver
                    )
                    addLog("Screenshot auto-sync enabled")
                } catch (e: Exception) {
                    addLog("Failed to register screenshot observer: ${e.message}")
                }
            }

            // Removed test receiver registration
        }
        return START_STICKY
    }

    override fun onDestroy() {
        isServiceRunning = false
        isRunning.value = false
        connectionStatus.value = "Disconnected"
        
        handler.post {
            clipboardManager.removePrimaryClipChangedListener(clipListener)
            try {
                contentResolver.unregisterContentObserver(screenshotObserver)
            } catch (e: Exception) {}
        }

        closeSockets()
        releaseMulticastLock()
        addLog("Clipboard Service stopped")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startForegroundService() {
        val channelId = "clipboard_sync_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId, "Clipboard Sync Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Clipboard Sync Active")
            .setContentText("Syncing text & images with Windows...")
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(1, notification)
        }
    }

    private fun acquireMulticastLock() {
        try {
            val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            multicastLock = wifi.createMulticastLock("ClipboardBridgeLock")
            multicastLock?.setReferenceCounted(true)
            multicastLock?.acquire()
        } catch (e: Exception) {
            addLog("Failed to acquire MulticastLock: ${e.message}")
        }
    }

    private fun releaseMulticastLock() {
        multicastLock?.let {
            if (it.isHeld) {
                it.release()
            }
        }
        multicastLock = null
    }

    private var serverIp: String? = null
    private var serverPort: Int = 9090

    private fun udpDiscoveryLoop() {
        val buffer = ByteArray(1024)
        try {
            val socket = DatagramSocket(null).apply {
                reuseAddress = true
                bind(InetSocketAddress(9091))
            }
            udpSocket = socket
            addLog("Listening for UDP broadcasts on port 9091...")

            while (isServiceRunning) {
                val packet = DatagramPacket(buffer, buffer.size)
                socket.receive(packet)
                val msg = String(packet.data, 0, packet.length, Charsets.UTF_8)
                if (msg.startsWith("CLIPBOARD_BRIDGE_SERVER:")) {
                    val parts = msg.substring("CLIPBOARD_BRIDGE_SERVER:".length).split(":")
                    if (parts.size == 2) {
                        val ip = parts[0]
                        val port = parts[1].toIntOrNull() ?: 9090
                        if (serverIp != ip || serverPort != port) {
                            serverIp = ip
                            serverPort = port
                            addLog("Discovered Windows Server at $ip:$port")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            if (isServiceRunning) {
                addLog("UDP discovery error: ${e.message}")
            }
        }
    }

    private fun tcpConnectionLoop() {
        while (isServiceRunning) {
            val ip = if (useAutoDiscovery.value) {
                var discovered = serverIp
                if (discovered == null) {
                    if (Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("google_sdk") || Build.HARDWARE.contains("goldfish") || Build.HARDWARE.contains("ranchu")) {
                        discovered = "10.0.2.2"
                    }
                }
                discovered
            } else {
                manualIp.value.ifEmpty { null }
            }
            val key = aesKey

            if (ip == null) {
                connectionStatus.value = if (useAutoDiscovery.value) "Searching Server..." else "Awaiting Server IP..."
                Thread.sleep(2000)
                continue
            }

            if (key == null) {
                connectionStatus.value = "Awaiting pairing PIN..."
                Thread.sleep(1000)
                continue
            }

            connectionStatus.value = "Connecting..."
            try {
                addLog("Connecting to server $ip:$serverPort...")
                val socket = Socket()
                socket.connect(InetSocketAddress(ip, serverPort), 5000)
                tcpSocket = socket
                connectionStatus.value = "Connected"
                addLog("Connected to Windows Server successfully!")

                // Start read loop
                val dis = DataInputStream(socket.getInputStream())
                while (isServiceRunning) {
                    val type = dis.readInt()
                    val length = dis.readInt()
                    if (length < 0 || length > 50 * 1024 * 1024) { // limit 50MB
                        throw IOException("Invalid packet length: $length")
                    }

                    val encryptedPayload = ByteArray(length)
                    dis.readFully(encryptedPayload)

                    try {
                        val decrypted = CryptoUtils.decrypt(encryptedPayload, key)
                        when (type) {
                            0 -> { // Text
                                val text = String(decrypted, Charsets.UTF_8)
                                addLog("Received text from Windows")
                                writeTextToClipboard(text)
                                showToast("Text received from Windows")
                            }
                            1 -> { // Image
                                addLog("Received image from Windows (${decrypted.size} bytes)")
                                writeImageToClipboard(decrypted)
                                showToast("Image received from Windows")
                            }
                            2 -> { // Heartbeat
                                // Send heartbeat response back to maintain connection
                                sendPacket(2, ByteArray(0))
                            }
                        }
                    } catch (e: Exception) {
                        addLog("Failed to decrypt or parse packet: ${e.message}")
                    }
                }

            } catch (e: Exception) {
                if (isServiceRunning) {
                    addLog("Connection lost or error: ${e.message}")
                    connectionStatus.value = "Disconnected"
                    tcpSocket?.close()
                    tcpSocket = null
                    Thread.sleep(3000) // retry delay
                }
            }
        }
    }

    @Synchronized
    private fun sendPacket(type: Int, payload: ByteArray) {
        if (wifiOnly.value && !isWifiConnected()) {
            addLog("Sync skipped: Wi-Fi only mode enabled")
            return
        }
        val dir = syncDirection.value
        if (dir == "windows_to_android") {
            addLog("Sync skipped: Sync direction is Windows -> Android only")
            return
        }

        val socket = tcpSocket
        val key = aesKey
        if (socket == null || socket.isClosed || key == null) return

        try {
            val encrypted = CryptoUtils.encrypt(payload, key)
            val out = socket.getOutputStream()
            val header = ByteBuffer.allocate(8)
                .putInt(type)
                .putInt(encrypted.size)
                .array()
            out.write(header)
            out.write(encrypted)
            out.flush()
        } catch (e: Exception) {
            addLog("Send error: ${e.message}")
        }
    }

    private fun isWifiConnected(): Boolean {
        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        return wifiManager.isWifiEnabled && wifiManager.connectionInfo.networkId != -1
    }

    private fun showToast(message: String) {
        handler.post {
            android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private fun handleLocalClipboardChange() {
        // Run network reading/hashing on background thread
        Thread {
            try {
                // Must access clip contents from Main or safe context
                handler.post {
                    val clip = clipboardManager.primaryClip
                    if (clip == null || clip.itemCount == 0) return@post

                    val item = clip.getItemAt(0)
                    val description = clipboardManager.primaryClipDescription
                    val isImageMime = description?.hasMimeType("image/*") ?: false

                    if (isImageMime && item.uri != null) {
                        val uri = item.uri
                        addLog("Local image clipboard update detected (via ClipDescription)")
                        val bytes = readBytesFromUri(uri)
                        if (bytes != null) {
                            val hash = getHash(bytes)
                            if (hash == lastReceivedImageHash || hash == lastSentImageHash) return@post

                            lastSentImageHash = hash
                            Thread {
                                sendPacket(1, bytes)
                            }.start()
                        }
                    } else if (item.text != null) {
                        val text = item.text.toString()
                        val hash = getHash(text.toByteArray())
                        
                        // Avoid syncing if it matches last received or last sent
                        if (hash == lastReceivedTextHash || hash == lastSentTextHash) return@post

                        lastSentTextHash = hash
                        addLog("Local text clipboard update detected")
                        Thread {
                            sendPacket(0, text.toByteArray(Charsets.UTF_8))
                        }.start()

                    } else if (item.uri != null) {
                        val uri = item.uri
                        val mimeType = contentResolver.getType(uri) ?: ""
                        if (mimeType.startsWith("image/") || uri.toString().contains("image")) {
                            addLog("Local image clipboard update detected (via URI check)")
                            val bytes = readBytesFromUri(uri)
                            if (bytes != null) {
                                val hash = getHash(bytes)
                                if (hash == lastReceivedImageHash || hash == lastSentImageHash) return@post

                                lastSentImageHash = hash
                                Thread {
                                    sendPacket(1, bytes)
                                }.start()
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                addLog("Error reading local clipboard: ${e.message}")
            }
        }.start()
    }

    private fun readBytesFromUri(uri: Uri): ByteArray? {
        return try {
            contentResolver.openInputStream(uri)?.use { it.readBytes() }
        } catch (e: Exception) {
            addLog("Failed to read image URI: ${e.message}")
            null
        }
    }

    private fun writeTextToClipboard(text: String) {
        val hash = getHash(text.toByteArray())
        lastReceivedTextHash = hash
        handler.post {
            val clip = ClipData.newPlainText("Synced Text", text)
            clipboardManager.setPrimaryClip(clip)
        }
    }

    private fun writeImageToClipboard(imageBytes: ByteArray) {
        val hash = getHash(imageBytes)
        lastReceivedImageHash = hash
        handler.post {
            try {
                val cacheDir = File(cacheDir, "shared_images")
                if (!cacheDir.exists()) cacheDir.mkdirs()
                
                // Clear old images
                cacheDir.listFiles()?.forEach { it.delete() }

                val file = File(cacheDir, "synced_${System.currentTimeMillis()}.png")
                file.writeBytes(imageBytes)

                val uri = FileProvider.getUriForFile(
                    this,
                    "$packageName.fileprovider",
                    file
                )

                val clip = ClipData.newUri(contentResolver, "Synced Image", uri)
                clipboardManager.setPrimaryClip(clip)
            } catch (e: Exception) {
                addLog("Failed to write image to clipboard: ${e.message}")
            }
        }
    }

    private fun getHash(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(bytes)
        return hashBytes.joinToString("") { "%02x".format(it) }
    }

    private fun closeSockets() {
        try {
            udpSocket?.close()
        } catch (e: Exception) {}
        udpSocket = null

        try {
            tcpSocket?.close()
        } catch (e: Exception) {}
        tcpSocket = null
    }
}
