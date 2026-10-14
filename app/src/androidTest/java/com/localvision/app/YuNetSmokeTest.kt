package com.localvision.app

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.localvision.app.vision.AnalysisConfig
import com.localvision.app.vision.ModelRegistry
import com.localvision.app.vision.RegistryBackedVisionEngine
import com.localvision.app.vision.VisionResult
import com.localvision.app.vision.VisionTask
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 真实 YuNet 权重的冒烟测试。
 *
 * 这里**不做**精度断言：不同 CPU 与 ONNX Runtime 版本的浮点输出不可直接比较。
 * 只验证真实推理链路不会崩溃、返回类型正确、坐标落在合法范围内。
 * 它不能替代独立测试集报告，也不代表质量达标。
 */
@RunWith(AndroidJUnit4::class)
class YuNetSmokeTest {

    private val application: Application = ApplicationProvider.getApplicationContext()
    private val fixtures = ImageFixtures(application)
    private var engine: RegistryBackedVisionEngine? = null

    @After
    fun tearDown() {
        engine?.close()
        fixtures.clear()
    }

    @Test
    fun bundledModelRunsAndReturnsContractCompliantResult() {
        val uri = fixtures.plainImage()
        val detector = RegistryBackedVisionEngine(
            application,
            ModelRegistry.load(application),
        ).also { engine = it }

        val result = runBlocking { detector.analyze(uri, VisionTask.FACE_DETECTION, AnalysisConfig()) }

        assertTrue("应返回人脸检测结果，实际为 ${result::class.java.name}", result is VisionResult.Detections)
        val detections = result as VisionResult.Detections
        assertTrue("耗时必须为正数", detections.elapsedMs > 0)
        detections.items.forEach { face ->
            val box = face.bounds
            assertTrue("left 必须落在 0..1，实际为 ${box.left}", box.left >= 0f && box.left <= 1f)
            assertTrue("right 必须落在 0..1，实际为 ${box.right}", box.right >= 0f && box.right <= 1f)
            assertTrue("top 必须落在 0..1，实际为 ${box.top}", box.top >= 0f && box.top <= 1f)
            assertTrue("bottom 必须落在 0..1，实际为 ${box.bottom}", box.bottom >= 0f && box.bottom <= 1f)
            assertTrue("矩形必须有正面积", box.right > box.left && box.bottom > box.top)
            face.keypoints.forEach { point ->
                assertTrue(
                    "关键点 ${point.name} 越界：(${point.point.x}, ${point.point.y})",
                    point.point.x >= 0f && point.point.x <= 1f && point.point.y >= 0f && point.point.y <= 1f,
                )
            }
        }
    }
}