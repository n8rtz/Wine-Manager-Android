package com.n8rtz.winetracker.scanner

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import com.yourapp.wine.capture.LabelPhotoTooLargeException
import com.yourapp.wine.capture.LocalLabelPhoto
import com.yourapp.wine.capture.ProductionLabelScanner
import com.yourapp.wine.capture.ScanReport
import com.yourapp.wine.capture.SelectedLabelPhotoImporter
import com.yourapp.wine.diagnostics.ScanInputSource
import com.yourapp.wine.diagnostics.ScanStage
import com.yourapp.wine.diagnostics.ScanStageTiming
import com.yourapp.wine.ocr.ScanDeadline
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/** Process-scoped scan ownership, independent of a screen or Activity ViewModel. */
internal object ProductionLabelScanSession {
    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutable = MutableStateFlow<LabelScanState>(LabelScanState.Idle)
    val state = mutable.asStateFlow()
    private var initialized = false
    private var job: Job? = null
    // Allows deterministic lifecycle tests without invoking native OCR. Never set by the app.
    @Volatile internal var scannerForTesting: (suspend (Context, File, Long, ScanInputSource) -> ScanReport)? = null
    private var failedServiceRequest: Long? = null
    private var nextId = System.currentTimeMillis()
    /** Single retained scan photo for the post-scan review display; replaced every scan. */
    private const val REVIEW_PHOTO_NAME = "label-scan-review.jpg"
    /**
     * Shutter-phase budget: capture plus the user's confirm/retake photo review. Separate from the
     * shared 15 s capture deadline the legacy scanner still uses; scan work has its own 90 s budget.
     */
    private const val CAPTURE_BUDGET_MS = 60_000L

    private fun initialize(context: Context) {
        if (!initialized) {
            mutable.value = PendingLabelScanStore(context).result() ?: LabelScanState.Idle
            initialized = true
        }
    }

    fun connect(context: Context) = synchronized(lock) {
        val app = context.applicationContext
        initialize(app)
        // If Android stopped the process during inference, the private input is retained and the
        // foreground service retries it when the user next opens the app.
        if (mutable.value is LabelScanState.Idle && PendingLabelScanStore(app).active() != null &&
            !LabelScanService.start(app)) {
            mutable.value = LabelScanState.Error(++nextId, "Could not resume the scan — retake or enter manually")
        }
    }

    fun requestCapture(context: Context): Boolean = synchronized(lock) {
        val app = context.applicationContext
        initialize(app)
        if (mutable.value !is LabelScanState.Idle && mutable.value !is LabelScanState.Error) {
            Log.d("ProductionLabelScanSession", "requestCapture rejected: state=${mutable.value}")
            return false
        }
        if (!LabelScanService.start(app)) {
            Log.w("ProductionLabelScanSession", "requestCapture: LabelScanService.start failed")
            mutable.value = LabelScanState.Error(++nextId, "Could not start the scan — try again or enter details manually")
            return false
        }
        val id = ++nextId
        Log.d("ProductionLabelScanSession", "requestCapture accepted: id=$id")
        mutable.value = LabelScanState.CapturingPhoto(id, SystemClock.elapsedRealtime())
        scope.launch {
            delay(CAPTURE_BUDGET_MS)
            synchronized(lock) {
                val state = mutable.value
                if (state is LabelScanState.CapturingPhoto && state.requestId == id) {
                    val report = state.rejected("The photo took too long — retake or enter details manually",
                        SystemClock.elapsedRealtime(), deadlineCancelled = true)
                    saveResult(app, report.requestId, report.result)
                    LabelScanService.stop(app)
                }
            }
        }
        true
    }

    fun captured(context: Context, id: Long, file: File) {
        val app = context.applicationContext
        val started = synchronized(lock) {
            val current = mutable.value
            if (current is LabelScanState.CapturingPhoto && current.requestId == id) {
                if (!PendingLabelScanStore(app).saveActive(id, file, ScanInputSource.CAMERA.name)) {
                    mutable.value = LabelScanState.Error(id, "Could not prepare the photo — retake or enter details manually")
                    LabelScanService.stop(app)
                    null
                } else {
                    mutable.value = LabelScanState.Processing(id, file.absolutePath, current.shutterMs)
                    current.shutterMs
                }
            } else null
        }
        if (started == null) {
            Log.w("ProductionLabelScanSession", "Stale capture $id ignored (state=${mutable.value}); deleting " + file.name)
            file.delete()
            return
        }
        launchPhotoScan(app, id, started, ScanInputSource.CAMERA, file) { LocalLabelPhoto(file, file.length()) }
    }

