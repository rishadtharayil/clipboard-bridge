package com.example.clipboardbridge.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

data class HistoryItem(
    val id: String = UUID.randomUUID().toString(),
    val type: String, // "text" or "image"
    val content: String, // text snippet or cache file path
    val timestamp: Long = System.currentTimeMillis()
)

object ClipboardHistoryManager {
    private const val PREFS_NAME = "ClipboardHistoryPrefs"
    private const val KEY_HISTORY = "history_list"
    private const val MAX_ITEMS = 10

    private val _historyItems = MutableStateFlow<List<HistoryItem>>(emptyList())
    val historyItems: StateFlow<List<HistoryItem>> = _historyItems

    fun initialize(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val jsonStr = prefs.getString(KEY_HISTORY, null)
        val items = mutableListOf<HistoryItem>()
        if (jsonStr != null) {
            try {
                val array = JSONArray(jsonStr)
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    val type = obj.getString("type")
                    val content = obj.getString("content")
                    
                    // Validate image cache path still exists
                    if (type == "image" && !File(content).exists()) {
                        continue
                    }
                    
                    items.add(
                        HistoryItem(
                            id = obj.optString("id", UUID.randomUUID().toString()),
                            type = type,
                            content = content,
                            timestamp = obj.getLong("timestamp")
                        )
                    )
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        _historyItems.value = items
    }

    private fun saveHistory(context: Context, items: List<HistoryItem>) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val array = JSONArray()
        for (item in items) {
            val obj = JSONObject()
            obj.put("id", item.id)
            obj.put("type", item.type)
            obj.put("content", item.content)
            obj.put("timestamp", item.timestamp)
            array.put(obj)
        }
        prefs.edit().putString(KEY_HISTORY, array.toString()).apply()
        _historyItems.value = items
    }

    fun addTextItem(context: Context, text: String) {
        if (text.isBlank()) return
        
        val currentList = _historyItems.value.toMutableList()
        
        // Prevent duplicate consecutive items
        if (currentList.isNotEmpty() && currentList[0].type == "text" && currentList[0].content == text) {
            return
        }
        
        // Remove existing duplicate text to move it to the top
        currentList.removeAll { it.type == "text" && it.content == text }

        val newItem = HistoryItem(type = "text", content = text)
        currentList.add(0, newItem)

        val trimmedList = currentList.take(MAX_ITEMS)
        saveHistory(context, trimmedList)
    }

    fun addImageItem(context: Context, imageBytes: ByteArray) {
        if (imageBytes.isEmpty()) return
        
        val fileName = "clip_img_${UUID.randomUUID()}.png"
        val file = File(context.cacheDir, fileName)
        try {
            FileOutputStream(file).use { fos ->
                fos.write(imageBytes)
            }
            
            val currentList = _historyItems.value.toMutableList()
            val newItem = HistoryItem(type = "image", content = file.absolutePath)
            currentList.add(0, newItem)

            // Cleanup old images if list grows beyond maximum capacity
            if (currentList.size > MAX_ITEMS) {
                val toRemove = currentList.subList(MAX_ITEMS, currentList.size)
                for (item in toRemove) {
                    if (item.type == "image") {
                        try {
                            File(item.content).delete()
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                }
                currentList.subList(MAX_ITEMS, currentList.size).clear()
            }
            
            saveHistory(context, currentList)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun clearHistory(context: Context) {
        for (item in _historyItems.value) {
            if (item.type == "image") {
                try {
                    File(item.content).delete()
                } catch (e: Exception) {}
            }
        }
        saveHistory(context, emptyList())
    }
}
