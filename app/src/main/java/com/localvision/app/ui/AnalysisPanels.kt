package com.localvision.app.ui

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.localvision.app.AnalysisStatus
import com.localvision.app.MainUiState
import com.localvision.app.MainViewModel
import com.localvision.app.R
import com.localvision.app.ResultSort
import com.localvision.app.clipboardLabel
import com.localvision.app.vision.Detection
import com.localvision.app.vision.ModelDescriptor
import com.localvision.app.vision.RecoveryAction
import java.util.Date
import java.util.Locale

internal val Ink = Color(0xFF182230)
internal val Brand = Color(0xFF176B5B)
internal val AppCanvas = Color(0xFFF5F7F8)
private val Muted = Color(0xFF536170)
private val Warn = Color(0xFF9B4D2B)
private val Danger = Color(0xFF9B2C2C)

/** 结果列表默认只展示前若干条，其余通过“查看全部”展开，不再静默截断。 */
internal const val COLLAPSED_RESULT_COUNT = 10

@Composable
internal fun ImagePanel(
    state: MainUiState,
    faces: List<Detection>,
    vm: MainViewModel,
    onPick: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(320.dp)
                    .background(Color(0xFFE6EEEC), RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center,
            ) {
                val uri = state.imageUri
                if (uri == null) {
                    Text(stringResource(R.string.label_placeholder_no_image), color = Ink)
                } else {
                    LocalImagePreview(
                        uri = uri,
                        modifier = Modifier.fillMaxSize(),
                        detections = faces,
                        showOverlay = state.showOverlay,
                        showBoxes = state.showBoxes,
                        showKeypoints = state.showKeypoints,
                        showLabels = state.showLabels,
                        selectedFace = state.selectedFace,
                        threshold = state.displayConfig.scoreThreshold,
                        nearThresholdLabel = stringResource(R.string.result_face_near_threshold),
                        onFaceClick = vm::selectFace,
                    )
                }
            }
            Text(
                stringResource(R.string.hint_preview_gestures),
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(state.showOverlay, vm::toggleOverlay, {
                    Text(
                        stringResource(
                            if (state.showOverlay) R.string.toggle_overlay_results
                            else R.string.toggle_overlay_original,
                        ),
                    )
                })
                FilterChip(state.showBoxes, vm::toggleBoxes, {
                    Text(stringResource(R.string.toggle_boxes))
                })
                FilterChip(state.showKeypoints, vm::toggleKeypoints, {
                    Text(stringResource(R.string.toggle_keypoints))
                })
                FilterChip(state.showLabels, vm::toggleLabels, {
                    Text(stringResource(R.string.toggle_labels))
                })
            }
            OutlinedButton(onClick = onPick, modifier = Modifier.fillMaxWidth()) {
                Text(
                    stringResource(
                        if (state.imageUri == null) R.string.action_pick_image
                        else R.string.action_replace_image,
                    ),
                )
            }
        }
    }
}

@Composable
internal fun TaskPanel(state: MainUiState, vm: MainViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.title_task), style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            com.localvision.app.vision.VisionTask.entries.forEach { task ->
                FilterChip(state.task == task, { vm.selectTask(task) }, { Text(task.title) })
            }
        }
        Text(state.task.description, color = Muted)
    }
}

/**
 * 模型卡片。[models] 可能为空（注册表被裁剪或初始化异常），此时展示“模型信息不可用”
 * 而不是调用 `.first()` 让整页在组合阶段崩溃。
 */
@Composable
internal fun ModelPanel(models: List<ModelDescriptor>) {
    val model = models.firstOrNull()
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(stringResource(R.string.title_model_status), style = MaterialTheme.typography.titleSmall)
            if (model == null) {
                Text(stringResource(R.string.model_info_unavailable), color = Warn)
            } else {
                ModelCard(model)
            }
        }
    }
}

