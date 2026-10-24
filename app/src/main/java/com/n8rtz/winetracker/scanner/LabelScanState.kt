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
        val source: ScanInputSource = ScanInputSource.CAMERA) : LabelScanState
    data class Review(val requestId: Long, val result: ScanReport) : LabelScanState
    data class Error(val requestId: Long, val message: String) : LabelScanState
    data class FinishingCancelledScan(val requestId: Long) : LabelScanState
}

/** No coordinates: prevents overlapping capture/import requests while native/IO cleanup is still running. */
val LabelScanState.isBusy: Boolean get() = this is LabelScanState.CapturingPhoto ||
    this is LabelScanState.ImportingPhoto || this is LabelScanState.Processing || this is LabelScanState.FinishingCancelledScan
