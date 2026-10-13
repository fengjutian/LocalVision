package com.localvision.app

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.localvision.app.vision.ModelDescriptor
import com.localvision.app.vision.ModelRegistry
import com.localvision.app.vision.RegistryBackedVisionEngine
import com.localvision.app.vision.VisionResult
import com.localvision.app.vision.VisionTask
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class MainUiState(
    val imageUri: Uri? = null,
    val task: VisionTask = VisionTask.FACE_DETECTION,
    val status: AnalysisStatus = AnalysisStatus.Idle,
)

sealed interface AnalysisStatus {
    data object Idle : AnalysisStatus
    data object Running : AnalysisStatus
    data class Success(val result: VisionResult) : AnalysisStatus
    data class Error(val message: String) : AnalysisStatus
}

class MainViewModel : ViewModel() {
    private val registry = ModelRegistry.default()
    private val engine = RegistryBackedVisionEngine(registry)
    private val _state = MutableStateFlow(MainUiState())
    val state: StateFlow<MainUiState> = _state.asStateFlow()
    private var analysisJob: Job? = null

    fun selectImage(uri: Uri) {
        analysisJob?.cancel()
        _state.update { it.copy(imageUri = uri, status = AnalysisStatus.Idle) }
    }

    fun selectTask(task: VisionTask) {
        analysisJob?.cancel()
        _state.update { it.copy(task = task, status = AnalysisStatus.Idle) }
    }

    fun modelsFor(task: VisionTask): List<ModelDescriptor> = registry.modelsFor(task)

    fun analyze() {
        analysisJob?.cancel()
        analysisJob = viewModelScope.launch {
            val snapshot = _state.value
            val image = snapshot.imageUri
            if (image == null) {
                _state.update { it.copy(status = AnalysisStatus.Error("请先选择一张图片")) }
                return@launch
            }
            _state.update { it.copy(status = AnalysisStatus.Running) }
            try {
                val result = engine.analyze(image, snapshot.task)
                _state.update { it.copy(status = AnalysisStatus.Success(result)) }
            } catch (_: CancellationException) {
                throw CancellationException()
            } catch (error: Exception) {
                _state.update { it.copy(status = AnalysisStatus.Error(error.message ?: "分析失败")) }
            }
        }
    }

    fun cancel() {
        analysisJob?.cancel()
        _state.update { it.copy(status = AnalysisStatus.Idle) }
    }
}

