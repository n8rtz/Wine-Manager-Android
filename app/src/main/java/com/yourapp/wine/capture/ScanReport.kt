package com.yourapp.wine.capture

import android.graphics.PointF
import com.yourapp.wine.diagnostics.ScanDiagnostics
import com.yourapp.wine.diagnostics.ScanFailure
import com.yourapp.wine.diagnostics.ScanInputSource
import com.yourapp.wine.diagnostics.ScanStage
import com.yourapp.wine.fill.FillResult
import com.yourapp.wine.ocr.EngineTiming
import com.yourapp.wine.ocr.TokenRecord

/** No image buffers/Mats retained: overlay boxes are original normalized coordinates, quad points original pixels. */
data class ScanReport(val fill: FillResult, val tokens: List<TokenRecord>, val origW: Int, val origH: Int,
    val rectW: Int, val rectH: Int, val quadOrig: List<PointF>?, val homography: List<Double>?,
    val timings: List<EngineTiming>, val elapsedMs: Long, val deadlineCancelled: Boolean,
    val peakWorkingBitmaps: Int, val workingBitmapsAfter: Int = 0,
    val diagnostics: ScanDiagnostics = ScanDiagnostics()) {
    companion object {
        /** No image coordinates/buffers: startup/capture failure is explicit RED in all six fields, never silent fill. */
        fun red(hint: String, elapsedMs: Long = 0, deadlineCancelled: Boolean = false,
                source: ScanInputSource = ScanInputSource.FILE, stage: ScanStage = ScanStage.INITIALIZE,
                failureType: String? = null): ScanReport = ScanReport(
            FillResult(com.yourapp.wine.fill.FieldId.entries.associateWith {
                com.yourapp.wine.fill.FieldCandidate(it, "", com.yourapp.wine.fill.UiState.RED, hint, emptyList())
            }, hint), emptyList(), 1, 1, 1, 1, null, null, emptyList(), elapsedMs, deadlineCancelled, 0,
            diagnostics = ScanDiagnostics(source = source, lastStage = stage,
                deadlineStage = if (deadlineCancelled) stage else null,
                failures = failureType?.let { listOf(ScanFailure(stage, it.take(160))) }.orEmpty()))
    }
}