@Composable
private fun ModelCard(model: ModelDescriptor) {
    var expanded by remember(model.id, model.version) { mutableStateOf(false) }
    var copied by remember(model.id, model.version) { mutableStateOf(false) }
    val context = LocalContext.current
    Text(
        model.displayName,
        color = if (model.installed) Brand else Warn,
    )
    Text(
        stringResource(R.string.model_summary_format, model.version, model.runtime, model.runtimeVersion),
        style = MaterialTheme.typography.bodySmall,
    )
    Text(
        stringResource(R.string.model_license_format, model.license),
        style = MaterialTheme.typography.bodySmall,
    )
    if (model.sha256.isNotEmpty()) {
        Text(
            stringResource(R.string.model_sha_short_format, model.shortSha256),
            style = MaterialTheme.typography.bodySmall,
        )
    }
    model.limitations.forEach { Text("· $it", style = MaterialTheme.typography.bodySmall, color = Muted) }
    TextButton(onClick = { expanded = !expanded }) {
        Text(
            stringResource(
                if (expanded) R.string.model_hide_details else R.string.model_show_details,
            ),
        )
    }
    if (expanded) {
        Text(
            stringResource(R.string.model_id_format, model.id),
            style = MaterialTheme.typography.bodySmall,
        )
        Text(stringResource(R.string.model_sha_full_format, model.sha256), style = MaterialTheme.typography.bodySmall)
        model.assetPath?.let {
            Text(stringResource(R.string.model_asset_format, it), style = MaterialTheme.typography.bodySmall)
        }
        if (model.sourceUrl.isNotEmpty()) {
            Text(
                stringResource(R.string.model_source_format, model.sourceUrl, model.sourceCommit),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        OutlinedButton(onClick = {
            copySha256(context, model)
            copied = true
        }) {
            Text(stringResource(if (copied) R.string.model_copied else R.string.model_copy_sha))
        }
    }
}

private fun copySha256(context: Context, model: ModelDescriptor) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    clipboard.setPrimaryClip(clipboardLabel(model.id, model.sha256))
}

@Composable
internal fun ThresholdPanel(state: MainUiState, vm: MainViewModel) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.title_threshold), style = MaterialTheme.typography.titleSmall)
            Text(
                stringResource(
                    R.string.threshold_value_format,
                    formatScore(state.config.scoreThreshold),
                    formatScore(state.config.nmsThreshold),
                ),
                style = MaterialTheme.typography.bodySmall,
            )
            Slider(
                value = state.config.scoreThreshold,
                onValueChange = vm::setScoreThreshold,
                valueRange = 0.3f..0.9f,
                steps = 11,
            )
            Text(stringResource(R.string.threshold_hint), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
internal fun RunningPanel(stage: com.localvision.app.vision.AnalysisStage) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(22.dp), color = Brand)
        Spacer(Modifier.size(10.dp))
        Text(stage.title)
    }
}

@Composable
internal fun ErrorPanel(
    status: AnalysisStatus.Error,
    onRetry: () -> Unit,
    onReselect: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(R.string.error_title_format, status.kind.label, status.message),
                color = Danger,
            )
            Text(status.kind.recovery.hintText(), style = MaterialTheme.typography.bodySmall)
            when (status.kind.recovery) {
                RecoveryAction.RETRY -> OutlinedButton(onClick = onRetry) {
                    Text(stringResource(R.string.action_retry))
                }

                RecoveryAction.RESELECT_IMAGE -> OutlinedButton(onClick = onReselect) {
                    Text(stringResource(R.string.action_reselect_image))
                }

                else -> Unit
            }
        }
    }
}

@Composable
private fun RecoveryAction.hintText(): String = stringResource(
    when (this) {
        RecoveryAction.RETRY -> R.string.recovery_retry
        RecoveryAction.RESELECT_IMAGE -> R.string.recovery_reselect
        RecoveryAction.USE_SMALLER_IMAGE -> R.string.recovery_smaller_image
        RecoveryAction.SELECT_INSTALLED_TASK -> R.string.recovery_select_task
        RecoveryAction.NONE -> R.string.recovery_none
    },
)

@Composable
internal fun ResultPanel(
    status: AnalysisStatus.Success,
    faces: List<Detection>,
    selected: Int?,
    sort: ResultSort,
    threshold: Float,
    onSelect: (Int?) -> Unit,
    onSort: (ResultSort) -> Unit,
) {
    val performance = (status.analysis.result as? com.localvision.app.vision.VisionResult.Detections)?.performance
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                if (faces.isEmpty()) stringResource(R.string.result_empty)
                else stringResource(R.string.result_count, faces.size),
            )
            Text(
                stringResource(R.string.result_total_ms, status.analysis.result.elapsedMs),
                style = MaterialTheme.typography.bodySmall,
            )
            if (performance != null) {
                Text(
                    stringResource(
                        R.string.result_performance_ms,
                        performance.decodeMs.toLong(),
                        performance.preprocessMs.toLong(),
                        performance.modelLoadMs.toLong(),
                        performance.inferenceMs.toLong(),
                        performance.postprocessMs.toLong(),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (faces.size > 1) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(sort == ResultSort.CONFIDENCE, { onSort(ResultSort.CONFIDENCE) }, {
                        Text(stringResource(R.string.result_sort_confidence))
                    })
                    FilterChip(sort == ResultSort.POSITION, { onSort(ResultSort.POSITION) }, {
                        Text(stringResource(R.string.result_sort_position))
                    })
                }
            }
            ResultList(faces, selected, threshold, onSelect)
        }
    }
}

