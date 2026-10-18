package com.yourapp.wine.diagnostics

import android.graphics.RectF
import com.yourapp.wine.capture.ScanReport
import com.yourapp.wine.fill.FieldId
import com.yourapp.wine.fill.UiState
import com.yourapp.wine.merge.TokenMerger
import java.util.Locale

/** Plain text only. Format on Default after the scan; no model, image, database, or network access. */
object ScanDiagnosticFormatter {
    const val MAX_CHARS = 32_000

    /** Original/rectified normalized evidence in; bounded shareable text out. Never accepts current manual form values. */
    fun format(report: ScanReport, environment: ScanEnvironment, scanId: Long): String {
        val out = ReportText()
        val trace = report.diagnostics
        out.line("WINE-SCAN-DIAGNOSTICS v1")
        out.line("Contains label OCR text and non-unique device/build information. Review before sharing.")
        out.line("No photo, content URI, GPS metadata, account data or manual cellar entries are attached.")
        out.line("scan_id=$scanId input=${trace.source} outcome=${outcome(report)}")
        out.line("app=${safe(environment.appVersion)} code=${environment.appVersionCode}")
        out.line("commit=${safe(environment.buildCommit)} branch=${safe(environment.buildBranch)} working_tree=${safe(environment.workingTree)}")
        out.line("android_sdk=${environment.androidSdk} release=${safe(environment.androidRelease)}")
        out.line("device=${safe(environment.manufacturer)} ${safe(environment.model)} hardware=${safe(environment.hardware)}")
        out.line("device_abis=${safe(environment.deviceAbis.joinToString())} packaged_abis=${safe(environment.packagedAbis)}")
        out.line()
        out.line("[Pipeline]")
        out.line("elapsed_ms=${report.elapsedMs} budget_ms=8000 deadline_cancelled=${report.deadlineCancelled} deadline_stage=${trace.deadlineStage} last_stage=${trace.lastStage}")
        out.line("input_bytes=${trace.inputBytes} import_ms=${trace.importMs}")
        out.line("original_upright=${report.origW}x${report.origH} working=${trace.workW}x${trace.workH} rectified=${report.rectW}x${report.rectH}")
        out.line("native_ready=${trace.nativeReady ?: "not_attempted"} opencv=${safe(trace.nativeVersion)} kb=${safe(trace.kbImplementation)}")
        out.line("quad_source=${safe(trace.quadSource)} label_quad_found=${report.quadOrig != null} homography_present=${report.homography != null}")
        report.quadOrig?.let { points ->
            out.line("quad_original_pixels=${points.joinToString { "(${number(it.x)},${number(it.y)})" }}")
        }
        trace.quality?.let {
            out.line("quality=${it.state} laplacian_variance=${number(it.laplacianVariance)} mean_luminance=${number(it.meanLuminance)}")
        }
        out.line("working_bitmaps_peak=${report.peakWorkingBitmaps} remaining=${report.workingBitmapsAfter}")
        out.line("hint=${safe(report.fill.overallHint)}")
        trace.stages.forEach { out.line("stage=${it.stage} ms=${it.ms}") }
        trace.failures.forEach { out.line("failure stage=${it.stage} type=${safe(it.type)}") }
        out.line()
        out.line("[Engines and passes]")
        out.line("Each recorded pass lists its actual image treatment in detail; ms measures the recognizer call (including input conversion/wait), not variant preparation.")
        out.line("V0 = base image (or explicitly reused probe); V1 = luminance contrast enhancement; V2 = conditional rescue, not always run.")
        out.line("Absent passes were not recorded as attempted; this can reflect scheduling, deadline, failure or unavailable engines.")
        out.line("Current ML Kit adapter converts every prepared image to grayscale NV21 (neutral chroma); color above describes preprocessing, not color OCR input.")
        if (trace.engines.isEmpty()) out.line("No engine inventory: initialization/capture may not have completed.")
        trace.engines.forEach { out.line("engine=${safe(it.id)} available=${it.available} reason=${safe(it.reason)}") }
        report.timings.forEach {
            out.line("pass=${safe(it.engineId)}/${safe(it.variantId)} ms=${it.ms} raw_lines=${it.tokenCount} deadline=${it.deadlineCancelled} detail=${safe(it.detail)}")
        }
        out.line()
        val merged = TokenMerger().merge(report.tokens)
        out.line("[Mapping and confidence]")
        out.line("raw_pass_readings=${trace.totalReadings} mapping_received=${trace.mappingReceived} mapping_rejected=${trace.mappingRejected} recovered_probe_readings=${trace.recoveredProbeReadings}")
        out.line("mapped_readings=${report.tokens.size} retained_clusters=${merged.size}")
        out.line("Repeated variants from one engine are NOT independent votes; estimated confidence cannot validate GREEN.")
        out.line("Singleton minimum: ML Kit 0.60, Paddle 0.75. Independent combined vote minimum: 0.60.")
        out.line("Field display may repair known word breaks or use an exact curated producer alias; raw readings/scores remain unchanged.")
        out.line()
        out.line("[Field decisions: scanner suggestions, NOT manual form values]")
        FieldId.entries.forEach { id ->
            val field = report.fill.fields.getValue(id)
            out.line("$id state=${field.state} value=\"${safe(field.value)}\" reason=${safe(field.reason, 512)}")
            field.sources.take(3).forEach { evidence ->
                out.line("  evidence=\"${safe(evidence.canonical)}\" vote=${number(evidence.vote)} reliable=${evidence.hasReliableSupport}")
            }
        }
        out.line()
        out.line("[Raw OCR samples before coordinate/confidence filtering]")
        out.line("sampled=${trace.reads.size}/${trace.totalReadings}; caps=30 localization + 80 primary label readings")
        trace.reads.forEachIndexed { i, read ->
            out.line("$i ${safe(read.engine)}/${safe(read.variant)} space=${safe(read.inputSpace)} mapped=${read.mapped ?: "localization_only"} " +
                "score=${number(read.score)} ${if (read.estimated) "estimated" else "measured"} text=\"${safe(read.text)}\"")
        }
        out.line()
        out.line("[Mapped readings before confidence filtering; boxes normalized 0..1]")
        report.tokens.take(80).forEachIndexed { i, read ->
            out.line("$i ${safe(read.engineId)}/${safe(read.variantId)} score=${number(read.score)} " +
                "${if (read.scoreIsEstimated) "estimated" else "measured"} text=\"${safe(read.textRaw)}\" " +
                "original=${box(read.bboxOrig)} rectified=${box(read.bboxRect)}")
        }
        if (report.tokens.size > 80) out.line("Mapped readings truncated: 80/${report.tokens.size} shown.")
        out.line()
        out.line("[Retained clusters]")
        merged.take(40).forEach { cluster ->
            val engines = cluster.members.groupBy { it.engineId }.map { (id, reads) ->
                "${safe(id)}:${number(reads.maxOf { it.score })}"
            }.joinToString()
            out.line("text=\"${safe(cluster.canonical)}\" vote=${number(cluster.vote)} reliable=${cluster.hasReliableSupport} engines=$engines")
        }
        out.line("END WINE-SCAN-DIAGNOSTICS")
        return out.finish()
    }

