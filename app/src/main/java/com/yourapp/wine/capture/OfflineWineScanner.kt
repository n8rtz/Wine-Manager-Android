package com.yourapp.wine.capture

import android.os.SystemClock
import android.os.Debug
import android.util.Log
import com.yourapp.wine.diagnostics.*
import com.yourapp.wine.fill.ConfidenceContext
import com.yourapp.wine.fill.ResilientFillEngine
import com.yourapp.wine.fill.UiState
import com.yourapp.wine.kb.WineKnowledgeBase
import com.yourapp.wine.ocr.*
import com.yourapp.wine.geom.*
import com.yourapp.wine.preprocess.Preprocess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Year

/** Offline capture orchestration only: geometry, preprocessing, OCR and confidence live in small modules. */
class OfflineWineScanner(private val kb: WineKnowledgeBase, private val engines: List<OcrEngine>) {
    private val fills = ResilientFillEngine(kb)
    private val scans = Semaphore(1)

    /** JPEG original coordinates are EXIF-upright; shutterMs MUST be elapsedRealtime at the shutter request. */
    suspend fun scan(file: File, shutterMs: Long, source: ScanInputSource = ScanInputSource.FILE): ScanReport = withContext(Dispatchers.Default) {
        val trace = ScanTraceRecorder(SystemClock::elapsedRealtime)
        var diagnostics = ScanDiagnostics(source = source, inputBytes = file.length(),
            kbImplementation = kb.javaClass.simpleName,
            engines = engines.map { ScanEngineStatus(it.id, it.available, (it as? NotAvailable)?.reason) })
        val deadline = ScanDeadline(shutterMs + ScanDeadline.BUDGET_MS - POSTPROCESS_RESERVE_MS)
        val records = mutableListOf<TokenRecord>(); val timings = mutableListOf<EngineTiming>()
        val workspace = BitmapWorkspace()
        var originalW = 1; var originalH = 1; var rectW = 1; var rectH = 1
        var workW = 1; var workH = 1
        val completedProbe = mutableListOf<RawToken>()
        var quad: List<android.graphics.PointF>? = null
        var homography: Matrix3? = null
        var frameGeom: FrameGeom? = null
        var hint: String? = null; var weakQuality = false; var qualityFailed = false
        var deadlineCancelled = false; var failed = false
        val ensemble = OcrEnsemble(engines, onTiming = { timings += it })
        try {
            val completed = deadline.run {
                scans.withPermit {
                    try {
                        currentCoroutineContext().ensureActive()
                        trace.enter(ScanStage.NATIVE_LOAD)
                        OpenCvRuntime.ensureLoaded()
                        diagnostics = diagnostics.copy(nativeReady = true, nativeVersion = org.opencv.core.Core.VERSION)
                        trace.enter(ScanStage.DECODE)
                        val capture = CaptureDecoder().decode(file, workspace)
                        val working = capture.bitmap
                        originalW = capture.origW; originalH = capture.origH
                        workW = working.width; workH = working.height
                        diagnostics = diagnostics.copy(workW = workW, workH = workH)
                        trace.enter(ScanStage.QUALITY)
                        val qualityGate = ImageQualityGate()
                        val quality = qualityGate.assess(working)
                        diagnostics = diagnostics.copy(quality = ScanQualityStats(quality.state.name,
                            quality.laplacianVariance, quality.meanLuminance))
                        hint = quality.hint; weakQuality = quality.state == UiState.YELLOW
                        if (quality.state == UiState.RED) { qualityFailed = true; return@withPermit }
                        var geom = FrameGeom(originalW, originalH, working.width, working.height,
                            working.width.toFloat() / originalW, null, null)
                        currentCoroutineContext().ensureActive()
                        if (deadline.expired) { deadlineCancelled = true; return@withPermit }
                        trace.enter(ScanStage.LOCALIZE)
                        quad = LabelQuadDetector().detect(working, geom)
                        diagnostics = diagnostics.copy(quadSource = if (quad != null) "contour" else "none")
                        var probe: List<RawToken>? = null
                        if (quad == null && !deadline.expired) {
                            // Only fallback localization OCRs the scene. It is never the primary field-reading path.
                            val cheap = OcrEnsemble(engines.filter { it.id == "mlkit" }, onTiming = { timings += it })
                                .run(listOf(OcrVariant("probe", { working }, {})),
                                    ScanDeadline(minOf(deadline.expiresAtMs, SystemClock.elapsedRealtime() + 1500)),
                                    onTokens = { reads ->
                                        completedProbe += reads
                                        reads.forEach { trace.reading(it, OcrInputKind.WORKING_LOCALIZATION) }
                                    }, inputKind = OcrInputKind.WORKING_LOCALIZATION)
                            if (!cheap.deadlineCancelled) probe = cheap.tokens
                            quad = TextHullQuadDetector().detect(cheap.tokens, geom)
                            diagnostics = diagnostics.copy(quadSource = if (quad != null) "text_hull" else "full_frame_fallback")
                        }
                        if (quad != null && qualityGate.labelTooSmall(quad!!, originalW, originalH)) {
                            qualityFailed = true; hint = "Move closer"; return@withPermit
                        }
                        currentCoroutineContext().ensureActive()
                        if (deadline.expired) { deadlineCancelled = true; return@withPermit }
                        geom = geom.copy(labelQuadOrig = quad)
                        trace.enter(ScanStage.WARP)
                        val rectified = Warp.perspective(working, geom, workspace)
                        frameGeom = rectified.geom; homography = rectified.originalToRect
                        rectW = rectified.bitmap.width; rectH = rectified.bitmap.height
                        // Free the working scene BEFORE allocating any rectified preprocess variant.
                        workspace.release(working)
                        val mapper = CoordMapper(rectified.originalToRect, originalW, originalH, rectW, rectH)
                        val workToRect = rectified.originalToRect * Matrix3.scale(originalW.toDouble() / geom.workW,
                            originalH.toDouble() / geom.workH)
                        val reuse = if (quad == null) probe?.map { it.copy(polygon = it.polygon.map(workToRect::map), variantId = "V0") } else null
                        val inputKind = if (quad != null) OcrInputKind.RECTIFIED_LABEL else OcrInputKind.FULL_FRAME_FALLBACK
                        trace.enter(ScanStage.OCR)
                        val batch = ensemble.run(Preprocess().variants(rectified.bitmap, workspace), deadline, reuseMlkitV0 = reuse,
                            onTokens = { raw -> raw.forEach { token ->
                                val mapped = mapper.toOriginal(token)
                                trace.reading(token, inputKind, mapped != null)
                                if (mapped != null) records += mapped
                            } }, inputKind = inputKind)
                        deadlineCancelled = deadlineCancelled || batch.deadlineCancelled
                    } finally {
                        // Close BEFORE releasing the app-scoped scan permit: parallel callers must
                        // never overlap their two-bitmap workspaces, including the cancellation path.
                        try { frameGeom?.H_origToRect?.release(); frameGeom = null } finally { workspace.close() }
                    }
                }
            }
            deadlineCancelled = deadlineCancelled || completed == null || deadline.expired
        } catch (cancel: CancellationException) {
            throw cancel // lifecycle/user cancellation is not a successful scan
        } catch (error: Exception) {
            trace.failed(error)
            failed = true; hint = "Could not finish reading — confirm partial results, retake or enter manually"
            Log.w("WineScanner", "Keeping mapped partial evidence after scan failure", error)
        } catch (error: LinkageError) {
            trace.failed(error)
            if (diagnostics.nativeReady != true) diagnostics = diagnostics.copy(nativeReady = false)
            failed = true; hint = "Native scanner unavailable — enter manually or rebuild with OpenCV"
            Log.e("WineScanner", "Native build/load failure", error)
        } catch (error: OutOfMemoryError) {
            trace.failed(error)
            failed = true; hint = "Memory pressure — retake or enter manually"
            Log.e("WineScanner", "Bounded scan could not allocate; releasing buffers", error)
        } finally {
            try { frameGeom?.H_origToRect?.release() } finally { workspace.close() }
        }
        if (deadlineCancelled) diagnostics = diagnostics.copy(deadlineStage = trace.currentStage)
        if (!qualityFailed && records.isEmpty() && completedProbe.isNotEmpty()) {
            // A localization pass completed, but later work timed out/failed. Keep mapped partial
            // evidence, not an unmapped crop or a silent empty result. Prefer the actual label H.
            var originalToRect = homography
            if (originalToRect == null) {
                quad = null // no completed rectification, so the full-frame fallback is YELLOW max
                val scale = minOf(1.0, 1280.0 / workW, 1920.0 / maxOf(workW, workH))
                rectW = (workW * scale).toInt().coerceAtLeast(1); rectH = (workH * scale).toInt().coerceAtLeast(1)
                originalToRect = Matrix3.scale(rectW.toDouble() / originalW, rectH.toDouble() / originalH)
                homography = originalToRect
            }
            val workToRect = originalToRect * Matrix3.scale(originalW.toDouble() / workW, originalH.toDouble() / workH)
            val mapper = CoordMapper(originalToRect, originalW, originalH, rectW, rectH)
            records += completedProbe.mapNotNull { token ->
                try { token.copy(polygon = token.polygon.map(workToRect::map)) }
                catch (_: IllegalArgumentException) { null }
            }
                .filter { token -> token.polygon.all { it.x in 0f..rectW.toFloat() && it.y in 0f..rectH.toFloat() } }
                .mapNotNull(mapper::toOriginal)
            diagnostics = diagnostics.copy(recoveredProbeReadings = records.size)
            weakQuality = true
            hint = hint ?: "Only a mapped localization pass finished — confirm partial results or retake"
        }
        trace.enter(ScanStage.FILL)
        var fill = if (qualityFailed) fills.red(hint ?: "Retake or enter manually") else
            fills.fill(records, Year.now().value, ConfidenceContext(labelFound = quad != null,
                deadlineReached = deadlineCancelled, weakQuality = weakQuality || failed,
                hint = hint ?: if (deadlineCancelled) "Time budget reached — confirm partial results or retake" else null))
        val elapsed = SystemClock.elapsedRealtime() - shutterMs
        if (elapsed >= ScanDeadline.BUDGET_MS && !qualityFailed) {
            deadlineCancelled = true
            diagnostics = diagnostics.copy(deadlineStage = diagnostics.deadlineStage ?: ScanStage.FILL)
            fill = fill.copy(fields = fill.fields.mapValues { (_, candidate) ->
                if (candidate.state == UiState.GREEN) candidate.copy(state = UiState.YELLOW,
                    reason = candidate.reason + "; deadline reached during postprocessing — confirm") else candidate
            }, overallHint = "Time budget reached — confirm partial results or retake")
        }
        Log.d("WineScanner", "scan ms=$elapsed tokens=${records.size} peakBitmaps=${workspace.peakCount} alive=${workspace.aliveCount} deadlineCancelled=$deadlineCancelled javaHeap=${Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()} nativeHeap=${Debug.getNativeHeapAllocatedSize()}")
        fill.fields.values.forEach { Log.d("WineScanner", "${it.field} ${it.state}: ${it.reason}") }
        ScanReport(fill, records.toList(), originalW, originalH, rectW, rectH, quad, homography?.values,
            timings.toList(), elapsed, deadlineCancelled, workspace.peakCount, workspace.aliveCount,
            diagnostics = trace.finish(diagnostics))
    }

    companion object { const val POSTPROCESS_RESERVE_MS = 300L }
}
