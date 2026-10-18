package com.yourapp.wine.diagnostics

import com.yourapp.wine.ocr.OcrInputKind
import com.yourapp.wine.ocr.RawToken

/** Scan-worker-owned recorder; samples are bounded separately so localization cannot crowd out label OCR. */
class ScanTraceRecorder(private val now: () -> Long) {
    private var enteredAt = now()
    private var stage = ScanStage.WAIT_FOR_SLOT
    val currentStage: ScanStage get() = stage
    private val timings = mutableListOf<ScanStageTiming>()
    private val failures = mutableListOf<ScanFailure>()
    private val probe = mutableListOf<ScanReadSample>()
    private val label = mutableListOf<ScanReadSample>()
    var totalReadings = 0; private set
    var mappingReceived = 0; private set
    var mappingRejected = 0; private set

    /** No coordinates: close the previous stage using the same monotonic clock as the scan. */
    fun enter(next: ScanStage) {
        closeStage()
        stage = next
        enteredAt = now()
    }

    /** No coordinates: retain only exception type/stage, not messages containing photo paths or content URIs. */
    fun failed(error: Throwable) {
        if (failures.size < 8) failures += ScanFailure(stage, error.javaClass.name.take(160))
    }

    /** Raw working/label pixel provenance in, bounded text/scores out; mapping success is supplied by the caller. */
    fun reading(token: RawToken, inputKind: OcrInputKind, mapped: Boolean? = null) {
        totalReadings++
        if (mapped != null) {
            mappingReceived++
            if (!mapped) mappingRejected++
        }
        val target = if (inputKind == OcrInputKind.WORKING_LOCALIZATION) probe else label
        val limit = if (inputKind == OcrInputKind.WORKING_LOCALIZATION) 30 else 80
        if (target.size < limit) target += ScanReadSample(token.text.take(256), token.score, token.scoreIsEstimated,
            token.engineId.take(40), token.variantId.take(40), inputKind.name, mapped)
    }

    /** No image ownership: finalizes the current stage and freezes bounded lists into the report. */
    fun finish(base: ScanDiagnostics): ScanDiagnostics {
        closeStage()
        return base.copy(lastStage = stage, stages = timings.toList(), failures = failures.toList(),
            reads = (probe + label).toList(), totalReadings = totalReadings,
            mappingReceived = mappingReceived, mappingRejected = mappingRejected)
    }

    private fun closeStage() {
        if (timings.size < 20) timings += ScanStageTiming(stage, (now() - enteredAt).coerceAtLeast(0))
    }
}
