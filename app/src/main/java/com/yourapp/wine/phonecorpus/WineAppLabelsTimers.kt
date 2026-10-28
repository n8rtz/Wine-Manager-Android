package com.yourapp.wine.phonecorpus

import java.util.Locale

/**
 * Pure formatting and live-tick arithmetic for the three 124-label benchmark timers:
 * 1. Total active test run time (`mm ss`),
 * 2. Average execution time per image (`s`),
 * 3. Live countdown of estimated time remaining (`mm ss`).
 */
internal object WineAppLabelsTimers {

    fun liveElapsedMs(
        accumulatedRunMs: Long,
        segmentStartedRealtimeMs: Long?,
        nowRealtimeMs: Long,
    ): Long {
        val base = accumulatedRunMs.coerceAtLeast(0L)
        val active = if (segmentStartedRealtimeMs != null) {
            (nowRealtimeMs - segmentStartedRealtimeMs).coerceAtLeast(0L)
        } else {
            0L
        }
        return base + active
    }

    fun averageImageMs(timedImagesTotalMs: Long, timedImagesCount: Int): Long? {
        if (timedImagesCount <= 0) return null
        return (timedImagesTotalMs.coerceAtLeast(0L) + timedImagesCount / 2) / timedImagesCount
    }

    /**
     * Computes live estimated remaining milliseconds:
     * - Returns `0L` (`00m 00s`) when [remainingImages] <= 0.
     * - Returns `null` (`--m --s`) before at least one image has been timed ([etaPerImageMs] == null).
     * - Subtracts the in-flight elapsed time of the currently running image (clamped to `0..etaPerImageMs`)
     *   so the countdown ticks down smoothly every second without going negative on a slow photo.
     */
    fun estimatedRemainingMs(
        remainingImages: Int,
        etaPerImageMs: Long?,
        currentImageStartedRealtimeMs: Long?,
        nowRealtimeMs: Long,
    ): Long? {
        if (remainingImages <= 0) return 0L
        val perImage = etaPerImageMs?.takeIf { it > 0L } ?: return null
        val queuedAfterCurrent = (remainingImages - 1).toLong() * perImage
        val currentRemaining = if (currentImageStartedRealtimeMs != null) {
            val inFlight = (nowRealtimeMs - currentImageStartedRealtimeMs).coerceAtLeast(0L)
            (perImage - inFlight).coerceAtLeast(0L)
        } else {
            perImage
        }
        return currentRemaining + queuedAfterCurrent
    }

    /** Formats active run time in `mm ss` (e.g. `04m 18s`). */
    fun formatElapsedMmSs(elapsedMs: Long): String {
        val totalSeconds = elapsedMs.coerceAtLeast(0L) / 1000L
        val minutes = totalSeconds / 60L
        val seconds = totalSeconds % 60L
        return "%02dm %02ds".format(Locale.US, minutes, seconds)
    }

    /** Formats average time per image in seconds `s` (e.g. `3.4s`, or `--s` before first image). */
    fun formatAverageSeconds(avgImageMs: Long?): String {
        if (avgImageMs == null) return "--s"
        return "%.1fs".format(Locale.US, avgImageMs.coerceAtLeast(0L) / 1000.0)
    }

    /** Formats countdown time remaining in `mm ss` (ceiling to whole seconds while > 0, `--m --s` when unknown). */
    fun formatCountdownMmSs(remainingMs: Long?): String {
        if (remainingMs == null) return "--m --s"
        val clamped = remainingMs.coerceAtLeast(0L)
        val totalSeconds = if (clamped == 0L) 0L else (clamped + 999L) / 1000L
        val minutes = totalSeconds / 60L
        val seconds = totalSeconds % 60L
        return "%02dm %02ds".format(Locale.US, minutes, seconds)
    }
}
