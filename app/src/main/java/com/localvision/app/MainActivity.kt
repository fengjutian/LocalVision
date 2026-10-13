package com.localvision.app

import android.os.Bundle
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.localvision.app.vision.ModelDescriptor
import com.localvision.app.vision.VisionTask
import com.localvision.app.vision.VisionResult
import com.localvision.app.ui.LocalImagePreview

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { LocalVisionApp() }
    }
}

private val Ink = Color(0xFF182230)
private val Brand = Color(0xFF176B5B)
private val Canvas = Color(0xFFF5F7F8)

@Composable
fun LocalVisionApp(vm: MainViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) vm.selectImage(uri)
    }
    MaterialTheme {
        Surface(color = Canvas, modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text("LocalVision", style = MaterialTheme.typography.headlineMedium, color = Ink)
                Text("图片留在设备上 · 模型能力透明可查", color = Color(0xFF536170))
                ImagePanel(
                    uri = state.imageUri,
                    detections = (state.status as? AnalysisStatus.Success)
                        ?.result?.let { it as? VisionResult.Detections }?.items.orEmpty(),
                    onPick = { picker.launch("image/*") },
                )
                Text("选择分析任务", style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    VisionTask.entries.forEach { task ->
                        FilterChip(
                            selected = state.task == task,
                            onClick = { vm.selectTask(task) },
                            label = { Text(task.title) },
                        )
                    }
                }
                Text(state.task.description, color = Color(0xFF536170))
                ModelCard(vm.modelsFor(state.task).first())
                StatusCard(state.status)
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
                Text(
                    "隐私说明：当前版本不申请相册全量权限，不上传、复制或持久化所选图片，也不保存人脸特征。",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF536170),
                )
            }
        }
    }
}

@Composable
private fun ImagePanel(
    uri: Uri?,
    detections: List<com.localvision.app.vision.Detection>,
    onPick: () -> Unit,
) {
    val hasImage = uri != null
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(
                modifier = Modifier.fillMaxWidth().height(170.dp)
                    .background(Color(0xFFE6EEEC), RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center,
            ) {
                if (uri == null) Text("尚未选择图片", color = Ink)
                else LocalImagePreview(uri = uri, detections = detections, modifier = Modifier.fillMaxSize())
            }
            OutlinedButton(onClick = onPick, modifier = Modifier.fillMaxWidth()) {
                Text(if (hasImage) "更换图片" else "从系统选择图片")
            }
        }
    }
}

@Composable
private fun ModelCard(model: ModelDescriptor) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text("模型状态", style = MaterialTheme.typography.titleSmall)
            Text(if (model.installed) model.displayName else "尚未安装真实模型", color = if (model.installed) Brand else Color(0xFF9B4D2B))
            Text("格式 ${model.format} · 版本 ${model.version} · 许可 ${model.license}", style = MaterialTheme.typography.bodySmall)
            Text(model.limitations, style = MaterialTheme.typography.bodySmall, color = Color(0xFF536170))
        }
    }
}

@Composable
private fun StatusCard(status: AnalysisStatus) {
    when (status) {
        AnalysisStatus.Idle -> Unit
        AnalysisStatus.Running -> Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(22.dp), color = Brand)
            Spacer(Modifier.size(10.dp))
            Text("正在本地分析…")
        }
        is AnalysisStatus.Error -> Card(modifier = Modifier.fillMaxWidth()) {
            Text(status.message, modifier = Modifier.padding(16.dp), color = Color(0xFF9B2C2C))
        }
        is AnalysisStatus.Success -> Card(modifier = Modifier.fillMaxWidth()) {
            val count = (status.result as? VisionResult.Detections)?.items?.size
            Text(
                "分析完成${count?.let { "，检测到 $it 张人脸" }.orEmpty()}，用时 ${status.result.elapsedMs} ms",
                modifier = Modifier.padding(16.dp),
            )
        }
    }
}

