package com.n8rtz.winetracker.scanner

import com.yourapp.wine.capture.ScanReport

sealed interface LabelScanState {
    data object Idle : LabelScanState
    data class CapturingPhoto(val requestId: Long, val shutterMs: Long) : LabelScanState
    data class Processing(val requestId: Long, val photoPath: String, val shutterMs: Long) : LabelScanState
    data class Review(val requestId: Long, val result: ScanReport) : LabelScanState
    data class Error(val requestId: Long, val message: String) : LabelScanState
    data class FinishingCancelledScan(val requestId: Long) : LabelScanState
}
