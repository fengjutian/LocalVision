package com.localvision.app.vision

class ModelRegistry(models: List<ModelDescriptor>) {
    private val modelsByTask = models.groupBy(ModelDescriptor::task)

    fun modelsFor(task: VisionTask): List<ModelDescriptor> = modelsByTask[task].orEmpty()

    fun activeModel(task: VisionTask): ModelDescriptor? =
        modelsFor(task).firstOrNull(ModelDescriptor::installed)

    companion object {
        fun default(): ModelRegistry = ModelRegistry(
            VisionTask.entries.map { task ->
                ModelDescriptor(
                    id = "pending-${task.name.lowercase()}",
                    displayName = "待选型模型",
                    version = "—",
                    task = task,
                    format = "ONNX",
                    license = "待审查",
                    installed = false,
                    limitations = "权重、许可证与真机性能验证完成前不进入发布包",
                )
            },
        )
    }
}

