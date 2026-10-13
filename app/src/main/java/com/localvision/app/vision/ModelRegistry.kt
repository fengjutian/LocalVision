package com.localvision.app.vision

class ModelRegistry(models: List<ModelDescriptor>) {
    private val modelsByTask = models.groupBy(ModelDescriptor::task)

    fun modelsFor(task: VisionTask): List<ModelDescriptor> = modelsByTask[task].orEmpty()

    fun activeModel(task: VisionTask): ModelDescriptor? =
        modelsFor(task).firstOrNull(ModelDescriptor::installed)

    companion object {
        fun default(): ModelRegistry = ModelRegistry(
            VisionTask.entries.map { task ->
                val face = task == VisionTask.FACE_DETECTION
                ModelDescriptor(
                    id = if (face) "yunet-face-detection" else "pending-${task.name.lowercase()}",
                    displayName = if (face) "YuNet 人脸检测" else "待选型模型",
                    version = if (face) "2023mar-fp32" else "—",
                    task = task,
                    format = "ONNX",
                    license = if (face) "MIT" else "待审查",
                    installed = face,
                    limitations = if (face) {
                        "仅检测人脸框与五点关键点，不识别身份；需继续完成真机性能与独立测试集验收"
                    } else {
                        "权重、许可证与真机性能验证完成前不进入发布包"
                    },
                )
            },
        )
    }
}

