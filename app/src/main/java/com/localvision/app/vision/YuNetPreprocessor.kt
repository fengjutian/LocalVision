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

object YuNetPreprocessor {
    const val INPUT_SIZE = 640

    /** OpenCV blobFromImage default contract: NCHW, BGR, float values in [0, 255]. */
    fun prepare(source: Bitmap): PreparedImage {
        require(source.width > 0 && source.height > 0)
        val scale = min(INPUT_SIZE.toFloat() / source.width, INPUT_SIZE.toFloat() / source.height)
        val scaledWidth = (source.width * scale).roundToInt().coerceIn(1, INPUT_SIZE)
        val scaledHeight = (source.height * scale).roundToInt().coerceIn(1, INPUT_SIZE)
        val padX = (INPUT_SIZE - scaledWidth) / 2
        val padY = (INPUT_SIZE - scaledHeight) / 2
        val letterboxed = createBitmap(INPUT_SIZE, INPUT_SIZE, Bitmap.Config.ARGB_8888)
        Canvas(letterboxed).apply {
            drawColor(Color.BLACK)
            drawBitmap(
                source,
                null,
                Rect(padX, padY, padX + scaledWidth, padY + scaledHeight),
                Paint(Paint.FILTER_BITMAP_FLAG),
            )
        }
        val pixels = IntArray(INPUT_SIZE * INPUT_SIZE)
        letterboxed.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)
        letterboxed.recycle()

        val plane = INPUT_SIZE * INPUT_SIZE
        val tensor = FloatArray(plane * 3)
        pixels.forEachIndexed { index, color ->
            tensor[index] = (color and 0xff).toFloat()
            tensor[plane + index] = (color shr 8 and 0xff).toFloat()
            tensor[plane * 2 + index] = (color shr 16 and 0xff).toFloat()
        }
        return PreparedImage(
            tensor = tensor,
            originalWidth = source.width,
            originalHeight = source.height,
            transform = LetterboxTransform(INPUT_SIZE, scaledWidth, scaledHeight, padX, padY),
        )
    }
}
