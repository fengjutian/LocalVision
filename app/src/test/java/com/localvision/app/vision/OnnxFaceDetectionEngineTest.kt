package com.localvision.app.vision

import android.graphics.Bitmap
import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 引擎的资源生命周期与并发语义。
 *
 * 全部通过 fake 运行时执行：不需要真实 ONNX 模型、不需要设备，
 * 只断言"创建 / 执行 / terminate / close"的次数与时序。
 */
class OnnxFaceDetectionEngineTest {

    // 1. 正常执行关闭输入 Tensor、Result、RunOptions
    @Test
    fun `successful run closes output and control and keeps the session open`() = runBlocking {
        val session = FakeSession()
        val engine = engineWith(session)

        val result = engine.analyze(uri(), AnalysisConfig(), {})

        assertEquals(1, result.items.size)
        assertEquals(1, session.runCount.get())
        assertEquals(3 * 640 * 640, session.inputSizes.single())
        assertArrayEquals(longArrayOf(1, 3, 640, 640), session.shapes.single())

        val control = session.controls.single()
        assertEquals(1, control.closeCount.get())
        assertEquals(0, control.terminateCount.get())
        assertEquals(1, session.producedOutputs.single().closeCount.get())

        // 会话必须活到显式 close 之后才释放
        assertEquals(0, session.closeCount.get())
        engine.close()
        assertEquals(1, session.closeCount.get())
    }

    // 2. runtime 抛异常时仍关闭本次控制资源
    @Test
    fun `runtime failure still closes the run control`() = runBlocking {
        val session = FakeSession(failure = IllegalStateException("本地推理执行失败：boom"))
        val engine = engineWith(session)

        val error = runCatching { engine.analyze(uri(), AnalysisConfig(), {}) }.exceptionOrNull()

        assertTrue("应上抛运行时异常，实际为 $error", error is IllegalStateException)
        assertEquals(1, session.controls.single().closeCount.get())
        assertEquals(0, session.controls.single().terminateCount.get())
        assertEquals(0, session.closeCount.get())
        engine.close()
        assertEquals(1, session.closeCount.get())
    }

    // 3. 协程取消时 terminate() 到达当前运行
    @Test
    fun `cancel terminates the active run`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val session = FakeSession(
            beforeReturn = { control ->
                started.complete(Unit)
                control.terminated.await(5, TimeUnit.SECONDS)
            },
        )
        val engine = engineWith(session)
        val outcome = CompletableDeferred<Throwable?>()

        val job = launch(Dispatchers.Default) {
            outcome.complete(runCatching { engine.analyze(uri(), AnalysisConfig(), {}) }.exceptionOrNull())
        }
        withTimeout(5_000) { started.await() }

        assertTrue(engine.cancelActive())
        withTimeout(5_000) { job.join() }

