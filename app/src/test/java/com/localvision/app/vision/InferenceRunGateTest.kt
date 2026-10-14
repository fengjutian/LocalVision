package com.localvision.app.vision

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class InferenceRunGateTest {
    @Test
    fun `runs are serialized`() = runBlocking {
        val gate = InferenceRunGate<FakeControl>()
        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val activeCount = AtomicInteger(0)
        val maximumActive = AtomicInteger(0)

        suspend fun guardedRun(wait: Boolean) = gate.run(::FakeControl) {
            val active = activeCount.incrementAndGet()
            maximumActive.updateAndGet { previous -> maxOf(previous, active) }
            try {
                if (wait) {
                    firstStarted.complete(Unit)
                    releaseFirst.await()
                }
            } finally {
                activeCount.decrementAndGet()
            }
        }

        val first = async(Dispatchers.Default) { guardedRun(wait = true) }
        firstStarted.await()
        val second = async(Dispatchers.Default) { guardedRun(wait = false) }
        delay(50)

        assertFalse(second.isCompleted)
        releaseFirst.complete(Unit)
        awaitAll(first, second)
        assertEquals(1, maximumActive.get())
        assertFalse(gate.isRunning())
    }

    @Test
    fun `cancel reaches active run and control is closed`() = runBlocking {
        val gate = InferenceRunGate<FakeControl>()
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        lateinit var control: FakeControl

        val run = async(Dispatchers.Default) {
            gate.run({ FakeControl().also { control = it } }) {
                started.complete(Unit)
                release.await()
            }
        }
        started.await()

        assertTrue(gate.cancelActive())
        withTimeout(1_000) { control.cancelled.await() }
        release.complete(Unit)
        run.await()

        assertTrue(control.closed.get())
        assertFalse(gate.isRunning())
        assertFalse(gate.cancelActive())
    }

    @Test
    fun `control is closed when run fails`() = runBlocking {
        val gate = InferenceRunGate<FakeControl>()
        lateinit var control: FakeControl

        runCatching {
            gate.run({ FakeControl().also { control = it } }) {
                error("expected")
            }
        }

        assertTrue(control.closed.get())
        assertFalse(gate.isRunning())
    }

    private class FakeControl : RunControl {
        val cancelled = CompletableDeferred<Unit>()
        val closed = AtomicBoolean(false)
        private var terminated = false

        override val terminationRequested: Boolean get() = terminated

        override fun terminate() {
            terminated = true
            cancelled.complete(Unit)
        }

        override fun close() {
            closed.set(true)
        }
    }
}
