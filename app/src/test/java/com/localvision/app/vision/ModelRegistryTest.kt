package com.localvision.app.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelRegistryTest {
    @Test
    fun `default catalog installs only reviewed face model`() {
        val registry = TestModelCatalog.registry()
        VisionTask.entries.forEach { task ->
            assertTrue(registry.modelsFor(task).isNotEmpty())
            if (task == VisionTask.FACE_DETECTION) assertNotNull(registry.activeModel(task))
            else assertNull(registry.activeModel(task))
        }
    }

    @Test
    fun `yunet metadata matches the published model card`() {
        val model = TestModelCatalog.faceModel()
        assertEquals("yunet-face-detection", model.id)
        assertEquals("2023mar-fp32", model.version)
        assertEquals("ONNX", model.format)
        assertEquals("ONNX_RUNTIME_ANDROID", model.runtime)
        assertEquals("1.22.0", model.runtimeVersion)
        assertEquals("MIT", model.license)
        assertEquals("models/yunet/face_detection_yunet_2023mar.onnx", model.assetPath)
        assertEquals(
            "8f2383e4dd3cfbb4553ea8718107fc0423210dc964f9f4280604804ed2552fa4",
            model.sha256,
        )
        assertEquals("https://github.com/opencv/opencv_zoo", model.sourceUrl)
        assertEquals("47534e27c9851bb1128ccc0102f1145e27f23f98", model.sourceCommit)
        assertEquals(ModelRegistry.CURRENT_SCHEMA_VERSION, model.schemaVersion)
        assertTrue(model.installed)
        assertTrue(model.limitations.isNotEmpty())
    }

    @Test
    fun `sha short form is a stable prefix`() {
        val model = TestModelCatalog.faceModel()
        assertEquals(model.sha256.take(12), model.shortSha256)
        assertEquals(12, model.shortSha256.length)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects unknown schema major version`() {
        face().copy(schemaVersion = ModelRegistry.CURRENT_SCHEMA_VERSION + 1)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects malformed sha256`() {
        face().copy(sha256 = "not-a-sha256")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects uppercase sha256`() {
        face().copy(sha256 = face().sha256.uppercase())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects truncated sha256`() {
        face().copy(sha256 = face().sha256.take(63))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `installed model must declare its asset path`() {
        face().copy(assetPath = null)
    }

    @Test
    fun `pending task may omit sha256`() {
        val pending = TestModelCatalog.registry().modelsFor(VisionTask.POSE_ESTIMATION).single()
        assertEquals("", pending.sha256)
        assertNull(pending.assetPath)
        assertTrue(!pending.installed)
    }

    @Test
    fun `empty catalog is safe and reports no supported task`() {
        val registry = ModelRegistry(emptyList())
        VisionTask.entries.forEach { task ->
            assertTrue(registry.modelsFor(task).isEmpty())
            assertNull(registry.activeModel(task))
            assertTrue(!registry.supports(task))
        }
    }

    @Test
    fun `uninstalling every model leaves face detection unsupported`() {
        val registry = ModelRegistry(
            TestModelCatalog.registry().modelsFor(VisionTask.FACE_DETECTION).map { it.copy(installed = false) },
        )
        assertNull(registry.activeModel(VisionTask.FACE_DETECTION))
        assertTrue(!registry.supports(VisionTask.FACE_DETECTION))
    }

    private fun face() = TestModelCatalog.faceModel()
}
