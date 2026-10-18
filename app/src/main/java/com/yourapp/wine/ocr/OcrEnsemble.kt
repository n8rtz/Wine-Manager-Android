package com.yourapp.wine.ocr

import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Lazy preparation/release guarantees raw + at most one extra rectified Bitmap. */
data class OcrVariant(val id: String, val prepare: () -> Bitmap, val release: (Bitmap) -> Unit,
    val enabled: (List<RawToken>) -> Boolean = { true },
    val toLabel: (RawToken, Int, Int) -> RawToken = { token, _, _ -> token },
    val description: (Int, Int) -> String? = { _, _ -> null })
data class EngineTiming(val engineId: String, val variantId: String, val ms: Long, val tokenCount: Int,
    val deadlineCancelled: Boolean, val detail: String? = null)
data class OcrBatch(val tokens: List<RawToken>, val timings: List<EngineTiming>, val deadlineCancelled: Boolean)

class OcrEnsemble(private val engines: List<OcrEngine>, private val log: (EngineTiming) -> Unit = { timing ->
    Log.d("WineScanner", "engine=${timing.engineId} variant=${timing.variantId} ms=${timing.ms} tokens=${timing.tokenCount} deadlineCancelled=${timing.deadlineCancelled} ${timing.detail.orEmpty()}")
}, private val onTiming: (EngineTiming) -> Unit = {}) {
    /** Reads the declared label/working/fallback pixel space; optional engines never receive a full scene. */
    suspend fun run(variants: List<OcrVariant>, deadline: ScanDeadline,
                    reuseMlkitV0: List<RawToken>? = null,
                    onTokens: (List<RawToken>) -> Unit = {},
                    inputKind: OcrInputKind = OcrInputKind.RECTIFIED_LABEL): OcrBatch {
        require(variants.size <= 3)
        val eligible = engines.filter { it.available && (inputKind == OcrInputKind.RECTIFIED_LABEL || it.id == "mlkit") }
        val tokens = mutableListOf<RawToken>(); val timings = mutableListOf<EngineTiming>()
        if (eligible.isEmpty()) return OcrBatch(tokens, timings, deadline.expired)
        var cancelled = false
        variantLoop@ for (variant in variants) {
            currentCoroutineContext().ensureActive()
            if (deadline.expired) { cancelled = true; break }
            if (!variant.enabled(tokens)) continue
            val bitmap = variant.prepare()
            val inputW = bitmap.width; val inputH = bitmap.height
            try {
                for (engine in eligible) {
                    if (deadline.expired) { cancelled = true; break@variantLoop }
                    val start = SystemClock.elapsedRealtime()
                    var detail: String? = variant.description(inputW, inputH)
                    val read = try {
                        if (engine.id == "mlkit" && variant.id == "V0" && reuseMlkitV0 != null) {
                            detail = "reused mapped quad probe"; reuseMlkitV0
                        } else deadline.run { engine.recognize(bitmap, variant.id, deadline) }
                    } catch (cancel: CancellationException) {
                        val timing = EngineTiming(engine.id, variant.id, SystemClock.elapsedRealtime() - start,
                            0, true, listOfNotNull(detail, "deadline/lifecycle cancelled awaiting engine").joinToString("; "))
                        timings += timing; log(timing); onTiming(timing)
                        throw cancel
                    }
                    catch (error: Exception) {
                        detail = listOfNotNull(detail, engine.failureDetail(error)).joinToString("; ").take(240)
                        emptyList()
                    }
                    // Variant resizing must be inverted BEFORE coordinate mapping, merging, or field extraction.
                    val labelRead = read?.map { variant.toLabel(it, inputW, inputH) }
                    val timing = EngineTiming(engine.id, variant.id, SystemClock.elapsedRealtime() - start,
                        labelRead?.size ?: 0, read == null || deadline.expired, detail)
                    timings += timing; log(timing); onTiming(timing)
                    if (labelRead != null) { tokens += labelRead; onTokens(labelRead) }
                    if (timing.deadlineCancelled) { cancelled = true; break@variantLoop }
                }
            } finally { variant.release(bitmap) }
        }
        return OcrBatch(tokens, timings, cancelled)
    }
}
