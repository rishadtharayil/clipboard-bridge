package com.example.clipboardbridge.ui.main

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.clipboardbridge.ClipboardSyncService
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

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

    val prefs = context.getSharedPreferences("ClipboardBridgePrefs", Context.MODE_PRIVATE)

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

    Column(
        modifier = modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "Clipboard Bridge",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )

        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Status: $connectionStatus", fontWeight = FontWeight.Bold)
                if (currentKey.isNotEmpty()) {
                    Text("Paired with Windows PC", color = Color(0xFF4CAF50))
                } else {
                    Text("Not Paired", color = Color(0xFFF44336))
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                    if (currentKey.isEmpty() || !isRunning) {
                        Button(onClick = {
                            val options = ScanOptions()
                            options.setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                            options.setPrompt("Scan Windows QR Code")
                            options.setBeepEnabled(false)
                            scanLauncher.launch(options)
                        }) {
                            Text(if (currentKey.isEmpty()) "Scan QR to Pair" else "Connect")
                        }
                    } else {
                        Button(
                            onClick = {
                                context.stopService(Intent(context, ClipboardSyncService::class.java))
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                        ) {
                            Text("Disconnect")
                        }
                    }
                }
            }
        }

        // Settings Card
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Settings", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                

                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                    Text("Auto Discover Server")
                    Switch(checked = useAutoDiscovery, onCheckedChange = { 
                        ClipboardSyncService.useAutoDiscovery.value = it
                        prefs.edit().putBoolean("useAutoDiscovery", it).apply()
                    })
                }

                if (!useAutoDiscovery) {
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
                        singleLine = true
                    )
                }

                Text("Sync Direction", fontWeight = FontWeight.SemiBold)
                val directions = listOf("bidirectional" to "Bidirectional", "windows_to_android" to "Windows -> Android", "android_to_windows" to "Android -> Windows")
                directions.forEach { (value, label) ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable {
                        ClipboardSyncService.syncDirection.value = value
                        prefs.edit().putString("syncDirection", value).apply()
                    }.padding(vertical = 4.dp)) {
                        RadioButton(selected = syncDirection == value, onClick = {
                            ClipboardSyncService.syncDirection.value = value
                            prefs.edit().putString("syncDirection", value).apply()
                        })
                        Text(label, modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
        }

        Text("Logs", fontWeight = FontWeight.Bold)
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth().background(Color.Black).padding(8.dp),
            reverseLayout = false
        ) {
            items(logs) { log ->
                Text(log, color = Color(0xFF00FF66), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