        val error = outcome.await()
        assertTrue("取消应表现为 CancellationException，实际为 $error", error is CancellationException)
        val control = session.controls.single()
        assertEquals(1, control.terminateCount.get())
        assertEquals(1, control.closeCount.get())
    }

    // 4. 尚未进入 run 时取消不会误终止下一次运行
    @Test
    fun `cancel while idle does not terminate the next run`() = runBlocking {
        val session = FakeSession()
        val engine = engineWith(session)

        assertFalse(engine.cancelActive())
        engine.analyze(uri(), AnalysisConfig(), {})

        assertEquals(1, session.runCount.get())
        assertEquals(0, session.controls.single().terminateCount.get())
        assertEquals(1, session.controls.single().closeCount.get())
    }

    // 5. 同一 Session 最大并发为 1
    @Test
    fun `session runs are serialized`() = runBlocking {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val session = FakeSession(
            beforeReturn = {
                started.countDown()
                release.await(5, TimeUnit.SECONDS)
            },
        )
        val engine = engineWith(session)

        val first = async(Dispatchers.Default) { runCatching { engine.analyze(uri(), AnalysisConfig(), {}) } }
        assertTrue("首个推理未在预期时间内启动", started.await(5, TimeUnit.SECONDS))
        val second = async(Dispatchers.Default) { runCatching { engine.analyze(uri(), AnalysisConfig(), {}) } }
        delay(200)

        assertFalse("第二个分析不应在第一个退出前完成", second.isCompleted)
        assertEquals(1, session.maxActive.get())

        release.countDown()
        awaitAll(first, second)
        assertEquals(1, session.maxActive.get())
        assertEquals(2, session.runCount.get())
        assertEquals(0, session.closeCount.get())
    }

    // 6. close() 在空闲状态只关闭一次
    @Test
    fun `close is idempotent while idle`() = runBlocking {
        val session = FakeSession()
        val engine = engineWith(session)

        engine.analyze(uri(), AnalysisConfig(), {})
        engine.close()
        engine.close()
        engine.close()

        assertEquals(1, session.closeCount.get())
    }

    // 7. close() 与执行并发时等待当前执行退出，不提前关闭 Session
    @Test
    fun `close during a run waits for the run to exit`() = runBlocking {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val session = FakeSession(
            beforeReturn = {
                started.countDown()
                release.await(5, TimeUnit.SECONDS)
            },
        )
        val engine = engineWith(session)

        val analysis = async(Dispatchers.Default) { runCatching { engine.analyze(uri(), AnalysisConfig(), {}) } }
        assertTrue("首个推理未在预期时间内启动", started.await(5, TimeUnit.SECONDS))

        engine.close()
        assertEquals("执行进行中不得关闭 Session", 0, session.closeCount.get())

        release.countDown()
        analysis.await()
        assertEquals("执行退出后必须关闭 Session", 1, session.closeCount.get())
    }

    // 8. close 请求后拒绝新执行
    @Test
    fun `analysis after close is rejected without creating a session`() = runBlocking {
        val session = FakeSession()
        val engine = engineWith(session)

        engine.close()
        val error = runCatching { engine.analyze(uri(), AnalysisConfig(), {}) }.exceptionOrNull()

        assertTrue(error is CancellationException)
        assertEquals(0, session.runCount.get())
        assertEquals(0, session.factory.createCount)
        assertEquals(0, session.closeCount.get())
    }

    // 9. 模型哈希错误不会创建 Session
    @Test
    fun `model hash mismatch prevents session creation`() = runBlocking {
        val session = FakeSession()
        val engine = engineWith(session, descriptor = faceModel(hashOf(byteArrayOf(9, 9, 9, 9))))

        val error = runCatching { engine.analyze(uri(), AnalysisConfig(), {}) }.exceptionOrNull()

        assertTrue("应映射为 InvalidModel，实际为 $error", error is AnalysisFailure.InvalidModel)
        assertEquals(0, session.factory.createCount)
        assertEquals(0, session.runCount.get())
        engine.close()
        assertEquals(0, session.closeCount.get())
    }

    // 10. 输出 shape 错误释放资源并映射为 InvalidOutput
    @Test
    fun `invalid output shape releases resources and maps to InvalidOutput`() = runBlocking {
        val session = FakeSession(outputs = { brokenShapeOutputs() })
        val engine = engineWith(session)

        val error = runCatching { engine.analyze(uri(), AnalysisConfig(), {}) }.exceptionOrNull()

        assertTrue("应映射为 InvalidOutput，实际为 $error", error is AnalysisFailure.InvalidOutput)
        assertEquals(1, session.controls.single().closeCount.get())
        assertEquals(1, session.producedOutputs.single().closeCount.get())
        assertEquals(0, session.closeCount.get())
    }

    @Test
    fun `missing output name is reported as InvalidOutput`() = runBlocking {
        val missing = faceOutputs().toMutableMap().apply { remove("cls_8") }
        val session = FakeSession(outputs = { missing })
        val engine = engineWith(session)

        val error = runCatching { engine.analyze(uri(), AnalysisConfig(), {}) }.exceptionOrNull()

        assertTrue(error is AnalysisFailure.InvalidOutput)
        assertTrue("错误信息应点名缺失的输出：${error?.message}", error?.message?.contains("缺少 cls_8") == true)
        assertEquals(1, session.controls.single().closeCount.get())
    }

    @Test
    fun `valid empty prediction is a success not a failure`() = runBlocking {
        val session = FakeSession(outputs = { faceOutputs { _, _ -> null } })
        val engine = engineWith(session)

        val result = engine.analyze(uri(), AnalysisConfig(), {})

        assertTrue(result.items.isEmpty())
        assertEquals(1, session.controls.single().closeCount.get())
    }

    @Test
    fun `preprocessing failure surfaces before any run starts`() = runBlocking {
        val session = FakeSession()
        val engine = OnnxFaceDetectionEngine(
            model = faceModel(),
            loadModelBytes = { MODEL_BYTES },
            sessionFactory = session.factory,
            decodeImage = { mock(Bitmap::class.java) },
            prepareImage = { error("预处理失败") },
        )

        val error = runCatching { engine.analyze(uri(), AnalysisConfig(), {}) }.exceptionOrNull()

        assertEquals("预处理失败", error?.message)
        assertEquals(0, session.factory.createCount)
        assertEquals(0, session.runCount.get())
    }

    }

// --- 文件级 fixtures：仅本测试文件可见 ---

private val MODEL_BYTES = byteArrayOf(1, 2, 3, 4, 5)
private val TENSOR = FloatArray(3 * 640 * 640)

private fun uri(): Uri = mock(Uri::class.java)

