package com.localvision.app.ui

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Image
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

@Composable
fun LocalImagePreview(uri: Uri, modifier: Modifier = Modifier) {
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
            is PreviewState.Ready -> Image(
                bitmap = current.bitmap,
                contentDescription = "待分析图片预览",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

private sealed interface PreviewState {
    data object Loading : PreviewState
    data object Error : PreviewState
    data class Ready(val bitmap: androidx.compose.ui.graphics.ImageBitmap) : PreviewState
}

