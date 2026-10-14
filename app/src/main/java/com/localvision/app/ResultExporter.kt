package com.localvision.app

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import com.localvision.app.vision.AndroidImageDecoder
import com.localvision.app.vision.CompletedAnalysis
import com.localvision.app.vision.Detection
import com.localvision.app.vision.PerformanceBreakdown
import com.localvision.app.vision.VisionResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

object ResultExporter {
    suspend fun saveAnnotated(
        context: Context,
        source: Uri,
        target: Uri,
        detections: List<Detection>,
        preserveExif: Boolean,
    ) = withContext(Dispatchers.IO) {
        val bitmap = annotatedBitmap(context, source, detections)
        context.contentResolver.openOutputStream(target, "w").use { output ->
            requireNotNull(output)
            bitmap.compress(Bitmap.CompressFormat.JPEG, 94, output)
        }
        bitmap.recycle()
        if (preserveExif) copySafeExif(context, source, target)
    }

    suspend fun saveJson(
        context: Context,
        target: Uri,
        completed: CompletedAnalysis,
        detections: List<Detection>,
    ) = withContext(Dispatchers.IO) {
        // 先在内存里把完整字节构建好，再打开目标流：构建期失败不会创建/污染目标文件。
        val payload = ResultJson.encode(completed, detections).toByteArray(StandardCharsets.UTF_8)
        requireNotNull(context.contentResolver.openOutputStream(target, "w")).use { it.write(payload) }
    }

    suspend fun shareAnnotated(
        context: Context,
        source: Uri,
        detections: List<Detection>,
    ) = withContext(Dispatchers.IO) {
        val directory = File(context.cacheDir, "shared_results").apply { mkdirs() }
        directory.listFiles()?.forEach {
            if (System.currentTimeMillis() - it.lastModified() > SHARE_FILE_TTL_MS) it.delete()
        }
        val file = File(directory, "localvision-${System.currentTimeMillis()}.jpg")
        val bitmap = annotatedBitmap(context, source, detections)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 94, it) }
        bitmap.recycle()
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/jpeg"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "分享人脸检测结果"))
    }

    private fun annotatedBitmap(
        context: Context,
        source: Uri,
        detections: List<Detection>,
    ): Bitmap {
        val decoded = AndroidImageDecoder.decode(context, source)
        val bitmap = decoded.copy(Bitmap.Config.ARGB_8888, true)
        decoded.recycle()
        val canvas = Canvas(bitmap)
        val stroke = (minOf(bitmap.width, bitmap.height) / 250f).coerceAtLeast(3f)
        val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(34, 211, 167)
            style = Paint.Style.STROKE
            strokeWidth = stroke
        }
        val pointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(255, 200, 87) }
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = stroke * 7
            setShadowLayer(3f, 1f, 1f, Color.BLACK)
        }
        detections.forEachIndexed { index, face ->
            val left = face.bounds.left * bitmap.width
            val top = face.bounds.top * bitmap.height
            val right = face.bounds.right * bitmap.width
            val bottom = face.bounds.bottom * bitmap.height
            canvas.drawRect(left, top, right, bottom, boxPaint)
            canvas.drawText(
                "${index + 1} ${(face.confidence * 100).toInt()}%",
                left,
                (top - 8).coerceAtLeast(textPaint.textSize),
                textPaint,
            )
            face.keypoints.forEach {
                canvas.drawCircle(it.point.x * bitmap.width, it.point.y * bitmap.height, stroke * 1.5f, pointPaint)
            }
        }
        return bitmap
    }

    /** 只复制不含 GPS、也不含方向信息的相机字段；用户默认关闭该选项。 */
    private fun copySafeExif(context: Context, source: Uri, target: Uri) {
        val tags = listOf(
            ExifInterface.TAG_DATETIME, ExifInterface.TAG_MAKE, ExifInterface.TAG_MODEL,
            ExifInterface.TAG_F_NUMBER, ExifInterface.TAG_EXPOSURE_TIME,
            ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY,
        )
        context.contentResolver.openInputStream(source).use { input ->
            if (input == null) return
            val from = ExifInterface(input)
            context.contentResolver.openFileDescriptor(target, "rw")?.use { descriptor ->
                val to = ExifInterface(descriptor.fileDescriptor)
                tags.forEach { tag -> from.getAttribute(tag)?.let { to.setAttribute(tag, it) } }
                to.saveAttributes()
            }
        }
    }

    private const val SHARE_FILE_TTL_MS = 24 * 60 * 60 * 1000L
}

