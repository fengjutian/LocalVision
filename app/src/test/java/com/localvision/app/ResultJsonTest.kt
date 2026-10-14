package com.localvision.app

import com.localvision.app.vision.AnalysisConfig
import com.localvision.app.vision.CompletedAnalysis
import com.localvision.app.vision.Detection
import com.localvision.app.vision.Keypoint
import com.localvision.app.vision.TestModelCatalog
import com.localvision.app.vision.NormalizedPoint
import com.localvision.app.vision.NormalizedRect
import com.localvision.app.vision.PerformanceBreakdown
import com.localvision.app.vision.VisionResult
import com.localvision.app.vision.VisionTask
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResultJsonTest {
    private val model = TestModelCatalog.faceModel()

    private fun completed(
        items: List<Detection>,
        config: AnalysisConfig = AnalysisConfig(0.62f, 0.35f),
    ) = CompletedAnalysis(
        result = VisionResult.Detections(
            items = items,
            elapsedMs = 120L,
            performance = PerformanceBreakdown(decodeMs = 1, preprocessMs = 2, modelLoadMs = 3, inferenceMs = 4, postprocessMs = 5),
        ),
        model = model,
        config = config,
    )

    private fun detection(confidence: Float = 0.87f) = Detection(
        bounds = NormalizedRect(0.1f, 0.2f, 0.3f, 0.4f),
        label = "人脸",
        confidence = confidence,
        keypoints = listOf(
            Keypoint("右眼", NormalizedPoint(0.15f, 0.25f), confidence),
            Keypoint("左眼", NormalizedPoint(0.25f, 0.25f), confidence),
            Keypoint("鼻尖", NormalizedPoint(0.20f, 0.31f), confidence),
            Keypoint("右嘴角", NormalizedPoint(0.16f, 0.37f), confidence),
            Keypoint("左嘴角", NormalizedPoint(0.24f, 0.37f), confidence),
        ),
    )

    @Test
    fun `writes the documented schema v1 envelope`() {
        val root = JSONObject(ResultJson.encode(completed(listOf(detection())), listOf(detection()), createdAtMs = 0L))
        assertEquals(1, root.getInt("schemaVersion"))
        assertEquals("FACE_DETECTION", root.getString("task"))
        assertEquals("EXIF_CORRECTED_ORIGINAL_NORMALIZED_0_1", root.getString("coordinateSystem"))

        val modelNode = root.getJSONObject("model")
        assertEquals("yunet-face-detection", modelNode.getString("id"))
        assertEquals("2023mar-fp32", modelNode.getString("version"))
        assertEquals(model.sha256, modelNode.getString("sha256"))
        assertEquals("ONNX_RUNTIME_ANDROID", modelNode.getString("runtime"))
        assertEquals("1.22.0", modelNode.getString("runtimeVersion"))
        assertEquals("MIT", modelNode.getString("license"))

        val configNode = root.getJSONObject("config")
        assertEquals(0.62, configNode.getDouble("scoreThreshold"), 1e-6)
        assertEquals(0.35, configNode.getDouble("nmsThreshold"), 1e-6)

        val performance = root.getJSONObject("performanceMs")
        assertEquals(120L, performance.getLong("total"))
        assertEquals(1L, performance.getLong("decode"))
        assertEquals(5L, performance.getLong("postprocess"))
    }

    @Test
    fun `floats are emitted as json numbers not strings`() {
        val raw = ResultJson.encode(completed(listOf(detection())), listOf(detection()))
        assertFalse(raw.contains("\"confidence\": \""))
        val detection = JSONObject(raw).getJSONArray("detections").getJSONObject(0)
        assertNumber(0.87, detection.get("confidence"))
        assertNumber(0.1, detection.getJSONObject("bounds").get("left"))
        assertNumber(0.15, detection.getJSONArray("keypoints").getJSONObject(0).get("x"))
        assertNumber(0.62, JSONObject(raw).getJSONObject("config").get("scoreThreshold"))
        assertNumber(0.35, JSONObject(raw).getJSONObject("config").get("nmsThreshold"))
    }

    /** 数值必须是 JSON number；字符串化的阈值会让下游无法直接参与计算。 */
    private fun assertNumber(expected: Double, actual: Any?) {
        assertTrue("expected a JSON number but was ${actual?.javaClass?.name}", actual is Number)
        assertEquals(expected, (actual as Number).toDouble(), 0.001)
    }

    @Test
    fun `encodes five named keypoints per detection`() {
        val root = JSONObject(ResultJson.encode(completed(listOf(detection())), listOf(detection())))
        val keypoints = root.getJSONArray("detections").getJSONObject(0).getJSONArray("keypoints")
        assertEquals(5, keypoints.length())
        assertEquals("右眼", keypoints.getJSONObject(0).getString("name"))
        assertEquals("左嘴角", keypoints.getJSONObject(4).getString("name"))
    }

    @Test
    fun `an empty detection list is still a valid success payload`() {
        val raw = ResultJson.encode(completed(emptyList()), emptyList())
        val root = JSONObject(raw)
        assertEquals(0, root.getJSONArray("detections").length())
        assertEquals("FACE_DETECTION", root.getString("task"))
    }

    @Test
    fun `never emits NaN or Infinity`() {
        val broken = detection(Float.NaN)
        val infinite = detection(Float.POSITIVE_INFINITY)
        val raw = ResultJson.encode(completed(listOf(broken, infinite)), listOf(broken, infinite))
        assertFalse(raw.contains("NaN"))
        assertFalse(raw.contains("Infinity"))
        val root = JSONObject(raw)
        assertEquals(0.0, root.getJSONArray("detections").getJSONObject(0).getDouble("confidence"), 1e-9)
    }

    @Test
    fun `timestamp is utc iso-8601`() {
        val root = JSONObject(ResultJson.encode(completed(emptyList()), emptyList(), createdAtMs = 0L))
        assertEquals("1970-01-01T00:00:00Z", root.getString("createdAt"))
    }

    @Test
    fun `timestamp does not depend on the default locale`() {
        val previous = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale("tr", "TR"))
            val root = JSONObject(ResultJson.encode(completed(emptyList()), emptyList(), createdAtMs = 0L))
            assertEquals("1970-01-01T00:00:00Z", root.getString("createdAt"))
        } finally {
            java.util.Locale.setDefault(previous)
        }
    }

    @Test
    fun `payload carries no uri path filename or device identifier`() {
        val raw = ResultJson.encode(completed(listOf(detection())), listOf(detection()))
        listOf("content://", "file://", "/storage/", "MediaStore", "ANDROID_ID", "gps", "latitude", ".jpg", ".png")
            .forEach { forbidden ->
                assertFalse("导出不应包含 $forbidden", raw.contains(forbidden, ignoreCase = true))
            }
        assertFalse(raw.contains(model.sourceUrl))
        assertFalse(raw.contains(model.assetPath!!))
    }
}
