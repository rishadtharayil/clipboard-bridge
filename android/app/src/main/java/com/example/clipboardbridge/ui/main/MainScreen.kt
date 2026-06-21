package com.example.clipboardbridge.ui.main

import android.content.Context
import android.content.ClipData
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.clipboardbridge.ClipboardSyncService
import com.example.clipboardbridge.data.ClipboardHistoryManager
import com.example.clipboardbridge.data.HistoryItem
import com.example.clipboardbridge.theme.AmberGold
import com.example.clipboardbridge.theme.GlowEmerald
import com.example.clipboardbridge.theme.SunsetCrimson
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val isRunning by ClipboardSyncService.isRunning.collectAsStateWithLifecycle()
    val connectionStatus by ClipboardSyncService.connectionStatus.collectAsStateWithLifecycle()
    val currentKey by ClipboardSyncService.key.collectAsStateWithLifecycle()
    val logs by ClipboardSyncService.logs.collectAsStateWithLifecycle()
    val syncDirection by ClipboardSyncService.syncDirection.collectAsStateWithLifecycle()
    val useAutoDiscovery by ClipboardSyncService.useAutoDiscovery.collectAsStateWithLifecycle()
    val manualIp by ClipboardSyncService.manualIp.collectAsStateWithLifecycle()
    
    val historyItems by ClipboardHistoryManager.historyItems.collectAsStateWithLifecycle()

    val prefs = context.getSharedPreferences("ClipboardBridgePrefs", Context.MODE_PRIVATE)
    var isLogsExpanded by remember { mutableStateOf(false) }

    val scanLauncher = androidx.activity.compose.rememberLauncherForActivityResult(ScanContract()) { result ->
        if (result.contents != null) {
            val key = result.contents
            if (key.length == 16) {
                ClipboardSyncService.key.value = key
                prefs.edit().putString("key", key).apply()
                
                // Start service automatically
                val serviceIntent = Intent(context, ClipboardSyncService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
            }
        }
    }

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        val launcher = androidx.activity.compose.rememberLauncherForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
        ) { _ -> }
        LaunchedEffect(Unit) {
            launcher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // Wrap the top container in a Scaffold with dark color scheme values
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Clipboard Bridge",
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleLarge
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.primary
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Status and Radar Section
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = connectionStatus,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        if (currentKey.isNotEmpty()) {
                            Text(
                                text = "Paired with Windows PC",
                                color = GlowEmerald,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                        } else {
                            Text(
                                text = "Not Paired",
                                color = SunsetCrimson,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                    
                    // Radar Animation Indicator
                    GlowingPulseIndicator(status = connectionStatus)
                }

                // Control Action Button
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    if (currentKey.isEmpty() || !isRunning) {
                        Button(
                            onClick = {
                                val options = ScanOptions()
                                options.setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                                options.setPrompt("Scan Windows QR Code")
                                options.setBeepEnabled(false)
                                scanLauncher.launch(options)
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(imageVector = Icons.Default.QrCodeScanner, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(if (currentKey.isEmpty()) "Scan QR to Pair" else "Connect")
                        }
                    } else {
                        Button(
                            onClick = {
                                context.stopService(Intent(context, ClipboardSyncService::class.java))
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(imageVector = Icons.Default.Close, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Disconnect")
                        }
                    }
                }
            }

            // Settings Card
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Settings", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

                    // Auto Discovery Switch
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column {
                            Text("Auto Discover Server", style = MaterialTheme.typography.bodyLarge)
                            Text("Locate Windows server in local network", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(
                            checked = useAutoDiscovery,
                            onCheckedChange = {
                                ClipboardSyncService.useAutoDiscovery.value = it
                                prefs.edit().putBoolean("useAutoDiscovery", it).apply()
                            }
                        )
                    }

                    // Manual IP entry visibility animation
                    AnimatedVisibility(
                        visible = !useAutoDiscovery,
                        enter = expandVertically(),
                        exit = shrinkVertically()
                    ) {
                        var ipInput by remember { mutableStateOf(manualIp) }
                        OutlinedTextField(
                            value = ipInput,
                            onValueChange = {
                                ipInput = it
                                ClipboardSyncService.manualIp.value = it
                                prefs.edit().putString("manualIp", it).apply()
                            },
                            label = { Text("Manual Server IP") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            shape = RoundedCornerShape(10.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(4.dp))
                    Text("Sync Direction", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)

                    // Segmented Button Sync Direction Selector
                    SyncDirectionSegmentedSelector(
                        selectedDirection = syncDirection,
                        onDirectionSelected = { value ->
                            ClipboardSyncService.syncDirection.value = value
                            prefs.edit().putString("syncDirection", value).apply()
                        }
                    )
                }
            }

            // Clipboard History Section
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Sync History",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        if (historyItems.isNotEmpty()) {
                            TextButton(onClick = { ClipboardHistoryManager.clearHistory(context) }) {
                                Text("Clear All", color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }

                    if (historyItems.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "No items synced yet",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    } else {
                        LazyColumn(
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth().weight(1f)
                        ) {
                            items(historyItems, key = { it.id }) { item ->
                                HistoryRowItem(item = item, context = context)
                            }
                        }
                    }
                }
            }

            // Collapsible Logs Terminal
            Card(
                colors = CardDefaults.cardColors(containerColor = Color.Black),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(8.dp)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { isLogsExpanded = !isLogsExpanded }
                            .padding(4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Terminal,
                                contentDescription = null,
                                tint = GlowEmerald,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "System Logs",
                                color = GlowEmerald,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                        Icon(
                            imageVector = if (isLogsExpanded) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowUp,
                            contentDescription = null,
                            tint = GlowEmerald
                        )
                    }

                    AnimatedVisibility(
                        visible = isLogsExpanded,
                        enter = expandVertically(),
                        exit = shrinkVertically()
                    ) {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(120.dp)
                                .background(Color.Black)
                                .padding(top = 4.dp),
                            reverseLayout = true
                        ) {
                            items(logs.reversed()) { log ->
                                Text(
                                    text = log,
                                    color = GlowEmerald,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                    modifier = Modifier.padding(vertical = 1.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun GlowingPulseIndicator(status: String, modifier: Modifier = Modifier) {
    val color = when {
        status.contains("Connected", ignoreCase = true) -> GlowEmerald
        status.contains("Connecting", ignoreCase = true) || status.contains("Searching", ignoreCase = true) -> AmberGold
        else -> SunsetCrimson
    }

    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val scale by infiniteTransition.animateFloat(
        initialValue = 0.6f,
        targetValue = 1.6f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "scale"
    )
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.8f,
        targetValue = 0.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "alpha"
    )

    Box(
        modifier = modifier.size(50.dp),
        contentAlignment = Alignment.Center
    ) {
        androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
            drawCircle(
                color = color,
                radius = size.minDimension / 2.2f * scale,
                alpha = alpha
            )
            drawCircle(
                color = color,
                radius = 8.dp.toPx()
            )
        }
    }
}

@Composable
fun SyncDirectionSegmentedSelector(
    selectedDirection: String,
    onDirectionSelected: (String) -> Unit
) {
    val directions = listOf(
        "bidirectional" to "Bidirectional",
        "windows_to_android" to "Win \u2192 And",
        "android_to_windows" to "And \u2192 Win"
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background, RoundedCornerShape(12.dp))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        directions.forEach { (value, label) ->
            val isSelected = selectedDirection == value
            val backgroundColor by animateColorAsState(
                targetValue = if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent,
                label = "bgColor"
            )
            val contentColor by animateColorAsState(
                targetValue = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                label = "textColor"
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(backgroundColor)
                    .clickable { onDirectionSelected(value) }
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = contentColor
                )
            }
        }
    }
}

@Composable
fun HistoryRowItem(item: HistoryItem, context: Context) {
    val clipboardManager = LocalClipboardManager.current
    val sdf = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    val timeString = remember(item.timestamp) { sdf.format(Date(item.timestamp)) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background, RoundedCornerShape(8.dp))
            .clickable {
                if (item.type == "text") {
                    clipboardManager.setText(AnnotatedString(item.content))
                    android.widget.Toast.makeText(context, "Text copied to clipboard", android.widget.Toast.LENGTH_SHORT).show()
                } else if (item.type == "image") {
                    // Try to copy image to clipboard via writing back to service cache
                    Thread {
                        try {
                            val file = File(item.content)
                            if (file.exists()) {
                                val bytes = file.readBytes()
                                // Call clipboard sync service directly to write it
                                val serviceIntent = Intent(context, ClipboardSyncService::class.java).apply {
                                    putExtra("write_image", bytes)
                                }
                                // We write it by triggering write directly on clipboard
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                val cacheDir = File(context.cacheDir, "shared_images")
                                if (!cacheDir.exists()) cacheDir.mkdirs()
                                val newFile = File(cacheDir, "synced_${System.currentTimeMillis()}.png")
                                newFile.writeBytes(bytes)
                                val uri = androidx.core.content.FileProvider.getUriForFile(
                                    context,
                                    "${context.packageName}.fileprovider",
                                    newFile
                                )
                                val clip = ClipData.newUri(context.contentResolver, "Synced Image", uri)
                                context.getMainExecutor().execute {
                                    clipboard.setPrimaryClip(clip)
                                    android.widget.Toast.makeText(context, "Image copied to clipboard", android.widget.Toast.LENGTH_SHORT).show()
                                }
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }.start()
                }
            }
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Icon / Preview Box
            if (item.type == "text") {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(6.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Description,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            } else {
                val bitmap = remember(item.content) {
                    try {
                        BitmapFactory.decodeFile(item.content)?.asImageBitmap()
                    } catch (e: Exception) {
                        null
                    }
                }
                if (bitmap != null) {
                    androidx.compose.foundation.Image(
                        bitmap = bitmap,
                        contentDescription = null,
                        modifier = Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(6.dp)),
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(6.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Image,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (item.type == "text") item.content else "Image Transferred",
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = timeString,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
        
        IconButton(
            onClick = {
                if (item.type == "text") {
                    clipboardManager.setText(AnnotatedString(item.content))
                    android.widget.Toast.makeText(context, "Text copied", android.widget.Toast.LENGTH_SHORT).show()
                } else {
                    Thread {
                        try {
                            val file = File(item.content)
                            if (file.exists()) {
                                val bytes = file.readBytes()
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                val cacheDir = File(context.cacheDir, "shared_images")
                                if (!cacheDir.exists()) cacheDir.mkdirs()
                                val newFile = File(cacheDir, "synced_${System.currentTimeMillis()}.png")
                                newFile.writeBytes(bytes)
                                val uri = androidx.core.content.FileProvider.getUriForFile(
                                    context,
                                    "${context.packageName}.fileprovider",
                                    newFile
                                )
                                val clip = ClipData.newUri(context.contentResolver, "Synced Image", uri)
                                context.getMainExecutor().execute {
                                    clipboard.setPrimaryClip(clip)
                                    android.widget.Toast.makeText(context, "Image copied", android.widget.Toast.LENGTH_SHORT).show()
                                }
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }.start()
                }
            },
            modifier = Modifier.size(36.dp)
        ) {
            Icon(
                imageVector = Icons.Default.ContentCopy,
                contentDescription = "Copy Item",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}
