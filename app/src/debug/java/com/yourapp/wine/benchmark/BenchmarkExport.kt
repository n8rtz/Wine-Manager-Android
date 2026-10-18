package com.yourapp.wine.benchmark

import com.yourapp.wine.diagnostics.AndroidScanEnvironment
import com.yourapp.wine.diagnostics.ScanDiagnosticFormatter

object BenchmarkExport {
    fun text(state:LabState):String=buildString {
        appendLine("WINE OCR LAB batch v2; batch=${state.batchId}")
        appendLine("Expected answers are evaluation-only. Blank fields are unscored. CSV has one row per attempted treatment or image failure.")
        appendLine("CSV includes all six expected/actual fields, raw OCR and scores. Leading formula characters are prefixed with an apostrophe for spreadsheet safety.")
        appendLine("Cases share a fixed crop PER IMAGE. Setup is unranked. Eight-second target per case; synchronous native calls can overrun. Exclude deadline/failure rows from accuracy rankings.")
        appendLine("Baseline uses up to three passes; other treatments use one. Raw-name similarity is best 1–3 line match, not full-label CER or confidence. No automatic winning filter is installed.")
        appendLine("PNG preview budget is 64 MiB divided among selected images. Missing previews may reflect this cap. Images are transient; saved CSV history persists until explicitly cleared.")
        state.runs.forEach { run ->
            appendLine("\nIMAGE ${run.id} label=${run.label} status=${run.status} input_sha256=${run.inputHash} base_sha256=${run.baseHash} seed=${run.seed}")
            run.expected.forEach { (field,value) -> appendLine("expected_$field=$value") }
            run.setup?.let { appendLine("[Unranked setup]");appendLine(ScanDiagnosticFormatter.format(it,AndroidScanEnvironment.current(),0)) }
            run.results.forEachIndexed { index,result ->
                appendLine("\nCASE ${index+1} ${result.recipe.id}: ${result.recipe.description}")
                appendLine("status=${result.status}; preparation_preview_ms=${result.preparationMs}; score=${result.score}")
                appendLine(ScanDiagnosticFormatter.format(result.report,AndroidScanEnvironment.current(),index+1L))
            }
        }
    }
}
