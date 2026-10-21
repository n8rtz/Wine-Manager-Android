package com.yourapp.wine.ocr

import android.os.SystemClock
import kotlinx.coroutines.withTimeoutOrNull

/** Absolute monotonic deadline includes capture, decode, geometry, OCR and confidence decisions. */
class ScanDeadline(val expiresAtMs: Long, private val now: () -> Long = SystemClock::elapsedRealtime) {
    val remainingMs: Long get() = (expiresAtMs - now()).coerceAtLeast(0L)
    val expired: Boolean get() = remainingMs == 0L

    /** No coordinates: cancels this operation at the shared deadline, rather than resetting a per-pass budget. */
    suspend fun <T : Any> run(reserveMs: Long = 0, operation: suspend () -> T): T? {
        val remaining = remainingMs - reserveMs
        if (remaining <= 0) return null
        return withTimeoutOrNull(remaining) { operation() }
    }

    companion object { const val BUDGET_MS = 15_000L }
}
