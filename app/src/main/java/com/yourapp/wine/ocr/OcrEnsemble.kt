package com.yourapp.wine.ocr

import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Lazy preparation/release guarantees raw + at most one extra rectified Bitmap. */
data class OcrVariant(val id: String, val prepare: () -> Bitmap, val release: (Bitmap) -> Unit,
    val enabled: (List<RawToken>) -> Boolean = { true })
data class EngineTiming(val engineId: String, val variantId: String, val ms: Long, val tokenCount: Int,
    val deadlineCancelled: Boolean, val detail: String? = null)
data class OcrBatch(val tokens: List<RawToken>, val timings: List<EngineTiming>, val deadlineCancelled: Boolean)

class OcrEnsemble(private val engines: List<OcrEngine>, private val log: (EngineTiming) -> Unit = { timing ->
    Log.d("WineScanner", "engine=${timing.engineId} variant=${timing.variantId} ms=${timing.ms} tokens=${timing.tokenCount} deadlineCancelled=${timing.deadlineCancelled} ${timing.detail.orEmpty()}")
}, private val onTiming: (EngineTiming) -> Unit = {}) {
    /** Sequential engines on at most three rectified variants; completed tokens are published before later timeout. */
    suspend fun run(variants: List<OcrVariant>, deadline: ScanDeadline,
                    reuseMlkitV0: List<RawToken>? = null,
                    onTokens: (List<RawToken>) -> Unit = {}): OcrBatch {
        require(variants.size <= 3)
        val tokens = mutableListOf<RawToken>(); val timings = mutableListOf<EngineTiming>()
        var cancelled = false
        variantLoop@ for (variant in variants) {
            currentCoroutineContext().ensureActive()
            if (deadline.expired) { cancelled = true; break }
            if (!variant.enabled(tokens)) continue
            val bitmap = variant.prepare()
            try {
                for (engine in engines.filter { it.available }) {
                    if (deadline.expired) { cancelled = true; break@variantLoop }
                    val start = SystemClock.elapsedRealtime()
                    var detail: String? = null
                    val read = try {
                        if (engine.id == "mlkit" && variant.id == "V0" && reuseMlkitV0 != null) {
                            detail = "reused mapped quad probe"; reuseMlkitV0
                        } else deadline.run { engine.recognize(bitmap, variant.id, deadline) }
                    } catch (cancel: CancellationException) {
                        val timing = EngineTiming(engine.id, variant.id, SystemClock.elapsedRealtime() - start,
                            0, true, "deadline/lifecycle cancelled awaiting engine")
                        timings += timing; log(timing); onTiming(timing)
                        throw cancel
                    }
                    catch (error: Exception) { detail = "failed: ${error.javaClass.simpleName}"; emptyList() }
                    val timing = EngineTiming(engine.id, variant.id, SystemClock.elapsedRealtime() - start,
                        read?.size ?: 0, read == null || deadline.expired, detail)
                    timings += timing; log(timing); onTiming(timing)
                    if (read != null) { tokens += read; onTokens(read) }
                    if (timing.deadlineCancelled) { cancelled = true; break@variantLoop }
                }
            } finally { variant.release(bitmap) }
        }
        return OcrBatch(tokens, timings, cancelled)
    }
}
