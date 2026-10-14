package com.localvision.app.vision

import android.content.Context
import android.net.Uri

interface VisionEngine {
    suspend fun analyze(
        image: Uri,
        task: VisionTask,
        config: AnalysisConfig = AnalysisConfig(),
        onStage: (AnalysisStage) -> Unit = {},
    ): VisionResult

    fun cancelActive(): Boolean = false

    /** 释放模型会话等原生资源；默认无资源可释放。 */
    fun close() = Unit
}

class RegistryBackedVisionEngine(
    context: Context,
    private val registry: ModelRegistry,
) : VisionEngine, AutoCloseable {
    private val appContext = context.applicationContext

    /**
     * 引擎只持有模型描述符，不自己知道 asset 路径和哈希——这些值属于模型目录。
     * 未安装人脸模型时初始化会抛 [AnalysisFailure.ModelUnavailable]，而不是造一个假引擎。
     */
    private val faceEngineDelegate = lazy { OnnxFaceDetectionEngine(appContext, requireFaceModel()) }
    private val faceEngine by faceEngineDelegate

    override suspend fun analyze(
        image: Uri,
        task: VisionTask,
        config: AnalysisConfig,
        onStage: (AnalysisStage) -> Unit,
    ): VisionResult {
        if (image == Uri.EMPTY) throw AnalysisFailure.NoImage()
        if (registry.activeModel(task) == null) throw AnalysisFailure.ModelUnavailable(task)
        return when (task) {
            VisionTask.FACE_DETECTION -> faceEngine.analyze(image, config, onStage)
            else -> throw AnalysisFailure.ModelUnavailable(task)
        }
    }

    private fun requireFaceModel(): ModelDescriptor =
        registry.activeModel(VisionTask.FACE_DETECTION)
            ?: throw AnalysisFailure.ModelUnavailable(VisionTask.FACE_DETECTION)

    override fun close() {
        if (faceEngineDelegate.isInitialized()) runCatching { faceEngine.close() }
    }

    override fun cancelActive(): Boolean =
        if (faceEngineDelegate.isInitialized()) faceEngine.cancelActive() else false
}
