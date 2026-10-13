package com.localvision.app.vision

import org.junit.Assert.assertEquals
import org.junit.Test

class CoordinateMapperTest {
    @Test
    fun `maps normalized box with vertical letterboxing`() {
        val result = CoordinateMapper.toViewport(
            rect = NormalizedRect(0.25f, 0.25f, 0.75f, 0.75f),
            imageWidth = 400,
            imageHeight = 200,
            viewportWidth = 200f,
            viewportHeight = 200f,
        )
        assertEquals(50f, result.left, 0.01f)
        assertEquals(75f, result.top, 0.01f)
        assertEquals(150f, result.right, 0.01f)
        assertEquals(125f, result.bottom, 0.01f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects invalid normalized rectangles`() {
        NormalizedRect(0.8f, 0.1f, 0.2f, 0.9f)
    }
}

