package com.localvision.app.vision

data class PixelRect(val left: Float, val top: Float, val right: Float, val bottom: Float)

/** Maps model coordinates onto an image shown with ContentScale.Fit. */
object CoordinateMapper {
    fun toViewport(
        rect: NormalizedRect,
        imageWidth: Int,
        imageHeight: Int,
        viewportWidth: Float,
        viewportHeight: Float,
    ): PixelRect {
        require(imageWidth > 0 && imageHeight > 0 && viewportWidth > 0 && viewportHeight > 0)
        val scale = minOf(viewportWidth / imageWidth, viewportHeight / imageHeight)
        val shownWidth = imageWidth * scale
        val shownHeight = imageHeight * scale
        val offsetX = (viewportWidth - shownWidth) / 2f
        val offsetY = (viewportHeight - shownHeight) / 2f
        return PixelRect(
            left = offsetX + rect.left * shownWidth,
            top = offsetY + rect.top * shownHeight,
            right = offsetX + rect.right * shownWidth,
            bottom = offsetY + rect.bottom * shownHeight,
        )
    }
}