    fun selected(context: Context, uri: Uri): Boolean = synchronized(lock) {
        val app = context.applicationContext
        initialize(app)
        if (mutable.value !is LabelScanState.Idle && mutable.value !is LabelScanState.Error) return false
        if (!LabelScanService.start(app)) {
            mutable.value = LabelScanState.Error(++nextId, "Could not start the scan — try again or enter details manually")
            return false
        }
        val id = ++nextId
        val started = SystemClock.elapsedRealtime()
        mutable.value = LabelScanState.ImportingPhoto(id, started)
        launchPhotoScan(app, id, started, ScanInputSource.PHOTO_LIBRARY) { SelectedLabelPhotoImporter(app).copy(uri) }
        true
    }

    private fun launchPhotoScan(app: Context, id: Long, started: Long, source: ScanInputSource,
        initialFile: File? = null, obtain: suspend () -> LocalLabelPhoto) {
        // A new scan drops the previous review photo; at most one is ever retained.
        runCatching { File(app.cacheDir, REVIEW_PHOTO_NAME).takeIf { it.exists() }?.delete() }
        // Install the job before dispatch so cancellation cannot race an as-yet unassigned job.
        val launched = scope.launch(start = CoroutineStart.LAZY) {
            var ownedFile: File? = initialFile
            var stage = if (source == ScanInputSource.PHOTO_LIBRARY) ScanStage.IMPORT else ScanStage.INITIALIZE
            var completed: ScanReport? = null
            try {
                val photo = ScanDeadline(started + ProductionLabelScanner.BUDGET_MS).run { obtain().also { ownedFile = it.file } }
                if (photo == null) {
                    completed = ScanReport.red("Time budget reached while opening the photo — choose again or enter manually",
                        SystemClock.elapsedRealtime() - started, true, source, stage)
                    return@launch
                }
                val preparationMs = SystemClock.elapsedRealtime() - started
                val admitted = synchronized(lock) {
                    val current = mutable.value
                    if ((current is LabelScanState.ImportingPhoto && current.requestId == id) ||
                        (current is LabelScanState.Processing && current.requestId == id)) {
                        check(PendingLabelScanStore(app).saveActive(id, photo.file, source.name)) { "Cannot save scan recovery state" }
                        mutable.value = LabelScanState.Processing(id, photo.file.absolutePath, started, source)
                        true
                    } else false
                }
                if (!admitted) return@launch
                stage = ScanStage.INITIALIZE
                val result = scannerForTesting?.invoke(app, photo.file, started, source)
                    ?: ProductionLabelScanner.scan(app, photo.file, started, source) { reported ->
                        // Real pipeline progress for the Add Wine analyzing bar; stale reports
                        // from a superseded request are ignored.
                        synchronized(lock) {
                            val current = mutable.value
                            if (current is LabelScanState.Processing && current.requestId == id) {
                                mutable.value = current.copy(step = reported)
                            }
                        }
                    }
                val importMs = if (source == ScanInputSource.PHOTO_LIBRARY) preparationMs else 0L
                completed = result.copy(diagnostics = result.diagnostics.copy(source = source,
                    inputBytes = photo.bytes, importMs = importMs,
                    stages = listOf(ScanStageTiming(if (source == ScanInputSource.PHOTO_LIBRARY)
                        ScanStage.IMPORT else ScanStage.CAPTURE, preparationMs)) + result.diagnostics.stages))
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                Log.w("ProductionLabelScanSession", "Label scan failed", error)
                val elapsed = SystemClock.elapsedRealtime() - started
                val timedOut = elapsed >= ProductionLabelScanner.BUDGET_MS
                val hint = when {
                    timedOut -> "Time budget reached while reading the photo — choose again or enter manually"
                    error is LabelPhotoTooLargeException -> "Selected photo exceeds 32 MiB — choose a smaller image"
                    stage == ScanStage.IMPORT -> "Could not open the selected photo — choose another image or enter manually"
                    else -> "Could not read the label — retake or enter details manually"
                }
                completed = ScanReport.red(hint, elapsed, timedOut, source, stage, error.javaClass.name)
            } catch (error: LinkageError) {
                completed = ScanReport.red("Scanner unavailable — retake or enter details manually",
                    SystemClock.elapsedRealtime() - started, source = source, stage = stage, failureType = error.javaClass.name)
            } catch (error: OutOfMemoryError) {
                completed = ScanReport.red("Not enough memory — choose a smaller photo or enter details manually",
                    SystemClock.elapsedRealtime() - started, source = source, stage = stage, failureType = error.javaClass.name)
            } finally {
                // A finished scan (readable or not) keeps its photo for the review display; a
                // cancelled one deletes it exactly as before. Persist after retaining so the
                // stored result carries the photo path, but publish only after cleanup so
                // another scan cannot start while this job is still removing files or stopping FGS.
                val reviewPhotoPath = if (completed != null) {
                    ownedFile?.takeIf { it.isFile }?.let { retainReviewPhoto(app, it) }
                } else null
                ownedFile?.let { if (it.exists() && !it.delete()) Log.w("ProductionLabelScanSession", "Could not delete private input") }
                val saved = synchronized(lock) {
                    val current = mutable.value
                    val owns = (current is LabelScanState.ImportingPhoto && current.requestId == id) ||
                        (current is LabelScanState.Processing && current.requestId == id)
                    if (owns && completed != null) PendingLabelScanStore(app).saveResult(id, completed!!, reviewPhotoPath) else false
                }
                PendingLabelScanStore(app).clearActive()
                LabelScanService.stop(app)
                finish(app, id, completed, saved, reviewPhotoPath)
            }
        }
        launched.invokeOnCompletion {
            initialFile?.delete()
            PendingLabelScanStore(app).clearActive(id)
            synchronized(lock) {
                if (mutable.value is LabelScanState.FinishingCancelledScan &&
                    (mutable.value as LabelScanState.FinishingCancelledScan).requestId == id) {
                    LabelScanService.stop(app)
                }
            }
            finish(app, id, null, false)
        }
        synchronized(lock) {
            job = launched
            val current = mutable.value
            val owns = (current is LabelScanState.ImportingPhoto && current.requestId == id) ||
                (current is LabelScanState.Processing && current.requestId == id)
            if (owns) launched.start() else launched.cancel()
        }
    }

