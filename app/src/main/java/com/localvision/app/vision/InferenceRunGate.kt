package com.localvision.app.vision

import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.coroutineContext

internal interface InferenceRunControl : AutoCloseable {
    fun cancel()
}

/** Serializes access to a model session and exposes the one active native run for cancellation. */
internal class InferenceRunGate<C : InferenceRunControl> {
    private val mutex = Mutex()
    private val active = AtomicReference<C?>(null)

    suspend fun <T> run(factory: () -> C, block: suspend (C) -> T): T = mutex.withLock {
        coroutineContext.ensureActive()
        val control = factory()
        check(active.compareAndSet(null, control)) { "Only one inference run may be active" }
        try {
            block(control)
        } finally {
            active.compareAndSet(control, null)
            control.close()
        }
    }

    fun cancelActive(): Boolean {
        val control = active.get() ?: return false
        control.cancel()
        return true
    }

    fun isRunning(): Boolean = active.get() != null

    fun tryLockForClose(): Boolean = mutex.tryLock()
    fun unlockAfterClose() = mutex.unlock()
}
