package com.localvision.app.vision

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 锁定 YuNet 输入契约：640x640、NCHW、BGR、FP32、[0,255]、黑色居中 letterbox。
 *
 * 这些纯函数不依赖设备和真实 ONNX 模型，可在普通 JVM 测试里运行。
 */
class YuNetPreprocessorTest {

    // 1. 单像素 ARGB 的通道顺序
    @Test
    fun `single pixel is converted to bgr channel order`() {
        val tensor = argbToNchwBgr(intArrayOf(0xFF112233.toInt()), 1, 1)

        assertEquals(3, tensor.size)
        assertEquals(0x33.toFloat(), tensor[0], 0f) // B
        assertEquals(0x22.toFloat(), tensor[1], 0f) // G
        assertEquals(0x11.toFloat(), tensor[2], 0f) // R
    }

    // 2. 多像素验证平面布局，而不是交错 BGR
    @Test
    fun `multiple pixels use plane layout instead of interleaved bgr`() {
        val pixels = intArrayOf(
            0xFF010203.toInt(),
            0xFF040506.toInt(),
            0xFF070809.toInt(),
            0xFF0A0B0C.toInt(),
        )

        val tensor = argbToNchwBgr(pixels, 2, 2)
        val plane = 4

        assertEquals(12, tensor.size)
        assertArrayEquals(floatArrayOf(3f, 6f, 9f, 12f), tensor.copyOfRange(0, plane), 0f)
        assertArrayEquals(floatArrayOf(2f, 5f, 8f, 11f), tensor.copyOfRange(plane, 2 * plane), 0f)
        assertArrayEquals(floatArrayOf(1f, 4f, 7f, 10f), tensor.copyOfRange(2 * plane, 3 * plane), 0f)
    }

    @Test
    fun `values stay in the zero to 255 range without normalization`() {
        val tensor = argbToNchwBgr(intArrayOf(0xFF000000.toInt(), 0xFFFFFFFF.toInt()), 1, 2)

        assertArrayEquals(floatArrayOf(0f, 255f, 0f, 255f, 0f, 255f), tensor, 0f)
    }

    // 3. 正方形输入无 padding
    @Test
    fun `square input has no padding`() {
        assertEquals(LetterboxGeometry(640, 640, 0, 0), calculateLetterbox(640, 640, 640))
        assertEquals(LetterboxGeometry(320, 320, 0, 0), calculateLetterbox(320, 320, 640))
    }

    // 4. 横图：垂直居中 padding
    @Test
    fun `landscape input pads vertically and stays centered`() {
        val geometry = calculateLetterbox(1280, 640, 640)

        assertEquals(640, geometry.scaledWidth)
        assertEquals(320, geometry.scaledHeight)
        assertEquals(0, geometry.padX)
        assertEquals(160, geometry.padY)
        assertCentered(640, geometry.padY, geometry.scaledHeight)
    }

    // 5. 竖图：水平居中 padding
    @Test
    fun `portrait input pads horizontally and stays centered`() {
        val geometry = calculateLetterbox(640, 1280, 640)

        assertEquals(320, geometry.scaledWidth)
        assertEquals(640, geometry.scaledHeight)
        assertEquals(160, geometry.padX)
        assertEquals(0, geometry.padY)
        assertCentered(640, geometry.padX, geometry.scaledWidth)
    }

    // 6. 极端宽高比仍保证缩放尺寸至少为 1
    @Test
    fun `extreme aspect ratios keep at least one scaled pixel`() {
        val wide = calculateLetterbox(10_000, 10, 640)
        assertEquals(640, wide.scaledWidth)
        assertEquals(1, wide.scaledHeight)
        assertEquals(0, wide.padX)
        assertEquals(319, wide.padY)
        assertTrue(wide.scaledHeight >= 1)

        val tall = calculateLetterbox(10, 10_000, 640)
        assertEquals(1, tall.scaledWidth)
        assertEquals(640, tall.scaledHeight)
        assertEquals(319, tall.padX)
        assertEquals(0, tall.padY)
        assertTrue(tall.scaledWidth >= 1)
    }

