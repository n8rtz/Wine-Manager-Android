package com.yourapp.wine.gliner

import android.content.Context
import android.os.Process
import android.util.Log
import java.util.concurrent.CancellationException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Installs/verifies the GLINER model bundle on a delayed, low-priority background thread shortly
 * after app start, so the first scan does not pay the ~131 MB unpack+hash cost inside its budget.
 * Best-effort only: any failure, interruption or skipped run leaves the lazy scan-time install
 * untouched, and that path still reports problems visibly at scan time. Call [schedule] once from
 * the Application; it is guarded to one run per process.
 */
object GlinerModelPrewarm {
    private const val TAG = "WineTracker"
    /** Delay after app start before the pre-warm may run; startup itself stays untouched. */
    private const val PREWARM_DELAY_MS = 8_000L
    /** Extra deferral while a scan is running, so the worker never contends with a live scan. */
    private const val BUSY_DEFER_MS = 15_000L
    private const val MAX_DEFERS = 3
    /** Free-space headroom above the pinned bundle bytes required before unpacking. */
    private const val SPACE_HEADROOM_BYTES = 64L * 1024 * 1024
    /** Pacing: every [CHUNKS_PER_PAUSE] checkActive calls (64 KB each) the worker sleeps [PAUSE_MS]. */
    private const val CHUNKS_PER_PAUSE = 16
    private const val PAUSE_MS = 25L

    private val scheduled = AtomicBoolean(false)

    /**
     * Schedules the one-shot pre-warm. [isScanBusy] is consulted before starting so a scan the
     * user already kicked off never shares the bundle lock or disk with this worker.
     */
    fun schedule(context: Context, isScanBusy: () -> Boolean = { false }) {
        if (!scheduled.compareAndSet(false, true)) return
        val app = context.applicationContext
        val executor = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "gliner-prewarm").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }
        }
        var defers = 0
        val attempt = object : Runnable {
            override fun run() {
                if (isScanBusy()) {
                    if (defers < MAX_DEFERS) {
                        defers++
                        Log.d(TAG, "GlinerModelPrewarm: scan running, deferring ($defers/$MAX_DEFERS)")
                        executor.schedule(this, BUSY_DEFER_MS, TimeUnit.MILLISECONDS)
                        return
                    }
                    Log.d(TAG, "GlinerModelPrewarm: scan still running after defers, proceeding anyway")
                }
                try {
                    // Lowered priority only (raising needs privileges); the paced checkActive below
                    // keeps disk/CPU pressure gentle — wall time does not matter here.
                    Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
                    prewarm(app)
                } catch (e: Exception) {
                    if (e is InterruptedException) Thread.currentThread().interrupt()
                    Log.w(TAG, "GlinerModelPrewarm: pre-warm failed, scan-time install still applies", e)
                } finally {
                    executor.shutdown()
                }
            }
        }
        executor.schedule(attempt, PREWARM_DELAY_MS, TimeUnit.MILLISECONDS)
        Log.d(TAG, "GlinerModelPrewarm: scheduled ${PREWARM_DELAY_MS}ms after start")
    }

    private fun prewarm(app: Context) {
        val pinnedBytes = runCatching { GlinerBundle.manifest(app).mapNotNull { it.bytes }.sum() }.getOrDefault(0L)
        val usable = GlinerBundle.privateDir(app).usableSpace
        if (usable < pinnedBytes + SPACE_HEADROOM_BYTES) {
            Log.i(TAG, "GlinerModelPrewarm: skipping, only ${usable / 1024 / 1024} MB free")
            return
        }
        // Every heavy loop in status() (asset copy, SHA-256, INT4 probe) honors checkActive per
        // chunk and caches its result in prefs, so pacing here slows only this worker: the scan
        // path passes its own unpaced checkActive and finds verified files waiting for it.
        var chunks = 0
        val status = GlinerBundle.status(app) {
            if (++chunks % CHUNKS_PER_PAUSE == 0) {
                try {
                    Thread.sleep(PAUSE_MS)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw CancellationException("pre-warm interrupted")
                }
            }
        }
        Log.i(TAG, "GlinerModelPrewarm: done (ready=${status.ready}, int4=${status.int4})")
    }
}
