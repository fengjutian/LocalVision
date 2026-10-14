package com.localvision.app

import android.app.Application
import android.net.Uri
import com.localvision.app.vision.AnalysisConfig
import com.localvision.app.vision.AnalysisFailure
import com.localvision.app.vision.AnalysisStage
import com.localvision.app.vision.Detection
import com.localvision.app.vision.FailureKind
import com.localvision.app.vision.ModelDescriptor
import com.localvision.app.vision.ModelRegistry
import com.localvision.app.vision.NormalizedRect
import com.localvision.app.vision.VisionEngine
import com.localvision.app.vision.VisionResult
import com.localvision.app.vision.VisionTask
import com.localvision.app.vision.TestModelCatalog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModelTest {
    private val faceModel = TestModelCatalog.faceModel()

    private val registryWithoutFaceModel = ModelRegistry(
        TestModelCatalog.registry().modelsFor(VisionTask.FACE_DETECTION).map { it.copy(installed = false) },
    )

    private fun viewModel(
        engine: VisionEngine,
        dispatcher: CoroutineDispatcher,
        registry: ModelRegistry = TestModelCatalog.registry(),
    ) = MainViewModel(
        application = Application(),
        registry = registry,
        engineOverride = engine,
        historyStore = FakeHistory(),
        scopeOverride = CoroutineScope(dispatcher),
    )

    private fun uri(): Uri = mock(Uri::class.java)

    @Test
    fun `analysis uses the config captured when the user tapped analyze`() = runTest {
        val engine = FakeEngine()
        val vm = viewModel(engine, UnconfinedTestDispatcher(testScheduler))

        vm.selectImage(uri())
        vm.setScoreThreshold(0.75f)
        vm.analyze()

        assertEquals(AnalysisConfig(0.75f, 0.3f), engine.requestedConfig)
        assertEquals(VisionTask.FACE_DETECTION, engine.requestedTask)
    }

    @Test
    fun `moving the slider mid-run does not change the in-flight request`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val engine = FakeEngine(gate = gate)
        val vm = viewModel(engine, UnconfinedTestDispatcher(testScheduler))

        vm.selectImage(uri())
        vm.setScoreThreshold(0.5f)
        vm.analyze()
        assertTrue(vm.state.value.status is AnalysisStatus.Running)

        vm.setScoreThreshold(0.9f)
        gate.complete(Unit)

        assertEquals(AnalysisConfig(0.5f, 0.3f), engine.requestedConfig)
        val completed = (vm.state.value.status as AnalysisStatus.Success).analysis
        assertEquals(AnalysisConfig(0.5f, 0.3f), completed.config)
    }

    @Test
    fun `changing the slider after success marks the result stale without rewriting it`() = runTest {
        val engine = FakeEngine()
        val vm = viewModel(engine, UnconfinedTestDispatcher(testScheduler))

        vm.selectImage(uri())
        vm.setScoreThreshold(0.4f)
        vm.analyze()
        assertFalse(vm.state.value.isResultStale)

        vm.setScoreThreshold(0.8f)
        val state = vm.state.value
        assertTrue(state.isResultStale)
        assertEquals(0.8f, state.config.scoreThreshold)
        // 展示与导出仍然使用成功时的快照
        assertEquals(0.4f, state.displayConfig.scoreThreshold)
        assertEquals(AnalysisConfig(0.4f, 0.3f), state.completed!!.config)
    }

    @Test
    fun `re-analysing with the new slider clears the stale flag`() = runTest {
        val engine = FakeEngine()
        val vm = viewModel(engine, UnconfinedTestDispatcher(testScheduler))

        vm.selectImage(uri())
        vm.analyze()
        vm.setScoreThreshold(0.85f)
        assertTrue(vm.state.value.isResultStale)

        vm.analyze()

        assertFalse(vm.state.value.isResultStale)
        assertEquals(AnalysisConfig(0.85f, 0.3f), vm.state.value.completed!!.config)
    }

    @Test
    fun `switching image discards the in-flight success`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val engine = FakeEngine(gate = gate)
        val vm = viewModel(engine, UnconfinedTestDispatcher(testScheduler))

        vm.selectImage(uri())
        vm.analyze()
        vm.selectImage(uri())
        gate.complete(Unit)

        assertTrue(vm.state.value.status is AnalysisStatus.Idle)
        assertNull(vm.state.value.completed)
    }

    @Test
    fun `switching task discards the in-flight failure`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val engine = FakeEngine(gate = gate)
        val vm = viewModel(engine, UnconfinedTestDispatcher(testScheduler))

        vm.selectImage(uri())
        vm.analyze()
        vm.selectTask(VisionTask.POSE_ESTIMATION)
        gate.complete(Unit)

        val status = vm.state.value.status
        assertTrue("过期错误不得覆盖当前状态，实际为 $status", status !is AnalysisStatus.Error)
    }

    @Test
    fun `cancel marks the run cancelled and delivers nothing`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val engine = FakeEngine(gate = gate)
        val vm = viewModel(engine, UnconfinedTestDispatcher(testScheduler))

        vm.selectImage(uri())
        vm.analyze()
        vm.cancel()
        gate.complete(Unit)

        assertTrue(vm.state.value.status is AnalysisStatus.Cancelled)
        assertNull(vm.state.value.completed)
        assertTrue(engine.cancelRequested)
    }

    @Test
    fun `an empty detection list is a success not an error`() = runTest {
        val vm = viewModel(FakeEngine(result = VisionResult.Detections(emptyList(), 7L)), UnconfinedTestDispatcher(testScheduler))

        vm.selectImage(uri())
        vm.analyze()

        assertTrue(vm.state.value.status is AnalysisStatus.Success)
        assertEquals(0, vm.state.value.completed!!.let { (it.result as VisionResult.Detections).items.size })
    }

    @Test
    fun `an uninstalled model reports model unavailable without calling the engine`() = runTest {
        val engine = FakeEngine()
        val vm = viewModel(engine, UnconfinedTestDispatcher(testScheduler), registryWithoutFaceModel)

        vm.selectImage(uri())
        vm.analyze()

        val status = vm.state.value.status as AnalysisStatus.Error
        assertEquals(FailureKind.MODEL, status.kind)
        assertNull("未安装模型时不得调用引擎", engine.requestedConfig)
    }

    @Test
    fun `analysing without an image fails fast`() = runTest {
        val vm = viewModel(FakeEngine(), UnconfinedTestDispatcher(testScheduler))
        vm.analyze()
        assertEquals(FailureKind.IMAGE, (vm.state.value.status as AnalysisStatus.Error).kind)
    }

    @Test
    fun `an in-flight failure is reported with its stable kind`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val vm = viewModel(
            FakeEngine(gate = gate, failure = AnalysisFailure.OutOfMemory()),
            UnconfinedTestDispatcher(testScheduler),
        )

        vm.selectImage(uri())
        vm.analyze()
        gate.complete(Unit)

        assertEquals(FailureKind.MEMORY, (vm.state.value.status as AnalysisStatus.Error).kind)
    }

    @Test
    fun `failure kinds map to stable recovery actions`() {
        assertEquals(FailureKind.IMAGE, AnalysisFailure.NoImage().kind)
        assertEquals(FailureKind.IMAGE, AnalysisFailure.InvalidImage().kind)
        assertEquals(FailureKind.MODEL, AnalysisFailure.ModelUnavailable(VisionTask.POSE_ESTIMATION).kind)
        assertEquals(FailureKind.MODEL, AnalysisFailure.InvalidModel("hash").kind)
        assertEquals(FailureKind.MODEL, AnalysisFailure.InvalidOutput("shape").kind)
        assertEquals(FailureKind.MEMORY, AnalysisFailure.OutOfMemory().kind)

        val status = errorStatus(IllegalStateException("boom"))
        assertEquals(FailureKind.UNKNOWN, status.kind)
        assertEquals("boom", status.message)
    }

    @Test
    fun `history records the model that produced the result`() = runTest {
        val store = FakeHistory().apply { enabled = true }
        val vm = MainViewModel(
            application = Application(),
            registry = TestModelCatalog.registry(),
            engineOverride = FakeEngine(result = VisionResult.Detections(listOf(detection()), 12L)),
            historyStore = store,
            scopeOverride = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
        )

        vm.selectImage(uri())
        vm.analyze()

        assertEquals(1, store.writes)
        assertEquals(faceModel.id, store.lastModelId)
    }

    private fun detection() = Detection(NormalizedRect(0.1f, 0.1f, 0.2f, 0.2f), "人脸", 0.9f)

    private class FakeEngine(
        private val result: VisionResult = VisionResult.Detections(emptyList(), 1L),
        private val gate: CompletableDeferred<Unit>? = null,
        private val failure: Throwable? = null,
    ) : VisionEngine {
        var requestedConfig: AnalysisConfig? = null
        var requestedTask: VisionTask? = null
        var cancelRequested = false

        override suspend fun analyze(
            image: Uri,
            task: VisionTask,
            config: AnalysisConfig,
            onStage: (AnalysisStage) -> Unit,
        ): VisionResult {
            requestedTask = task
            requestedConfig = config
            onStage(AnalysisStage.INFERENCE)
            gate?.await()
            failure?.let { throw it }
            return result
        }

        override fun cancelActive(): Boolean {
            cancelRequested = true
            return true
        }
    }

    private class FakeHistory : HistoryStore {
        override var enabled = false
        var writes = 0
        var lastModelId: String? = null

        override fun entries(): List<HistoryEntry> = emptyList()

        override fun add(result: VisionResult.Detections, model: ModelDescriptor) {
            writes++
            lastModelId = model.id
        }

        override fun clear() = Unit
    }
}
