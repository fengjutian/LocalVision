package com.localvision.app.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.exifinterface.media.ExifInterface
import kotlin.math.max

/** Decodes HEIF/WebP/PNG/JPEG through the platform and bounds peak memory for large images. */
object AndroidImageDecoder {
    private const val MAX_DECODE_EDGE = 4096

    fun decode(context: Context, uri: Uri): Bitmap {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) decodeModern(context, uri)
            else decodeLegacy(context, uri)
        } catch (error: AnalysisFailure) {
            throw error
        } catch (error: SecurityException) {
            throw AnalysisFailure.InvalidImage("没有读取权限或授权已失效")
        } catch (error: OutOfMemoryError) {
            throw AnalysisFailure.OutOfMemory()
        } catch (error: Exception) {
            throw AnalysisFailure.InvalidImage(error.message ?: "格式损坏或设备不支持该编码")
        }
    }

    /**
     * 平台解码路径。调用方已做 `SDK_INT >= P` 判断，这里用注解把约束显式化；
     * 否则 lint 无法跨函数识别守卫，会在 minSdk 26 上误报 NewApi。
     */
    @RequiresApi(Build.VERSION_CODES.P)
    private fun decodeModern(context: Context, uri: Uri): Bitmap {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.isMutableRequired = false
            sampleSize(max(info.size.width, info.size.height)).takeIf { it > 1 }?.let(decoder::setTargetSampleSize)
        }
    }

    private fun decodeLegacy(context: Context, uri: Uri): Bitmap {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "无法打开图片" }
            BitmapFactory.decodeStream(input, null, bounds)
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw AnalysisFailure.InvalidImage("无法识别图片尺寸或格式")
        }
        val orientation = resolver.openInputStream(uri).use { input ->
            requireNotNull(input)
            ExifInterface(input).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        }
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(max(bounds.outWidth, bounds.outHeight))
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bitmap = resolver.openInputStream(uri).use { input ->
            requireNotNull(BitmapFactory.decodeStream(input, null, options)) { "图片数据损坏或格式不受支持" }
        }
        return applyExif(bitmap, orientation)
    }

    internal fun sampleSize(longestEdge: Int): Int {
        var sample = 1
        while (longestEdge / (sample * 2) >= MAX_DECODE_EDGE) sample *= 2
        return sample
    }

    internal fun exifMatrix(orientation: Int): Matrix = Matrix().apply {
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> postScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> postRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { postScale(-1f, 1f); postRotate(270f) }
            ExifInterface.ORIENTATION_ROTATE_90 -> postRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> { postScale(-1f, 1f); postRotate(90f) }
            ExifInterface.ORIENTATION_ROTATE_270 -> postRotate(270f)
        }
    }

    private fun applyExif(bitmap: Bitmap, orientation: Int): Bitmap {
        if (orientation == ExifInterface.ORIENTATION_NORMAL || orientation == ExifInterface.ORIENTATION_UNDEFINED) return bitmap
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, exifMatrix(orientation), true)
            .also { if (it !== bitmap) bitmap.recycle() }
    }
}