@Composable
private fun ResultList(
    faces: List<Detection>,
    selected: Int?,
    threshold: Float,
    onSelect: (Int?) -> Unit,
) {
    var showAll by remember(faces) { mutableStateOf(false) }
    val visible = if (showAll || faces.size <= COLLAPSED_RESULT_COUNT) faces
    else faces.take(COLLAPSED_RESULT_COUNT)
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        visible.forEachIndexed { index, face ->
            val nearThreshold = face.confidence < threshold
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { onSelect(if (selected == index) null else index) }
                    .background(if (selected == index) Color(0x22FFA726) else Color.Transparent)
                    .padding(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(
                        R.string.result_face_row,
                        index + 1,
                        (face.confidence * 100).toInt(),
                    ),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                )
                // 接近阈值不能只靠颜色表达：叠加层会变淡，这里同时给出文字标记。
                if (nearThreshold) {
                    Text(
                        stringResource(R.string.result_face_near_threshold),
                        style = MaterialTheme.typography.bodySmall,
                        color = Muted,
                    )
                }
            }
        }
        if (faces.size > COLLAPSED_RESULT_COUNT) {
            if (showAll) {
                TextButton(onClick = { showAll = false }) {
                    Text(stringResource(R.string.result_collapse))
                }
            } else {
                TextButton(onClick = { showAll = true }) {
                    Text(stringResource(R.string.result_show_all, faces.size))
                }
            }
        }
    }
}

@Composable
internal fun ExportPanel(
    preserveExif: Boolean,
    onPreserveExif: (Boolean) -> Unit,
    onSaveImage: () -> Unit,
    onSaveJson: () -> Unit,
    onShare: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.title_export), style = MaterialTheme.typography.titleSmall)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(preserveExif, onPreserveExif)
                Text(stringResource(R.string.export_preserve_exif))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onSaveImage) { Text(stringResource(R.string.export_save_image)) }
                OutlinedButton(onClick = onSaveJson) { Text(stringResource(R.string.export_json)) }
                OutlinedButton(onClick = onShare) { Text(stringResource(R.string.export_share)) }
            }
            Text(stringResource(R.string.export_privacy_note), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
internal fun HistoryPanel(state: MainUiState, vm: MainViewModel) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column {
                    Text(stringResource(R.string.title_history), style = MaterialTheme.typography.titleSmall)
                    Text(stringResource(R.string.history_hint), style = MaterialTheme.typography.bodySmall)
                }
                Switch(state.historyEnabled, vm::setHistoryEnabled)
            }
            if (!state.historyEnabled) {
                Text(stringResource(R.string.history_off), style = MaterialTheme.typography.bodySmall)
            } else {
                if (state.history.isEmpty()) {
                    Text(stringResource(R.string.history_empty), style = MaterialTheme.typography.bodySmall)
                }
                state.history.take(COLLAPSED_RESULT_COUNT).forEach { entry ->
                    Text(
                        stringResource(
                            R.string.history_entry,
                            DATE_FORMAT.format(Date(entry.timestampMs)),
                            entry.faceCount,
                            entry.elapsedMs.toInt(),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (state.history.isNotEmpty()) {
                    TextButton(onClick = vm::clearHistory) {
                        Text(stringResource(R.string.history_clear))
                    }
                }
            }
        }
    }
}

private val DATE_FORMAT = java.text.DateFormat.getDateTimeInstance(
    java.text.DateFormat.SHORT,
    java.text.DateFormat.SHORT,
)

/** 展示用小数必须绑定当前 Locale；JSON 侧另走 Locale-independent 数值。 */
private fun formatScore(value: Float): String = String.format(Locale.getDefault(), "%.2f", value)
