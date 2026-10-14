package com.localvision.app

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.localvision.app.vision.AnalysisConfig
import com.localvision.app.vision.AnalysisFailure
import com.localvision.app.vision.AnalysisStage
import com.localvision.app.vision.CompletedAnalysis
import com.localvision.app.vision.FailureKind
import com.localvision.app.vision.ModelDescriptor
import com.localvision.app.vision.ModelRegistry
import com.localvision.app.vision.RegistryBackedVisionEngine
import com.localvision.app.vision.VisionEngine
import com.localvision.app.vision.VisionEngineProvider
import com.localvision.app.vision.VisionResult
import com.localvision.app.vision.VisionTask
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
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
    val config: AnalysisConfig = AnalysisConfig(),
    val showOverlay: Boolean = true,
    val showBoxes: Boolean = true,
    val showKeypoints: Boolean = true,
    val showLabels: Boolean = true,
    val selectedFace: Int? = null,
    val sort: ResultSort = ResultSort.CONFIDENCE,
    val historyEnabled: Boolean = false,
    val history: List<HistoryEntry> = emptyList(),
) {
    /** 最近一次成功结果。参数变化不会清空它，只标记为过期。 */
    val completed: CompletedAnalysis?
        get() = (status as? AnalysisStatus.Success)?.analysis

    /** 展示与导出永远读成功时的参数快照，而不是当前滑杆值。 */
    val displayConfig: AnalysisConfig
        get() = completed?.config ?: config

    /** 当前参数与成功结果不一致：需要提示重新分析，但不能假装结果已重算。 */
    val isResultStale: Boolean
        get() = completed?.config?.equals(config) == false
}

sealed interface AnalysisStatus {
    data object Idle : AnalysisStatus
    data class Running(val stage: AnalysisStage) : AnalysisStatus
    data object Cancelled : AnalysisStatus
    data class Success(val analysis: CompletedAnalysis) : AnalysisStatus
    data class Error(val kind: FailureKind, val message: String) : AnalysisStatus
}

/**
 * 把任意异常收敛成稳定的 UI 错误状态。
 *
 * 空检测是合法的成功结果，绝不能落到这里——否则用户会以为"没检测到"是"出错"。
 */
internal fun errorStatus(error: Throwable): AnalysisStatus.Error = when (error) {
    is AnalysisFailure -> AnalysisStatus.Error(error.kind, error.message ?: "分析失败")
    else -> AnalysisStatus.Error(FailureKind.UNKNOWN, error.message ?: "分析失败")
}

/**
 * 生产构造由 `viewModel()` 反射调用；内部主构造允许测试注入注册表、引擎、历史和协程作用域，
 * 从而在不依赖 Main dispatcher 与真实 Android 组件的情况下覆盖取消/过期结果路径。
 */
class MainViewModel internal constructor(
    application: Application,
    val registry: ModelRegistry,
    engineOverride: VisionEngine?,
    private val historyStore: HistoryStore,
    private val scopeOverride: CoroutineScope?,
) : AndroidViewModel(application) {

    constructor(application: Application) : this(
        application = application,
        registry = ModelRegistry.load(application),
        engineOverride = VisionEngineProvider.currentOverride(),
        historyStore = AnalysisHistory(application),
        scopeOverride = null,
    )

    private val engine: VisionEngine = engineOverride ?: RegistryBackedVisionEngine(application, registry)

    /** 生产走 viewModelScope（随配置变更自动取消）；测试注入独立作用域。 */
    private val scope: CoroutineScope get() = scopeOverride ?: viewModelScope

    private val _state = MutableStateFlow(
        MainUiState(historyEnabled = historyStore.enabled, history = historyStore.entries()),
    )
    val state: StateFlow<MainUiState> = _state.asStateFlow()

    private var analysisJob: Job? = null
    private var requestGeneration = 0L

    fun modelsFor(task: VisionTask): List<ModelDescriptor> = registry.modelsFor(task)

    fun activeModel(task: VisionTask): ModelDescriptor? = registry.activeModel(task)

    fun selectImage(uri: Uri) {
        invalidateAnalysis()
        _state.update { it.copy(imageUri = uri, status = AnalysisStatus.Idle, selectedFace = null) }
    }

    fun selectTask(task: VisionTask) {
        invalidateAnalysis()
        _state.update { it.copy(task = task, status = AnalysisStatus.Idle, selectedFace = null) }
    }

    fun setScoreThreshold(value: Float) = _state.update {
        it.copy(config = it.config.copy(scoreThreshold = value.coerceIn(0f, 1f)))
    }

    fun setNmsThreshold(value: Float) = _state.update {
        it.copy(config = it.config.copy(nmsThreshold = value.coerceIn(0f, 1f)))
    }

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

    fun analyze() {
        val snapshot = _state.value
        if (snapshot.status is AnalysisStatus.Running) return
        val image = snapshot.imageUri ?: run {
            _state.update { it.copy(status = errorStatus(AnalysisFailure.NoImage())) }
            return
        }
        val model = registry.activeModel(snapshot.task)
        if (model == null) {
            _state.update { it.copy(status = errorStatus(AnalysisFailure.ModelUnavailable(snapshot.task))) }
            return
        }

        // 参数快照在点击时冻结。运行期间用户继续拖动滑杆，也不会改变本次请求。
        val config = snapshot.config
        val generation = ++requestGeneration
        _state.update { it.copy(status = AnalysisStatus.Running(AnalysisStage.DECODING)) }
        analysisJob = scope.launch {
            try {
                val result = engine.analyze(image, snapshot.task, config) { stage ->
                    if (generation == requestGeneration) {
                        _state.update { it.copy(status = AnalysisStatus.Running(stage)) }
                    }
                }
                if (generation != requestGeneration) return@launch
                _state.update {
                    it.copy(
                        status = AnalysisStatus.Success(CompletedAnalysis(result, model, config)),
                        selectedFace = null,
                    )
                }
                (result as? VisionResult.Detections)?.let { historyStore.add(it, model) }
                _state.update { it.copy(history = historyStore.entries()) }
            } catch (_: CancellationException) {
                if (generation == requestGeneration) {
                    _state.update { it.copy(status = AnalysisStatus.Cancelled) }
                }
            } catch (error: Exception) {
                if (generation != requestGeneration) return@launch
                _state.update { it.copy(status = errorStatus(error)) }
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
