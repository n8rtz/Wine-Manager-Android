package com.n8rtz.winetracker.scanner

/**
 * Admits at most one preview frame per user-initiated scan request.
 * The frame is held in PROCESSING until OCR completes so a second OCR job cannot start meanwhile.
 */
internal class OneShotScanGate {
    private sealed interface Phase {
        data object Idle : Phase
        data class Requested(val requestId: Long) : Phase
        data class Processing(val requestId: Long, val cancelled: Boolean = false) : Phase
    }

    private var phase: Phase = Phase.Idle

    @Synchronized
    fun request(requestId: Long): Boolean {
        if (phase != Phase.Idle) return false
        phase = Phase.Requested(requestId)
        return true
    }

    @Synchronized
    fun claimNextFrame(): Long? {
        val requested = phase as? Phase.Requested ?: return null
        phase = Phase.Processing(requested.requestId)
        return requested.requestId
    }

    /** Returns false when the request was cancelled while OCR was running. */
    @Synchronized
    fun finish(requestId: Long): Boolean {
        val processing = phase as? Phase.Processing ?: return false
        if (processing.requestId != requestId) return false
        phase = Phase.Idle
        return !processing.cancelled
    }

    @Synchronized
    fun cancel(requestId: Long): Boolean = when (val current = phase) {
        is Phase.Requested -> {
            if (current.requestId != requestId) {
                false
            } else {
                phase = Phase.Idle
                true
            }
        }
        is Phase.Processing -> {
            if (current.requestId != requestId || current.cancelled) {
                false
            } else {
                phase = current.copy(cancelled = true)
                true
            }
        }
        Phase.Idle -> false
    }
}
