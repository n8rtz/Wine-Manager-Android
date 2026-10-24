package com.yourapp.wine

import android.graphics.Bitmap
import android.graphics.PointF
import com.yourapp.wine.ocr.*
import kotlinx.coroutines.runBlocking

/** Shared policy-only fake-engine checks: never native Paddle execution or measured OCR accuracy. */
object EnsemblePolicyChecks {
    fun optionalEnginesNeverReadAnUnrectifiedScene() = runBlocking {
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        var latinReads = 0
        var paddleReads = 0
        fun fake(id: String, read: () -> Unit) = object : OcrEngine {
            override val id = id
            override val available = true
            override suspend fun recognize(bitmap: Bitmap, variantId: String, deadline: ScanDeadline): List<RawToken> {
                read()
                return listOf(RawToken("2018", .9f, listOf(PointF(0f, 0f), PointF(2f, 0f),
                    PointF(2f, 2f), PointF(0f, 2f)), id, variantId))
            }
        }
        val latin = fake("mlkit") { latinReads++ }
        val paddle = fake("paddle") { paddleReads++ }
        val now = { System.nanoTime() / 1_000_000 }
        try {
            val ensemble = OcrEnsemble(listOf(latin, paddle), {})
            for (kind in listOf(OcrInputKind.WORKING_LOCALIZATION, OcrInputKind.FULL_FRAME_FALLBACK)) {
                val batch = ensemble.run(listOf(OcrVariant("V0", { bitmap }, {})),
                    ScanDeadline(now() + 1000, now), inputKind = kind)
                check(batch.tokens.single().engineId == "mlkit" && !batch.deadlineCancelled)
                check(paddleReads == 0)
            }
            val label = ensemble.run(listOf(OcrVariant("V0", { bitmap }, {})),
                ScanDeadline(now() + 1000, now), inputKind = OcrInputKind.RECTIFIED_LABEL)
            check(paddleReads == 1 && latinReads == 3 && label.tokens.size == 2)
            val skipped = OcrEnsemble(listOf(paddle), {}).run(
                listOf(OcrVariant("V0", { error("No eligible engine: do not allocate a preprocessing variant") }, {})),
                ScanDeadline(now() + 1000, now), inputKind = OcrInputKind.FULL_FRAME_FALLBACK)
            check(skipped.tokens.isEmpty() && paddleReads == 1)
        } finally { bitmap.recycle() }
    }
}
