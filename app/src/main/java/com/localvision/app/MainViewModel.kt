package com.localvision.app

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.localvision.app.vision.AnalysisFailure
import com.localvision.app.vision.AnalysisOptions
import com.localvision.app.vision.AnalysisStage
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

enum class ResultSort { CONFIDENCE, POSITION }

data class MainUiState(
    val imageUri: Uri? = null,
    val task: VisionTask = VisionTask.FACE_DETECTION,
    val status: AnalysisStatus = AnalysisStatus.Idle,
    val scoreThreshold: Float = 0.6f,
    val nmsThreshold: Float = 0.3f,
    val showOverlay: Boolean = true,
    val showBoxes: Boolean = true,
    val showKeypoints: Boolean = true,
    val showLabels: Boolean = true,
    val selectedFace: Int? = null,
    val sort: ResultSort = ResultSort.CONFIDENCE,
    val historyEnabled: Boolean = false,
    val history: List<HistoryEntry> = emptyList(),
)

sealed interface AnalysisStatus {
    data object Idle : AnalysisStatus
    data class Running(val stage: AnalysisStage) : AnalysisStatus
    data object Cancelled : AnalysisStatus
    data class Success(val result: VisionResult) : AnalysisStatus
    data class Error(val kind: ErrorKind, val message: String) : AnalysisStatus
}

enum class ErrorKind { IMAGE, MODEL, MEMORY, UNKNOWN }

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val registry = ModelRegistry.default()
    private val engine = RegistryBackedVisionEngine(application, registry)
    private val historyStore = AnalysisHistory(application)
    private val _state = MutableStateFlow(
        MainUiState(historyEnabled = historyStore.enabled, history = historyStore.entries()),
    )
    val state: StateFlow<MainUiState> = _state.asStateFlow()
    private var analysisJob: Job? = null
    private var requestGeneration = 0L

    fun selectImage(uri: Uri) {
        invalidateAnalysis()
        _state.update { it.copy(imageUri = uri, status = AnalysisStatus.Idle, selectedFace = null) }
    }

    fun selectTask(task: VisionTask) {
        invalidateAnalysis()
        _state.update { it.copy(task = task, status = AnalysisStatus.Idle, selectedFace = null) }
    }

    fun setScoreThreshold(value: Float) = _state.update { it.copy(scoreThreshold = value) }
    fun toggleOverlay() = _state.update { it.copy(showOverlay = !it.showOverlay) }
    fun toggleBoxes() = _state.update { it.copy(showBoxes = !it.showBoxes) }
    fun toggleKeypoints() = _state.update { it.copy(showKeypoints = !it.showKeypoints) }
    fun toggleLabels() = _state.update { it.copy(showLabels = !it.showLabels) }
    fun selectFace(index: Int?) = _state.update { it.copy(selectedFace = index) }
    fun setSort(sort: ResultSort) = _state.update { it.copy(sort = sort, selectedFace = null) }

    fun setHistoryEnabled(enabled: Boolean) {
        historyStore.enabled = enabled
        _state.update { it.copy(historyEnabled = enabled) }
    }

    fun clearHistory() {
        historyStore.clear()
        _state.update { it.copy(history = emptyList()) }
    }

    fun modelsFor(task: VisionTask): List<ModelDescriptor> = registry.modelsFor(task)

    fun analyze() {
        val snapshot = _state.value
        if (snapshot.status is AnalysisStatus.Running) return
        val image = snapshot.imageUri ?: run {
            _state.update { it.copy(status = AnalysisStatus.Error(ErrorKind.IMAGE, "请先选择一张图片")) }
            return
        }
        val generation = ++requestGeneration
        _state.update { it.copy(status = AnalysisStatus.Running(AnalysisStage.DECODING)) }
        analysisJob = viewModelScope.launch {
            try {
                val result = engine.analyze(
                    image = image,
                    task = snapshot.task,
                    options = AnalysisOptions(snapshot.scoreThreshold, snapshot.nmsThreshold),
                    onStage = { stage ->
                        if (generation == requestGeneration) _state.update { it.copy(status = AnalysisStatus.Running(stage)) }
                    },
                )
                if (generation != requestGeneration) return@launch
                _state.update { it.copy(status = AnalysisStatus.Success(result), selectedFace = null) }
                (result as? VisionResult.Detections)?.let(historyStore::add)
                _state.update { it.copy(history = historyStore.entries()) }
            } catch (_: CancellationException) {
                if (generation == requestGeneration) _state.update { it.copy(status = AnalysisStatus.Cancelled) }
            } catch (error: Exception) {
                if (generation != requestGeneration) return@launch
                val kind = when (error) {
                    is AnalysisFailure.InvalidImage -> ErrorKind.IMAGE
                    is AnalysisFailure.InvalidModel, is AnalysisFailure.InvalidOutput,
                    is AnalysisFailure.ModelUnavailable -> ErrorKind.MODEL
                    is AnalysisFailure.OutOfMemory -> ErrorKind.MEMORY
                    else -> ErrorKind.UNKNOWN
                }
                _state.update { it.copy(status = AnalysisStatus.Error(kind, error.message ?: "分析失败")) }
            }
        }
    }

    fun cancel() {
        requestGeneration++
        stopActiveInference()
        _state.update { it.copy(status = AnalysisStatus.Cancelled) }
    }

    fun cancelIfRunning() {
        if (_state.value.status is AnalysisStatus.Running) cancel()
    }

    private fun invalidateAnalysis() {
        requestGeneration++
        stopActiveInference()
    }

    private fun stopActiveInference() {
        engine.cancelActive()
        analysisJob?.cancel()
    }

    override fun onCleared() {
        invalidateAnalysis()
        engine.close()
        super.onCleared()
    }
}
