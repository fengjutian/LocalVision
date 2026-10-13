package com.localvision.app.ui

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import com.localvision.app.vision.Detection
import com.localvision.app.vision.CoordinateMapper

@Composable
fun LocalImagePreview(
    uri: Uri,
    detections: List<Detection> = emptyList(),
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var state by remember(uri) { mutableStateOf<PreviewState>(PreviewState.Loading) }

    LaunchedEffect(uri) {
        state = withContext(Dispatchers.IO) {
            runCatching {
                context.contentResolver.openInputStream(uri).use { input ->
                    requireNotNull(input) { "Image cannot be opened" }
                    requireNotNull(BitmapFactory.decodeStream(input)) { "Image cannot be decoded" }
                }
            }.fold(
                onSuccess = { PreviewState.Ready(it.asImageBitmap()) },
                onFailure = { PreviewState.Error },
            )
        }
    }

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        when (val current = state) {
            PreviewState.Loading -> CircularProgressIndicator()
            PreviewState.Error -> Text("图片预览失败")
            is PreviewState.Ready -> {
                Image(
                    bitmap = current.bitmap,
                    contentDescription = "待分析图片预览",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
                Canvas(Modifier.fillMaxSize()) {
                    detections.forEach { detection ->
                        val rect = CoordinateMapper.toViewport(
                            detection.bounds,
                            current.bitmap.width,
                            current.bitmap.height,
                            size.width,
                            size.height,
                        )
                        drawRect(
                            color = Color(0xFF22D3A7),
                            topLeft = Offset(rect.left, rect.top),
                            size = Size(rect.right - rect.left, rect.bottom - rect.top),
                            style = Stroke(width = 3f),
                        )
                        detection.keypoints.forEach { point ->
                            val mapped = CoordinateMapper.toViewport(
                                com.localvision.app.vision.NormalizedRect(
                                    point.point.x, point.point.y, point.point.x, point.point.y,
                                ),
                                current.bitmap.width,
                                current.bitmap.height,
                                size.width,
                                size.height,
                            )
                            drawCircle(Color(0xFFFFC857), radius = 5f, center = Offset(mapped.left, mapped.top))
                        }
                    }
                }
            }
        }
    }
}

private sealed interface PreviewState {
    data object Loading : PreviewState
    data object Error : PreviewState
    data class Ready(val bitmap: androidx.compose.ui.graphics.ImageBitmap) : PreviewState
}

