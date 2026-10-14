package com.yourapp.wine.ocr

import android.graphics.Bitmap
import android.graphics.PointF
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.util.concurrent.Executor

/** App-scoped bundled Latin recognizer. No fromFilePath/full-resolution decode and no model download. */
class MlKitOcrEngine(private val nativeGate: NativeEngineGate) : OcrEngine {
    override val id = "mlkit"
    override val available = true
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val directExecutor = Executor { it.run() }

    /** Receives rectified-label pixels; Task owns bytes, so deadline cancellation never leaves a borrowed Bitmap. */
    override suspend fun recognize(bitmap: Bitmap, variantId: String, deadline: ScanDeadline): List<RawToken> {
        val lease = nativeGate.acquire()
        var input: Nv21Input? = null
        var submitted = false
        try {
            input = Nv21Input.copy(bitmap, deadline) ?: return emptyList()
            currentCoroutineContext().ensureActive()
            if (deadline.expired) return emptyList()
            val owner = input
            val task = recognizer.process(owner.image)
            task.addOnCompleteListener(directExecutor) { owner.release(); lease.close() }
            submitted = true
            val result = task.await() // cancellation stops waiting; native completion still owns/releases the lease
            // Keep a uniform LINE granularity with Paddle det/line-rec. Mixing word boxes with a
            // whole-line Paddle box would let containment union-find swallow otherwise distinctive words.
            return result.textBlocks.flatMap { it.lines }.mapNotNull { line ->
                val points = line.cornerPoints?.map { p -> PointF(p.x.toFloat().coerceIn(0f, bitmap.width.toFloat()),
                    p.y.toFloat().coerceIn(0f, bitmap.height.toFloat())) }
                    ?: line.boundingBox?.let { r -> listOf(PointF(r.left.toFloat(), r.top.toFloat()),
                        PointF(r.right.toFloat(), r.top.toFloat()), PointF(r.right.toFloat(), r.bottom.toFloat()), PointF(r.left.toFloat(), r.bottom.toFloat())) }
                    ?: return@mapNotNull null
                val confidences = line.elements.map { it.confidence?.takeIf { value -> value.isFinite() && value in 0f..1f } }
                val estimated = confidences.isEmpty() || confidences.any { it == null }
                // Conservative real element confidence, not a made-up consensus across variants.
                val confidence = confidences.filterNotNull().minOrNull()?.coerceIn(0f, 1f) ?: 0.65f
                RawToken(line.text, confidence, points, id, variantId, estimated)
            }.take(MAX_TOKENS)
        } finally {
            if (!submitted) { input?.release(); lease.close() }
        }
    }

    /** No coordinates: only used if app-scoped construction fails, before any recognition Task starts. */
    override fun close() { recognizer.close() }

    companion object { const val MAX_TOKENS = 300 }
}
