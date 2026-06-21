package com.example.clipboardbridge

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.example.clipboardbridge.theme.ClipboardBridgeTheme
import com.example.clipboardbridge.ui.main.MainScreen
import com.example.clipboardbridge.data.ClipboardHistoryManager

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    ClipboardHistoryManager.initialize(applicationContext)

    val prefs = getSharedPreferences("ClipboardBridgePrefs", Context.MODE_PRIVATE)
    val key = prefs.getString("key", null)
    
    // Auto-start service if paired
    if (key != null && key.length == 16) {
        ClipboardSyncService.key.value = key
        val serviceIntent = Intent(this, ClipboardSyncService::class.java)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
    }

    enableEdgeToEdge()
    setContent {
      ClipboardBridgeTheme { Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { MainScreen() } }
    }
  }
}
