package com.localvision.app.vision

import android.content.Context
import android.net.Uri

interface VisionEngine {
    suspend fun analyze(
        image: Uri,
        task: VisionTask,
        options: AnalysisOptions = AnalysisOptions(),
        onStage: (AnalysisStage) -> Unit = {},
    ): VisionResult

    fun cancelActive(): Boolean = false
}

class RegistryBackedVisionEngine(
    context: Context,
    private val registry: ModelRegistry,
) : VisionEngine, AutoCloseable {
    private val faceEngineDelegate = lazy { OnnxFaceDetectionEngine(context.applicationContext) }
    private val faceEngine by faceEngineDelegate

    override suspend fun analyze(
        image: Uri,
        task: VisionTask,
        options: AnalysisOptions,
        onStage: (AnalysisStage) -> Unit,
    ): VisionResult {
        if (image == Uri.EMPTY) throw AnalysisFailure.NoImage()
        if (registry.activeModel(task) == null) throw AnalysisFailure.ModelUnavailable(task)
        return when (task) {
            VisionTask.FACE_DETECTION -> faceEngine.analyze(image, options, onStage)
            else -> throw AnalysisFailure.ModelUnavailable(task)
        }
    }

    override fun close() {
        if (faceEngineDelegate.isInitialized()) runCatching { faceEngine.close() }
    }

    override fun cancelActive(): Boolean =
        if (faceEngineDelegate.isInitialized()) faceEngine.cancelActive() else false
}