private fun hashOf(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

private fun faceModel(sha256: String = hashOf(MODEL_BYTES)): ModelDescriptor =
    requireNotNull(TestModelCatalog.registry().activeModel(VisionTask.FACE_DETECTION)).copy(sha256 = sha256)

private fun engineWith(
    session: FakeSession,
    descriptor: ModelDescriptor = faceModel(),
): OnnxFaceDetectionEngine = OnnxFaceDetectionEngine(
    model = descriptor,
    loadModelBytes = { MODEL_BYTES },
    sessionFactory = session.factory,
    decodeImage = { mock(Bitmap::class.java) },
    prepareImage = { PreparedImage(TENSOR, 1280, 720, LetterboxTransform.identity(640)) },
)

/** 构造符合 YuNet 输出契约的假输出；scoreAt 决定哪些 anchor 命中。 */
private fun faceOutputs(
    scoreAt: (stride: Int, index: Int) -> Float? = { stride, index ->
        if (stride == 8 && index == 0) 0.9f else null
    },
): Map<String, FloatArray> {
    val outputs = LinkedHashMap<String, FloatArray>()
    for (stride in listOf(8, 16, 32)) {
        val cells = 640 / stride
        val count = cells * cells
        val cls = FloatArray(count)
        val obj = FloatArray(count)
        val bbox = FloatArray(count * 4)
        val kps = FloatArray(count * 10)
        for (index in 0 until count) {
            val score = scoreAt(stride, index) ?: continue
            cls[index] = score
            obj[index] = score
            bbox[index * 4] = 0.5f
            bbox[index * 4 + 1] = 0.5f
            bbox[index * 4 + 2] = 0f
            bbox[index * 4 + 3] = 0f
            kps[index * 10] = 0.5f
            kps[index * 10 + 1] = 0.5f
        }
        outputs["cls_$stride"] = cls
        outputs["obj_$stride"] = obj
        outputs["bbox_$stride"] = bbox
        outputs["kps_$stride"] = kps
    }
    return outputs
}

private fun brokenShapeOutputs(): Map<String, FloatArray> =
    faceOutputs().toMutableMap().apply { this["kps_8"] = FloatArray(7) }

private class FakeOutput(private val values: Map<String, FloatArray>) : FaceRuntimeOutput {
    val closeCount = AtomicInteger(0)

    override fun floats(name: String): FloatArray? = values[name]

    override fun close() {
        closeCount.incrementAndGet()
    }
}

private class FakeSessionFactory(private val session: () -> FaceRuntimeSession) : FaceRuntimeSessionFactory {
    @Volatile var createCount = 0

    override fun create(modelBytes: ByteArray): FaceRuntimeSession {
        createCount++
        return session()
    }

    override fun newRunControl(): RunControl = FakeControl()
}

private class FakeControl : RunControl {
    val terminated = CountDownLatch(1)
    val terminateCount = AtomicInteger(0)
    val closeCount = AtomicInteger(0)

    override val terminationRequested: Boolean get() = terminateCount.get() > 0

    override fun terminate() {
        terminateCount.incrementAndGet()
        terminated.countDown()
    }

    override fun close() {
        closeCount.incrementAndGet()
    }
}

private class FakeSession(
    private val outputs: () -> Map<String, FloatArray> = { faceOutputs() },
    private val beforeReturn: (FakeControl) -> Unit = {},
    private val failure: Throwable? = null,
) : FaceRuntimeSession {
    val factory = FakeSessionFactory { this }
    val runCount = AtomicInteger(0)
    val closeCount = AtomicInteger(0)
    val maxActive = AtomicInteger(0)
    val controls = CopyOnWriteArrayList<FakeControl>()
    val producedOutputs = CopyOnWriteArrayList<FakeOutput>()
    val inputSizes = CopyOnWriteArrayList<Int>()
    val shapes = CopyOnWriteArrayList<LongArray>()

    private val active = AtomicInteger(0)

    override fun run(input: FloatArray, shape: LongArray, control: RunControl): FaceRuntimeOutput {
        val fake = control as FakeControl
        controls += fake
        inputSizes += input.size
        shapes += shape.copyOf()
        runCount.incrementAndGet()
        val current = active.incrementAndGet()
        maxActive.updateAndGet { previous -> maxOf(previous, current) }
        try {
            beforeReturn(fake)
            // ONNX Runtime 在 setTerminate 之后会让 run 返回错误，这里模拟同样的时序。
            if (fake.terminationRequested) throw IllegalStateException("本地推理执行失败：Run cancelled")
            failure?.let { throw it }
            return FakeOutput(outputs()).also { producedOutputs += it }
        } finally {
            active.decrementAndGet()
        }
    }

    override fun close() {
        closeCount.incrementAndGet()
    }
}