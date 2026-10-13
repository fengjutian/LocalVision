package com.localvision.app.vision

import android.graphics.Bitmap

data class PreparedImage(
    val tensor: FloatArray,
    val originalWidth: Int,
    val originalHeight: Int,
)

object YuNetPreprocessor {
    const val INPUT_SIZE = 640

    /** OpenCV blobFromImage default contract: NCHW, BGR, float values in [0, 255]. */
    fun prepare(source: Bitmap): PreparedImage {
        require(source.width > 0 && source.height > 0)
        val resized = Bitmap.createScaledBitmap(source, INPUT_SIZE, INPUT_SIZE, true)
        val pixels = IntArray(INPUT_SIZE * INPUT_SIZE)
        resized.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)
        if (resized !== source) resized.recycle()

        val plane = INPUT_SIZE * INPUT_SIZE
        val tensor = FloatArray(plane * 3)
        pixels.forEachIndexed { index, color ->
            tensor[index] = (color and 0xff).toFloat()
            tensor[plane + index] = (color shr 8 and 0xff).toFloat()
            tensor[plane * 2 + index] = (color shr 16 and 0xff).toFloat()
        }
        return PreparedImage(tensor, source.width, source.height)
    }
}
