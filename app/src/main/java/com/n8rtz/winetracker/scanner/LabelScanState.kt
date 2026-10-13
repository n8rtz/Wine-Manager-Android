package com.n8rtz.winetracker.scanner

sealed interface LabelScanState {
    data object Idle : LabelScanState
    data class CapturingPhoto(val requestId: Long) : LabelScanState
    data class Processing(val requestId: Long, val photoPath: String) : LabelScanState
    data class Review(val requestId: Long, val result: ScannedWine) : LabelScanState
    data class Error(val requestId: Long, val message: String) : LabelScanState
    data class FinishingCancelledScan(val requestId: Long) : LabelScanState
}
