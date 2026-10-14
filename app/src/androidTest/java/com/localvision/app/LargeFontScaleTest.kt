package com.localvision.app

import android.app.Application
import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.localvision.app.vision.VisionEngineProvider
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 200% 字体缩放下的可访问性。
 *
 * 只覆盖 LocalDensity 的 fontScale，不改设备真实设置，
 * 因此测试是确定性的，也不会把模拟器留在被修改的状态。
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class LargeFontScaleTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun keyControlsAndFullListRemainReachableAtDoubleFontScale() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val engine = ScriptedVisionEngine().apply {
            script = { _, _, _ -> ScriptedVisionEngine.detections(12) }
        }
        VisionEngineProvider.installForTest(engine)
        val fixtures = ImageFixtures(context)
        try {
            val viewModel = MainViewModel(context as Application)
            viewModel.selectImage(fixtures.plainImage())
            viewModel.analyze()

            composeRule.setContent {
                val base = LocalDensity.current
                CompositionLocalProvider(
                    LocalDensity provides Density(density = base.density, fontScale = DOUBLE_FONT_SCALE),
                ) {
                    LocalVisionApp(viewModel)
                }
            }

            composeRule.onNodeWithContentDescription(PREVIEW_DESCRIPTION).assertIsDisplayed()
            composeRule.onNodeWithText(context.getString(R.string.action_analyze))
                .performScrollTo()
                .assertIsDisplayed()
            composeRule.onNodeWithText(context.getString(R.string.result_show_all, 12))
                .performScrollTo()
                .assertIsDisplayed()
                .performClick()
            composeRule.onNodeWithText(LAST_ROW, substring = true).assertIsDisplayed()
            composeRule.onNodeWithText(context.getString(R.string.result_collapse))
                .performScrollTo()
                .assertIsDisplayed()

            assertEquals(1, engine.calls)
        } finally {
            fixtures.clear()
            VisionEngineProvider.installForTest(null)
        }
    }

    private companion object {
        const val PREVIEW_DESCRIPTION = "待分析图片预览"
        const val LAST_ROW = "人脸 12 ·"

        /** 系统 200% 字体缩放。 */
        const val DOUBLE_FONT_SCALE = 2f
    }
}