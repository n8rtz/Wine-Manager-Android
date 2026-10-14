package com.yourapp.wine.ocr

import kotlinx.coroutines.sync.Semaphore
import java.util.concurrent.atomic.AtomicBoolean

/** Shared by app-scoped engines. Async native tasks keep their lease until the actual Task completes. */
class NativeEngineGate {
    private val semaphore = Semaphore(1)
    /** No coordinates: cancellably waits for the sole native lease within the ensemble's enclosing deadline. */
    suspend fun acquire(): Lease { semaphore.acquire(); return Lease { semaphore.release() } }

    class Lease internal constructor(private val release: () -> Unit) {
        private val closed = AtomicBoolean(false)
        /** No coordinates: called by native completion (not by a timed-out bitmap owner). */
        fun close() { if (closed.compareAndSet(false, true)) release() }
    }
}
