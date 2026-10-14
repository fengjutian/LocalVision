package com.localvision.app.vision

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtException
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.nio.FloatBuffer
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.coroutineContext

/**
 * YuNet + ONNX Runtime CPU 引擎。
 *
 * 权重路径与 SHA-256 来自 [ModelDescriptor]（即 [ModelRegistry]），引擎自身不再持有模型常量，
 * 保证"模型是什么"只有一处定义。
 */
class OnnxFaceDetectionEngine(
    private val context: Context,
    private val model: ModelDescriptor,
) : AutoCloseable {
    private val modelAsset: String = checkNotNull(model.assetPath) { "模型 ${model.id} 未声明 assetPath" }
    private val environment = OrtEnvironment.getEnvironment()
    private val modelBytes by lazy {
        context.assets.open(modelAsset).use { it.readBytes() }.also(::verifyModel)
    }
    private val sessionOptionsDelegate = lazy {
        OrtSession.SessionOptions().apply {
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            setIntraOpNumThreads(maxOf(1, Runtime.getRuntime().availableProcessors() / 2))
        }
    }
    private val sessionDelegate = lazy {
        environment.createSession(modelBytes, sessionOptionsDelegate.value)
    }
    private val session: OrtSession by sessionDelegate
    private val runGate = InferenceRunGate<OrtRunControl>()
    private val closeRequested = AtomicBoolean(false)
    private val runtimeClosed = AtomicBoolean(false)

    suspend fun analyze(
        uri: Uri,
        config: AnalysisConfig,
        onStage: (AnalysisStage) -> Unit,
    ): VisionResult.Detections = withContext(Dispatchers.Default) {
        coroutineContext.ensureActive()
        if (closeRequested.get()) throw CancellationException("Inference engine is closed")
        val start = System.nanoTime()
        onStage(AnalysisStage.DECODING)
        val decodeStart = System.nanoTime()
        val bitmap = try {
            AndroidImageDecoder.decode(context, uri)
        } catch (_: OutOfMemoryError) {
            throw AnalysisFailure.OutOfMemory()
        }
        val decodeMs = elapsedSince(decodeStart)

        onStage(AnalysisStage.PREPROCESSING)
        val preprocessStart = System.nanoTime()
        val prepared = try { YuNetPreprocessor.prepare(bitmap) } finally { bitmap.recycle() }
        coroutineContext.ensureActive()
        val tensor = OnnxTensor.createTensor(
            environment,
            FloatBuffer.wrap(prepared.tensor),
            longArrayOf(1, 3, YuNetPreprocessor.INPUT_SIZE.toLong(), YuNetPreprocessor.INPUT_SIZE.toLong()),
        )
        val preprocessMs = elapsedSince(preprocessStart)

        val modelLoadStart = System.nanoTime()
        if (!sessionDelegate.isInitialized()) onStage(AnalysisStage.LOADING_MODEL)
        session
        val modelLoadMs = elapsedSince(modelLoadStart)

        var inferenceMs = 0L
        var postprocessMs = 0L
        val detections = tensor.use { input ->
            onStage(AnalysisStage.INFERENCE)
            val inferenceStart = System.nanoTime()
            var runControl: OrtRunControl? = null
            try {
                runGate.run({ OrtRunControl().also { runControl = it } }) { control ->
                    coroutineContext.ensureActive()
                    if (closeRequested.get()) throw CancellationException("Inference engine is closed")
                    session.run(mapOf(INPUT_NAME to input), control.options).use { result ->
                        inferenceMs = elapsedSince(inferenceStart)
                        coroutineContext.ensureActive()
                        onStage(AnalysisStage.POSTPROCESSING)
                        val postprocessStart = System.nanoTime()
                        YuNetPostprocessor.decode(
                            outputs = readOutputs(result),
                            transform = prepared.transform,
                            scoreThreshold = config.scoreThreshold,
                            nmsThreshold = config.nmsThreshold,
                        ).also { postprocessMs = elapsedSince(postprocessStart) }
                    }
                }
            } catch (error: OrtException) {
                if (runControl?.cancellationRequested == true || !coroutineContext.isActive || closeRequested.get()) {
                    throw CancellationException("ONNX inference cancelled").also { it.initCause(error) }
                }
                throw error
            } finally {
                if (closeRequested.get()) closeRuntime()
            }
        }
        VisionResult.Detections(
            items = detections,
            elapsedMs = elapsedSince(start),
            performance = PerformanceBreakdown(
                decodeMs = decodeMs,
                preprocessMs = preprocessMs,
                modelLoadMs = modelLoadMs,
                inferenceMs = inferenceMs,
                postprocessMs = postprocessMs,
            ),
        )
    }

    private fun readOutputs(result: OrtSession.Result): YuNetOutputs {
        fun output(prefix: String, stride: Int): FloatArray {
            val value = result.get("${prefix}_$stride").orElseThrow {
                AnalysisFailure.InvalidOutput("缺少 ${prefix}_$stride")
            }
            val tensor = value as? OnnxTensor
                ?: throw AnalysisFailure.InvalidOutput("${prefix}_$stride 不是 Tensor")
            val buffer = tensor.floatBuffer
            return FloatArray(buffer.remaining()).also { buffer.get(it) }
        }
        val strides = listOf(8, 16, 32)
        return YuNetOutputs(
            cls = strides.associateWith { output("cls", it) },
            obj = strides.associateWith { output("obj", it) },
            bbox = strides.associateWith { output("bbox", it) },
            kps = strides.associateWith { output("kps", it) },
        )
    }

    private fun verifyModel(bytes: ByteArray) {
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
        if (hash != model.sha256) throw AnalysisFailure.InvalidModel("SHA-256 不匹配")
    }

    private fun elapsedSince(startNanos: Long): Long = (System.nanoTime() - startNanos) / 1_000_000

    /** Requests native termination for the currently executing ORT run, if any. */
    fun cancelActive(): Boolean = runGate.cancelActive()

    override fun close() {
        closeRequested.set(true)
        cancelActive()
        if (runGate.tryLockForClose()) {
            try {
                closeRuntime()
            } finally {
                runGate.unlockAfterClose()
            }
        }
    }

    private fun closeRuntime() {
        if (!runtimeClosed.compareAndSet(false, true)) return
        if (sessionDelegate.isInitialized()) runCatching { session.close() }
        if (sessionOptionsDelegate.isInitialized()) runCatching { sessionOptionsDelegate.value.close() }
    }

    private class OrtRunControl : InferenceRunControl {
        val options = OrtSession.RunOptions()
        private val closed = AtomicBoolean(false)
        private val terminateRequested = AtomicBoolean(false)
        val cancellationRequested: Boolean get() = terminateRequested.get()

        override fun cancel() {
            terminateRequested.set(true)
            if (!closed.get()) runCatching { options.setTerminate(true) }
        }

        override fun close() {
            if (closed.compareAndSet(false, true)) options.close()
        }
    }

    companion object {
        private const val INPUT_NAME = "input"
    }
}
