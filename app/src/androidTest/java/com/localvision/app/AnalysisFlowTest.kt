package com.localvision.app

import androidx.annotation.StringRes
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.localvision.app.vision.AnalysisFailure
import com.localvision.app.vision.VisionTask
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain

/**
 * 人脸检测主流程的仪器化测试。
 *
 * 全部走真实 Activity + 真实 ViewModel + 真实 Compose UI，只把推理引擎换成
 * [ScriptedVisionEngine]。图片选择通过 ViewModel 的 `selectImage` 入口注入，
 * 不依赖系统 DocumentsUI，因此可以无人值守重复运行。
 */
class AnalysisFlowTest {

    private val engine = ScriptedVisionEngine()
    private val composeRule = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain
        .outerRule(FakeEngineRule(engine))
        .around(composeRule)

    private lateinit var fixtures: ImageFixtures

    private val viewModel: MainViewModel
        get() = ViewModelProvider(composeRule.activity)[MainViewModel::class.java]

    @Before
    fun setUp() {
        fixtures = ImageFixtures(InstrumentationRegistry.getInstrumentation().targetContext)
        engine.script = { _, _, _ -> ScriptedVisionEngine.detections() }
    }

    @After
    fun tearDown() {
        fixtures.clear()
    }

    // 1. 未选图时分析按钮不可用
    @Test
    fun analyzeIsDisabledUntilAnImageIsSelected() {
        composeRule.onNodeWithText(text(R.string.action_analyze)).assertIsNotEnabled()
    }

    // 2. 注入图片 URI 后显示预览并可开始分析
    @Test
    fun selectingImageShowsPreviewAndEnablesAnalysis() {
        composeRule.runOnIdle { viewModel.selectImage(fixtures.plainImage()) }

        composeRule.onNodeWithContentDescription(PREVIEW_DESCRIPTION).assertIsDisplayed()
        composeRule.onNodeWithText(text(R.string.action_analyze)).assertIsEnabled()
    }

    // 带 EXIF 旋转标记的合成图片必须能正常解码，不能被当成损坏文件
    @Test
    fun exifRotatedImageStillPreviewsAndCanBeAnalyzed() {
        composeRule.runOnIdle { viewModel.selectImage(fixtures.exifRotatedImage()) }

        composeRule.onNodeWithContentDescription(PREVIEW_DESCRIPTION).assertIsDisplayed()
        composeRule.onNodeWithText(text(R.string.action_analyze)).assertIsEnabled()
    }

    // 3. 有人脸结果显示数量、详情和真实模型信息
    @Test
    fun detectionsShowCountRowsAndModelInformation() {
        composeRule.runOnIdle {
            viewModel.selectImage(fixtures.plainImage())
            viewModel.analyze()
        }

        composeRule.onNodeWithText(text(R.string.result_count, 2))
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithText(FIRST_ROW, substring = true)
            .performScrollTo()
            .assertIsDisplayed()
        // 运行时名称来自真实注册表，证明模型元数据确实进了 UI
        composeRule.onNodeWithText(RUNTIME_LABEL, substring = true).assertIsDisplayed()
    }

    // 4. 空检测是成功空结果，不是错误
    @Test
    fun emptyDetectionsAreReportedAsSuccess() {
        engine.script = { _, _, _ -> ScriptedVisionEngine.empty() }

        composeRule.runOnIdle {
            viewModel.selectImage(fixtures.plainImage())
            viewModel.analyze()
        }

        composeRule.onNodeWithText(text(R.string.result_empty))
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithText(text(R.string.recovery_none)).assertDoesNotExist()
    }

    // 5. 运行中取消进入 Cancelled，随后到达的结果不显示
    @Test
    fun cancellingARunShowsCancelledAndDiscardsLateResult() {
        val gate = CompletableDeferred<Unit>()
        engine.script = { _, _, _ ->
            gate.await()
            ScriptedVisionEngine.detections()
        }

        composeRule.runOnIdle {
            viewModel.selectImage(fixtures.plainImage())
            viewModel.analyze()
        }
        composeRule.onNodeWithText(text(R.string.action_cancel_analysis)).assertIsDisplayed().performClick()

        composeRule.onNodeWithText(text(R.string.status_cancelled))
            .performScrollTo()
            .assertIsDisplayed()

        gate.complete(Unit)
        composeRule.waitForIdle()
        composeRule.onNodeWithText(text(R.string.result_count, 2)).assertDoesNotExist()
    }

    // 6. 换图后旧请求即使返回结果也不能交付
    @Test
    fun switchingImageDiscardsResultFromPreviousRequest() {
        val gate = CompletableDeferred<Unit>()
        engine.script = { _, _, _ ->
            // 故意忽略取消：模拟"结果晚于换图到达"的竞态
            withContext(NonCancellable) { gate.await() }
            ScriptedVisionEngine.detections()
        }

        composeRule.runOnIdle {
            viewModel.selectImage(fixtures.plainImage())
            viewModel.analyze()
        }
        composeRule.onNodeWithText(text(R.string.action_cancel_analysis)).assertIsDisplayed()

        composeRule.runOnIdle { viewModel.selectImage(fixtures.plainImage(width = 320, height = 320)) }
        gate.complete(Unit)
        composeRule.waitUntil(ENGINE_TIMEOUT_MS) { engine.completed == 1 }

        composeRule.onNodeWithText(text(R.string.result_count, 2)).assertDoesNotExist()
        composeRule.onNodeWithText(text(R.string.action_analyze)).assertIsEnabled()
    }

