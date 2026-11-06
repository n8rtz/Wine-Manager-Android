package com.yourapp.wine.capture

import android.content.Context
import android.os.SystemClock
import com.yourapp.wine.diagnostics.ScanEngineStatus
import com.yourapp.wine.diagnostics.ScanInputSource
import com.yourapp.wine.gliner.GlinerProductionScanner
import com.yourapp.wine.ocr.ScanDeadline
import kotlinx.coroutines.CancellationException
import java.io.File

/** Add Wine routes to GLiNER first and keeps the old scanner as an explicit offline fallback. */
internal object ProductionLabelScanner {
    // The measured GLiNER cold first result can exceed the old 15-second legacy OCR budget.
    // This overall deadline includes photo import/capture, model loading, OCR, inference and fallback.
    const val BUDGET_MS=90_000L
    private const val FALLBACK_RESERVE_MS=15_000L

    suspend fun scan(context:Context,photo:File,started:Long,source:ScanInputSource):ScanReport {
        val deadline=ScanDeadline(started+BUDGET_MS)
        val glinerDeadline=deadline.expiresAtMs-FALLBACK_RESERVE_MS
        val attempt=if(SystemClock.elapsedRealtime()<glinerDeadline) {
            ScanDeadline(glinerDeadline).run {
                GlinerProductionScanner.scan(context,photo,started,source,glinerDeadline,BUDGET_MS)
            } ?: GlinerProductionScanner.Attempt.Unavailable("GLiNER timed out")
        } else GlinerProductionScanner.Attempt.Unavailable("not enough time for GLiNER")
        return select(attempt) { reason ->
            val legacy=try {
                deadline.run {
                    val scanner=WineScannerServices.get(context)
                    // The legacy scanner retains its own 15-second bound; it gets a fresh absolute
                    // start but cannot outlive the original Add Wine deadline.
                    scanner.scan(photo,SystemClock.elapsedRealtime(),source)
                }
            } catch(cancel:CancellationException) { throw cancel }
            catch(error:Exception) { null }
            val result=legacy ?: ScanReport.red("GLiNER and the fallback scanner could not finish — retake or enter manually",
                SystemClock.elapsedRealtime()-started,deadline.expired,source)
            val fallbackHint=if(legacy!=null) "GLiNER unavailable; previous offline scanner used. Confirm the results."
                else "GLiNER and the previous offline scanner were unavailable."
            result.copy(elapsedMs=SystemClock.elapsedRealtime()-started,
                fill=result.fill.copy(overallHint=listOfNotNull(fallbackHint,result.fill.overallHint).joinToString(" ")),
                diagnostics=result.diagnostics.copy(pipeline="legacy_fallback",budgetMs=BUDGET_MS,
                    engines=result.diagnostics.engines + ScanEngineStatus("gliner-small-int4",false,reason)))
        }
    }

    /** Kept injectable so fallback selection/cancellation are tested without running OCR. */
    internal suspend fun select(attempt:GlinerProductionScanner.Attempt,
        fallback:suspend (String)->ScanReport):ScanReport = when(attempt) {
        is GlinerProductionScanner.Attempt.Success -> attempt.report
        is GlinerProductionScanner.Attempt.Unavailable -> fallback(attempt.reason)
    }
}
