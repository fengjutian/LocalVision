package com.localvision.app.vision

/**
 * 结构化模型元数据。
 *
 * 字段与 `docs/PRODUCT_SPEC.md` §8 的模型清单 schema 对齐。[ModelRegistry] 是这些值的唯一来源：
 * UI 的模型面板、导出 JSON、分析历史都从这里取值，任何一处都不得再硬编码模型 ID 或版本。
 */
data class ModelDescriptor(
    val schemaVersion: Int,
    val id: String,
    val displayName: String,
    val version: String,
    val task: VisionTask,
    val format: String,
    val runtime: String,
    val runtimeVersion: String,
    val assetPath: String?,
    val sha256: String,
    val license: String,
    val sourceUrl: String,
    val sourceCommit: String,
    val installed: Boolean,
    val limitations: List<String>,
) {
    init {
        require(schemaVersion == ModelRegistry.CURRENT_SCHEMA_VERSION) {
            "不支持的模型清单 schema 版本：$schemaVersion"
        }
        require(id.isNotBlank()) { "模型 id 不能为空" }
        if (sha256.isNotEmpty()) {
            require(SHA256_PATTERN.matches(sha256)) { "sha256 必须是 64 位小写十六进制：$sha256" }
        }
        require(!installed || (assetPath != null && sha256.isNotEmpty())) {
            "已安装模型必须同时声明 assetPath 与 sha256"
        }
    }

    /** 面板上展示的哈希短摘要；完整值由调用方按需展开并允许复制。 */
    val shortSha256: String get() = sha256.take(SHORT_SHA256_LENGTH)

    companion object {
        private val SHA256_PATTERN = Regex("^[0-9a-f]{64}$")
        const val SHORT_SHA256_LENGTH = 12
    }
}

class ModelRegistry(models: List<ModelDescriptor>) {
    private val modelsByTask = models.groupBy(ModelDescriptor::task)

    fun modelsFor(task: VisionTask): List<ModelDescriptor> = modelsByTask[task].orEmpty()

    fun activeModel(task: VisionTask): ModelDescriptor? = modelsFor(task).firstOrNull(ModelDescriptor::installed)

    /** 任务是否具备真实可执行模型。为 false 时 UI 不得展示"可分析"状态。 */
    fun supports(task: VisionTask): Boolean = activeModel(task) != null

    companion object {
        /** 当前实现支持的主版本；未知主版本必须拒绝加载而不是静默忽略。 */
        const val CURRENT_SCHEMA_VERSION = 1

        /** 内置目录。数值必须与 `third_party/yunet/MODEL_CARD.md` 保持一致。 */
        fun default(): ModelRegistry = ModelRegistry(
            VisionTask.entries.map { task ->
                if (task == VisionTask.FACE_DETECTION) yunetFaceDetection() else pending(task)
            },
        )

        private fun yunetFaceDetection() = ModelDescriptor(
            schemaVersion = CURRENT_SCHEMA_VERSION,
            id = "yunet-face-detection",
            displayName = "YuNet 人脸检测",
            version = "2023mar-fp32",
            task = VisionTask.FACE_DETECTION,
            format = "ONNX",
            runtime = "ONNX_RUNTIME_ANDROID",
            runtimeVersion = "1.22.0",
            assetPath = "models/yunet/face_detection_yunet_2023mar.onnx",
            sha256 = "8f2383e4dd3cfbb4553ea8718107fc0423210dc964f9f4280604804ed2552fa4",
            license = "MIT",
            sourceUrl = "https://github.com/opencv/opencv_zoo",
            sourceCommit = "47534e27c9851bb1128ccc0102f1145e27f23f98",
            installed = true,
            limitations = listOf(
                "仅检测人脸框与五点关键点，不识别身份，也不保存人脸特征",
                "模型置信度不是校准后的概率",
                "尚需完成独立测试集与三档真机验收",
            ),
        )

        private fun pending(task: VisionTask) = ModelDescriptor(
            schemaVersion = CURRENT_SCHEMA_VERSION,
            id = "pending-${task.name.lowercase()}",
            displayName = "待选型模型",
            version = "—",
            task = task,
            format = "—",
            runtime = "—",
            runtimeVersion = "—",
            assetPath = null,
            sha256 = "",
            license = "待审查",
            sourceUrl = "",
            sourceCommit = "",
            installed = false,
            limitations = listOf("权重、许可证与真机性能验证完成前不进入发布包"),
        )
    }
}
