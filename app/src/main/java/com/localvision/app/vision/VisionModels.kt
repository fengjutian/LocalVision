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

/**
 * 一次分析请求的参数快照。
 *
 * 阈值只在点击分析时冻结：UI 上的滑杆修改的是"下一次分析要用的参数"。因此成功结果必须
 * 自带快照，展示弱化和导出都读快照，绝不读当前滑杆值——否则用户会把"参数已改"误读成
 * "结果已重算"。
 */
data class AnalysisConfig(
    val scoreThreshold: Float = DEFAULT_SCORE_THRESHOLD,
    val nmsThreshold: Float = DEFAULT_NMS_THRESHOLD,
) {
    init {
        require(scoreThreshold in 0f..1f) { "scoreThreshold 必须在 0..1" }
        require(nmsThreshold in 0f..1f) { "nmsThreshold 必须在 0..1" }
    }

    companion object {
        const val DEFAULT_SCORE_THRESHOLD = 0.6f
        const val DEFAULT_NMS_THRESHOLD = 0.3f
    }
}

/** 成功结果连同产生它的模型与参数，供展示、导出和历史复用。 */
data class CompletedAnalysis(
    val result: VisionResult,
    val model: ModelDescriptor,
    val config: AnalysisConfig,
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

sealed class AnalysisFailure(message: String) : Exception(message) {
    class NoImage : AnalysisFailure("请先选择一张图片")
    class ModelUnavailable(task: VisionTask) :
        AnalysisFailure("${task.title}模型尚未安装。当前版本只提供安全的接入骨架，不会生成模拟识别结果。")
    class InvalidImage(reason: String = "无法读取该图片") : AnalysisFailure("图片读取失败：$reason")
    class InvalidModel(reason: String) : AnalysisFailure("模型校验失败：$reason")
    class InvalidOutput(reason: String) : AnalysisFailure("模型输出不兼容：$reason")
    class OutOfMemory : AnalysisFailure("设备内存不足，请关闭其他应用或选择尺寸更小的图片")

    /**
     * 稳定的 UI 错误分类：同一个异常永远映射到同一类，便于文案统一与自动化断言。
     * 未知异常一律落到 [FailureKind.UNKNOWN]，不会被误报成图片或模型问题。
     */
    val kind: FailureKind
        get() = when (this) {
            is NoImage, is InvalidImage -> FailureKind.IMAGE
            is ModelUnavailable, is InvalidModel, is InvalidOutput -> FailureKind.MODEL
            is OutOfMemory -> FailureKind.MEMORY
        }
}

/** 用户遇到错误后唯一合理的下一步，UI 据此给出可操作建议而不是只显示一句话。 */
enum class RecoveryAction {
    RETRY,
    RESELECT_IMAGE,
    USE_SMALLER_IMAGE,
    SELECT_INSTALLED_TASK,
    NONE,
}

enum class FailureKind(val label: String, val recovery: RecoveryAction) {
    IMAGE("图片不可用", RecoveryAction.RESELECT_IMAGE),
    MODEL("模型不可用", RecoveryAction.SELECT_INSTALLED_TASK),
    MEMORY("内存不足", RecoveryAction.USE_SMALLER_IMAGE),
    UNKNOWN("未知错误", RecoveryAction.RETRY),
}


