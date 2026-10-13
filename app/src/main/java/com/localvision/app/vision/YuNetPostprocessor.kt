package com.localvision.app.vision

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

data class YuNetOutputs(
    val cls: Map<Int, FloatArray>,
    val obj: Map<Int, FloatArray>,
    val bbox: Map<Int, FloatArray>,
    val kps: Map<Int, FloatArray>,
)

object YuNetPostprocessor {
    private val strides = listOf(8, 16, 32)
    private val keypointNames = listOf("右眼", "左眼", "鼻尖", "右嘴角", "左嘴角")

    fun decode(
        outputs: YuNetOutputs,
        scoreThreshold: Float = 0.6f,
        nmsThreshold: Float = 0.3f,
        inputSize: Int = YuNetPreprocessor.INPUT_SIZE,
        transform: LetterboxTransform = LetterboxTransform.identity(inputSize),
    ): List<Detection> {
        val candidates = mutableListOf<Detection>()
        for (stride in strides) {
            val cols = inputSize / stride
            val rows = inputSize / stride
            val count = rows * cols
            val cls = requireOutput(outputs.cls, stride, count, 1, "cls")
            val obj = requireOutput(outputs.obj, stride, count, 1, "obj")
            val bbox = requireOutput(outputs.bbox, stride, count, 4, "bbox")
            val kps = requireOutput(outputs.kps, stride, count, 10, "kps")
            for (index in 0 until count) {
                val score = sqrt(cls[index].coerceIn(0f, 1f) * obj[index].coerceIn(0f, 1f))
                if (score < scoreThreshold) continue
                val row = index / cols
                val col = index % cols
                val cx = (col + bbox[index * 4]) * stride
                val cy = (row + bbox[index * 4 + 1]) * stride
                val width = exp(bbox[index * 4 + 2]) * stride
                val height = exp(bbox[index * 4 + 3]) * stride
                val left = transform.originalX(cx - width / 2f)
                val top = transform.originalY(cy - height / 2f)
                val right = transform.originalX(cx + width / 2f)
                val bottom = transform.originalY(cy + height / 2f)
                if (right <= left || bottom <= top) continue
                val keypoints = keypointNames.mapIndexed { pointIndex, name ->
                    Keypoint(
                        name = name,
                        point = NormalizedPoint(
                            transform.originalX((kps[index * 10 + pointIndex * 2] + col) * stride),
                            transform.originalY((kps[index * 10 + pointIndex * 2 + 1] + row) * stride),
                        ),
                        confidence = score,
                    )
                }
                candidates += Detection(NormalizedRect(left, top, right, bottom), "人脸", score, keypoints)
            }
        }
        return nms(candidates.sortedByDescending(Detection::confidence), nmsThreshold)
    }

    private fun requireOutput(
        outputs: Map<Int, FloatArray>,
        stride: Int,
        count: Int,
        fields: Int,
        name: String,
    ): FloatArray {
        val value = outputs[stride] ?: throw AnalysisFailure.InvalidOutput("缺少 ${name}_$stride")
        if (value.size != count * fields) {
            throw AnalysisFailure.InvalidOutput("${name}_$stride 长度应为 ${count * fields}，实际为 ${value.size}")
        }
        return value
    }

    internal fun nms(items: List<Detection>, threshold: Float): List<Detection> {
        val kept = mutableListOf<Detection>()
        for (item in items) {
            if (kept.none { iou(it.bounds, item.bounds) >= threshold }) kept += item
        }
        return kept
    }

    internal fun iou(a: NormalizedRect, b: NormalizedRect): Float {
        val width = max(0f, min(a.right, b.right) - max(a.left, b.left))
        val height = max(0f, min(a.bottom, b.bottom) - max(a.top, b.top))
        val intersection = width * height
        val areaA = (a.right - a.left) * (a.bottom - a.top)
        val areaB = (b.right - b.left) * (b.bottom - b.top)
        val union = areaA + areaB - intersection
        return if (union <= 0f) 0f else intersection / union
    }
}