    // 9. 宽高为零（以及非法 inputSize）时抛出明确异常
    @Test
    fun `invalid sizes are rejected with an explicit error`() {
        assertThrows(IllegalArgumentException::class.java) { calculateLetterbox(0, 640, 640) }
        assertThrows(IllegalArgumentException::class.java) { calculateLetterbox(640, 0, 640) }
        assertThrows(IllegalArgumentException::class.java) { calculateLetterbox(-1, 640, 640) }
        assertThrows(IllegalArgumentException::class.java) { calculateLetterbox(640, 640, 0) }
    }

    @Test
    fun `pixel count must match the declared size`() {
        assertThrows(IllegalArgumentException::class.java) { argbToNchwBgr(IntArray(3), 2, 2) }
    }

    // 7. 内容区的逆变换
    @Test
    fun `transform maps the content region back to original coordinates`() {
        val landscape = LetterboxTransform(640, 640, 320, 0, 160)
        assertEquals(0f, landscape.originalY(160f), 1e-6f)
        assertEquals(0.5f, landscape.originalY(320f), 1e-6f)
        assertEquals(1f, landscape.originalY(480f), 1e-6f)
        assertEquals(0f, landscape.originalX(0f), 1e-6f)
        assertEquals(1f, landscape.originalX(640f), 1e-6f)

        val portrait = LetterboxTransform(640, 320, 640, 160, 0)
        assertEquals(0f, portrait.originalX(160f), 1e-6f)
        assertEquals(0.5f, portrait.originalX(320f), 1e-6f)
        assertEquals(1f, portrait.originalX(480f), 1e-6f)
        assertEquals(0f, portrait.originalY(0f), 1e-6f)
        assertEquals(1f, portrait.originalY(640f), 1e-6f)

        val identity = LetterboxTransform.identity(640)
        assertEquals(0.25f, identity.originalX(160f), 1e-6f)
        assertEquals(1f, identity.originalX(640f), 1e-6f)
    }

    // 8. 锁定当前钳位语义，本次不擅自修改
    @Test
    fun `existing clamping semantics are pinned`() {
        val full = LetterboxTransform(640, 640, 640, 0, 0)
        assertEquals(0f, full.originalX(-100f), 0f)
        assertEquals(1f, full.originalX(1_000f), 0f)
        assertEquals(0f, full.originalY(-0.001f), 0f)
        assertEquals(1f, full.originalY(640.001f), 0f)

        // 已知取舍：落在 letterbox padding 上的关键点会被钳到内容边界，
        // 而不是被标记为"不可见"。这里只固定现状，便于将来修正时做对比。
        val letterboxed = LetterboxTransform(640, 320, 640, 160, 0)
        assertEquals(0f, letterboxed.originalX(160f), 0f)
        assertEquals(0f, letterboxed.originalX(100f), 0f)
        assertEquals(0f, letterboxed.originalX(0f), 0f)
    }

    @Test
    fun `letterbox never crops the source image`() {
        for ((width, height) in listOf(640 to 640, 1280 to 640, 640 to 1280, 4032 to 3024, 1 to 1)) {
            val geometry = calculateLetterbox(width, height, 640)
            assertTrue("scaledWidth ${geometry.scaledWidth} 超出输入", geometry.scaledWidth in 1..640)
            assertTrue("scaledHeight ${geometry.scaledHeight} 超出输入", geometry.scaledHeight in 1..640)
            assertTrue("padX 越界", geometry.padX >= 0)
            assertTrue("padY 越界", geometry.padY >= 0)
            assertTrue("内容区超出输入", geometry.padX + geometry.scaledWidth <= 640)
            assertTrue("内容区超出输入", geometry.padY + geometry.scaledHeight <= 640)
            // 等比性：两个方向的缩放比例一致（允许 1 像素取整误差）
            assertEquals(
                width.toFloat() / height,
                geometry.scaledWidth.toFloat() / geometry.scaledHeight,
                0.05f,
            )
        }
    }

    private fun assertCentered(inputSize: Int, pad: Int, scaled: Int) {
        val trailing = inputSize - pad - scaled
        assertTrue("padding 应居中，实际 pad=$pad scaled=$scaled", trailing - pad in 0..1)
    }
}