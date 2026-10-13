package com.localvision.app

import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.localvision.app.ui.LocalImagePreview
import com.localvision.app.vision.Detection
import com.localvision.app.vision.ModelDescriptor
import com.localvision.app.vision.VisionResult
import com.localvision.app.vision.VisionTask
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { LocalVisionApp() }
    }
}

private val Ink = Color(0xFF182230)
private val Brand = Color(0xFF176B5B)
private val AppCanvas = Color(0xFFF5F7F8)

@Composable
fun LocalVisionApp(vm: MainViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var preserveExif by remember { mutableStateOf(false) }
    var confirmShare by remember { mutableStateOf(false) }
    val result = (state.status as? AnalysisStatus.Success)?.result as? VisionResult.Detections
    val sortedFaces = remember(result, state.sort) {
        when (state.sort) {
            ResultSort.CONFIDENCE -> result?.items?.sortedByDescending(Detection::confidence)
            ResultSort.POSITION -> result?.items?.sortedWith(compareBy({ it.bounds.top }, { it.bounds.left }))
        }.orEmpty()
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { it?.let(vm::selectImage) }
    val saveImage = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/jpeg")) { target ->
        if (target != null && state.imageUri != null) scope.launch {
            runCatching { ResultExporter.saveAnnotated(context, state.imageUri!!, target, sortedFaces, preserveExif) }
                .onSuccess { Toast.makeText(context, "结果图片已保存", Toast.LENGTH_SHORT).show() }
                .onFailure { Toast.makeText(context, "保存失败：${it.message}", Toast.LENGTH_LONG).show() }
        }
    }
    val saveJson = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { target ->
        if (target != null) scope.launch {
            runCatching { ResultExporter.saveJson(context, target, sortedFaces) }
                .onSuccess { Toast.makeText(context, "JSON 已导出", Toast.LENGTH_SHORT).show() }
                .onFailure { Toast.makeText(context, "导出失败：${it.message}", Toast.LENGTH_LONG).show() }
        }
    }

    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { vm.cancelIfRunning() }

    if (confirmShare) AlertDialog(
        onDismissRequest = { confirmShare = false },
        title = { Text("分享检测结果？") },
        text = { Text("分享图片可能包含可识别的人脸。LocalVision 不会在本地保留原图副本，分享目标应用可能有自己的数据政策。") },
        confirmButton = { TextButton(onClick = {
            confirmShare = false
            val source = state.imageUri ?: return@TextButton
            scope.launch { runCatching { ResultExporter.shareAnnotated(context, source, sortedFaces) }
                .onFailure { Toast.makeText(context, "分享失败：${it.message}", Toast.LENGTH_LONG).show() } }
        }) { Text("继续分享") } },
        dismissButton = { TextButton(onClick = { confirmShare = false }) { Text("取消") } },
    )

    MaterialTheme {
        Surface(color = AppCanvas, modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text("LocalVision", style = MaterialTheme.typography.headlineMedium, color = Ink)
                Text("图片留在设备上 · 模型能力透明可查", color = Color(0xFF536170))
                ImagePanel(state, sortedFaces, vm, onPick = { picker.launch("image/*") })
                TaskPanel(state, vm)
                ModelCard(vm.modelsFor(state.task).first())
                ConfidencePanel(state, vm)
                StatusCard(state.status, sortedFaces, state.selectedFace, state.sort, vm::selectFace, vm::setSort)
                if (state.status is AnalysisStatus.Running) {
                    OutlinedButton(onClick = vm::cancel, modifier = Modifier.fillMaxWidth()) { Text("取消") }
                } else {
                    Button(
                        onClick = vm::analyze,
                        enabled = state.imageUri != null,
                        colors = ButtonDefaults.buttonColors(containerColor = Brand),
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("开始本地分析") }
                }
                if (result != null) ExportPanel(
                    preserveExif = preserveExif,
                    onPreserveExif = { preserveExif = it },
                    onSaveImage = { saveImage.launch("LocalVision-${System.currentTimeMillis()}.jpg") },
                    onSaveJson = { saveJson.launch("LocalVision-${System.currentTimeMillis()}.json") },
                    onShare = { confirmShare = true },
                )
                HistoryPanel(state, vm)
                Text(
                    "模型置信度是模型评分，不等于事实概率。应用默认不上传图片、不保存原图副本或人脸特征。",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF536170),
                )
            }
        }
    }
}

@Composable
private fun ImagePanel(state: MainUiState, faces: List<Detection>, vm: MainViewModel, onPick: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(
                Modifier.fillMaxWidth().height(320.dp).background(Color(0xFFE6EEEC), RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center,
            ) {
                val uri = state.imageUri
                if (uri == null) Text("尚未选择图片", color = Ink) else LocalImagePreview(
                    uri = uri,
                    detections = faces,
                    showOverlay = state.showOverlay,
                    showBoxes = state.showBoxes,
                    showKeypoints = state.showKeypoints,
                    showLabels = state.showLabels,
                    selectedFace = state.selectedFace,
                    threshold = state.scoreThreshold,
                    onFaceClick = vm::selectFace,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            Text("双指缩放/平移，双击复位；点击人脸框可高亮", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(state.showOverlay, vm::toggleOverlay, { Text(if (state.showOverlay) "查看结果" else "查看原图") })
                FilterChip(state.showBoxes, vm::toggleBoxes, { Text("框") })
                FilterChip(state.showKeypoints, vm::toggleKeypoints, { Text("关键点") })
                FilterChip(state.showLabels, vm::toggleLabels, { Text("标签") })
            }
            OutlinedButton(onClick = onPick, modifier = Modifier.fillMaxWidth()) {
                Text(if (state.imageUri == null) "从系统选择图片" else "更换图片")
            }
        }
    }
}

@Composable
private fun TaskPanel(state: MainUiState, vm: MainViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("选择分析任务", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            VisionTask.entries.forEach { task ->
                FilterChip(state.task == task, { vm.selectTask(task) }, { Text(task.title) })
            }
        }
        Text(state.task.description, color = Color(0xFF536170))
    }
}

