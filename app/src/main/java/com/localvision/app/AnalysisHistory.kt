package com.localvision.app

import android.content.Context
import com.localvision.app.vision.VisionResult
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class HistoryEntry(
    val id: String,
    val timestampMs: Long,
    val model: String,
    val faceCount: Int,
    val elapsedMs: Long,
)

class AnalysisHistory(context: Context) {
    private val preferences = context.getSharedPreferences("analysis_history", Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = preferences.getBoolean("enabled", false)
        set(value) { preferences.edit().putBoolean("enabled", value).apply() }

    fun entries(now: Long = System.currentTimeMillis()): List<HistoryEntry> {
        val cutoff = now - RETENTION_MS
        val items = runCatching {
            val array = JSONArray(preferences.getString("entries", "[]"))
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.getJSONObject(index)
                    val entry = HistoryEntry(
                        id = item.getString("id"),
                        timestampMs = item.getLong("timestampMs"),
                        model = item.getString("model"),
                        faceCount = item.getInt("faceCount"),
                        elapsedMs = item.getLong("elapsedMs"),
                    )
                    if (entry.timestampMs >= cutoff) add(entry)
                }
            }
        }.getOrDefault(emptyList())
        persist(items)
        return items.sortedByDescending(HistoryEntry::timestampMs)
    }

    fun add(result: VisionResult.Detections) {
        if (!enabled) return
        val next = listOf(
            HistoryEntry(UUID.randomUUID().toString(), System.currentTimeMillis(), "YuNet 2023mar FP32", result.items.size, result.elapsedMs),
        ) + entries()
        persist(next.take(MAX_ENTRIES))
    }

    fun clear() = preferences.edit().remove("entries").apply()

    private fun persist(items: List<HistoryEntry>) {
        val array = JSONArray()
        items.forEach { entry ->
            array.put(JSONObject().apply {
                put("id", entry.id)
                put("timestampMs", entry.timestampMs)
                put("model", entry.model)
                put("faceCount", entry.faceCount)
                put("elapsedMs", entry.elapsedMs)
            })
        }
        preferences.edit().putString("entries", array.toString()).apply()
    }

    companion object {
        private const val MAX_ENTRIES = 100
        private const val RETENTION_MS = 30L * 24 * 60 * 60 * 1000
    }
}
