package com.localvision.app.vision

import org.junit.Assert.assertEquals
import org.junit.Test

class AndroidImageDecoderTest {
    @Test
    fun `uses power of two sampling for oversized images`() {
        assertEquals(1, AndroidImageDecoder.sampleSize(4096))
        assertEquals(2, AndroidImageDecoder.sampleSize(8192))
        assertEquals(4, AndroidImageDecoder.sampleSize(20000))
    }
}
