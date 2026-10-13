package com.localvision.app

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
import com.localvision.app.vision.Detection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

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

    suspend fun saveJson(context: Context, target: Uri, detections: List<Detection>) = withContext(Dispatchers.IO) {
        val root = JSONObject().apply {
            put("schemaVersion", 1)
            put("model", "YuNet 2023mar FP32")
            put("coordinateSystem", "normalized-original-image")
            put("faces", JSONArray().apply {
                detections.forEach { face -> put(JSONObject().apply {
                    put("confidence", face.confidence.toDouble())
                    put("bounds", JSONObject().apply {
                        put("left", face.bounds.left.toDouble()); put("top", face.bounds.top.toDouble())
                        put("right", face.bounds.right.toDouble()); put("bottom", face.bounds.bottom.toDouble())
                    })
                    put("keypoints", JSONArray().apply {
                        face.keypoints.forEach { point -> put(JSONObject().apply {
                            put("name", point.name); put("x", point.point.x.toDouble()); put("y", point.point.y.toDouble())
                        }) }
                    })
                }) }
            })
        }
        requireNotNull(context.contentResolver.openOutputStream(target, "w"))
            .bufferedWriter().use { it.write(root.toString(2)) }
    }

    suspend fun shareAnnotated(context: Context, source: Uri, detections: List<Detection>) = withContext(Dispatchers.IO) {
        val directory = File(context.cacheDir, "shared_results").apply { mkdirs() }
        directory.listFiles()?.forEach { if (System.currentTimeMillis() - it.lastModified() > 24 * 60 * 60 * 1000) it.delete() }
        val file = File(directory, "localvision-${System.currentTimeMillis()}.jpg")
        val bitmap = annotatedBitmap(context, source, detections)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 94, it) }
        bitmap.recycle()
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/jpeg"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(Intent.createChooser(intent, "分享人脸检测结果").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun annotatedBitmap(context: Context, source: Uri, detections: List<Detection>): Bitmap {
        val decoded = AndroidImageDecoder.decode(context, source)
        val bitmap = decoded.copy(Bitmap.Config.ARGB_8888, true)
        decoded.recycle()
        val canvas = Canvas(bitmap)
        val stroke = (minOf(bitmap.width, bitmap.height) / 250f).coerceAtLeast(3f)
        val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(34, 211, 167); style = Paint.Style.STROKE; strokeWidth = stroke }
        val pointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(255, 200, 87); style = Paint.Style.FILL }
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = stroke * 7; setShadowLayer(3f, 1f, 1f, Color.BLACK) }
        detections.forEachIndexed { index, face ->
            val left = face.bounds.left * bitmap.width; val top = face.bounds.top * bitmap.height
            val right = face.bounds.right * bitmap.width; val bottom = face.bounds.bottom * bitmap.height
            canvas.drawRect(left, top, right, bottom, boxPaint)
            canvas.drawText("${index + 1} ${(face.confidence * 100).toInt()}%", left, (top - 8).coerceAtLeast(textPaint.textSize), textPaint)
            face.keypoints.forEach { canvas.drawCircle(it.point.x * bitmap.width, it.point.y * bitmap.height, stroke * 1.5f, pointPaint) }
        }
        return bitmap
    }

    private fun copySafeExif(context: Context, source: Uri, target: Uri) {
        val tags = listOf(
            ExifInterface.TAG_DATETIME, ExifInterface.TAG_MAKE, ExifInterface.TAG_MODEL,
            ExifInterface.TAG_F_NUMBER, ExifInterface.TAG_EXPOSURE_TIME, ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY,
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
}
