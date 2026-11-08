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
        val step: LabelScanProgressStep = LabelScanProgressStep.PREPARE_READER) : LabelScanState
    data class Review(val requestId: Long, val result: ScanReport, val photoPath: String? = null) : LabelScanState
    data class Error(val requestId: Long, val message: String) : LabelScanState
    data class FinishingCancelledScan(val requestId: Long) : LabelScanState
}

/** No coordinates: prevents overlapping capture/import requests while native/IO cleanup is still running. */
val LabelScanState.isBusy: Boolean get() = this is LabelScanState.CapturingPhoto ||
    this is LabelScanState.ImportingPhoto || this is LabelScanState.Processing || this is LabelScanState.FinishingCancelledScan

/**
 * User-facing progress through photo analysis. Separate from the diagnostics [ScanStage]
 * vocabulary: every step is a real pipeline event, worded for the Add Wine status line.
 */
enum class LabelScanProgressStep {
    PREPARE_READER,
    LOAD_READER,
    PREPARE_PHOTO,
    READ_TEXT,
    CHECK_READ,
    UNDERSTAND_LABEL,
    FILL_DETAILS,
}

/**
 * Determinate progress for the Add Wine analyzing bar, shared by the camera overlay and the
 * form so both surfaces report the same step. Null means the brief finishing state, which
 * stays indeterminate; non-progress states have no entry here.
 */
val LabelScanState.scanProgressFraction: Float? get() = when (this) {
    is LabelScanState.CapturingPhoto -> 0.05f
    is LabelScanState.ImportingPhoto -> 0.05f
    is LabelScanState.Processing -> when (step) {
        LabelScanProgressStep.PREPARE_READER -> 0.12f
        LabelScanProgressStep.LOAD_READER -> 0.22f
        LabelScanProgressStep.PREPARE_PHOTO -> 0.32f
        LabelScanProgressStep.READ_TEXT -> 0.46f
        LabelScanProgressStep.CHECK_READ -> 0.60f
        LabelScanProgressStep.UNDERSTAND_LABEL -> 0.74f
        LabelScanProgressStep.FILL_DETAILS -> 0.90f
    }
    is LabelScanState.FinishingCancelledScan -> null
    else -> null
}

/** One-line step label shown below the Add Wine analyzing bar. Null when nothing is running. */
val LabelScanState.scanStepLabel: String? get() = when (this) {
    is LabelScanState.CapturingPhoto -> "Taking high-quality photo…"
    is LabelScanState.ImportingPhoto -> "Opening selected photo…"
    is LabelScanState.Processing -> when (step) {
        LabelScanProgressStep.PREPARE_READER -> "Getting the label reader ready…"
        LabelScanProgressStep.LOAD_READER -> "Loading the label reader…"
        LabelScanProgressStep.PREPARE_PHOTO -> "Preparing the photo…"
        LabelScanProgressStep.READ_TEXT -> "Reading the label text…"
        LabelScanProgressStep.CHECK_READ -> "Checking with a second read…"
        LabelScanProgressStep.UNDERSTAND_LABEL -> "Understanding the label…"
        LabelScanProgressStep.FILL_DETAILS -> "Filling in the wine details…"
    }
    is LabelScanState.FinishingCancelledScan -> "Finishing the previous scan…"
    else -> null
}