    /** Moves the finished scan's photo to the single retained review slot. Never fails the scan. */
    private fun retainReviewPhoto(app: Context, file: File): String? = runCatching {
        val retained = File(app.cacheDir, REVIEW_PHOTO_NAME)
        runCatching { if (retained.exists()) retained.delete() }
        if (!file.renameTo(retained)) {
            file.inputStream().use { input -> retained.outputStream().use { output -> input.copyTo(output) } }
            if (!file.delete()) Log.w("ProductionLabelScanSession", "Could not delete private input")
        }
        retained.takeIf { it.isFile }?.absolutePath
    }.getOrNull()

    private fun saveResult(app: Context, id: Long, result: ScanReport, photoPath: String? = null) {
        mutable.value = if (PendingLabelScanStore(app).saveResult(id, result, photoPath)) LabelScanState.Review(id, result, photoPath)
            else LabelScanState.Error(id, "Could not save the scan result — try again or enter details manually")
    }
    private fun finish(app: Context, id: Long, result: ScanReport?, saved: Boolean, reviewPhotoPath: String? = null) = synchronized(lock) {
        val current = mutable.value
        val owns = (current is LabelScanState.ImportingPhoto && current.requestId == id) ||
            (current is LabelScanState.Processing && current.requestId == id)
        when {
            owns && result != null && saved -> mutable.value = LabelScanState.Review(id, result, reviewPhotoPath)
            owns && result != null -> mutable.value = LabelScanState.Error(id, "Could not save the scan result — try again or enter details manually")
            owns -> mutable.value = LabelScanState.Error(id, "Scan cancelled — retake or choose another photo")
            current is LabelScanState.FinishingCancelledScan && current.requestId == id -> {
                mutable.value = if (failedServiceRequest == id) LabelScanState.Error(id,
                    "Could not keep the scan running — try again or enter manually") else LabelScanState.Idle
                if (failedServiceRequest == id) failedServiceRequest = null
            }
        }
    }

