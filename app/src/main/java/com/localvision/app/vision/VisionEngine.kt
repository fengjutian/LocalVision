package com.localvision.app.vision

import android.net.Uri

interface VisionEngine {
    suspend fun analyze(image: Uri, task: VisionTask): VisionResult
}

class RegistryBackedVisionEngine(private val registry: ModelRegistry) : VisionEngine {
    override suspend fun analyze(image: Uri, task: VisionTask): VisionResult {
        if (image == Uri.EMPTY) throw AnalysisFailure.NoImage()
        if (registry.activeModel(task) == null) throw AnalysisFailure.ModelUnavailable(task)
        error("An installed model must provide its own VisionEngine adapter")
    }
}