@Composable
private fun ConfidencePanel(state: MainUiState, vm: MainViewModel) {
    Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) {
        Text("结果阈值", style = MaterialTheme.typography.titleSmall)
        Text("置信度 ≥ ${"%.2f".format(state.scoreThreshold)} · NMS ${"%.2f".format(state.nmsThreshold)}")
        Slider(
            value = state.scoreThreshold,
            onValueChange = vm::setScoreThreshold,
            valueRange = 0.3f..0.9f,
            steps = 11,
        )
        Text("阈值修改将在下一次分析时生效；接近阈值的结果会弱化显示。", style = MaterialTheme.typography.bodySmall)
    } }
}

@Composable
private fun ModelCard(model: ModelDescriptor) {
    Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text("模型状态", style = MaterialTheme.typography.titleSmall)
        Text(if (model.installed) model.displayName else "尚未安装真实模型", color = if (model.installed) Brand else Color(0xFF9B4D2B))
        Text("格式 ${model.format} · 版本 ${model.version} · 许可 ${model.license}", style = MaterialTheme.typography.bodySmall)
        Text(model.limitations, style = MaterialTheme.typography.bodySmall, color = Color(0xFF536170))
    } }
}

@Composable
private fun StatusCard(
    status: AnalysisStatus,
    faces: List<Detection>,
    selected: Int?,
    sort: ResultSort,
    onSelect: (Int?) -> Unit,
    onSort: (ResultSort) -> Unit,
) {
    when (status) {
        AnalysisStatus.Idle -> Unit
        AnalysisStatus.Cancelled -> Text("分析已取消，未保存本次结果", color = Color(0xFF536170))
        is AnalysisStatus.Running -> Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(22.dp), color = Brand); Spacer(Modifier.size(10.dp)); Text(status.stage.title)
        }
        is AnalysisStatus.Error -> Card(Modifier.fillMaxWidth()) {
            Text("${status.kind.name} · ${status.message}", Modifier.padding(16.dp), color = Color(0xFF9B2C2C))
        }
        is AnalysisStatus.Success -> Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val detectionResult = status.result as? VisionResult.Detections
            Text(if (faces.isEmpty()) "分析成功：未检测到人脸" else "分析完成，检测到 ${faces.size} 张人脸")
            Text("总耗时 ${status.result.elapsedMs} ms", style = MaterialTheme.typography.bodySmall)
            detectionResult?.performance?.let { p -> Text(
                "解码 ${p.decodeMs} · 预处理 ${p.preprocessMs} · 模型加载 ${p.modelLoadMs} · 推理 ${p.inferenceMs} · 后处理 ${p.postprocessMs} ms",
                style = MaterialTheme.typography.bodySmall,
            ) }
            if (faces.size > 1) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(sort == ResultSort.CONFIDENCE, { onSort(ResultSort.CONFIDENCE) }, { Text("按置信度") })
                FilterChip(sort == ResultSort.POSITION, { onSort(ResultSort.POSITION) }, { Text("按位置") })
            }
            faces.take(10).forEachIndexed { index, face -> Text(
                "人脸 ${index + 1} · 模型置信度 ${(face.confidence * 100).toInt()}%",
                modifier = Modifier.fillMaxWidth().clickable { onSelect(if (selected == index) null else index) }
                    .background(if (selected == index) Color(0x22FFA726) else Color.Transparent).padding(4.dp),
                style = MaterialTheme.typography.bodySmall,
            ) }
        } }
    }
}

@Composable
private fun ExportPanel(
    preserveExif: Boolean,
    onPreserveExif: (Boolean) -> Unit,
    onSaveImage: () -> Unit,
    onSaveJson: () -> Unit,
    onShare: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("保存与分享", style = MaterialTheme.typography.titleSmall)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(preserveExif, onPreserveExif)
            Text("保留非位置类 EXIF（默认关闭）")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onSaveImage) { Text("保存标注图") }
            OutlinedButton(onClick = onSaveJson) { Text("导出 JSON") }
            OutlinedButton(onClick = onShare) { Text("分享") }
        }
        Text("导出不会保存人脸裁剪或特征向量；即使勾选 EXIF，也不会复制 GPS 信息。", style = MaterialTheme.typography.bodySmall)
    } }
}

@Composable
private fun HistoryPanel(state: MainUiState, vm: MainViewModel) {
    Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Column { Text("检测历史", style = MaterialTheme.typography.titleSmall); Text("仅保存元数据，30 天自动过期", style = MaterialTheme.typography.bodySmall) }
            Switch(state.historyEnabled, vm::setHistoryEnabled)
        }
        if (!state.historyEnabled) Text("默认关闭，不记录图片、URI、缩略图或人脸特征。", style = MaterialTheme.typography.bodySmall)
        else {
            if (state.history.isEmpty()) Text("暂无历史", style = MaterialTheme.typography.bodySmall)
            state.history.take(10).forEach { entry -> Text(
                "${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(entry.timestampMs))} · ${entry.faceCount} 张 · ${entry.elapsedMs} ms",
                style = MaterialTheme.typography.bodySmall,
            ) }
            if (state.history.isNotEmpty()) TextButton(onClick = vm::clearHistory) { Text("清除全部历史") }
        }
    } }
}
