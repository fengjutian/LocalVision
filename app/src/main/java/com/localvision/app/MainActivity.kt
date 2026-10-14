package com.localvision.app

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.localvision.app.ui.AppCanvas
import com.localvision.app.ui.Brand
import com.localvision.app.ui.ErrorPanel
import com.localvision.app.ui.ExportPanel
import com.localvision.app.ui.HistoryPanel
import com.localvision.app.ui.ImagePanel
import com.localvision.app.ui.Ink
import com.localvision.app.ui.ModelPanel
import com.localvision.app.ui.ResultPanel
import com.localvision.app.ui.RunningPanel
import com.localvision.app.ui.TaskPanel
import com.localvision.app.ui.ThresholdPanel
import com.localvision.app.vision.Detection
import com.localvision.app.vision.VisionResult
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { LocalVisionApp() }
    }
}

@Composable
private fun LocalVisionApp(vm: MainViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var preserveExif by remember { mutableStateOf(false) }
    var confirmShare by remember { mutableStateOf(false) }

    // 旧结果不会因为改参数而消失：展示与导出都用成功时的快照。
    val completed = state.completed
    val rawFaces = (completed?.result as? VisionResult.Detections)?.items.orEmpty()
    val sortedFaces = remember(rawFaces, state.sort) {
        when (state.sort) {
            ResultSort.CONFIDENCE -> rawFaces.sortedByDescending(Detection::confidence)
            ResultSort.POSITION -> rawFaces.sortedWith(compareBy({ it.bounds.top }, { it.bounds.left }))
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) {
        it?.let(vm::selectImage)
    }
    val saveImage = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("image/jpeg"),
    ) { target ->
        if (target != null && state.imageUri != null) scope.launch {
            runCatching {
                ResultExporter.saveAnnotated(context, state.imageUri!!, target, sortedFaces, preserveExif)
            }.onSuccess {
                Toast.makeText(context, R.string.export_image_done, Toast.LENGTH_SHORT).show()
            }.onFailure {
                Toast.makeText(context, context.getString(R.string.export_image_failed, it.message), Toast.LENGTH_LONG).show()
            }
        }
    }
    val saveJson = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { target ->
        val analysis = completed
        if (target != null && analysis != null) scope.launch {
            runCatching { ResultExporter.saveJson(context, target, analysis, sortedFaces) }
                .onSuccess {
                    Toast.makeText(context, R.string.export_json_done, Toast.LENGTH_SHORT).show()
                }.onFailure {
                    Toast.makeText(context, context.getString(R.string.export_json_failed, it.message), Toast.LENGTH_LONG).show()
                }
        }
    }

    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { vm.cancelIfRunning() }

    if (confirmShare) {
        AlertDialog(
            onDismissRequest = { confirmShare = false },
            title = { Text(stringResource(R.string.share_dialog_title)) },
            text = { Text(stringResource(R.string.share_dialog_text)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmShare = false
                    val source = state.imageUri ?: return@TextButton
                    scope.launch {
                        runCatching { ResultExporter.shareAnnotated(context, source, sortedFaces) }
                            .onFailure {
                                Toast.makeText(context, context.getString(R.string.share_failed, it.message), Toast.LENGTH_LONG).show()
                            }
                    }
                }) { Text(stringResource(R.string.share_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmShare = false }) { Text(stringResource(R.string.share_cancel)) }
            },
        )
    }

    MaterialTheme {
        Surface(color = AppCanvas, modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium, color = Ink)
                Text(stringResource(R.string.brand_tagline), color = Ink.copy(alpha = 0.7f))
                ImagePanel(state, sortedFaces, vm, onPick = { picker.launch("image/*") })
                TaskPanel(state, vm)
                ModelPanel(vm.modelsFor(state.task))
                ThresholdPanel(state, vm)

                when (val status = state.status) {
                    AnalysisStatus.Idle -> Unit
                    AnalysisStatus.Cancelled -> Text(stringResource(R.string.status_cancelled))
                    is AnalysisStatus.Running -> RunningPanel(status.stage)
                    is AnalysisStatus.Error -> ErrorPanel(
                        status = status,
                        onRetry = vm::analyze,
                        onReselect = { picker.launch("image/*") },
                    )

                    is AnalysisStatus.Success -> ResultPanel(
                        status = status,
                        faces = sortedFaces,
                        selected = state.selectedFace,
                        sort = state.sort,
                        threshold = state.displayConfig.scoreThreshold,
                        onSelect = vm::selectFace,
                        onSort = vm::setSort,
                    )
                }

                if (state.isResultStale) {
                    Text(
                        stringResource(R.string.stale_notice),
                        style = MaterialTheme.typography.bodyMedium,
                        color = androidx.compose.ui.graphics.Color(0xFF9B4D2B),
                    )
                }

                val activeModel = vm.activeModel(state.task)
                if (state.status is AnalysisStatus.Running) {
                    OutlinedButton(onClick = vm::cancel, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.action_cancel_analysis))
                    }
                } else {
                    Button(
                        onClick = vm::analyze,
                        enabled = state.imageUri != null && activeModel != null,
                        colors = ButtonDefaults.buttonColors(containerColor = Brand),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            stringResource(
                                if (state.isResultStale) R.string.action_reanalyze_with_new_config
                                else R.string.action_analyze,
                            ),
                        )
                    }
                }
                // 模型缺失时禁用按钮并在此说明原因；点击后再弹错误属于第二套路径，这里不重复。
                if (activeModel == null) {
                    Text(stringResource(R.string.model_missing_blocking_hint), style = MaterialTheme.typography.bodySmall)
                }

                if (completed != null) {
                    ExportPanel(
                        preserveExif = preserveExif,
                        onPreserveExif = { preserveExif = it },
                        onSaveImage = { saveImage.launch("LocalVision-${System.currentTimeMillis()}.jpg") },
                        onSaveJson = { saveJson.launch("LocalVision-${System.currentTimeMillis()}.json") },
                        onShare = { confirmShare = true },
                    )
                }

                HistoryPanel(state, vm)
                Text(
                    stringResource(R.string.footer_privacy),
                    style = MaterialTheme.typography.bodySmall,
                    color = Ink.copy(alpha = 0.7f),
                )
            }
        }
    }
}
