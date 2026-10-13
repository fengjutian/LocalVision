package com.localvision.app.vision

import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelRegistryTest {
    @Test
    fun `default catalog is explicit about unavailable models`() {
        val registry = ModelRegistry.default()
        VisionTask.entries.forEach { task ->
            assertTrue(registry.modelsFor(task).isNotEmpty())
            assertNull(registry.activeModel(task))
        }
    }
}

