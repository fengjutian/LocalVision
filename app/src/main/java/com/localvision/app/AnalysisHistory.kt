package com.localvision.app

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.localvision.app.vision.ModelDescriptor
import com.localvision.app.vision.VisionResult
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class HistoryEntry(
    val id: String,
    val timestampMs: Long,
    val modelId: String,
    val modelVersion: String,
    val faceCount: Int,
    val elapsedMs: Long,
)

/**
 * 分析历史只保存元数据：模型标识、时间、人脸数量、耗时。
 * 永远不写入原图、URI、文件名、缩略图或人脸特征。
 */
interface HistoryStore {
    var enabled: Boolean

    fun entries(): List<HistoryEntry>

    fun add(result: VisionResult.Detections, model: ModelDescriptor)

    fun clear()
}

class AnalysisHistory internal constructor(
    private val preferences: SharedPreferences,
) : HistoryStore {

    constructor(context: Context) : this(
        context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE),
    )

    override var enabled: Boolean
        get() = preferences.getBoolean(KEY_ENABLED, false)
        set(value) {
            preferences.edit { putBoolean(KEY_ENABLED, value) }
        }

    override fun entries(): List<HistoryEntry> = entriesAt(System.currentTimeMillis())

    internal fun entriesAt(now: Long): List<HistoryEntry> {
        val stored = parse(preferences.getString(KEY_ENTRIES, null))
        val kept = stored.filter { it.timestampMs >= now - RETENTION_MS }
            .sortedByDescending { it.timestampMs }
        // 只有真的裁掉过期条目时才回写，避免每次读取都产生一次无意义的磁盘写入。
        if (kept.size != stored.size) persist(kept)
        return kept
    }

    override fun add(result: VisionResult.Detections, model: ModelDescriptor) {
        if (!enabled) return
        val entry = HistoryEntry(
            id = UUID.randomUUID().toString(),
            timestampMs = System.currentTimeMillis(),
            modelId = model.id,
            modelVersion = model.version,
            faceCount = result.items.size,
            elapsedMs = result.elapsedMs,
        )
        persist((listOf(entry) + entries()).take(MAX_ENTRIES))
    }

    override fun clear() {
        preferences.edit { remove(KEY_ENTRIES) }
    }

    /** 用 opt* 读取：任何历史 blob（含旧版本格式）都不得让读取路径抛异常。 */
    private fun parse(raw: String?): List<HistoryEntry> {
        if (raw.isNullOrBlank()) return emptyList()
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val id = item.optString("id")
                if (id.isEmpty()) continue
                add(
                    HistoryEntry(
                        id = id,
                        timestampMs = item.optLong("timestampMs"),
                        modelId = item.optString("modelId"),
                        modelVersion = item.optString("modelVersion"),
                        faceCount = item.optInt("faceCount"),
                        elapsedMs = item.optLong("elapsedMs"),
                    ),
                )
            }
        }
    }

    private fun persist(items: List<HistoryEntry>) {
        val array = JSONArray()
        items.forEach { entry ->
            array.put(JSONObject().apply {
                put("id", entry.id)
                put("timestampMs", entry.timestampMs)
                put("modelId", entry.modelId)
                put("modelVersion", entry.modelVersion)
                put("faceCount", entry.faceCount)
                put("elapsedMs", entry.elapsedMs)
            })
        }
        preferences.edit {
            putInt(KEY_SCHEMA, SCHEMA_VERSION)
            putString(KEY_ENTRIES, array.toString())
        }
    }

    companion object {
        const val SCHEMA_VERSION = 1
        private const val PREFERENCES_NAME = "analysis_history"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_ENTRIES = "entries"
        private const val KEY_SCHEMA = "schemaVersion"
        private const val MAX_ENTRIES = 100
        private const val RETENTION_MS = 30L * 24 * 60 * 60 * 1000
    }
}
