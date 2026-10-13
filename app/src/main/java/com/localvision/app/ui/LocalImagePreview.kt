package com.localvision.app.ui

import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import com.localvision.app.vision.AndroidImageDecoder
import com.localvision.app.vision.CoordinateMapper
import com.localvision.app.vision.Detection
import com.localvision.app.vision.NormalizedRect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun LocalImagePreview(
    uri: Uri,
    detections: List<Detection> = emptyList(),
    showOverlay: Boolean = true,
    showBoxes: Boolean = true,
    showKeypoints: Boolean = true,
    showLabels: Boolean = true,
    selectedFace: Int? = null,
    threshold: Float = 0.6f,
    onFaceClick: (Int?) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var state by remember(uri) { mutableStateOf<PreviewState>(PreviewState.Loading) }
    var scale by remember(uri) { mutableFloatStateOf(1f) }
    var offset by remember(uri) { mutableStateOf(Offset.Zero) }

    LaunchedEffect(uri) {
        state = withContext(Dispatchers.IO) {
            runCatching { AndroidImageDecoder.decode(context, uri) }.fold(
                onSuccess = { PreviewState.Ready(it.asImageBitmap()) },
                onFailure = { PreviewState.Error(it.message ?: "图片预览失败") },
            )
        }
    }

    Box(
        modifier = modifier
            .pointerInput(uri) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(1f, 6f)
                    offset = if (scale == 1f) Offset.Zero else offset + pan
                }
            }
            .pointerInput(uri) {
                detectTapGestures(onDoubleTap = { scale = 1f; offset = Offset.Zero })
            },
        contentAlignment = Alignment.Center,
    ) {
        when (val current = state) {
            PreviewState.Loading -> CircularProgressIndicator()
            is PreviewState.Error -> Text(current.message)
            is PreviewState.Ready -> Box(
                Modifier.fillMaxSize().graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y
                },
            ) {
                Image(
                    bitmap = current.bitmap,
                    contentDescription = "待分析图片预览",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
                if (showOverlay) Canvas(
                    Modifier.fillMaxSize().pointerInput(detections, current.bitmap) {
                        detectTapGestures { tap ->
                            val hit = detections.indices.lastOrNull { index ->
                                val mapped = CoordinateMapper.toViewport(
                                    detections[index].bounds,
                                    current.bitmap.width,
                                    current.bitmap.height,
                                    size.width.toFloat(),
                                    size.height.toFloat(),
                                )
                                tap.x in mapped.left..mapped.right && tap.y in mapped.top..mapped.bottom
                            }
                            onFaceClick(hit)
                        }
                    },
                ) {
                    detections.forEachIndexed { index, detection ->
                        val rect = CoordinateMapper.toViewport(
                            detection.bounds, current.bitmap.width, current.bitmap.height, size.width, size.height,
                        )
                        val selected = selectedFace == index
                        val lowConfidence = detection.confidence < threshold + 0.1f
                        val color = when {
                            selected -> Color(0xFFFFA726)
                            lowConfidence -> Color(0x8822D3A7)
                            else -> Color(0xFF22D3A7)
                        }
                        if (showBoxes) drawRect(
                            color = color,
                            topLeft = Offset(rect.left, rect.top),
                            size = Size(rect.right - rect.left, rect.bottom - rect.top),
                            style = Stroke(width = if (selected) 6f else 3f),
                        )
                        if (showKeypoints) detection.keypoints.forEach { point ->
                            val mapped = CoordinateMapper.toViewport(
                                NormalizedRect(point.point.x, point.point.y, point.point.x, point.point.y),
                                current.bitmap.width, current.bitmap.height, size.width, size.height,
                            )
                            drawCircle(Color(0xFFFFC857), radius = if (selected) 7f else 5f, center = Offset(mapped.left, mapped.top))
                        }
                        if (showLabels) drawContext.canvas.nativeCanvas.drawText(
                            "${index + 1}  ${(detection.confidence * 100).toInt()}%",
                            rect.left,
                            (rect.top - 8f).coerceAtLeast(20f),
                            android.graphics.Paint().apply {
                                this.color = android.graphics.Color.WHITE
                                textSize = 28f
                                isFakeBoldText = true
                                setShadowLayer(3f, 1f, 1f, android.graphics.Color.BLACK)
                            },
                        )
                    }
                }
            }
        }
    }
}

private sealed interface PreviewState {
    data object Loading : PreviewState
    data class Error(val message: String) : PreviewState
    data class Ready(val bitmap: androidx.compose.ui.graphics.ImageBitmap) : PreviewState
}