    // 7. 切换到没有模型的任务：显示未安装说明，不调用引擎，也不产生模拟结果
    @Test
    fun taskWithoutInstalledModelBlocksAnalysisAndNeverCallsEngine() {
        composeRule.runOnIdle {
            viewModel.selectImage(fixtures.plainImage())
            viewModel.selectTask(VisionTask.POSE_ESTIMATION)
        }

        composeRule.onNodeWithText(text(R.string.model_missing_blocking_hint))
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithText(text(R.string.action_analyze)).assertIsNotEnabled()

        val before = engine.calls
        composeRule.runOnIdle { viewModel.analyze() }
        composeRule.waitForIdle()

        assertEquals("模型缺失时引擎不得被调用", before, engine.calls)
        composeRule.onNodeWithText(text(R.string.recovery_select_task)).assertIsDisplayed()
        composeRule.onNodeWithText(text(R.string.result_empty)).assertDoesNotExist()
    }

    // 8. 模型失败与内存不足必须给出各自的恢复建议
    @Test
    fun modelFailureAsksForInstalledTask() {
        engine.script = { _, _, _ -> throw AnalysisFailure.InvalidModel("权重校验失败") }

        composeRule.runOnIdle {
            viewModel.selectImage(fixtures.plainImage())
            viewModel.analyze()
        }

        composeRule.onNodeWithText(text(R.string.recovery_select_task))
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun outOfMemoryAsksForSmallerImage() {
        engine.script = { _, _, _ -> throw AnalysisFailure.OutOfMemory() }

        composeRule.runOnIdle {
            viewModel.selectImage(fixtures.plainImage())
            viewModel.analyze()
        }

        composeRule.onNodeWithText(text(R.string.recovery_smaller_image))
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun unknownRuntimeFailureOffersRetry() {
        engine.script = { _, _, _ -> throw IllegalStateException("运行时异常") }

        composeRule.runOnIdle {
            viewModel.selectImage(fixtures.plainImage())
            viewModel.analyze()
        }

        composeRule.onNodeWithText(text(R.string.recovery_retry))
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithText(text(R.string.action_retry)).assertIsDisplayed()
    }

    // 9. 参数变化后结果标记过期，重新分析后清除
    @Test
    fun changingParametersMarksResultStaleUntilReanalyzed() {
        composeRule.runOnIdle {
            viewModel.selectImage(fixtures.plainImage())
            viewModel.analyze()
        }
        composeRule.onNodeWithText(text(R.string.result_count, 2)).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(text(R.string.stale_notice)).assertDoesNotExist()

        composeRule.runOnIdle { viewModel.setScoreThreshold(0.9f) }
        composeRule.onNodeWithText(text(R.string.stale_notice)).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(text(R.string.action_reanalyze_with_new_config)).assertIsDisplayed()

        composeRule.runOnIdle { viewModel.analyze() }
        composeRule.onNodeWithText(text(R.string.stale_notice)).assertDoesNotExist()
        composeRule.onNodeWithText(text(R.string.result_count, 2)).assertIsDisplayed()
    }

    // 10. 超过十条的结果不能被静默截断
    @Test
    fun longResultListIsNotSilentlyTruncated() {
        engine.script = { _, _, _ -> ScriptedVisionEngine.detections(12) }

        composeRule.runOnIdle {
            viewModel.selectImage(fixtures.plainImage())
            viewModel.analyze()
        }

        composeRule.onNodeWithText(text(R.string.result_count, 12))
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithText(ROW_10, substring = true)
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithText(text(R.string.result_show_all, 12))
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()

        composeRule.onNodeWithText(ROW_12, substring = true).assertIsDisplayed()
        composeRule.onNodeWithText(text(R.string.result_collapse)).assertIsDisplayed()
    }

    // 11. 进入后台触发取消，恢复后状态可理解
    @Test
    fun leavingToBackgroundCancelsRunningAnalysis() {
        val gate = CompletableDeferred<Unit>()
        engine.script = { _, _, _ ->
            gate.await()
            ScriptedVisionEngine.detections()
        }

        composeRule.runOnIdle {
            viewModel.selectImage(fixtures.plainImage())
            viewModel.analyze()
        }
        composeRule.onNodeWithText(text(R.string.action_cancel_analysis)).assertIsDisplayed()

        composeRule.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        composeRule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)

        composeRule.onNodeWithText(text(R.string.status_cancelled))
            .performScrollTo()
            .assertIsDisplayed()
        assertTrue("进入后台必须向引擎发送取消", engine.cancelCount > 0)

        gate.complete(Unit)
        composeRule.waitForIdle()
        composeRule.onNodeWithText(text(R.string.result_count, 2)).assertDoesNotExist()
    }

    private fun text(@StringRes id: Int, vararg args: Any): String =
        composeRule.activity.getString(id, *args)

    private companion object {
        const val ENGINE_TIMEOUT_MS = 5_000L
        const val PREVIEW_DESCRIPTION = "待分析图片预览"
        const val RUNTIME_LABEL = "ONNX_RUNTIME_ANDROID"

        // 结果行的百分比由 Float 格式化而来，直接匹配序号更稳定。
        const val FIRST_ROW = "人脸 1 ·"
        const val ROW_10 = "人脸 10 ·"
        const val ROW_12 = "人脸 12 ·"
    }
}