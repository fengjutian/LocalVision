package com.localvision.app.vision

import android.content.Context
import androidx.annotation.VisibleForTesting

/**
 * 引擎装配点。
 *
 * 生产路径只在这里创建真实引擎；仪器化测试用 [installForTest] 注入 fake，
 * 这样 UI 流程验证不依赖真实权重，也不需要自动化系统文件选择器。
 * 没有注入时行为与改造前完全一致。
 */
object VisionEngineProvider {
    @Volatile
    private var override: VisionEngine? = null

    /** 注入或清除测试引擎；传 null 恢复真实引擎。 */
    @VisibleForTesting
    fun installForTest(engine: VisionEngine?) {
        override = engine
    }

    /** 当前注入的引擎；生产恒为 null。 */
    fun currentOverride(): VisionEngine? = override

    fun create(context: Context): VisionEngine =
        override ?: RegistryBackedVisionEngine(context.applicationContext, ModelRegistry.default())
}