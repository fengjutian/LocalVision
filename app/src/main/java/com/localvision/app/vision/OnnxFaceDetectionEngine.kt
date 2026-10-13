package com.localvision.app.vision

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.nio.FloatBuffer
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

class OnnxFaceDetectionEngine(private val context: Context) : AutoCloseable {
    private val environment = OrtEnvironment.getEnvironment()
    private val modelBytes by lazy {
        context.assets.open(MODEL_ASSET).use { it.readBytes() }.also(::verifyModel)
    }
    private val session: OrtSession by lazy {
        environment.createSession(modelBytes, OrtSession.SessionOptions().apply {
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            setIntraOpNumThreads(maxOf(1, Runtime.getRuntime().availableProcessors() / 2))
        })
    }

    suspend fun analyze(uri: Uri): VisionResult.Detections = withContext(Dispatchers.Default) {
        coroutineContext.ensureActive()
        val start = System.nanoTime()
        val bitmap = AndroidImageDecoder.decode(context, uri)
        val prepared = try { YuNetPreprocessor.prepare(bitmap) } finally { bitmap.recycle() }
        coroutineContext.ensureActive()
        val tensor = OnnxTensor.createTensor(
            environment,
            FloatBuffer.wrap(prepared.tensor),
            longArrayOf(1, 3, YuNetPreprocessor.INPUT_SIZE.toLong(), YuNetPreprocessor.INPUT_SIZE.toLong()),
        )
        val detections = tensor.use { input ->
            session.run(mapOf(INPUT_NAME to input)).use { result ->
                coroutineContext.ensureActive()
                YuNetPostprocessor.decode(readOutputs(result))
            }
        }
        VisionResult.Detections(detections, (System.nanoTime() - start) / 1_000_000)
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
        if (hash != MODEL_SHA256) throw AnalysisFailure.InvalidModel("SHA-256 不匹配")
    }

    override fun close() {
        runCatching { session.close() }
    }

    companion object {
        private const val MODEL_ASSET = "models/yunet/face_detection_yunet_2023mar.onnx"
        private const val MODEL_SHA256 = "8f2383e4dd3cfbb4553ea8718107fc0423210dc964f9f4280604804ed2552fa4"
        private const val INPUT_NAME = "input"
    }
}
