package com.yourapp.wine.capture

import android.content.Context
import android.os.SystemClock
import com.yourapp.wine.diagnostics.ScanDiagnostics
import com.yourapp.wine.diagnostics.ScanEngineStatus
import com.yourapp.wine.diagnostics.ScanInputSource
import com.yourapp.wine.diagnostics.ScanStage
import com.yourapp.wine.gliner.GlinerProductionScanner
import com.yourapp.wine.ocr.ScanDeadline
import java.io.File

/** The existing Add Wine scan action uses the same local pipeline as the GLiNER lab. */
internal object ProductionLabelScanner {
    // The measured first result can exceed the old 15-second budget. Includes photo capture/import,
    // model loading, OCR and field extraction, with no second scanner or separate user action.
    const val BUDGET_MS=90_000L

    suspend fun scan(context:Context,photo:File,started:Long,source:ScanInputSource,
        onStage:(ScanStage)->Unit={}):ScanReport {
        val deadline=ScanDeadline(started+BUDGET_MS)
        val attempt=deadline.run {
            GlinerProductionScanner.scan(context,photo,started,source,deadline.expiresAtMs,BUDGET_MS,onStage)
        } ?: GlinerProductionScanner.Attempt.Unavailable("scanner timed out")
        return report(attempt,SystemClock.elapsedRealtime()-started,source,deadline.expired)
    }

    /** Failure must never masquerade as a successful scan by the old scanner. */
    internal fun report(attempt:GlinerProductionScanner.Attempt,elapsedMs:Long,
        source:ScanInputSource,deadlineExpired:Boolean):ScanReport = when(attempt) {
        is GlinerProductionScanner.Attempt.Success -> attempt.report
        is GlinerProductionScanner.Attempt.Unavailable -> ScanReport.red(
            "Could not read the label — retake or enter details manually",elapsedMs,deadlineExpired,source,
            stage=ScanStage.INITIALIZE).copy(diagnostics=ScanDiagnostics(source=source,pipeline="gliner",
                budgetMs=BUDGET_MS,lastStage=ScanStage.INITIALIZE,
                deadlineStage=if(deadlineExpired) ScanStage.INITIALIZE else null,
                engines=listOf(ScanEngineStatus("gliner-small-int4",false,attempt.reason))))
    }
}
