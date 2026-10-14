package com.localvision.app

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 走真实解码器与分析引擎的图片错误路径。
 *
 * 这里**不注入** fake 引擎：损坏字节必须在真实解码链路上失败，
 * 而且失败发生在加载权重之前，因此不需要模型也能得到稳定断言。
 */
@RunWith(AndroidJUnit4::class)
class RealImageErrorTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    private lateinit var fixtures: ImageFixtures
    private val viewModel: MainViewModel
        get() = ViewModelProvider(composeRule.activity)[MainViewModel::class.java]

    @Before
    fun setUp() {
        fixtures = ImageFixtures(context)
    }

    @After
    fun tearDown() {
        fixtures.clear()
    }

    @Test
    fun corruptImageShowsReselectAdviceInsteadOfRetry() {
        val uri = fixtures.corruptImage()
        composeRule.runOnIdle { viewModel.selectImage(uri) }
        composeRule.onNodeWithText(text(R.string.action_analyze)).assertIsEnabled()
        composeRule.onNodeWithText(text(R.string.action_analyze)).performClick()

        composeRule.onNodeWithText(text(R.string.recovery_reselect))
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithText(text(R.string.action_reselect_image))
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithText(text(R.string.recovery_smaller_image)).assertDoesNotExist()
    }

    private fun text(id: Int): String = composeRule.activity.getString(id)
}