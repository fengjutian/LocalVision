package com.localvision.app.vision

import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.coroutineContext

/**
 * Serializes access to a model session and exposes the one active native run for cancellation.
 *
 * 取消句柄就是运行时的 [RunControl]——不为取消另建第二套状态机，
 * 因此"同一 Session 串行"与"取消当前 run"永远由同一份状态决定。
 */
internal class InferenceRunGate<C : RunControl> {
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
        control.terminate()
        return true
    }

    fun isRunning(): Boolean = active.get() != null

    fun tryLockForClose(): Boolean = mutex.tryLock()
    fun unlockAfterClose() = mutex.unlock()
}
