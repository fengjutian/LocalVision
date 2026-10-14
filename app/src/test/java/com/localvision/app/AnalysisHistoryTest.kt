package com.localvision.app

import com.localvision.app.vision.Detection
import com.localvision.app.vision.ModelDescriptor
import com.localvision.app.vision.ModelRegistry
import com.localvision.app.vision.NormalizedRect
import com.localvision.app.vision.VisionResult
import com.localvision.app.vision.VisionTask
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnalysisHistoryTest {
    private val model: ModelDescriptor =
        ModelRegistry.default().activeModel(VisionTask.FACE_DETECTION)!!

    private fun history(prefs: FakeSharedPreferences = FakeSharedPreferences()) =
        AnalysisHistory(prefs) to prefs

    private fun detections(count: Int = 1) =
        VisionResult.Detections(
            List(count) { Detection(NormalizedRect(0.1f, 0.1f, 0.2f, 0.2f), "人脸", 0.9f) },
            elapsedMs = 42L,
        )

    @Test
    fun `is disabled by default`() {
        val (store, _) = history()
        assertFalse(store.enabled)
    }

    @Test
    fun `does not persist anything while disabled`() {
        val (store, prefs) = history()
        store.add(detections(), model)
        assertEquals(emptyList<HistoryEntry>(), store.entries())
        assertFalse(prefs.values.containsKey("entries"))
    }

    @Test
    fun `records model identity from the descriptor`() {
        val (store, prefs) = history()
        store.enabled = true
        store.add(detections(3), model)
        val entry = store.entries().single()
        assertEquals(model.id, entry.modelId)
        assertEquals(model.version, entry.modelVersion)
        assertEquals(3, entry.faceCount)
        assertEquals(42L, entry.elapsedMs)
        assertTrue(prefs.values.containsKey("entries"))
    }

    @Test
    fun `caps the stored list at one hundred entries`() {
        val (store, _) = history()
        store.enabled = true
        repeat(105) { store.add(detections(), model) }
        assertEquals(100, store.entries().size)
    }

    @Test
    fun `drops entries older than thirty days and rewrites once`() {
        val now = 1_800_000_000_000L
        val prefs = FakeSharedPreferences(mapOf("entries" to entriesJson(now - 31L * 24 * 3600 * 1000, now - 1000)))
        val (store, _) = history(prefs)
        val kept = store.entriesAt(now)
        assertEquals(1, kept.size)
        assertEquals(JSONArray(prefs.values["entries"] as String).length(), 1)
    }

    @Test
    fun `reading without expiry performs no write`() {
        val now = 1_800_000_000_000L
        val prefs = FakeSharedPreferences(mapOf("entries" to entriesJson(now - 1000, now - 2000)))
        val (store, _) = history(prefs)
        assertEquals(2, store.entriesAt(now).size)
        assertEquals(0, prefs.editCount)
    }

    @Test
    fun `sorted newest first`() {
        val now = 1_800_000_000_000L
        val prefs = FakeSharedPreferences(mapOf("entries" to entriesJson(now - 5000, now - 100)))
        val (store, _) = history(prefs)
        val entries = store.entriesAt(now)
        assertEquals(listOf(entries[0].timestampMs, entries[1].timestampMs), listOf(now - 100, now - 5000))
    }

    @Test
    fun `tolerates a corrupt blob instead of throwing`() {
        val prefs = FakeSharedPreferences(mapOf("entries" to "not-json-at-all"))
        val (store, _) = history(prefs)
        assertEquals(emptyList<HistoryEntry>(), store.entries())
    }

    @Test
    fun `clear removes stored entries`() {
        val (store, prefs) = history()
        store.enabled = true
        store.add(detections(), model)
        store.clear()
        assertEquals(emptyList<HistoryEntry>(), store.entries())
        assertFalse(prefs.values.containsKey("entries"))
    }

    @Test
    fun `persisted payload carries no image uri or face data`() {
        val (store, prefs) = history()
        store.enabled = true
        store.add(detections(2), model)
        val payload = prefs.values["entries"].toString()
        listOf("content://", "file://", "/storage/", "uri", "bitmap", "embedding", "gps", ".jpg", "path")
            .forEach { forbidden ->
                assertFalse("历史不应包含 $forbidden", payload.contains(forbidden, ignoreCase = true))
            }
    }

    private fun entriesJson(vararg timestamps: Long): String {
        val array = JSONArray()
        timestamps.forEachIndexed { index, timestamp ->
            array.put(
                org.json.JSONObject().apply {
                    put("id", "entry-$index")
                    put("timestampMs", timestamp)
                    put("modelId", model.id)
                    put("modelVersion", model.version)
                    put("faceCount", 1)
                    put("elapsedMs", 10)
                },
            )
        }
        return array.toString()
    }
}
