package com.yourapp.wine.gliner

import android.content.Context
import android.util.Log
import com.yourapp.wine.capture.WineScannerServices
import com.yourapp.wine.gazetteer.ProducerGazetteer
import com.yourapp.wine.textlab.TextLabWineExtraction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

/**
 * First-scan warm-up. Most users scan once per app session, so keeping the
 * scanner warm *between* scans buys nothing; instead the expensive cold
 * work — bundle install/verify, ONNX session creation, knowledge-base and
 * expanded-gazetteer opens — runs while the user is still framing the
 * photo on the Add Wine screen, and the scan consumes the prepared pieces.
 *
 * Prepared pieces are single-use: [takeModel]/[takeWarmSession] hand them
 * to one scan, which closes them exactly as if it had opened them itself.
 * Anything never consumed simply stays ready for the next scan; [discard]
 * releases it early (e.g. a cancelled capture). Preparation failures are
 * swallowed — the scan then opens everything itself, as before.
 */
internal object GlinerScanWarmup {
    private const val TAG = "WineTracker"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private class PreparedModel(val model: GlinerOnnxModel, val bytes: Long, val modified: Long)

    private var modelJob: Deferred<PreparedModel?>? = null
    private var warmJob: Deferred<TextLabWineExtraction.WarmSession?>? = null

    /** Idempotent: safe to call on every Add Wine open, capture and import. */
    @Synchronized
    fun begin(context: Context) {
        val app = context.applicationContext
        if (modelJob == null) modelJob = scope.async { prepareModel(app) }
        if (warmJob == null) warmJob = scope.async { prepareWarmSession(app) }
        // The GLiNER OCR path also awaits the app-scoped legacy services
        // for its scan lock; start that initialization now too so it
        // overlaps framing instead of preceding OCR.
        scope.launch { runCatching { WineScannerServices.get(app) } }
    }

    private fun prepareModel(app: Context): PreparedModel? = try {
        val status = GlinerBundle.status(app)
        val file = status.model
        if (!status.ready || !status.int4 || status.config == null || file == null) {
            Log.d(TAG, "GlinerScanWarmup: bundle not ready, skipping model pre-open")
            null
        } else {
            val opened = GlinerOnnxModel.open(app, status, GlinerLaneConfig())
            Log.d(TAG, "GlinerScanWarmup: ONNX session pre-opened")
            PreparedModel(opened, file.length(), file.lastModified())
        }
    } catch (cancel: CancellationException) {
        throw cancel
    } catch (error: Exception) {
        Log.d(TAG, "GlinerScanWarmup: model pre-open failed (${error.javaClass.simpleName}); scan will open it")
        null
    }

    private fun prepareWarmSession(app: Context): TextLabWineExtraction.WarmSession? {
        val session = TextLabWineExtraction.WarmSession(app)
        return try {
            session.knowledgeBase()
            if (session.cachedGazetteer(true) == null) {
                session.cacheGazetteer(true, ProducerGazetteer.openExpanded(app), emptyMap())
            }
            Log.d(TAG, "GlinerScanWarmup: KB + expanded gazetteer pre-opened")
            session
        } catch (cancel: CancellationException) {
            runCatching { session.close() }
            throw cancel
        } catch (error: Exception) {
            runCatching { session.close() }
            Log.d(TAG, "GlinerScanWarmup: warm session failed (${error.javaClass.simpleName}); scan will open its own")
            null
        }
    }

    /**
     * The pre-opened ONNX session, if one is ready (or finishes in time for
     * the caller to simply await it) and still matches the bundle file the
     * caller just verified. Consumes the prepared model.
     */
    suspend fun takeModel(status: GlinerBundleStatus): GlinerOnnxModel? {
        val job = synchronized(this) { modelJob.also { modelJob = null } } ?: return null
        val prepared = try {
            job.await()
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Exception) {
            null
        } ?: return null
        val file = status.model
        if (file == null || file.length() != prepared.bytes || file.lastModified() != prepared.modified) {
            // Bundle changed since pre-open (e.g. lab import): never scan
            // with a session built on a stale file.
            runCatching { prepared.model.close() }
            return null
        }
        return prepared.model
    }

    /** The pre-opened KB/gazetteer session, if ready. Consumes it. */
    suspend fun takeWarmSession(): TextLabWineExtraction.WarmSession? {
        val job = synchronized(this) { warmJob.also { warmJob = null } } ?: return null
        return try {
            job.await()
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Exception) {
            null
        }
    }

    /** Releases anything prepared but unconsumed (cancelled capture). */
    fun discard() {
        val models = synchronized(this) { modelJob.also { modelJob = null } }
        val sessions = synchronized(this) { warmJob.also { warmJob = null } }
        if (models == null && sessions == null) return
        scope.launch {
            models?.let { job -> runCatching { job.await()?.model?.close() } }
            sessions?.let { job -> runCatching { job.await()?.close() } }
        }
    }
}