    fun captureFailed(context: Context, id: Long, error: Throwable) = synchronized(lock) {
        val current = mutable.value
        if (current is LabelScanState.CapturingPhoto && current.requestId == id) {
            val report = current.rejected("Could not take the photo — retake or enter details manually",
                SystemClock.elapsedRealtime(), failureType = error.javaClass.name)
            saveResult(context.applicationContext, id, report.result)
            LabelScanService.stop(context.applicationContext)
        }
    }

    fun dismiss(context: Context, id: Long) = synchronized(lock) {
        val app = context.applicationContext
        when (val current = mutable.value) {
            is LabelScanState.CapturingPhoto -> if (current.requestId == id) { mutable.value = LabelScanState.Idle; LabelScanService.stop(app) }
            is LabelScanState.ImportingPhoto -> if (current.requestId == id) { mutable.value = LabelScanState.FinishingCancelledScan(id); job?.cancel() }
            is LabelScanState.Processing -> if (current.requestId == id) { mutable.value = LabelScanState.FinishingCancelledScan(id); job?.cancel() }
            is LabelScanState.Review -> if (current.requestId == id) { PendingLabelScanStore(app).clearResult(id); mutable.value = LabelScanState.Idle }
            is LabelScanState.Error -> if (current.requestId == id) mutable.value = LabelScanState.Idle
            else -> Unit
        }
    }

    /** Lifecycle changes cancel an unfinished shutter only; scans already reading continue. */
    fun cancelPendingCapture(context: Context) {
        val id = synchronized(lock) { (mutable.value as? LabelScanState.CapturingPhoto)?.requestId }
        if (id != null) dismiss(context, id)
    }
    fun cancelActive(context: Context) {
        val id = synchronized(lock) { when (val state = mutable.value) {
            is LabelScanState.CapturingPhoto -> state.requestId
            is LabelScanState.ImportingPhoto -> state.requestId
            is LabelScanState.Processing -> state.requestId
            is LabelScanState.Review -> state.requestId
            is LabelScanState.Error -> state.requestId
            else -> null
        } }
        if (id != null) dismiss(context, id)
    }
    fun serviceFailed() {
        synchronized(lock) {
            when (val state = mutable.value) {
                is LabelScanState.CapturingPhoto -> mutable.value = LabelScanState.Error(state.requestId,
                    "Could not keep the scan running — try again or enter manually")
                is LabelScanState.ImportingPhoto -> {
                    failedServiceRequest = state.requestId
                    mutable.value = LabelScanState.FinishingCancelledScan(state.requestId)
                    job?.cancel()
                }
                is LabelScanState.Processing -> {
                    failedServiceRequest = state.requestId
                    mutable.value = LabelScanState.FinishingCancelledScan(state.requestId)
                    job?.cancel()
                }
                else -> Unit
            }
        }
    }
    /** Android may recreate the sticky service after process eviction; retry a copied private photo. */
    fun recover(context: Context) = synchronized(lock) {
        val app = context.applicationContext
        initialize(app)
        if (mutable.value is LabelScanState.Review) {
            val active = PendingLabelScanStore(app).active()
            val review = mutable.value as LabelScanState.Review
            if (active?.requestId == review.requestId) {
                val oldFile = File(active.path)
                if (oldFile.parentFile?.canonicalFile == app.cacheDir.canonicalFile) oldFile.delete()
                PendingLabelScanStore(app).clearActive()
            }
            LabelScanService.stop(app)
            return
        }
        if (mutable.value !is LabelScanState.Idle) return
        val stored = PendingLabelScanStore(app).active() ?: run { LabelScanService.stop(app); return }
        val file = File(stored.path)
        if (!file.isFile || file.parentFile?.canonicalFile != app.cacheDir.canonicalFile) {
            PendingLabelScanStore(app).clearActive()
            mutable.value = LabelScanState.Error(stored.requestId, "Scan interrupted — retake or choose another photo")
            LabelScanService.stop(app)
            return
        }
        val source = runCatching { ScanInputSource.valueOf(stored.source) }.getOrDefault(ScanInputSource.CAMERA)
        nextId = maxOf(nextId, stored.requestId)
        val started = SystemClock.elapsedRealtime()
        mutable.value = LabelScanState.Processing(stored.requestId, file.absolutePath, started, source)
        launchPhotoScan(app, stored.requestId, started, source, file) { LocalLabelPhoto(file, file.length()) }
    }
}
