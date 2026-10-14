package com.localvision.app

import android.net.Uri
import com.localvision.app.vision.AnalysisConfig
import com.localvision.app.vision.AnalysisStage
import com.localvision.app.vision.Detection
import com.localvision.app.vision.Keypoint
import com.localvision.app.vision.NormalizedPoint
import com.localvision.app.vision.NormalizedRect
import com.localvision.app.vision.PerformanceBreakdown
import com.localvision.app.vision.VisionEngine
import com.localvision.app.vision.VisionEngineProvider
import com.localvision.app.vision.VisionResult
import com.localvision.app.vision.VisionTask
import org.junit.rules.ExternalResource
import kotlin.math.min

/**
 * 可脚本化的 fake 引擎。
 *
 * 它只决定"这次分析返回什么、抛什么、挂起多久"，因此可以在不加载真实权重、
 * 也不操作 DocumentsUI 的前提下验证 UI 流程。调用次数、取消次数和关闭次数都被记录，
 * 用来断言"引擎根本没有被调用"这类负向结论。
 */
class ScriptedVisionEngine : VisionEngine {

    @Volatile
    var script: suspend (Uri, VisionTask, AnalysisConfig) -> VisionResult = { _, _, _ -> detections() }

    @Volatile
    var calls: Int = 0
        private set

    /** 已完成的 script 调用数；用于等待"结果晚到"这类竞态真正结束。 */
    @Volatile
    var completed: Int = 0
        private set

    @Volatile
    var cancelCount: Int = 0
        private set

    @Volatile
    var closeCount: Int = 0
        private set

    override suspend fun analyze(
        image: Uri,
        task: VisionTask,
        config: AnalysisConfig,
        onStage: (AnalysisStage) -> Unit,
    ): VisionResult {
        calls++
        onStage(AnalysisStage.INFERENCE)
        return script(image, task, config).also { completed++ }
    }

    override fun cancelActive(): Boolean {
        cancelCount++
        return true
    }

    override fun close() {
        closeCount++
    }

    companion object {
        fun detections(count: Int = 2): VisionResult.Detections = VisionResult.Detections(
            items = List(count) { detectionAt(it) },
            elapsedMs = 42L,
            performance = PerformanceBreakdown(
                decodeMs = 5,
                preprocessMs = 6,
                modelLoadMs = 7,
                inferenceMs = 18,
                postprocessMs = 6,
            ),
        )

        fun empty(): VisionResult.Detections =
            VisionResult.Detections(items = emptyList(), elapsedMs = 30L)

        private fun detectionAt(index: Int): Detection {
            val left = 0.08f + index * 0.05f
            val top = 0.08f + index * 0.05f
            return Detection(
                bounds = NormalizedRect(
                    left = left,
                    top = top,
                    right = min(0.95f, left + 0.2f),
                    bottom = min(0.95f, top + 0.2f),
                ),
                label = "人脸",
                confidence = 0.95f - index * 0.01f,
                keypoints = listOf(
                    Keypoint("右眼", NormalizedPoint(0.30f, 0.30f), 0.9f),
                    Keypoint("左眼", NormalizedPoint(0.40f, 0.30f), 0.9f),
                ),
            )
        }
    }
}

/**
 * 通过 RuleChain 包在 Activity 规则外层，确保 fake 引擎在 Activity 启动、
 * ViewModel 被创建之前就已就位——否则 UI 拿到的会是真实引擎。
 */
class FakeEngineRule(private val engine: VisionEngine) : ExternalResource() {
    override fun before() {
        VisionEngineProvider.installForTest(engine)
    }

    override fun after() {
        VisionEngineProvider.installForTest(null)
    }
}