    private fun outcome(report: ScanReport): String = when {
        report.diagnostics.failures.isNotEmpty() -> "pipeline_error"
        report.diagnostics.quality?.state == "RED" -> "quality_rejected"
        report.deadlineCancelled -> "deadline_partial_or_empty"
        report.tokens.isEmpty() -> "no_mapped_text"
        listOf(FieldId.PRODUCER, FieldId.WINE_NAME).all { report.fill.fields.getValue(it).state == UiState.RED } -> "no_supported_identity"
        else -> "review_ready"
    }
    private fun box(box: RectF) = "(${number(box.left)},${number(box.top)},${number(box.right)},${number(box.bottom)})"
    private fun number(value: Number): String = value.toDouble().let {
        if (it.isFinite()) String.format(Locale.ROOT, "%.3f", it) else "$it(invalid)"
    }
    private fun safe(text: String?, max: Int = 256): String = (text ?: "none").take(max * 2)
        .replace(Regex("[\\p{Cntrl}]+"), " ").take(max)

    private class ReportText {
        private val text = StringBuilder()
        private var truncated = false
        fun line(value: String = "") {
            val available = MAX_CHARS - 64 - text.length
            if (available <= 0) { truncated = true; return }
            val bounded = value.take(available)
            text.append(bounded).append('\n')
            if (bounded.length < value.length) truncated = true
        }
        fun finish(): String = text.toString() + if (truncated) "[Report truncated to $MAX_CHARS characters]\n" else ""
    }
}
