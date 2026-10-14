package com.localvision.app.vision

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.coroutineContext

/**
 * YuNet + ONNX Runtime CPU 引擎。
 *
 * 权重路径与 SHA-256 来自 [ModelDescriptor]（即 [ModelRegistry]），引擎自身不再持有模型常量，
 * 保证"模型是什么"只有一处定义。
 *
 * 会话、解码和预处理都是可注入边界，ONNX 类型不会穿透到领域层或 UI，
 * 因此资源生命周期可以在纯 JVM 测试里验证。
 */
class OnnxFaceDetectionEngine internal constructor(
    private val model: ModelDescriptor,
    private val loadModelBytes: () -> ByteArray,
    private val sessionFactory: FaceRuntimeSessionFactory,
    private val decodeImage: (Uri) -> Bitmap,
    private val prepareImage: (Bitmap) -> PreparedImage,
) : AutoCloseable {

    /** 模型必须自带 asset 路径；这是构造期就能发现的配置错误，不该等到首次分析。 */
    init {
        requireAssetPath(model)
    }

    constructor(context: Context, model: ModelDescriptor) : this(
        model = model,
        loadModelBytes = { context.assets.open(requireAssetPath(model)).use { it.readBytes() } },
        sessionFactory = OrtFaceRuntimeSessionFactory(),
        decodeImage = { uri -> AndroidImageDecoder.decode(context, uri) },
        prepareImage = { bitmap -> YuNetPreprocessor.prepare(bitmap) },
    )

    private val modelBytes by lazy { loadModelBytes().also(::verifyModel) }
    private val sessionDelegate = lazy { sessionFactory.create(modelBytes) }
    private val session: FaceRuntimeSession by sessionDelegate
    private val runGate = InferenceRunGate<RunControl>()
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
            decodeImage(uri)
        } catch (_: OutOfMemoryError) {
            throw AnalysisFailure.OutOfMemory()
        }
        val decodeMs = elapsedSince(decodeStart)

        onStage(AnalysisStage.PREPROCESSING)
        val preprocessStart = System.nanoTime()
        val prepared = try { prepareImage(bitmap) } finally { bitmap.recycle() }
        coroutineContext.ensureActive()
        val preprocessMs = elapsedSince(preprocessStart)

        val modelLoadStart = System.nanoTime()
        if (!sessionDelegate.isInitialized()) onStage(AnalysisStage.LOADING_MODEL)
        session
        val modelLoadMs = elapsedSince(modelLoadStart)

        var inferenceMs = 0L
        var postprocessMs = 0L
        onStage(AnalysisStage.INFERENCE)
        val inferenceStart = System.nanoTime()
        val detections = runGate.run(sessionFactory::newRunControl) { control ->
            try {
                coroutineContext.ensureActive()
                if (closeRequested.get()) throw CancellationException("Inference engine is closed")
                session.run(prepared.tensor, TENSOR_SHAPE, control).use { output ->
                    inferenceMs = elapsedSince(inferenceStart)
                    coroutineContext.ensureActive()
                    onStage(AnalysisStage.POSTPROCESSING)
                    val postprocessStart = System.nanoTime()
                    YuNetPostprocessor.decode(
                        outputs = readOutputs(output),
                        transform = prepared.transform,
                        scoreThreshold = config.scoreThreshold,
                        nmsThreshold = config.nmsThreshold,
                    ).also { postprocessMs = elapsedSince(postprocessStart) }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                // 只有在取消、协程退出或引擎关闭期间出现的错误才算取消；
                // 其余原样上抛，让 ViewModel 映射成可区分的失败类型。
                if (control.terminationRequested || !coroutineContext.isActive || closeRequested.get()) {
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

    private fun readOutputs(output: FaceRuntimeOutput): YuNetOutputs {
        fun value(prefix: String, stride: Int): FloatArray {
            val name = "${prefix}_$stride"
            return output.floats(name) ?: throw AnalysisFailure.InvalidOutput("缺少 $name")
        }
        val strides = listOf(8, 16, 32)
        return YuNetOutputs(
            cls = strides.associateWith { value("cls", it) },
            obj = strides.associateWith { value("obj", it) },
            bbox = strides.associateWith { value("bbox", it) },
            kps = strides.associateWith { value("kps", it) },
        )
    }

    private fun verifyModel(bytes: ByteArray) {
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
        if (hash != model.sha256) throw AnalysisFailure.InvalidModel("SHA-256 不匹配")
    }

    private fun elapsedSince(startNanos: Long): Long = (System.nanoTime() - startNanos) / 1_000_000

    /** 请求原生终止当前正在执行的那一次推理；空闲或尚未进入 run 时返回 false。 */
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
        // Session 只在当前执行退出互斥区之后才关闭；close() 与运行并发时由调用方延后到这里。
        if (!runtimeClosed.compareAndSet(false, true)) return
        if (sessionDelegate.isInitialized()) runCatching { session.close() }
    }

    private companion object {
        /** YuNet 输入契约：NCHW、3 通道、640x640。 */
        val TENSOR_SHAPE = longArrayOf(1, 3, 640, 640)

        fun requireAssetPath(model: ModelDescriptor): String =
            checkNotNull(model.assetPath) { "模型 ${model.id} 未声明 assetPath" }
    }
}