package com.localvision.app.vision

enum class VisionTask(val title: String, val description: String) {
    FACE_DETECTION("人脸检测", "检测人脸位置，不识别身份"),
    POSE_ESTIMATION("人体姿态", "估计人体关键点与骨架"),
    STOREFRONT_ANALYSIS("店铺分析", "检测门面、判断类别并读取招牌")
}

data class NormalizedPoint(val x: Float, val y: Float) {
    init {
        require(x in 0f..1f && y in 0f..1f) { "Coordinates must be normalized" }
    }
}

data class NormalizedRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    init {
        require(left in 0f..1f && top in 0f..1f && right in 0f..1f && bottom in 0f..1f)
        require(left <= right && top <= bottom)
    }
}

sealed interface VisionResult {
    val elapsedMs: Long

    data class Detections(
        val items: List<Detection>,
        override val elapsedMs: Long,
        val performance: PerformanceBreakdown = PerformanceBreakdown(),
    ) : VisionResult

    data class Poses(
        val people: List<Pose>,
        override val elapsedMs: Long,
    ) : VisionResult

    data class Storefronts(
        val stores: List<Storefront>,
        override val elapsedMs: Long,
    ) : VisionResult
}

data class PerformanceBreakdown(
    val decodeMs: Long = 0,
    val preprocessMs: Long = 0,
    val modelLoadMs: Long = 0,
    val inferenceMs: Long = 0,
    val postprocessMs: Long = 0,
)

data class AnalysisOptions(
    val scoreThreshold: Float = 0.6f,
    val nmsThreshold: Float = 0.3f,
)

enum class AnalysisStage(val title: String) {
    DECODING("正在读取图片"),
    PREPROCESSING("正在预处理"),
    LOADING_MODEL("首次加载模型"),
    INFERENCE("正在本地推理"),
    POSTPROCESSING("正在整理结果"),
}

data class Detection(
    val bounds: NormalizedRect,
    val label: String,
    val confidence: Float,
    val keypoints: List<Keypoint> = emptyList(),
)
data class Keypoint(val name: String, val point: NormalizedPoint, val confidence: Float)
data class Pose(val keypoints: List<Keypoint>, val confidence: Float)
data class Storefront(
    val bounds: NormalizedRect,
    val category: String?,
    val signText: String?,
    val confidence: Float,
)

data class ModelDescriptor(
    val id: String,
    val displayName: String,
    val version: String,
    val task: VisionTask,
    val format: String,
    val license: String,
    val installed: Boolean,
    val limitations: String,
)

sealed class AnalysisFailure(message: String) : Exception(message) {
    class NoImage : AnalysisFailure("请先选择一张图片")
    class ModelUnavailable(task: VisionTask) :
        AnalysisFailure("${task.title}模型尚未安装。当前版本只提供安全的接入骨架，不会生成模拟识别结果。")
    class InvalidImage(reason: String = "无法读取该图片") : AnalysisFailure("图片读取失败：$reason")
    class InvalidModel(reason: String) : AnalysisFailure("模型校验失败：$reason")
    class InvalidOutput(reason: String) : AnalysisFailure("模型输出不兼容：$reason")
    class OutOfMemory : AnalysisFailure("设备内存不足，请关闭其他应用或选择尺寸更小的图片")
}

