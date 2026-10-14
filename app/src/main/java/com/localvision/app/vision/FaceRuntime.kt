package com.localvision.app.vision

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtException
import ai.onnxruntime.OrtSession
import java.nio.FloatBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 推理运行时边界。
 *
 * 领域层和 ViewModel/UI 只认识这里的接口；ONNX Runtime 的类型被限制在
 * [OrtFaceRuntimeSessionFactory] / [OrtRunControl] 实现内部，不向上泄漏。
 */

/**
 * 单次运行的取消句柄。每次 run 使用独立实例，[terminate] 只影响当前这次运行。
 */
internal interface RunControl : AutoCloseable {
    /** 请求终止当前正在执行的原生运行。 */
    fun terminate()

    /** 是否已经请求过终止；用于把运行期错误区分成"取消"而不是"模型/环境故障"。 */
    val terminationRequested: Boolean
}

/** 一次运行的输出。实现负责把底层句柄的确定性释放放在 [close]。 */
internal interface FaceRuntimeOutput : AutoCloseable {
    /** 返回具名输出的副本；名字不存在时返回 null，由领域层决定错误语义。 */
    fun floats(name: String): FloatArray?
}

/** 一个模型会话。整个进程生命周期内复用，close 后不可再用。 */
internal interface FaceRuntimeSession : AutoCloseable {
    /**
     * 执行一次推理。[input] 与 [shape] 描述一个 NCHW 输入张量，
     * 返回值必须被调用方关闭。
     */
    fun run(input: FloatArray, shape: LongArray, control: RunControl): FaceRuntimeOutput
}

/**
 * 会话工厂。控制对象也由同一工厂产出，因为取消语义必须与具体运行时匹配。
 */
internal interface FaceRuntimeSessionFactory {
    fun create(modelBytes: ByteArray): FaceRuntimeSession

    /** 为即将开始的一次 run 创建独立的控制对象。 */
    fun newRunControl(): RunControl
}

internal class SimpleFaceRuntimeOutput(
    private val values: Map<String, FloatArray>,
) : FaceRuntimeOutput {
    override fun floats(name: String): FloatArray? = values[name]
    override fun close() = Unit
}

/**
 * ONNX Runtime 实现。
 *
 * - Session 与 SessionOptions 同生命周期，[close] 一并释放。
 * - 输入 Tensor 和输出 Result 在 [run] 内部就确定性关闭，
 *   这样原生内存的占用时间不依赖调用方的后处理耗时。
 * - OrtException 不会逃出本文件：领域层只看到带原因的普通运行时异常。
 */
internal class OrtFaceRuntimeSessionFactory(
    private val environment: OrtEnvironment = OrtEnvironment.getEnvironment(),
) : FaceRuntimeSessionFactory {

    override fun create(modelBytes: ByteArray): FaceRuntimeSession {
        val sessionOptions = OrtSession.SessionOptions().apply {
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            setIntraOpNumThreads(maxOf(1, Runtime.getRuntime().availableProcessors() / 2))
        }
        return try {
            OrtFaceRuntimeSession(
                session = environment.createSession(modelBytes, sessionOptions),
                sessionOptions = sessionOptions,
                environment = environment,
            )
        } catch (error: Throwable) {
            runCatching { sessionOptions.close() }
            throw error
        }
    }

    override fun newRunControl(): RunControl = OrtRunControl()
}

private class OrtFaceRuntimeSession(
    private val session: OrtSession,
    private val sessionOptions: OrtSession.SessionOptions,
    private val environment: OrtEnvironment,
) : FaceRuntimeSession {

    override fun run(input: FloatArray, shape: LongArray, control: RunControl): FaceRuntimeOutput {
        val options = (control as? OrtRunControl)?.options
            ?: throw IllegalStateException("RunControl 必须由 ${OrtFaceRuntimeSessionFactory::class.simpleName} 创建")
        val tensor = try {
            OnnxTensor.createTensor(environment, FloatBuffer.wrap(input), shape)
        } catch (error: OrtException) {
            throw runtimeFailure(error)
        }
        try {
            val result = try {
                session.run(mapOf(INPUT_NAME to tensor), options)
            } catch (error: OrtException) {
                throw runtimeFailure(error)
            }
            try {
                return SimpleFaceRuntimeOutput(copyOutputs(result))
            } finally {
                result.close()
            }
        } finally {
            tensor.close()
        }
    }

    private fun copyOutputs(result: OrtSession.Result): Map<String, FloatArray> {
        val outputs = LinkedHashMap<String, FloatArray>()
        for ((name, value) in result) {
            val tensor = value as? OnnxTensor
                ?: throw AnalysisFailure.InvalidOutput("$name 不是 Tensor")
            val buffer = tensor.floatBuffer
            outputs[name] = FloatArray(buffer.remaining()).also { buffer.get(it) }
        }
        return outputs
    }

    override fun close() {
        runCatching { session.close() }
        runCatching { sessionOptions.close() }
    }

    private fun runtimeFailure(error: OrtException): RuntimeException =
        IllegalStateException("本地推理执行失败：${error.message}", error)

    private companion object {
        const val INPUT_NAME = "input"
    }
}

/**
 * ONNX Runtime 的取消句柄。
 *
 * [terminate] 先置位再调用 `setTerminate(true)`，因此即使句柄已经关闭，
 * 领域层仍能读到 [terminationRequested] 并把错误判定为取消。
 */
internal class OrtRunControl : RunControl {
    val options = OrtSession.RunOptions()
    private val closed = AtomicBoolean(false)
    private val terminateRequested = AtomicBoolean(false)

    override val terminationRequested: Boolean get() = terminateRequested.get()

    override fun terminate() {
        terminateRequested.set(true)
        if (!closed.get()) runCatching { options.setTerminate(true) }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) options.close()
    }
}