/**
 * JSON 导出契约 v1。
 *
 * 刻意写成纯函数：schema 结构、坐标契约、模型标识、参数快照和隐私字段都能被单测钉死，
 * 不需要 Android 组件。导出内容不包含原图 URI、文件名、绝对路径、图片字节或设备标识。
 */
object ResultJson {
    const val SCHEMA_VERSION = 1
    const val COORDINATE_SYSTEM = "EXIF_CORRECTED_ORIGINAL_NORMALIZED_0_1"

    private val UTC_SECONDS: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC)

    fun encode(
        completed: CompletedAnalysis,
        detections: List<Detection>,
        createdAtMs: Long = System.currentTimeMillis(),
    ): String {
        val root = JSONObject()
        root.put("schemaVersion", SCHEMA_VERSION)
        root.put("createdAt", UTC_SECONDS.format(Instant.ofEpochMilli(createdAtMs)))
        root.put("task", completed.model.task.name)
        root.put("coordinateSystem", COORDINATE_SYSTEM)
        root.put("model", encodeModel(completed))
        root.put("config", encodeConfig(completed))
        root.put("performanceMs", encodePerformance(completed.result))
        root.put("detections", JSONArray().apply { detections.forEach { put(encodeDetection(it)) } })
        return root.toString(2)
    }

    private fun encodeModel(completed: CompletedAnalysis) = JSONObject().apply {
        val model = completed.model
        put("id", model.id)
        put("version", model.version)
        put("sha256", model.sha256)
        put("runtime", model.runtime)
        put("runtimeVersion", model.runtimeVersion)
        put("license", model.license)
    }

    private fun encodeConfig(completed: CompletedAnalysis) = JSONObject().apply {
        put("scoreThreshold", completed.config.scoreThreshold.toDouble().finite())
        put("nmsThreshold", completed.config.nmsThreshold.toDouble().finite())
    }

    private fun encodePerformance(result: VisionResult): JSONObject {
        val breakdown = (result as? VisionResult.Detections)?.performance ?: PerformanceBreakdown()
        return JSONObject().apply {
            put("decode", breakdown.decodeMs)
            put("preprocess", breakdown.preprocessMs)
            put("modelLoad", breakdown.modelLoadMs)
            put("inference", breakdown.inferenceMs)
            put("postprocess", breakdown.postprocessMs)
            put("total", result.elapsedMs)
        }
    }

    private fun encodeDetection(detection: Detection) = JSONObject().apply {
        put("label", detection.label)
        put("confidence", detection.confidence.toDouble().finite())
        put("bounds", JSONObject().apply {
            put("left", detection.bounds.left.toDouble().finite())
            put("top", detection.bounds.top.toDouble().finite())
            put("right", detection.bounds.right.toDouble().finite())
            put("bottom", detection.bounds.bottom.toDouble().finite())
        })
        put("keypoints", JSONArray().apply {
            detection.keypoints.forEach { point ->
                put(JSONObject().apply {
                    put("name", point.name)
                    put("x", point.point.x.toDouble().finite())
                    put("y", point.point.y.toDouble().finite())
                    put("confidence", point.confidence.toDouble().finite())
                })
            }
        })
    }

    /**
     * JSON number 不能是 NaN/Infinity——org.json 会直接抛异常。
     * 模型分数异常时降级为 0 并继续导出整份文件，而不是让用户丢掉这次结果。
     */
    private fun Double.finite(): Double = if (isFinite()) this else 0.0
}

/** 供 UI "复制完整哈希" 使用，与剪贴板实现解耦以便测试。 */
internal fun clipboardLabel(modelId: String, sha256: String): ClipData =
    ClipData.newPlainText("LocalVision $modelId SHA-256", sha256)
