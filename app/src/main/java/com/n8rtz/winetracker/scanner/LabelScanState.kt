package com.n8rtz.winetracker.scanner

import com.yourapp.wine.capture.ScanReport
import com.yourapp.wine.diagnostics.ScanInputSource
import com.yourapp.wine.diagnostics.ScanStage

sealed interface LabelScanState {
    data object Idle : LabelScanState
    data class CapturingPhoto(val requestId: Long, val shutterMs: Long) : LabelScanState {
        /** No coordinates: elapsed-realtime input creates image-free RED review for a rejected still capture. */
        fun rejected(hint: String, nowMs: Long, deadlineCancelled: Boolean = false, failureType: String? = null): Review = Review(requestId,
            ScanReport.red(hint, (nowMs - shutterMs).coerceAtLeast(0), deadlineCancelled,
                source = ScanInputSource.CAMERA, stage = ScanStage.CAPTURE, failureType = failureType))
    }
    data class ImportingPhoto(val requestId: Long, val startedMs: Long) : LabelScanState
    data class Processing(val requestId: Long, val photoPath: String, val shutterMs: Long,
        val source: ScanInputSource = ScanInputSource.CAMERA,
        val stage: ScanStage = ScanStage.INITIALIZE) : LabelScanState
    data class Review(val requestId: Long, val result: ScanReport) : LabelScanState
    data class Error(val requestId: Long, val message: String) : LabelScanState
    data class FinishingCancelledScan(val requestId: Long) : LabelScanState
}

/** No coordinates: prevents overlapping capture/import requests while native/IO cleanup is still running. */
val LabelScanState.isBusy: Boolean get() = this is LabelScanState.CapturingPhoto ||
    this is LabelScanState.ImportingPhoto || this is LabelScanState.Processing || this is LabelScanState.FinishingCancelledScan

/**
 * Determinate progress for the Add Wine analyzing bar, shared by the camera overlay and the
 * form so both surfaces report the same step. Null means the brief finishing state, which
 * stays indeterminate; non-progress states have no entry here.
 */
val LabelScanState.scanProgressFraction: Float? get() = when (this) {
    is LabelScanState.CapturingPhoto -> 0.05f
    is LabelScanState.ImportingPhoto -> 0.05f
    is LabelScanState.Processing -> when (stage) {
        ScanStage.OCR -> 0.40f
        ScanStage.GLINER -> 0.70f
        ScanStage.FILL -> 0.90f
        else -> 0.15f
    }
    is LabelScanState.FinishingCancelledScan -> null
    else -> null
}

/** One-line step label shown below the Add Wine analyzing bar. Null when nothing is running. */
val LabelScanState.scanStepLabel: String? get() = when (this) {
    is LabelScanState.CapturingPhoto -> "Taking high-quality photo…"
    is LabelScanState.ImportingPhoto -> "Opening selected photo…"
    is LabelScanState.Processing -> when (stage) {
        ScanStage.OCR -> "Reading text from the photo…"
        ScanStage.GLINER -> "Understanding the label…"
        ScanStage.FILL -> "Filling in the wine details…"
        else -> "Preparing the label reader…"
    }
    is LabelScanState.FinishingCancelledScan -> "Finishing the previous scan…"
    else -> null
}
