package com.localvision.app.vision

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelRegistryTest {
    @Test
    fun `default catalog installs only reviewed face model`() {
        val registry = ModelRegistry.default()
        VisionTask.entries.forEach { task ->
            assertTrue(registry.modelsFor(task).isNotEmpty())
            if (task == VisionTask.FACE_DETECTION) assertNotNull(registry.activeModel(task))
            else assertTrue(registry.activeModel(task) == null)
        }
    }
}


