package com.localvision.app.vision

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import androidx.core.graphics.createBitmap
import kotlin.math.min
import kotlin.math.roundToInt

data class LetterboxTransform(
    val inputSize: Int,
    val scaledWidth: Int,
    val scaledHeight: Int,
    val padX: Int,
    val padY: Int,
) {
    fun originalX(inputX: Float): Float = ((inputX - padX) / scaledWidth).coerceIn(0f, 1f)
    fun originalY(inputY: Float): Float = ((inputY - padY) / scaledHeight).coerceIn(0f, 1f)

    companion object {
        fun identity(inputSize: Int) = LetterboxTransform(inputSize, inputSize, inputSize, 0, 0)
    }
}

data class PreparedImage(
    val tensor: FloatArray,
    val originalWidth: Int,
    val originalHeight: Int,
    val transform: LetterboxTransform,
)

/** letterbox 之后原图内容落在输入正方形中的位置；padding 取整数除法，差 1 像素时留在上/左侧。 */
internal data class LetterboxGeometry(
    val scaledWidth: Int,
    val scaledHeight: Int,
    val padX: Int,
    val padY: Int,
)

/**
 * 等比缩放到 [inputSize] 并居中 padding 的纯计算，不依赖任何 Android 绘图 API。
 *
 * 刻意保持 OpenCV `blobFromImage` 的 letterbox 契约：缩放后至少 1 像素、不裁剪、padding 居中。
 */
internal fun calculateLetterbox(width: Int, height: Int, inputSize: Int): LetterboxGeometry {
    require(inputSize > 0) { "inputSize 必须为正数：$inputSize" }
    require(width > 0 && height > 0) { "图片尺寸必须为正数：${width}x$height" }
    val scale = min(inputSize.toFloat() / width, inputSize.toFloat() / height)
    val scaledWidth = (width * scale).roundToInt().coerceIn(1, inputSize)
    val scaledHeight = (height * scale).roundToInt().coerceIn(1, inputSize)
    return LetterboxGeometry(
        scaledWidth = scaledWidth,
        scaledHeight = scaledHeight,
        padX = (inputSize - scaledWidth) / 2,
        padY = (inputSize - scaledHeight) / 2,
    )
}

/**
 * ARGB 像素转 NCHW BGR FP32，取值保留在 `[0,255]`，不做任何归一化。
 *
 * 输出布局是三个平面依次排列（全部 B、然后全部 G、然后全部 R），不是逐像素交错的 BGR。
 */
internal fun argbToNchwBgr(pixels: IntArray, width: Int, height: Int): FloatArray {
    val plane = width * height
    require(pixels.size == plane) { "像素数量应为 $plane，实际为 ${pixels.size}" }
    val tensor = FloatArray(plane * 3)
    pixels.forEachIndexed { index, color ->
        tensor[index] = (color and 0xff).toFloat()
        tensor[plane + index] = (color shr 8 and 0xff).toFloat()
        tensor[plane * 2 + index] = (color shr 16 and 0xff).toFloat()
    }
    return tensor
}

object YuNetPreprocessor {
    const val INPUT_SIZE = 640

    /** OpenCV blobFromImage default contract: NCHW, BGR, float values in [0, 255]. */
    fun prepare(source: Bitmap): PreparedImage {
        val geometry = calculateLetterbox(source.width, source.height, INPUT_SIZE)
        val letterboxed = createBitmap(INPUT_SIZE, INPUT_SIZE, Bitmap.Config.ARGB_8888)
        val tensor = try {
            Canvas(letterboxed).apply {
                drawColor(Color.BLACK)
                drawBitmap(
                    source,
                    null,
                    Rect(
                        geometry.padX,
                        geometry.padY,
                        geometry.padX + geometry.scaledWidth,
                        geometry.padY + geometry.scaledHeight,
                    ),
                    Paint(Paint.FILTER_BITMAP_FLAG),
                )
            }
            val pixels = IntArray(INPUT_SIZE * INPUT_SIZE)
            letterboxed.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)
            argbToNchwBgr(pixels, INPUT_SIZE, INPUT_SIZE)
        } finally {
            // 临时 Bitmap 只属于本方法：getPixels 或像素转换抛异常时同样必须回收。
            // 调用方传入的 source Bitmap 由调用方负责，这里绝不回收。
            letterboxed.recycle()
        }
        return PreparedImage(
            tensor = tensor,
            originalWidth = source.width,
            originalHeight = source.height,
            transform = LetterboxTransform(
                inputSize = INPUT_SIZE,
                scaledWidth = geometry.scaledWidth,
                scaledHeight = geometry.scaledHeight,
                padX = geometry.padX,
                padY = geometry.padY,
            ),
        )
    }
}